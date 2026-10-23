# C1AS / BEPC — Validation des imports officiels

Validation locale du 23 octobre 2026. Backend uniquement. Aucun accès Supabase,
import production, publication officielle, commit, push ou déploiement.

## État initial

Le workspace parent n'est pas un dépôt Git ; `git status --short` a ensuite été
exécuté dans `natijati-mr-api`. Deux fichiers étaient déjà modifiés :
`ExcelResultItemReader.java` (essai DataFormatter) et `application.yml` (150 000
lignes). Ces modifications ont été examinées : leur intention est conservée,
avec correction ciblée de la régression XLS et configuration explicite de la limite.
Aucun fichier n'a été restauré arbitrairement. Le frontend n'a pas été modifié.

Pipeline audité : upload contrôlé et copie temporaire → preflight sans écriture
(SAX, validation des champs et détection des doublons) → réservation de session
IMPORTING → Batch de 500 avec le même reader/processeur → writer JPA → comptage
réel et READY. Un échec nettoie les résultats partiels ; publication distincte
et explicite. Les copies et spools sont supprimés. Les transactions restent par
chunk, avec nettoyage compensatoire en cas d'échec traité.

## Analyse du fichier C1AS

`RESU_C1AS_2026_15_07-2026_121140.xlsx` : une feuille, 121 141 lignes Excel,
12 colonnes, **121 140 résultats**, aucune ligne vide, formule ou cellule en erreur.

En-têtes exacts : `Noreg`, `Numéro_C1AS`, `NODOSS`, `NOM_AR`, `LIEU NAISS_AR`,
`TYPE`, `Ecole_AR`, `ANNEE_NAISS`, `WILAYA_AR`, `MOUGHATAA_AR`,
`Centre Examen_AR`, `TOTAL`.

| Excel | Clé normalisée | Java / SQL | Type SQL | Conclusion |
| --- | --- | --- | --- | --- |
| Numéro_C1AS | NUMERO_C1AS | candidateNumber / candidate_number | BIGINT | Facultatif, non unique ; source conservée séparément |
| NODOSS | NODOSS | dossierNumber / dossier_number | BIGINT NOT NULL | Clé unique par session ; source conservée séparément |
| NOM_AR | NOM_AR | nameAr / name_ar | VARCHAR(255) | Obligatoire, longueur max réelle 50 |
| TYPE | TYPE | candidateType / candidate_type | VARCHAR(30) | Max 2, casse originale conservée |
| Ecole_AR | ECOLE_AR | schoolAr / school_ar | VARCHAR(255) | Max 36 |
| WILAYA_AR | WILAYA_AR | wilayaAr / wilaya_ar | VARCHAR(150) | Max 18, 16 valeurs distinctes |
| MOUGHATAA_AR | MOUGHATAA_AR | moughataaAr / moughataa_ar | VARCHAR(150) | Max 12 |
| Centre Examen_AR | CENTRE_EXAMEN_AR | examCenterAr / exam_center_ar | VARCHAR(255) | Max 36, 1 397 centres distincts |
| TOTAL | TOTAL | totalScore / total_score | NUMERIC(18,12) | Compatible après suppression du bruit négligeable |
| Noreg | NOREG | — | — | Ignorée |
| LIEU NAISS_AR | LIEU_NAISS_AR | — | — | Ignorée |
| ANNEE_NAISS | ANNEE_NAISS | — | — | Ignorée |

Les 11 premières colonnes sont textuelles, format `General`. TOTAL est numérique,
format visuel `0.00`, plage réelle 0–198. Ce format visuel masquerait des décimales
légitimes : il ne doit pas piloter l'import. Les valeurs métier observées vont
jusqu'à quatre décimales ; cinq cellules XML contiennent un artefact supplémentaire.

Les dossiers ont exactement 10 caractères (71 631 commencent par zéro), les
numéros C1AS exactement 3 (93 645 commencent par zéro). Aucun doublon de dossier,
y compris après conversion numérique. Seulement 359 numéros C1AS distincts :
les 120 781 répétitions supplémentaires sont normales pour ce champ, qui n'est
pas l'identifiant unique global. La contrainte existante porte correctement sur
`(exam_session_id, dossier_number)`.

## Cause exacte du bug C1AS

La cellule L55082 contient `<v>72.252200000000002</v>`. openpyxl l'expose comme
`72.2522`, mais le reader SAX transmet le texte XML au processeur. Le BigDecimal
obtenu a 15 décimales ; l'ancien contrôle strict `scale <= 12` rejetait cette
représentation malgré un écart de seulement `2E-15` avec la valeur métier.

`NumberToTextConverter` concerne le chemin XLS ; les XLSX passent par
`XlsxRowSpool`, qui lit le XML brut. Le changement DataFormatter initial ne
corrigeait donc pas cette cause et réduisait `12.345` à `12.3` dans un XLS
formaté `0.0`.

| Ligne Excel | XML brut TOTAL | Valeur retenue |
| --- | --- | --- |
| 55082 | 72.252200000000002 | 72.2522 |
| 57196 | 131.08000000000001 | 131.08 |
| 60506 | 75.349999999999994 | 75.35 |
| 63925 | 94.251999999999995 | 94.252 |
| 97135 | 75.540000000000006 | 75.54 |

## Correction numérique

Uniquement pour un score dépassant la scale SQL, calcul d'un candidat stockable
avec `BigDecimal.setScale(scale, HALF_EVEN)`. Acceptation si l'écart absolu est
au plus `min(0,5 ULP binary64, 10⁻¹⁴ point)`. `Math.ulp` sert à mesurer le bruit,
jamais à produire la note stockée. Une valeur non nulle ne peut pas devenir zéro.
Les six chiffres entiers maximum et les plages pédagogiques restent contrôlés.

`12.345`, `4.263157895`, `9.947368421` restent exactement inchangés. Les identifiants
ne passent jamais par ce traitement. Négatifs, dépassements et arrondis significatifs
restent refusés. BAC/C1AS conservent NUMERIC(18,12). Les erreurs exposent examen,
ligne, champ, raison contrôlée et `"value": "[masquée]"`.

La limite de lignes est maintenant définie uniquement dans application.yml :
`${APP_IMPORT_MAX_ROWS:150000}`. Preflight et Batch injectent cette même propriété
sans fallback 100 000 ; le reader exige une limite explicite. Les chemins XLS
et XLSX la contrôlent. Le test synthétique accepte 121 140 lignes sous 150 000
et refuse ce même fichier lorsque la limite configurée est 121 139.

## Analyse du fichier BEPC

`Res_Global_.Bepc_15-07-2026_75694.xlsx` : une feuille, dimension Excel
**75 696 × 11**, dont 9 colonnes renseignées, une ligne d'en-tête,
**75 694 résultats** et une dernière ligne vide. Les deux colonnes sans en-tête
sont entièrement vides. Aucun doublon candidat, formule ou cellule en erreur.

En-têtes exacts : `Numéro`, `NOM`, `DATE NISS`, `LIEU NAISS`, `Code Wilaya`,
`Wilaya`, `Centre`, `Moyg`, `Decision`.

Toutes les colonnes utiles sont complètes. Une cellule LIEU NAISS est vide ; cette
colonne est ignorée. DATE NISS contient des dates Excel (`mm-dd-yy`), également
ignorées. Numéro, NOM, Code Wilaya, Wilaya, Centre et Decision sont des textes
`General` ; Moyg est numérique `General`. Longueurs maximales : nom 60, code 2,
wilaya 18, centre 34, décision 24. Aucune incompatibilité de longueur SQL.

16 codes wilaya distincts, de 01 à 16 ; correspondance univoque avec 16 libellés,
dont `Complexe - Gambie` pour 16. **354 centres distincts**. Les codes sont
stockés en INTEGER ; le zéro de remplissage des codes territoriaux n'est pas
un numéro candidat. Les 75 694 numéros candidat sont des textes de 5 caractères,
dont 9 970 avec zéro initial ; aucun format personnalisé numérique ne les génère.

| Décision source exacte | Nombre | Normalisation |
| --- | ---: | --- |
| Admis | 20 890 | ADMITTED |
| Ajourné | 51 229 | FAILED |
| Absent | 2 942 | ABSENT |
| Examen annulé -Telephone | 151 | CANCELLED |
| Examen-Annulé-Telephone | 11 | CANCELLED |
| Eliminé | 471 | ELIMINATED |

Les six libellés sont reconnus et conservés dans `decision_original`.
Les décisions inconnues restent refusées. ELIMINATED reste distinct de FAILED
et CANCELLED. Les libellés des distributions backend couvrent aussi ABSENT.

Moyg : 0 à **18.662500000000001 dans le XML** (18.6625 affiché par openpyxl),
jusqu'à **18 décimales**. **41 942 cellules** dépassent 12 décimales dans le XML.
La précision réelle n'est pas celle des exemples affichés à neuf décimales :
`4.263157895` correspond ici à `4.2631578947368398`. Ramener cette dernière à
12 décimales exigerait environ `1.6E-13` point d'écart, supérieur à la tolérance.
Il s'agit donc d'une incompatibilité réelle de V1, pas d'un défaut à masquer.

## Conformité table BEPC

`RawExamRow.values` contient les clés normalisées ci-dessous. Le processeur
construit BepcResultCommand, puis BepcResult.from copie chaque champ dans l'entité.

| Excel | Header normalisé | Java (commande → entité) | SQL | Type | Conclusion |
| --- | --- | --- | --- | --- | --- |
| Numéro | NUMERO | candidateNumber | candidate_number | BIGINT NOT NULL | OK, clé numérique conservée |
| Numéro | NUMERO | candidateNumberOriginal | candidate_number_original | VARCHAR(255) | V2, texte source avec zéros |
| NOM | NOM | nameFr | name_fr | VARCHAR(255) NOT NULL | OK |
| Code Wilaya | CODE_WILAYA | wilayaCode | wilaya_code | INTEGER | OK, 01–16 → 1–16 |
| Wilaya | WILAYA | wilayaFr | wilaya_fr | VARCHAR(150) | OK |
| Centre | CENTRE | examCenterFr | exam_center_fr | VARCHAR(255) | OK |
| Moyg | MOYG | score (BigDecimal) | score | NUMERIC(24,18) | V2 nécessaire, XML conservé exactement |
| Decision | DECISION | decisionCode (enum) | decision_code | VARCHAR(40) | Six libellés reconnus |
| Decision | DECISION | decisionOriginal | decision_original | VARCHAR(150) | Original conservé |
| DATE NISS | DATE_NISS | — | — | — | Ignorée volontairement |
| LIEU NAISS | LIEU_NAISS | — | — | — | Ignorée volontairement |
| Paramètres import | — | sessionId → examSession | exam_session_id | BIGINT NOT NULL, FK | OK |
| Année import | — | ExamSession.year | exam_session.exam_year | INTEGER NOT NULL | 2026 vérifié |
| Session import | — | ExamSession.sessionType | exam_session.session_type | VARCHAR(30) NOT NULL | NORMAL vérifié |

Unicité conservée : `(exam_session_id, candidate_number)` ; session unique par
`(exam_type, exam_year, session_type)`. Le repository filtre examen, année,
type/statut de session et numéro. Les recherches avec et sans zéros initiaux
retrouvent la même clé, y compris dans le catalogue après correction.

JPA, JDBC et DTO conservent BigDecimal ; les tests REST vérifient les nombres JSON
en les relisant comme BigDecimal. Les moyennes statistiques utilisent AVG(NUMERIC),
sans arrondi forcé à deux décimales. Les scores nuls et les zéros sont distincts.
Le frontend existant utilise `toFixed(2)` pour l'affichage ; il n'a pas été modifié.

## Résultats import C1AS

| Mesure | Résultat |
| --- | ---: |
| rows | 121140 |
| readRows | 121140 |
| resultCount | 121140 |
| rejectedRows | 0 |
| status | COMPLETED |
| sessionStatus | READY |
| Preflight indépendant | 3.790 s |
| Import Batch | 16.521 s |
| Upload complet, preflight compris | 20.186 s |
| Débit Batch | 7332.6 lignes/s |
| Pic indicatif des pools heap | 248.0 MiB |

## Résultats import BEPC

| Mesure | Résultat |
| --- | ---: |
| rows | 75694 |
| readRows | 75694 |
| resultCount | 75694 |
| rejectedRows | 0 |
| status | COMPLETED |
| sessionStatus | READY |
| Preflight indépendant | 3.008 s |
| Import Batch | 11.843 s |
| Upload complet, preflight compris | 13.898 s |
| Débit Batch | 6391.4 lignes/s |
| Pic indicatif des pools heap | 207.4 MiB |

Tous les champs métier et identifiants des **196 834 lignes** ont été comparés
à PostgreSQL, avec curseur JDBC et reader SAX. C1AS L55082 est stockée comme
`72.252200000000`, égale exactement à `72.2522`. Les zéros de fin SQL ne modifient
pas la valeur. Le fichier C1AS contient bien 121 140 dossiers uniques.

| Ligne BEPC | XML brut | PostgreSQL NUMERIC(24,18) |
| --- | --- | --- |
| 3 | 4.2631578947368398 | 4.263157894736839800 |
| 7 | 5.8421052631578902 | 5.842105263157890200 |
| 8 | 9.7631578947368407 | 9.763157894736840700 |
| 25 | 9.9473684210526301 | 9.947368421052630100 |
| 23 | 10.1 | 10.100000000000000000 |
| 11 | 0 | 0.000000000000000000 |

Les sessions sont READY, non vides, avec compte réel cohérent : conditions de
publication remplies. `published_at` reste NULL pour les imports officiels.
Les tests existants exercent la publication uniquement sur des données synthétiques.
Copies, uploads et spools supprimés ; empreintes SHA-256 des originaux inchangées :

- C1AS : `4bedb0cfa9a25006d0e39ff9c2d71a87fa6b950f5267d0ad6687dd15f3df25c8`
- BEPC : `74f1be4fce42a0279cee71717ec89553b2bd6cd41780019307d20ba9171edbfc`

## Tests

| Commande / suite | Tests | Échecs | Erreurs | Ignorés |
| --- | ---: | ---: | ---: | ---: |
| `mvn -B test` | 108 | 0 | 0 | 0 |
| `mvn -B verify` — unitaires | 108 | 0 | 0 | 0 |
| `mvn -B verify` — BackendIT | 56 | 0 | 0 | 0 |
| `mvn -B verify` — ImportIT | 21 | 0 | 0 | 0 |
| Contrôle opt-in OfficialFilesCheck, après verify | 2 | 0 | 0 | 0 |

Soit **187 cas distincts validés** (les relances des 108 unitaires ne sont pas
additionnées). `git diff --check` réussit. La commande complète des imports réels
figure dans [src/test/README.md](../src/test/README.md). Mesures et échantillons
reproductibles dans `target/official-audit/{c1as,bepc}-import.json` ; rapports JUnit
sous `target/surefire-reports` et `target/failsafe-reports`.

Couverture ajoutée : cinq artefacts C1AS, valeurs précises fournies et XML BEPC,
plages/capacité SQL, arrondi significatif et sous-flux refusés, headers réels,
colonnes ignorées, textes source des identifiants, décisions officielles et inconnues,
limite configurable 121 140 lignes, limite XLS, migration V1 → V2 préservant une
note historique, JPA/JSON/recherche/statistiques et comparaison complète officielle.
Aucun test existant supprimé ; tests BAC conservés et réussis.

Les premières exécutions ont révélé l'attachement Mockito interdit par le sandbox
(résolu par l'exécution autorisée hors sandbox), un cas de sous-flux numérique
(maintenant refusé), le filtre catalogue avec zéros initiaux (corrigé), et un pool
de test trop petit pour les connexions simultanées de migration (Flyway utilise
désormais des connexions directes au même Testcontainer). Les passes finales
ne comportent aucun test ignoré ni échec.

## Fichiers modifiés

- Production : `ExamResultProcessor`, `ExcelResultItemReader`, `ImportPreflight`,
  `BatchImportConfiguration`, `BepcResultCommand`, `C1asResultCommand`,
  `BepcResult`, `C1asResult`, `DecisionCode`, `DecisionNormalizer`,
  `PublicReadRepository`, `application.yml`.
- Migration ajoutée : `V2__preserve_official_result_precision.sql`.
- Tests ajoutés : `OfficialFormatTest`, `OfficialFilesCheck`.
- Tests adaptés/étendus : `ImportValidationTest`, `ImportIT`, `BackendIT`.
- Documentation : `docs/IMPORTS.md`, `src/test/README.md`, ce rapport.
- `README.md`, frontend et fichiers Excel originaux inchangés.

## Migration Flyway

**Migration V2 ajoutée. V1 inchangée.** Deux incompatibilités avérées la motivent :
la capacité de score BEPC insuffisante et la perte de la représentation textuelle
des identifiants. V2 élargit `bepc_result.score` à NUMERIC(24,18) et ajoute
`candidate_number_original` aux deux tables, `dossier_number_original` à C1AS.
Les clés numériques, contraintes et score C1AS/BAC ne changent pas. Les colonnes
source restent NULL pour les imports antérieurs ; aucun remplissage inventé.
Migration testée depuis V1 avec données existantes et sur une base vide.

## Limites restantes

- Mesures locales PostgreSQL 17.11-alpine / JVM plafonnée à 640 MiB, sans latence
  Supabase ni reproduction du CPU/mémoire Cloud Run. Aucun engagement de durée Cloud Run.
- Le pic heap est la somme des pics des pools, indicateur approximatif, pas le RSS
  ni la mémoire totale conteneur. Les spools /tmp comptent en mémoire sur Cloud Run.
- Le writer JPA utilise IDENTITY/saveAll ; les INSERT ne sont pas nécessairement
  groupés. Le risque de latence distante et les limites d'un arrêt brutal restent ceux
  documentés dans IMPORTS.md.
- L'API historique expose encore la clé numérique en chaîne, sans zéros ; leur
  représentation source est préservée en base. Affichage frontend à deux décimales
  inchangé, malgré la précision complète conservée et transmise par le backend.
- Les agrégats historiques n'ont pas de compteurs dédiés ABSENT/ELIMINATED ; ces
  candidats restent inclus dans le total et leurs scores non NULL dans AVG.
- La tolérance définit une borne de bruit négligeable, pas une preuve de l'intention
  de l'auteur d'une future cellule. Tout dépassement significatif reste bloquant.
- V2 est préparée et validée localement, jamais appliquée en production ici.

## Conclusion

C1AS VALIDÉ

BEPC VALIDÉ

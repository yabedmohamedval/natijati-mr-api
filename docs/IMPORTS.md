# Import Excel — politique P2-E

## Flux et contrat

Depuis P4-C, `POST /api/admin/imports` est **asynchrone** et retourne HTTP 202.
Le contrat complet, le schéma V3 et les commandes d’infrastructure préparées sont
dans [ASYNC_IMPORTS.md](ASYNC_IMPORTS.md).

1. L’API contrôle année, taille, extension, magic bytes et archive ; elle stocke
   sous une clé UUID dans un bucket privé et crée `ImportExecution QUEUED`.
2. Elle déclenche un Cloud Run Job avec l’importId puis répond 202 avec Location.
3. Le worker télécharge la source et prévalide toutes les lignes sans créer de
   session métier. Un fichier invalide devient FAILED, sans nouvelle ExamSession.
4. Après validation seulement, réservation transactionnelle du triplet et Batch
   par chunks de 500. Résultats et compteurs de progression commitent ensemble.
5. READY après concordance des comptes ; publication explicite inchangée.
6. Échec traité : nettoyage de la session et FAILED atomiques. La source est
   supprimée après READY/FAILED ; une interruption conserve la source pour reprise.

`GET /api/admin/imports/{importId}` donne statut, compteurs, pourcentage et erreurs
contrôlées ; `GET /api/admin/imports?limit=20` liste les imports récents. ADMIN requis.
Le DTO final historique `ImportJobResponse` n’est plus le contrat HTTP. Un mauvais
fichier métier est désormais signalé via le GET après 202, et non par HTTP 400 du
POST. `ignored_columns` reste enregistré pour le diagnostic technique interne.
Aucune ligne invalide n’est ignorée : `rejectedRows=0`, erreur bloquante dans `errors`.

## Formats et validation

- .xls / OLE2 et .xlsx / OOXML uniquement ; magic bytes cohérents avec l'extension.
  Le Content-Type fourni par le navigateur n'est pas une preuve de format.
- Taille maximale 50 MiB, également contrôlée pendant la copie.
- Archives XLSX : au plus 10 000 entrées, 100 MiB par entrée, 256 MiB déclarés
  décompressés ; les protections zip-bomb Apache POI restent actives.
- Une seule feuille ; rejet des classeurs vides, illisibles, chiffrés ou corrompus.
- Au plus 64 colonnes. Formules et cellules Excel en erreur refusées, y compris
  dans les colonnes ignorées. Aucune évaluation de formule.
- Headers normalisés : casse, accents, séparateurs, espaces et caractères invisibles.
  Les doublons après normalisation sont bloquants. Pas d'alias approximatifs.
- BAC : NUM_BAC, NOM_FR, MOY_BAC, DECISION obligatoires.
- BEPC : NUMERO, NOM, MOYG, DECISION obligatoires.
- C1AS : NODOSS, NOM_AR, TOTAL obligatoires ; NUMERO_C1AS reste facultatif.
- Les colonnes non utilisées sont ignorées et comptées dans `ignoredColumns`.
  Dates/lieux de naissance ne sont ni stockés ni validés : ils n'interviennent pas
  dans le résultat public. Une date incorrecte dans ces colonnes n'altère donc pas
  l'import ; une formule dans ces colonnes reste interdite.
- Les lignes entièrement vides sont ignorées ; une ligne partielle est validée
  normalement et échoue si un champ obligatoire manque.
- Identifiant positif, entier exact dans la capacité BIGINT ; doublons détectés
  pendant la prévalidation et protégés aussi par les contraintes PostgreSQL.
- Nom français BAC/BEPC et nom arabe C1AS obligatoires. Textes limités aux tailles
  des colonnes SQL ; valeurs numériques trop longues/exposants extrêmes refusés.
- Notes BAC/BEPC entre 0 et 20 ; note vide uniquement pour ABSENT/CANCELLED.
- Total C1AS obligatoire, non négatif et compatible NUMERIC(18,12). Aucun plafond
  pédagogique arbitraire : le plafond officiel reste à confirmer pour chaque format.
- Scores en `BigDecimal` : BAC/C1AS `NUMERIC(18,12)` ; BEPC `NUMERIC(24,18)`
  depuis V2, car le XML officiel contient de véritables décimales au-delà de 12.
  Aucun formatage visuel Excel n'est utilisé pour arrondir les notes.
- Décisions : Admis/Admise, Ajourné/Ajournée, Sessionnaire, Annulation,
  Annulation de l'examen, Absent/Absente (accents/casse normalisés). Autres valeurs
  refusées ; ajouter un alias seulement après confirmation d'un format officiel.

### Précision Excel et identifiants officiels

La normalisation concerne uniquement les scores qui dépassent la scale SQL.
On calcule le candidat stockable par `BigDecimal.setScale(scale, HALF_EVEN)`,
puis on accepte uniquement si l'écart absolu est inférieur ou égal à
`min(0.5 × Math.ulp(score.doubleValue()), 1E-14)` point. Le double sert seulement
à mesurer l'espacement binary64 ; la valeur stockée et la comparaison d'écart
restent en `BigDecimal`. Une valeur non nulle ne peut pas être ramenée à zéro.
Les contrôles de plage, de scale et des six chiffres entiers restent en vigueur.

Exemple C1AS : `72.252200000000002` devient `72.2522` (écart `2E-15`).
`12.345`, `4.263157895`, `9.947368421` restent intacts. Une valeur telle que
`1.1234567891234` en C1AS/BAC est refusée : son arrondi à 12 décimales dépasse
la tolérance. Cette politique définit un bruit négligeable ; elle ne permet pas
de prouver l'intention de l'auteur pour toute future cellule ambiguë.

BEPC : la ligne 3 contient réellement `4.2631578947368398`, et non
`4.263157895`. V2 élargit uniquement `bepc_result.score` à 18 décimales : toutes
les notes XML du fichier 2026 sont conservées exactement, sans arrondi à 9 ou
12 décimales. Les zéros de fin ajoutés par PostgreSQL ne changent pas la valeur.
Les DTO, la recherche et `AVG(score)` utilisent `BigDecimal`/`NUMERIC` ; JSON
expose un nombre décimal. Le frontend existant affiche deux décimales via
`toFixed(2)` : c'est une présentation, pas une précision de stockage.

Les identifiants des fichiers 2026 sont du texte, format Excel `General`, avec
largeur fixe : BEPC 5 caractères, dossier C1AS 10, numéro C1AS 3. Les zéros sont
donc présents dans la source, pas générés par un format numérique Excel. V2
conserve les chaînes après trim dans `candidate_number_original` (BEPC/C1AS)
et `dossier_number_original` (C1AS). Les clés numériques, contraintes et routes
de recherche restent compatibles ; le catalogue accepte les clés avec ou sans
zéros initiaux, comme la route de résultat. L'API historique renvoie l'identifiant
numérique sous forme de chaîne. Les imports antérieurs ont des colonnes source
NULL, sans reconstruction spéculative. Aucune donnée nominative supplémentaire.

### Colonnes des fichiers 2026

| Examen | Colonnes utilisées (normalisées) | Colonnes ignorées |
| --- | --- | --- |
| C1AS | NUMERO_C1AS, NODOSS, NOM_AR, TYPE, ECOLE_AR, WILAYA_AR, MOUGHATAA_AR, CENTRE_EXAMEN_AR, TOTAL | NOREG, LIEU_NAISS_AR, ANNEE_NAISS |
| BEPC | NUMERO, NOM, CODE_WILAYA, WILAYA, CENTRE, MOYG, DECISION | DATE_NISS, LIEU_NAISS |

Les deux colonnes BEPC sans en-tête et entièrement vides ne sont pas comptées
dans `ignoredColumns` ; ce compteur vaut 2 pour BEPC, 3 pour C1AS.
`Numéro` → `NUMERO`, `Code Wilaya` → `CODE_WILAYA`, `Moyg` → `MOYG`,
`Decision` → `DECISION`, `Numéro_C1AS` → `NUMERO_C1AS`,
`Centre Examen_AR` → `CENTRE_EXAMEN_AR`.

Décisions BEPC supplémentaires confirmées : `Examen annulé -Telephone` et
`Examen-Annulé-Telephone` → CANCELLED ; `Eliminé` → ELIMINATED.
Le libellé source reste dans `decision_original`. Aucun rapprochement approximatif.
Les agrégats historiques exposent admitted/failed/secondSession/cancelled ; les
absents et éliminés sont inclus dans candidateCount et dans AVG si leur score
n'est pas NULL, mais n'ont pas de compteur dédié dans le DTO existant.

## Atomicité, nouvelle tentative et concurrence

Les transactions sont celles des chunks, pas une transaction de 65 000 lignes.
Les chunks précédents sont réellement commités. Lors d'un échec traité, le listener
supprime tous les résultats de la session en une transaction et marque FAILED avec
resultCount=0. Les sessions voisines ne sont pas touchées.

Un second fichier, identique ou différent, est refusé pour IMPORTING, READY ou
PUBLISHED. Il n'existe pas de remplacement silencieux. Un FAILED peut être réservé
à nouveau ; les éventuels résidus sont supprimés avant le nouvel import. Un nouvel
UUID d'essai crée une nouvelle JobInstance. `preventRestart()` interdit la reprise
d'un ancien job au dernier chunk. Cela évite de reprendre un curseur sur des données
supprimées ou sur un fichier temporaire déjà nettoyé.

Le verrou transactionnel protège aussi la création d'une session inexistante et
fonctionne avec un pooler transactionnel. La contrainte unique protège le triplet.
Deux sessions NORMAL/COMPLEMENTARY restent indépendantes. Le worker utilise en plus
un verrou PostgreSQL de session pour limiter globalement la charge à un import.
Ce verrou exige une connexion directe ou un pooler SESSION pour le Job ; la
compatibilité pooler transactionnel du seul verrou métier ne suffit pas.

Publication : READY non vide, avec compte réel cohérent. DRAFT, IMPORTING et FAILED
sont refusés. Publier une session déjà PUBLISHED est idempotent.

**Arrêt brutal / OOM / perte de base :** aucun finally/listener n'est garanti.
La session peut rester IMPORTING, avec des chunks partiels, et des fichiers peuvent
rester orphelins. Elle n'est ni visible publiquement ni publiable ; aucune reprise
concurrente automatique n'est permise. L'opérateur doit confirmer l'arrêt du job
avant de relancer le worker avec le même importId : nettoyage puis reprise complète.
Ne pas marquer automatiquement FAILED une session
sur un simple dépassement de délai HTTP : le job peut encore fonctionner.

## Mémoire et performance

La mesure initiale XSSFWorkbook sur 65 000 lignes BAC × 18 colonnes échouait avec
une heap de 640 MiB. XLSX utilise désormais SAX Apache POI avec une table de chaînes
partagées en lecture seule et un spool binaire temporaire par passe. Les cellules
ne sont pas gardées en mémoire ; les chaînes partagées et les identifiants du
contrôle de doublons y restent. XLS conserve WorkbookFactory et charge le workbook.

La limite par défaut est **150 000 lignes de données**, configurée une seule fois
dans `application.yml` : `app.import-max-rows: ${APP_IMPORT_MAX_ROWS:150000}`.
Preflight et Batch injectent cette même propriété, sans fallback indépendant.
Le reader exige une limite explicite, appliquée en XLSX comme en XLS ; les lignes
entièrement vides et l'en-tête n'entrent pas dans ce plafond. Le fichier C1AS réel
de 121 140 lignes passe, le dépassement d'une limite configurée reste bloquant.
La prévalidation et Batch lisent deux fois le fichier ; le spool est supprimé
entre les passes. Sur Cloud Run, les fichiers /tmp
comptent aussi dans la mémoire du conteneur.

Le chunk reste 500. Le writer JPA utilise saveAll avec des entités IDENTITY :
`hibernate.jdbc.batch_size=500` ne garantit pas 500 INSERT groupés. La latence réseau
vers PostgreSQL reste donc un risque concret malgré les bons temps locaux.
Mesurer avec une base de recette distante avant de garantir les 300 secondes.
Un writer JDBC batch pourra être évalué si cette latence devient limitante.

Les mesures sont synthétiques, locales, avec PostgreSQL Docker, sans reproduire
1 CPU Cloud Run ni le réseau Supabase. Les pics heap additionnés des pools sont des
indicateurs approximatifs : pas une mesure RSS du conteneur. Prévoir JVM native,
métaspace, uploads, spools et trafic public en plus de la heap.

## Tests

```sh
mvn -B test
mvn -B verify
# Manuels, exclus des conventions *Test et *IT :
mvn -B -Dit.test=ImportVolumeCheck -Dimport.volume.rows=10000 -DargLine=-Xmx640m verify
mvn -B -Dit.test=ImportVolumeCheck -Dimport.volume.rows=65000 -DargLine=-Xmx640m verify
mvn -B -Dit.test=ImportAtomicityCheck -DargLine=-Xmx640m verify
```

Les suites standard utilisent des fichiers synthétiques ; PostgreSQL Testcontainers
est obligatoire. Aucun DB_URL réel, Supabase ou Cloud Run n'est utilisé.
Un contrôle officiel distinct et explicite est documenté dans
[src/test/README.md](../src/test/README.md). Il copie les fichiers hors dépôt,
vérifie toutes les lignes en base et les empreintes des originaux, sans publier.
Les résultats mesurés figurent dans [le rapport 2026](IMPORTS_OFFICIAL_2026.md).
Le test d'atomicité injecte une erreur PostgreSQL après 40 000 écritures de lignes commitées sur
65 000 ; il vérifie zéro résultat conservé, FAILED, publication interdite, nettoyage
et nouvelle tentative réussie. Les tests standard couvrent aussi 10 %, 50 %, 90 %
sur 5 000 lignes et la concurrence de réservation.

## Exploitation

Les logs de fin donnent examen, année, session, sessionId, jobExecutionId, statut,
lignes lues, lignes commitées avant nettoyage, lignes conservées et durée. Les
messages de validation ne contiennent pas de valeur nominative. Les métadonnées
Batch conservent les compteurs et l'erreur technique ; aucun stockage massif
supplémentaire d'erreurs n'est créé.

Le lancement HTTP est désormais durable et asynchrone : voir
[ASYNC_IMPORTS.md](ASYNC_IMPORTS.md) pour le worker sans Tomcat, GCS privé,
le fencing des tentatives, les états abandonnés et les limites du dispatch.
Le preflight et le moteur restent synchrones **dans le processus Job**.
Aucune infrastructure cloud n’a été modifiée pendant P4-C.

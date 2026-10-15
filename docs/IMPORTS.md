# Import Excel — politique P2-E

## Flux et contrat

POST /api/admin/imports reste synchrone et authentifié. Un seul upload est traité à
la fois par JVM (les autres appels sont refusés et peuvent être retentés).

1. Contrôle de l'année (1900–2100), de la taille et du format réel.
2. Copie dans un fichier généré, sans réutiliser le nom fourni par le client.
3. Prévalidation de toutes les lignes, sans écriture métier. Premier défaut bloquant.
4. Réservation transactionnelle du triplet examen/année/session : verrou PostgreSQL
   `pg_try_advisory_xact_lock`, puis statut IMPORTING avant le lancement du job.
5. Batch par chunks de 500. Les résultats restent invisibles aux endpoints publics.
6. COMPLETED et résultat non vide : READY ; publication explicite par l'administrateur.
7. Échec traité : suppression SQL des résultats de cette session et passage FAILED.
8. Nettoyage du fichier uploadé dans finally ; nettoyage des fichiers de lecture à la fermeture.

Les cinq champs historiques de réponse restent présents. Champs supplémentaires :
`readRows`, `rejectedRows`, `durationMs`, `errors` et `ignoredColumns`.
`importedRows` représente les lignes conservées (zéro après nettoyage d'un échec).
`rejectedRows` compte les défauts de lignes identifiés, pas toutes les lignes non lues.
L'import est strict : aucune ligne erronée n'est ignorée pour rendre le fichier READY.
Les erreurs de prévalidation renvoient HTTP 400 et une seule entrée technique dans
`errors` : examen, ligne Excel (1-based), champ, raison, valeur masquée. La ligne 0
signale une erreur globale. Un échec Batch retourne son statut et son identifiant,
avec un diagnostic technique générique si aucune ligne précise n'est identifiable.

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
- Aucun arrondi silencieux au-delà des 12 décimales stockables.
- Décisions : Admis/Admise, Ajourné/Ajournée, Sessionnaire, Annulation,
  Annulation de l'examen, Absent/Absente (accents/casse normalisés). Autres valeurs
  refusées ; ajouter un alias seulement après confirmation d'un format officiel.

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
Deux sessions NORMAL/COMPLEMENTARY distinctes peuvent être réservées indépendamment
sur des instances différentes. Une seule lecture/import par JVM limite la mémoire.

Publication : READY non vide, avec compte réel cohérent. DRAFT, IMPORTING et FAILED
sont refusés. Publier une session déjà PUBLISHED est idempotent.

**Arrêt brutal / OOM / perte de base :** aucun finally/listener n'est garanti.
La session peut rester IMPORTING, avec des chunks partiels, et des fichiers peuvent
rester orphelins. Elle n'est ni visible publiquement ni publiable ; aucune reprise
concurrente automatique n'est permise. L'opérateur doit confirmer l'arrêt du job
avant une récupération contrôlée. Ne pas marquer automatiquement FAILED une session
sur un simple dépassement de délai HTTP : le job peut encore fonctionner.

## Mémoire et performance

La mesure initiale XSSFWorkbook sur 65 000 lignes BAC × 18 colonnes échouait avec
une heap de 640 MiB. XLSX utilise désormais SAX Apache POI avec une table de chaînes
partagées en lecture seule et un spool binaire temporaire par passe. Les cellules
ne sont pas gardées en mémoire ; les chaînes partagées et les identifiants du
contrôle de doublons y restent. XLS conserve WorkbookFactory et charge le workbook.

La limite par défaut est 100 000 lignes (`app.import-max-rows`). Ne pas la relever
à 150k/500k sans mesure complète. La prévalidation et Batch lisent deux fois le
fichier ; le spool est supprimé entre les passes. Sur Cloud Run, les fichiers /tmp
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

Tous les fichiers sont synthétiques ; PostgreSQL Testcontainers est obligatoire.
Aucun DB_URL réel, Supabase, Cloud Run ou fichier officiel n'est utilisé.
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

Le lancement est toujours synchrone dans la requête HTTP. Un timeout HTTP ne prouve
pas l'arrêt du job. Pour une exploitation répétée proche des limites de temps,
ou pour une récupération fiable après arrêt d'instance, évaluer ultérieurement
Cloud Run Jobs et un stockage de fichiers durable. Aucune migration d'infrastructure
n'est réalisée dans P2-E. Le système actuel ne garantit pas un import achevé après
interruption forcée d'une instance.

# Tests backend

## Commandes et prérequis

Java 21 et Maven sont nécessaires. Docker doit être accessible à l’utilisateur
Maven pour les intégrations ; le premier lancement peut télécharger les images.

```sh
mvn -B test
mvn -B verify
```

`test` lance les `*Test` unitaires/ciblés sans conteneur. `verify` ajoute les `*IT`
via Maven Failsafe, actuellement `BackendIT` et `ImportIT`. Une absence de Docker
fait échouer les intégrations : ni skip silencieux ni fallback vers une base externe.
Les rapports de référence sont dans `target/surefire-reports` et `target/failsafe-reports`.

## Isolation

PostgreSQL `17.11-alpine` est lancé sur un port aléatoire par Testcontainers, sans
réutilisation entre exécutions. `@ServiceConnection` fournit les connexions JDBC
et Flyway, indépendamment de `DB_URL`.

Le profil `integration` est forcé ; `application-local` n’est pas chargé. Les
propriétés de test imposent une URL datasource inutilisable en secours, des
identifiants synthétiques et aucune URL Flyway distincte. Les tests contrôlent la
connexion obtenue. Aucun secret local, Supabase ou Cloud Run. Les suites standard
n'utilisent aucun fichier officiel ; le contrôle opt-in ci-dessous exige des chemins explicites.

Flyway applique les migrations réelles ; Hibernate utilise `validate`. Spring Batch
initialise ses tables, mais aucun job n’est lancé automatiquement au démarrage.
Les tests d’import déclenchent ensuite explicitement leurs jobs synthétiques.

## Répartition

| Suite | Responsabilité |
| --- | --- |
| `*Test` | Services/contrôleurs ciblés et validation Excel avec petits fichiers POI |
| `BackendIT` | Contexte Spring, Flyway, mappings, contraintes/index, JPA/SQL, catalogue, REST, erreurs, Security, CORS, JobRepository, binding Hikari et politique Actuator |
| `ImportIT` | Imports BAC/BEPC/C1AS, fichiers invalides, idempotence, sessions, concurrence de réservation, commits/nettoyage, publication, fichiers temporaires |
| `OfficialFormatTest` | Headers officiels, 5 artefacts C1AS, scores BEPC, 6 décisions, zéros source, 121 140 lignes et limite configurable |

`BackendIT` utilise des fixtures transactionnelles annulées après chaque test et
des savepoints pour les contraintes. MockMvc exécute MVC et les filtres sans serveur
HTTP réseau. Les mêmes identifiants dans différentes sessions ont des notes distinctes.

`ImportIT` ne s’enveloppe pas dans une transaction de test : les chunks commitent
réellement. Des erreurs PostgreSQL sont injectées après 10 %, 50 % et 90 % d’un
fichier synthétique de 5 000 lignes ; le nettoyage, FAILED et la nouvelle tentative
sont vérifiés. La suppression des fixtures est limitée à la base du conteneur.

## Contrôles manuels de volume et d’atomicité

Hors conventions `*Test` / `*IT`, donc exclus de `verify` par défaut :

```sh
mvn -B -Dit.test=ImportVolumeCheck -Dimport.volume.rows=10000 -DargLine=-Xmx640m verify
mvn -B -Dit.test=ImportVolumeCheck -Dimport.volume.rows=65000 -DargLine=-Xmx640m verify
mvn -B -Dit.test=ImportAtomicityCheck -DargLine=-Xmx640m verify
```

`ImportVolumeCheck` génère un BAC synthétique à 18 colonnes et mesure génération,
import, débit, taille et indicateur heap. `ImportAtomicityCheck` injecte une erreur
après 40 000 écritures commitées sur 65 000, puis vérifie zéro résultat conservé,
FAILED, publication interdite, nettoyage et nouvelle tentative réussie.
Voir [la politique d’import](../../docs/IMPORTS.md) pour les limites de ces mesures.

## Fichiers officiels locaux, contrôle explicite

```sh
mvn -B -Dit.test=OfficialFilesCheck \
  -Dimport.official.c1as=../RESU_C1AS_2026_15_07-2026_121140.xlsx \
  -Dimport.official.bepc=../Res_Global_.Bepc_15-07-2026_75694.xlsx \
  -DargLine=-Xmx640m verify
```

Ce contrôle est exclu de `verify` par défaut. Il échoue si un chemin manque.
Les fichiers restent hors git, en lecture seule ; les validations utilisent des
copies temporaires. SHA-256 avant/après confirme que les originaux ne changent pas.
Le datasource JDBC doit être exactement celui du PostgreSQL Testcontainers.
Flyway V1+V2 et Hibernate validate sont exécutés. Aucune publication des résultats
officiels : vérification de READY, du compte réel et de published_at NULL.

Les 196 834 lignes sont comparées une par une : identifiants numériques et texte
source, noms, territoires, centres, autres champs métier, décisions et notes.
Les assertions textuelles n'affichent pas les valeurs personnelles en cas d'échec.
La comparaison PostgreSQL utilise un curseur par lots de 500 ; le reader reste SAX.
Les fichiers upload/spool/copie doivent disparaître. Les compteurs, durées séparées
du preflight et du Batch, débit et indicateur heap sont écrits dans
`target/official-audit/{c1as,bepc}-import.json` sans identité personnelle.
Les six notes BEPC déterministes sont comparées au XML brut, pas à une valeur
tronquée à neuf décimales.

`ImportIT` vérifie aussi la précision BEPC sur des fixtures synthétiques via JPA,
JSON, recherche et statistiques. `BackendIT` vérifie la migration V1 → V2 avec
une note préexistante dans un schéma distinct du conteneur. Les données historiques
restent inchangées ; leurs identifiants source restent NULL.

## Limites

Ces tests ne simulent pas l’arrêt brutal de Cloud Run, le réseau Supabase ou une
charge nationale. Ils ne constituent pas un benchmark distribué ni une qualification
500k lignes. Pas de couverture JaCoCo ajoutée ; privilégier des assertions utiles
sur la sécurité, les sessions, le schéma et les effets transactionnels.

## Configuration production (P4-A)

`BackendIT` injecte des variables synthétiques DB_POOL_SIZE=3, DB_MIN_IDLE=1,
DB_CONNECTION_TIMEOUT_MS=12345, DB_IDLE_TIMEOUT_MS=120000 et
DB_MAX_LIFETIME_MS=600000. Les assertions portent sur le HikariDataSource réel,
pas sur les propriétés YAML. Avant correction du préfixe, le maximum constaté
était 10 au lieu de 3 ; après correction toutes les valeurs sont bindées.

Les tests vérifient les trois endpoints health publics sans détails, metrics avec
credentials HTTP Basic corrects/incorrects et rôle insuffisant, les endpoints
Actuator refusés et Swagger public. La DB réelle valide le cas UP. Le cas DOWN
remplace temporairement le contributeur `db` par un indicateur contrôlé, puis
restaure l’original : readiness échoue, liveness reste UP et aucun détail sensible
n’est envoyé. Ce test ne simule pas une latence réseau ou une panne Supabase réelle.

`AdminConfigurationTest` charge la configuration Security et les propriétés YAML
réelles, sans datasource ni variables système/locales. Il vérifie que l’absence
d’ADMIN_PASSWORD empêche le démarrage et qu’une valeur synthétique explicite crée
un administrateur dont le mot de passe est encodé.

# National Exams Mauritania — Backend

Backend Java 21 / Spring Boot pour importer et consulter les résultats :

- Baccalauréat
- BEPC
- Concours d’entrée en première année du collège (C1AS)

## Technologies

- Java 21
- Spring Boot 3.5
- Spring Data JPA
- Spring Batch
- Spring Security
- Apache POI
- PostgreSQL
- Flyway
- Springdoc OpenAPI / Swagger UI

## 1. Lancer PostgreSQL

```bash
docker compose up -d
```

## 2. Lancer le backend

```bash
mvn spring-boot:run
```

Sous Windows :

```powershell
mvn spring-boot:run
```

## 3. Swagger

Ouvrir :

```text
http://localhost:8080/swagger-ui.html
```

## 4. Identifiants admin de développement

```text
Utilisateur : admin
Mot de passe : change-me-now
```

Ne pas utiliser ces identifiants en production. Utiliser les variables :

```bash
export ADMIN_USERNAME=admin
export ADMIN_PASSWORD='un-mot-de-passe-fort'
```

## 5. Importer le BAC

```bash
curl -u admin:change-me-now \
  -X POST "http://localhost:8080/api/admin/imports?examType=BAC&year=2026&sessionType=NORMAL" \
  -F "file=@/chemin/RESULTATS_BAC_2026.xlsx"
```

## 6. Importer le BEPC

```bash
curl -u admin:change-me-now \
  -X POST "http://localhost:8080/api/admin/imports?examType=BEPC&year=2026" \
  -F "file=@/chemin/Res_Global_Bepc_2026.xlsx"
```

## 7. Importer le C1AS

```bash
curl -u admin:change-me-now \
  -X POST "http://localhost:8080/api/admin/imports?examType=C1AS&year=2026" \
  -F "file=@/chemin/RESU_C1AS_2026.xlsx"
```

## 8. Publier la session

```bash
curl -u admin:change-me-now \
  -X POST "http://localhost:8080/api/admin/imports/1/publish"
```

## 9. Chercher un résultat

BAC :

```bash
curl "http://localhost:8080/api/public/results/bac/2026/43831"
```

BEPC :

```bash
curl "http://localhost:8080/api/public/results/bepc/2026/24634"
```

C1AS, par numéro de dossier :

```bash
curl "http://localhost:8080/api/public/results/c1as/2026/dossier/101449037"
```

## Remarques importantes

- Pour C1AS, `NODOSS` est utilisé comme identifiant public principal.
- Le champ `Numéro_C1AS` peut être local à un centre et n’est donc pas déclaré unique.
- Les dates et lieux de naissance ne sont pas stockés dans ce premier MVP.
- Une seule ligne invalide fait échouer l’import afin d’éviter de publier un fichier incomplet. Le prochain lot ajoutera une table détaillée des erreurs et un écran de prévisualisation.
- L’import est synchrone dans ce premier lot. Une exécution asynchrone et une API de suivi seront ajoutées ensuite.

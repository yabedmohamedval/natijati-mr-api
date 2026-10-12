# CI/CD — backend et configuration WIF commune

## Politique

- PR vers `main` et push `main` : CI uniquement, `contents: read`, aucun secret ni OIDC.
- Production : Actions → **Backend production** → Run workflow → branche **main**.
  Aucun paramètre de ref arbitraire : le SHA du lancement est validé puis déployé,
  même si `main` avance entre-temps. Les autres branches ne passent pas le garde-fou.
- Le job `validate` réutilise `ci.yml` au même commit. Le job `deploy` attend son succès
  et l’Environment `production`. Les approbations doivent être configurées dans GitHub.
- Les déploiements sont sérialisés, `cancel-in-progress: false`. GitHub peut remplacer
  une exécution encore en attente ; ce mécanisme n’est pas une file FIFO de releases.
- Aucune release SemVer ni `latest` nécessaire : SHA Git complet, digest d’image et
  révision Cloud Run dans le résumé du workflow permettent la traçabilité.

## CI backend

Runner GitHub `ubuntu-24.04`, Temurin 21, cache Maven officiel setup-java. Docker est
fourni par le runner ; PostgreSQL 17.11 est lancé uniquement par Testcontainers.
`mvn -B test`, puis `mvn -B verify` (qui relance les tests unitaires), puis
`docker build`. Pas de connexion Supabase, pas de push d’image.
Rapports Surefire/Failsafe conservés 7 jours uniquement en cas d’échec ; pas de JAR
ou d’image archivé inutilement. Limite : 25 minutes.

## CD backend

Le Dockerfile existant construit l’image directement sur GitHub. Ce choix évite
Cloud Build, son compte de build et les permissions supplémentaires. La CI et le CD
exercent le même Dockerfile ; le CD reconstruit après validation pour éviter un
transport d’image volumineux entre jobs. Les dépendances/images de base ne sont pas
entièrement verrouillées par digest : même SHA source ne signifie pas build bit à bit
identique. Le **digest réellement poussé** est toujours celui déployé.

1. Valider projet `natijati-mr`, région `europe-west1`, service `natijati-api`.
2. Construire `$REGION-docker.pkg.dev/$PROJECT/natijati-repo/natijati-api:$GITHUB_SHA`
   avant toute authentification cloud.
3. OIDC → WIF → compte de déploiement : jeton OAuth éphémère (1 h), sans clé privée.
   `CLOUDSDK_AUTH_ACCESS_TOKEN` est utilisé uniquement dans les étapes GCP et Docker.
   Aucune ADC persistante backend ; le jeton ne doit jamais être affiché.
4. Lire le service existant sans afficher sa configuration. Refuser s’il n’existe pas,
   s’il comporte plusieurs conteneurs ou si DB_PASSWORD / ADMIN_PASSWORD n’ont pas
   tous deux de référence Secret Manager.
5. Pousser l’image, récupérer son digest SHA256, mettre à jour **uniquement l’image**
   via `gcloud run services update`, puis router 100 % du trafic vers la dernière révision.
   Pas de modification des variables, secrets, CPU, mémoire, concurrency, sondes ou IAM.
6. Vérifier la révision prête, son image, les variables/secrets inchangés et le trafic.
7. Vérifier HTTP 200 et JSON sur `/actuator/health` (UP) et `/api/public/catalog/exams`.
   Aucun numéro de candidat. Les échecs font échouer le workflow, sans rollback automatique.

`update-traffic --to-latest` est intentionnel : pas de canary ni de conservation d’un
ancien partage de trafic. Ne pas utiliser ce workflow pour une stratégie multi-révisions
sans l’adapter. Un timeout ou un smoke test en échec peut laisser une révision déployée :
consulter l’état Cloud Run avant de relancer.

## Variables GitHub

Dans chaque dépôt : Settings → Environments → `production` → Environment variables.
Ce sont des identifiants, **pas des secrets**. Les Repository variables fonctionnent
également, mais un seul emplacement évite les surcharges ambiguës.

| Dépôt | Variable | Valeur attendue |
|---|---|---|
| API | `GCP_PROJECT_ID` | `natijati-mr` |
| API | `GCP_REGION` | `europe-west1` |
| API | `GCP_WORKLOAD_IDENTITY_PROVIDER` | `projects/<PROJECT_NUMBER>/locations/global/workloadIdentityPools/<POOL_ID>/providers/<PROVIDER_ID>` |
| API | `GCP_SERVICE_ACCOUNT` | `<DEPLOY_SA_ID>@natijati-mr.iam.gserviceaccount.com` |
| Front | `FIREBASE_PROJECT_ID` | `natijati-mr-3232f` |
| Front | `FIREBASE_WORKLOAD_IDENTITY_PROVIDER` | chemin du provider dans le projet Firebase |
| Front | `FIREBASE_SERVICE_ACCOUNT` | `<DEPLOY_SA_ID>@natijati-mr-3232f.iam.gserviceaccount.com` |

**Aucun Repository Secret nécessaire** pour cette stratégie. DB_URL, DB_USERNAME,
DB_POOL_SIZE, IMPORT_DIRECTORY et les mappings DB_PASSWORD / ADMIN_PASSWORD restent
sur le service Cloud Run. Le compte runtime existant doit pouvoir lire ces deux secrets.
Ne pas donner cette permission de lecture au compte CI de déploiement.

## Préparation manuelle — commandes NON exécutées par les workflows

À exécuter ultérieurement par un opérateur autorisé, après revue. Aucun workflow ne
crée de pool, provider, compte, dépôt Artifact Registry, service Cloud Run ou site Firebase.
Les deux projets/services doivent déjà exister. Ne jamais utiliser Owner/Editor pour
les comptes de déploiement.

### 1. Préparer GitHub

- Créer `production` dans **chacun** des dépôts.
- Autoriser uniquement la branche `main` pour cet Environment.
- Configurer un reviewer et, si disponible/adapté, empêcher l’auto-approbation et le
  contournement administrateur. La disponibilité dépend du plan et de la visibilité.
- Protéger `main` : PR, revue des changements workflows/scripts, checks
  `Backend validation` ou `Frontend validation` requis après leur première exécution.
  Ne pas rendre le workflow manuel de production obligatoire pour merger.
- Laisser les permissions GITHUB_TOKEN par défaut en lecture. Pas d’auto-merge Dependabot.

### 2. Créer deux identités séparées

Exécuter la procédure ci-dessous **une fois par dépôt**, avec des valeurs distinctes :

```bash
# Backend
DEPLOY_PROJECT_ID='natijati-mr'
GITHUB_REPOSITORY='yabedmohamedval/natijati-mr-api'
DEPLOY_SA_ID='<backend-deploy-sa-id>'
WIF_POOL_ID='<backend-pool-id>'
WIF_PROVIDER_ID='<backend-provider-id>'

# Pour la deuxième exécution, remplacer ces cinq variables :
# DEPLOY_PROJECT_ID='natijati-mr-3232f'
# GITHUB_REPOSITORY='yabedmohamedval/natijati-mr-front'
# DEPLOY_SA_ID='<frontend-deploy-sa-id>'
# WIF_POOL_ID='<frontend-pool-id>'
# WIF_PROVIDER_ID='<frontend-provider-id>'

# IDs numériques obtenus dans l’API GitHub officielle, sans token dans la commande.
GITHUB_OWNER_ID='<numeric-owner-id>'
GITHUB_REPOSITORY_ID='<numeric-repository-id>'
# Exemple de lecture manuelle : gh api "repos/$GITHUB_REPOSITORY" --jq '{repo_id: .id, owner_id: .owner.id}'
DEPLOY_PROJECT_NUMBER=$(gcloud projects describe "$DEPLOY_PROJECT_ID" --format='value(projectNumber)')
DEPLOY_SA_EMAIL="$DEPLOY_SA_ID@$DEPLOY_PROJECT_ID.iam.gserviceaccount.com"

gcloud services enable iam.googleapis.com iamcredentials.googleapis.com sts.googleapis.com \
  --project="$DEPLOY_PROJECT_ID"
gcloud iam service-accounts create "$DEPLOY_SA_ID" --project="$DEPLOY_PROJECT_ID" \
  --display-name='GitHub production deployment'
gcloud iam workload-identity-pools create "$WIF_POOL_ID" \
  --project="$DEPLOY_PROJECT_ID" --location=global --display-name='GitHub production'
gcloud iam workload-identity-pools providers create-oidc "$WIF_PROVIDER_ID" \
  --project="$DEPLOY_PROJECT_ID" --location=global --workload-identity-pool="$WIF_POOL_ID" \
  --issuer-uri='https://token.actions.githubusercontent.com' \
  --attribute-mapping='google.subject=assertion.sub,attribute.repository_id=assertion.repository_id,attribute.repository_owner_id=assertion.repository_owner_id' \
  --attribute-condition="assertion.repository_owner_id == '$GITHUB_OWNER_ID' && assertion.repository_id == '$GITHUB_REPOSITORY_ID' && assertion.repository == '$GITHUB_REPOSITORY' && assertion.ref == 'refs/heads/main' && assertion.event_name == 'workflow_dispatch' && assertion.sub == 'repo:$GITHUB_REPOSITORY:environment:production' && assertion.workflow_ref == '$GITHUB_REPOSITORY/.github/workflows/deploy.yml@refs/heads/main'"
gcloud iam service-accounts add-iam-policy-binding "$DEPLOY_SA_EMAIL" \
  --project="$DEPLOY_PROJECT_ID" --role=roles/iam.workloadIdentityUser \
  --member="principalSet://iam.googleapis.com/projects/$DEPLOY_PROJECT_NUMBER/locations/global/workloadIdentityPools/$WIF_POOL_ID/attribute.repository_id/$GITHUB_REPOSITORY_ID"
```

Les IDs numériques évitent les risques de réattribution des noms de dépôt/propriétaire.
La condition lie aussi main, workflow_dispatch, le fichier de déploiement et
l’Environment production. Adapter explicitement si le subject OIDC GitHub a été
personnalisé. Attendre la propagation IAM avant le premier essai.

### 3. IAM backend, sur les ressources existantes

```bash
DEPLOY_PROJECT_ID='natijati-mr'
DEPLOY_REGION='europe-west1'
DEPLOY_SA_EMAIL='<backend-deploy-sa-id>@natijati-mr.iam.gserviceaccount.com'
RUNTIME_SA_EMAIL='<existing-cloud-run-runtime-sa-email>'

gcloud services enable run.googleapis.com artifactregistry.googleapis.com --project="$DEPLOY_PROJECT_ID"
gcloud artifacts repositories add-iam-policy-binding natijati-repo \
  --project="$DEPLOY_PROJECT_ID" --location="$DEPLOY_REGION" \
  --member="serviceAccount:$DEPLOY_SA_EMAIL" --role=roles/artifactregistry.writer
gcloud run services add-iam-policy-binding natijati-api \
  --project="$DEPLOY_PROJECT_ID" --region="$DEPLOY_REGION" \
  --member="serviceAccount:$DEPLOY_SA_EMAIL" --role=roles/run.developer
gcloud iam service-accounts add-iam-policy-binding "$RUNTIME_SA_EMAIL" \
  --project="$DEPLOY_PROJECT_ID" --member="serviceAccount:$DEPLOY_SA_EMAIL" \
  --role=roles/iam.serviceAccountUser
```

`artifactregistry.writer` inclut la lecture nécessaire au digest et au déploiement.
`run.developer` suffit pour changer l’image et le trafic du service existant ; pas
besoin de `run.admin`. `serviceAccountUser` porte uniquement sur l’identité runtime.
Le Cloud Run service agent doit conserver ses permissions existantes de lecture de
l’image. Ne pas remplacer ses bindings. Aucun rôle Cloud Build nécessaire.

Prérequis séparé, à vérifier par l’opérateur : les versions des secrets existent et
le runtime a `roles/secretmanager.secretAccessor` **sur chaque secret concerné**.
Ne pas stocker leur valeur dans GitHub. Le workflow vérifie les mappings sans lire
les secrets ; il ne peut pas confirmer leurs valeurs ou les permissions runtime avant
le démarrage de la révision.

### 4. IAM frontend

```bash
DEPLOY_PROJECT_ID='natijati-mr-3232f'
DEPLOY_SA_EMAIL='<frontend-deploy-sa-id>@natijati-mr-3232f.iam.gserviceaccount.com'
gcloud services enable firebase.googleapis.com firebasehosting.googleapis.com apikeys.googleapis.com \
  --project="$DEPLOY_PROJECT_ID"
gcloud projects add-iam-policy-binding "$DEPLOY_PROJECT_ID" \
  --member="serviceAccount:$DEPLOY_SA_EMAIL" --role=roles/firebasehosting.admin
gcloud projects add-iam-policy-binding "$DEPLOY_PROJECT_ID" \
  --member="serviceAccount:$DEPLOY_SA_EMAIL" --role=roles/serviceusage.apiKeysViewer
```

Ces rôles prédéfinis correspondent au déploiement Hosting via Firebase CLI selon sa
documentation. Pas de Firebase Admin global, Cloud Run, base de données ou Secret
Manager pour cette identité. Ne pas confondre Firebase CLI (ADC) et Firebase Admin SDK.

### 5. Renseigner les variables et effectuer la première recette distante

Copier les identifiants provider et compte créés dans les variables du bon Environment.
Vérifier les mappings Secret Manager backend et l’accès public existant du service.
Après commit/push autorisés ultérieurement : faire passer la CI, examiner le SHA,
puis lancer manuellement le workflow depuis main et approuver production.
Aucune de ces actions distantes n’est effectuée pendant P4-B.

## Revenir à une révision backend précédente

Le résumé conserve l’ancien trafic. Après diagnostic, utiliser la console Cloud Run
ou, **manuellement seulement** :

```bash
gcloud run services update-traffic natijati-api \
  --project=natijati-mr --region=europe-west1 \
  --to-revisions='<previous-ready-revision>=100'
```

Vérifier ensuite les deux endpoints publics. Ceci ne restaure pas la base : Flyway
s’exécute au démarrage ; une évolution incompatible de schéma demande son propre plan.

## Maintenance et sources

Actions officielles fixées à des SHA complets, commentées avec leur version vérifiée :
checkout 7.0.1, setup-java 6.1.0, setup-node 7.1.0, upload-artifact 7.0.2,
auth 3 et setup-gcloud 3.0.1. Dependabot hebdomadaire propose des PR groupées ; les
montées majeures demandent une revue manuelle. Aucun auto-merge/déploiement.
Les caches officiels contiennent Maven/npm, jamais target/dist/node_modules.
Les fichiers temporaires `gha-creds-*.json` sont ignorés ; aucun artifact ne doit les inclure.

- [Auth Google / WIF et jetons](https://github.com/google-github-actions/auth)
- [WIF pour pipelines, restrictions GitHub](https://docs.cloud.google.com/iam/docs/workload-identity-federation-with-deployment-pipelines)
- [Rôles de déploiement Cloud Run](https://docs.cloud.google.com/run/docs/deploying)
- [Mise à jour d’un service existant](https://docs.cloud.google.com/sdk/gcloud/reference/run/services/update)
- [Firebase CLI et ADC](https://firebase.google.com/docs/cli#cli-ci-systems)
- [Rôles Firebase Hosting](https://firebase.google.com/docs/projects/iam/roles-predefined-product#hosting)
- [Logiciels du runner Ubuntu 24.04](https://github.com/actions/runner-images/blob/main/images/ubuntu/Ubuntu2404-Readme.md)

## Limites de validation

La validation locale couvre commandes applicatives, Docker et syntaxe des workflows.
Elle ne prouve pas l’échange OIDC, les bindings IAM, les approbations GitHub ou les
smoke tests distants. Ceux-ci nécessitent la préparation manuelle puis un premier
déploiement autorisé. Aucun appel au cloud n’est nécessaire pour valider la CI locale.

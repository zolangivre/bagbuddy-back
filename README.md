# BagBuddy — backend

Backend en microservices Spring Boot (Eureka, API gateway, trip / transaction /
review / stripe services) + Keycloak pour l'authentification, orchestrés avec
Docker Compose pour le développement local.

C'est le repo backend de BagBuddy. Le front web (Angular) vit dans un repo
séparé : `bagbuddy-front`. Les deux se lancent indépendamment ; le front tape
sur la gateway en `http://localhost:8080` et sur Keycloak en
`http://localhost:8000`.

## Prérequis

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) lancé

## Setup

```bash
cp .env.example .env
```

Remplis `.env` avec les mots de passe locaux de ton choix (ils n'existent que
dans tes conteneurs Postgres/Keycloak locaux, rien n'est envoyé à l'extérieur —
voir les commentaires de `.env.example` pour le rôle de chaque valeur).

## Tout lancer

```bash
docker compose -f docker-compose.dev.yml up --build -d
```

Le premier run télécharge/build toutes les images, ça peut prendre quelques
minutes. Une fois debout, tout ceci est prêt sans configuration supplémentaire :

| Service                 | URL                              |
| ----------------------- | -------------------------------- |
| API gateway             | http://localhost:8080            |
| Keycloak                | http://localhost:8000            |
| Console admin Keycloak  | http://localhost:8000/admin (`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` du `.env`) |
| Dashboard Eureka        | http://localhost:8761            |
| trip-service            | http://localhost:8082            |
| transaction-service     | http://localhost:8083            |
| review-service          | http://localhost:8084            |

Keycloak réimporte le realm `bagbuddy` depuis
`keycloak/import/bagbuddy-realm.json` à chaque démarrage — les clients
`bagbuddy-web` (front Angular) et `bagbuddy-mobile` (app Expo historique) ainsi
qu'un compte de test sont prêts immédiatement, aucune configuration manuelle de
Keycloak nécessaire :

- **username :** `testuser`
- **password :** `Test1234!`

`stripe-service` est défini mais commenté dans `docker-compose.dev.yml` — il lui
faut de vraies clés de test `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` dans
`.env` pour servir à quelque chose. Décommente son bloc une fois que tu les as.

## Front web

Le client Keycloak `bagbuddy-web` est un client public en PKCE configuré pour
`http://localhost:4200` (redirect URIs, web origins, post-logout). La gateway
autorise le CORS depuis `CORS_ALLOWED_ORIGINS` (`http://localhost:4200` par
défaut, plusieurs origines possibles séparées par des virgules). Si tu sers le
front sur un autre port ou domaine, mets à jour les deux :
`CORS_ALLOWED_ORIGINS` dans `.env` et le client `bagbuddy-web` dans le realm.

## Commandes du quotidien

Redémarrer un service après une modif de code :
```bash
docker compose -f docker-compose.dev.yml restart <service_name>
```

Tout arrêter (ajoute `-v` pour effacer aussi les bases et repartir de zéro) :
```bash
docker compose -f docker-compose.dev.yml down -v
```

## Ajouter un microservice

N'oublie pas d'ajouter un Dockerfile et le bloc de service correspondant dans
`docker-compose.dev.yml` quand tu en crées un.

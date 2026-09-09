# BagBuddy — backend

Backend en microservices Spring Boot (Eureka, API gateway, trip / transaction /
review / stripe / user services) + Keycloak pour l'authentification, orchestrés
avec Docker Compose pour le développement local.

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

Deux valeurs méritent une attention particulière, les secrets des clients
confidentiels Keycloak. Ils sont injectés dans Keycloak à l'import du realm,
donc ils ne figurent nulle part dans le dépôt — génère-les une fois chacun avec
`openssl rand -base64 32` :

- `KEYCLOAK_SERVICE_CLIENT_SECRET` — client `bagbuddy`, appels
  machine-à-machine (tarification d'une réservation, confirmation d'un
  paiement) ;
- `KEYCLOAK_ACCOUNTS_CLIENT_SECRET` — client `bagbuddy-accounts`, utilisé par
  `userservice` pour l'API d'administration de Keycloak (inscription,
  changement d'email, mot de passe). Secret distinct : ces droits ne doivent
  pas être portés par le client de tarification.

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
| user-service            | http://localhost:8086            |

Keycloak réimporte le realm `bagbuddy` depuis
`keycloak/import/bagbuddy-realm.json` à chaque démarrage — les clients
`bagbuddy-web` (front Angular) et `bagbuddy-mobile` (app Expo historique) ainsi
qu'un compte de test sont prêts immédiatement, aucune configuration manuelle de
Keycloak nécessaire :

- **username :** `testuser`
- **password :** `Test1234!`

`stripe-service` est défini mais commenté dans `docker-compose.dev.yml` — il lui
faut de vraies clés de test `STRIPE_SECRET_KEY` / `STRIPE_PUBLISHABLE_KEY` /
`STRIPE_WEBHOOK_SECRET` dans `.env` pour servir à quelque chose. Décommente son
bloc une fois que tu les as.

## Authentification

Tous les services sont des **resource servers OAuth2** : chaque requête doit
porter un `Authorization: Bearer <access_token>` Keycloak valide, sinon c'est
401. Le front obtient ce token en PKCE auprès de Keycloak, puis appelle la
gateway avec.

Au-delà de l'authentification, chaque service applique ses propres règles de
propriété : on ne modifie que ses propres annonces, on ne lit que les
transactions dont on est partie, et les coordonnées (email, téléphone) d'un
autre membre ne sortent jamais des endpoints de navigation.

Deux endpoints ne sont **pas** joignables avec un token utilisateur — ils
exigent le rôle realm `service`, porté uniquement par le client confidentiel
`bagbuddy` :

| Endpoint                                    | Appelé par           | Pourquoi |
| ------------------------------------------- | -------------------- | -------- |
| `GET /trips/internal/{id}`                     | transaction-service  | tarifer une réservation contre l'annonce réelle |
| `POST /trips/internal/{id}/reserve`            | transaction-service  | décrémenter le poids restant sous verrou |
| `POST /transactions/internal/{id}/payment`     | stripe-service       | enregistrer un paiement confirmé par webhook signé |

À noter pour le front : le poids restant d'une annonce n'est plus à décrémenter
côté client après une réservation — `transaction-service` s'en charge lors de la
création de la transaction, sous verrou, ce qui évite de survendre la capacité.

Le montant d'un paiement n'est jamais fourni par le client : `stripe-service`
lit la transaction, qui a elle-même été tarifée côté serveur à partir de
l'annonce. Et le passage en « payé » n'est accepté que depuis un webhook Stripe
dont la signature est vérifiée (`STRIPE_WEBHOOK_SECRET`).

## Front web

Le front web ne renvoie pas vers les pages de Keycloak : il a ses propres
écrans de connexion, d'inscription et de compte. Le client `bagbuddy-web` est
donc un client public avec le **grant `password`** (direct access grant) activé
et le flux redirection désactivé, et c'est `userservice` qui relaie vers l'API
d'administration ce qu'un navigateur ne peut pas porter :

| Appel | Jeton | Effet |
| --- | --- | --- |
| `POST /users/register` | aucun | crée le compte Keycloak (email = identifiant) |
| `PUT /users/me/identity` | utilisateur | prénom, nom, email |
| `PUT /users/me/password` | utilisateur | vérifie l'actuel, puis le remplace |

Deux réglages d'origine à tenir alignés si tu sers le front ailleurs que sur
`http://localhost:4200` : les **web origins** du client `bagbuddy-web` dans le
realm (sans elles, Keycloak refuse les appels token / userinfo / logout du
front, qui sont de simples `fetch` cross-origin) et `CORS_ALLOWED_ORIGINS` dans
`.env` pour la gateway (plusieurs origines possibles, séparées par des
virgules).

## Commandes du quotidien

Redémarrer un service après une modif de code :
```bash
docker compose -f docker-compose.dev.yml restart <service_name>
```

Tout arrêter (ajoute `-v` pour effacer aussi les bases et repartir de zéro) :
```bash
docker compose -f docker-compose.dev.yml down -v
```

## Tests

Chaque service se teste indépendamment ; les tests tournent sur une base H2 en
mémoire, sans Docker ni Keycloak :

```bash
cd tripservice && ./mvnw test
```

`tripservice`, `transactionservice` et `userservice` embarquent des tests de
sécurité qui vérifient concrètement les règles ci-dessus (accès anonyme refusé,
propriété, masquage des coordonnées, montant non falsifiable).

## Ajouter un microservice

N'oublie pas d'ajouter un Dockerfile et le bloc de service correspondant dans
`docker-compose.dev.yml` quand tu en crées un — avec ses variables `PORT`,
`JWT_ISSUER_URIS`, `JWT_JWK_SET_URI` et `JWT_AUDIENCE`.

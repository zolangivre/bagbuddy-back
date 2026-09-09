# BagBuddy — backend

Backend en microservices Spring Boot (Eureka, API gateway, trip / transaction /
review / stripe / user services) + Keycloak pour l'authentification, orchestrés
avec Docker Compose pour le développement local.

L'API est en **GraphQL** : chaque service expose son propre schéma sous son
préfixe de chemin (`/trips/graphql`, `/transactions/graphql`, ...). Il ne reste
du REST que là où GraphQL n'a pas de sens — webhook Stripe et appels
service-à-service (voir « API GraphQL » plus bas).

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

## Service discovery

Les services s'enregistrent aupres d'Eureka au demarrage, et l'`apigateway`
resout ses routes a travers le registre : il ne connait aucune URL de service,
seulement des `lb://trip-service`, `lb://user-service`, etc. Le dashboard
`http://localhost:8761` liste ce qui est reellement enregistre — c'est le
premier endroit ou regarder quand une route repond mal.

Deux comportements a connaitre :

- **Un service non enregistre renvoie `503`**, et non un refus de connexion.
  `stripe-service` etant commente dans `docker-compose.dev.yml`, `/stripe/**`
  repond 503 par defaut : c'est normal.
- **L'enregistrement n'est pas instantane.** Les cadences sont volontairement
  serrees en dev (renouvellement et rafraichissement toutes les 5 s, eviction
  toutes les 10 s, auto-preservation coupee, cache du load-balancer a 5 s) : un
  service redemarre redevient routable en ~4 s, la ou les valeurs par defaut
  d'Eureka demanderaient 30 a 90 s. Ce sont des reglages de developpement — ils
  generent beaucoup de trafic de controle et desactivent le filet de securite
  qui protege le registre d'une micro-coupure reseau. A elargir avant toute
  mise en production.

Les trois appels service-a-service (`transactionservice` -> `tripservice`,
`stripeservice` -> `transactionservice`) restent volontairement sur des URLs
statiques : ils sont peu nombreux, figes, et sur le chemin du paiement.

## API GraphQL

Un schéma par service, servi sous le préfixe du service : la gateway route par
simple préfixe de chemin, sans réécriture, et un appel direct au conteneur
emprunte exactement la même URL.

| Service | Endpoint (via la gateway) | Console GraphiQL (accès direct, dev) |
| --- | --- | --- |
| trip-service        | `POST http://localhost:8080/trips/graphql`        | http://localhost:8082/trips/graphiql |
| transaction-service | `POST http://localhost:8080/transactions/graphql` | http://localhost:8083/transactions/graphiql |
| review-service      | `POST http://localhost:8080/reviews/graphql`      | http://localhost:8084/reviews/graphiql |
| user-service        | `POST http://localhost:8080/users/graphql`        | http://localhost:8086/users/graphiql |
| stripe-service      | `POST http://localhost:8080/stripe/graphql`       | http://localhost:8085/stripe/graphiql |

Le schéma de chaque service est lisible dans
`<service>/src/main/resources/graphql/schema.graphqls` — c'est la source de
vérité de ce que l'API accepte et renvoie. La console GraphiQL est activée par
`GRAPHIQL_ENABLED=true` (déjà positionné dans `docker-compose.dev.yml`) et
coupée par défaut ailleurs.

Un appel ressemble à ceci :

```bash
curl http://localhost:8080/trips/graphql \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ activeTrips { id departureAirport arrivalAirport pricePerKg userInfo { name } } }"}'
```

Trois scalaires maison complètent les types de base de GraphQL, qui n'en a que
cinq : `DateTime` (ISO-8601 local), `BigDecimal` (prix et poids, en décimal
exact — sérialiser un montant en `Float` perdrait de la précision) et `Long`.

### Ce qui reste volontairement en REST

| Endpoint | Pourquoi |
| --- | --- |
| `POST /stripe/webhook` | c'est Stripe qui appelle, avec le corps brut nécessaire à la vérification de signature ; l'appelant ne choisit pas ses champs |
| `/trips/internal/**`, `/transactions/internal/**` | appels service-à-service, un seul appelant et une seule forme de réponse ; les garder en REST évite d'ouvrir `/graphql` au jeton de service |
| `/actuator/health/**` | sondes de santé |

### Lire les erreurs

En GraphQL le transport répond `200` même quand l'opération échoue : le sens est
porté par `errors[].extensions.classification`, et c'est cela que le client doit
lire, pas le code HTTP.

| classification | signification |
| --- | --- |
| `UNAUTHORIZED`   | authentification manquante |
| `FORBIDDEN`      | jeton valide, mais droit manquant (annonce d'un autre, transaction dont on n'est pas partie…) |
| `NOT_FOUND`      | ressource inexistante |
| `BAD_REQUEST`    | règle métier violée (transition d'état invalide, note hors 1–5, mot de passe trop court…) |
| `ValidationError`| la requête ne respecte pas le schéma : champ inconnu, type incorrect, argument manquant |

Le seul code HTTP qui reste porteur de sens est le `401` : sans jeton, la chaîne
de sécurité rejette la requête avant qu'elle n'atteigne le schéma. Une exception,
`userservice`, détaillée plus bas.

## Authentification

Tous les services sont des **resource servers OAuth2** : chaque requête doit
porter un `Authorization: Bearer <access_token>` Keycloak valide, sinon c'est
401. Le front obtient ce token en PKCE auprès de Keycloak, puis appelle la
gateway avec.

**Une exception, `userservice`.** L'inscription fait partie du même schéma que
le reste, et GraphQL n'expose qu'une seule URL : on ne peut donc plus ouvrir
l'inscription par le chemin comme le faisait `POST /users/register`.
`/users/graphql` est par conséquent le seul endpoint GraphQL du projet
atteignable sans jeton, et l'authentification y est portée opération par
opération, par `@PreAuthorize` sur chaque resolver. Conséquence pratique : toute
opération ajoutée à `UserGraphQlController` doit recevoir son `@PreAuthorize`,
sans quoi elle devient anonyme. `UserProfileSecurityTest` passe en revue chaque
opération du schéma sans jeton pour verrouiller cette règle.

Au-delà de l'authentification, chaque service applique ses propres règles de
propriété : on ne modifie que ses propres annonces, on ne lit que les
transactions dont on est partie, et les coordonnées (email, téléphone) d'un
autre membre ne sortent jamais des endpoints de navigation.

Trois endpoints ne sont **pas** joignables avec un token utilisateur — ils
exigent le rôle realm `service`, porté uniquement par le client confidentiel
`bagbuddy`. Ce sont aussi les seuls appels service-à-service restés en REST :

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

| Opération (sur `/users/graphql`) | Jeton | Effet |
| --- | --- | --- |
| `mutation { register(input: …) }` | aucun | crée le compte Keycloak (email = identifiant) |
| `mutation { updateIdentity(input: …) }` | utilisateur | prénom, nom, email |
| `mutation { changePassword(input: …) }` | utilisateur | vérifie l'actuel, puis le remplace |

Les deux dernières agissent sur le `sub` du jeton : aucune ne prend
d'identifiant d'utilisateur en argument, pour qu'un bug ne puisse pas devenir la
modification du compte d'autrui.

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

Les cinq services métier embarquent des tests de sécurité qui vérifient
concrètement les règles ci-dessus, en tapant sur le vrai endpoint GraphQL à
travers toute la chaîne de filtres : accès anonyme refusé, propriété, masquage
des coordonnées, montant non falsifiable, et refus par le schéma lui-même des
champs qu'un client ne doit pas pouvoir écrire (`userId`, `total`, `sellerId`…).

## Ajouter un microservice

N'oublie pas d'ajouter un Dockerfile et le bloc de service correspondant dans
`docker-compose.dev.yml` quand tu en crées un — avec ses variables `PORT`,
`JWT_ISSUER_URIS`, `JWT_JWK_SET_URI`, `JWT_AUDIENCE` et
`EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`.

Côté service, il lui faut aussi la dépendance
`spring-cloud-starter-netflix-eureka-client`, le bloc `eureka:` de son
`application.yml` (recopiable depuis n'importe quel service existant),
`eureka.client.enabled=false` dans ses propriétés de test, et son chemin GraphQL
préfixé (`spring.graphql.http.path`). Enfin, ajoute sa route `lb://<nom>` dans
`apigateway/src/main/resources/application.yaml`, où `<nom>` est son
`spring.application.name`.

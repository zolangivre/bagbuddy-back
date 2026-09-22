# BagBuddy — backend

Backend en microservices Spring Boot (Eureka, API gateway, trip / transaction /
review / stripe / user services) + Keycloak pour l'authentification, orchestrés
avec Docker Compose, en local comme en production.

L'API est en **GraphQL** : chaque service expose son propre schéma sous son
préfixe de chemin (`/trips/graphql`, `/transactions/graphql`, ...). Il ne reste
du REST que là où GraphQL n'a pas de sens — webhook Stripe et appels
service-à-service (voir « API GraphQL » plus bas).

C'est le repo backend de BagBuddy. Le front web (Angular) vit dans un repo
séparé : `bagbuddy-front`. Les deux se lancent indépendamment ; en local le
front tape sur la gateway en `http://localhost:8080` et sur Keycloak en
`http://localhost:8000`.

**Ce que l'application fait.** Un voyageur publie le poids disponible dans ses
bagages, un expéditeur en réserve une partie, ils se parlent, l'argent est
encaissé puis réparti à la fin, et chacun note l'autre. Autour de ça : comptes et
mots de passe, recherche et alertes sur les annonces, messagerie, code de remise
du colis, avis, favoris, signalements, emails à chaque étape. Le détail est dans
[Le domaine métier](#le-domaine-métier).

**Sommaire**

1. [Développement local](#développement-local)
   — [lancer](#tout-lancer) · [données de test](#données-de-test) · [Stripe en local](#stripe-en-local) · [commandes](#commandes-du-quotidien) · [tests](#tests)
2. [Le domaine métier](#le-domaine-métier)
   — [annonces](#annonces) · [réservations](#réservations) · [avis](#avis) · [favoris, signalements](#favoris-signalements) · [emails](#les-emails-envoyés) · [comptes](#comptes) · [réglages](#les-réglages-métier)
3. [Production](#production)
   — [architecture](#architecture-de-production) · [déploiement pas à pas](#déploiement-pas-à-pas) · [exploitation](#exploitation) · [dépannage](#dépannage)
4. [Architecture technique](#architecture)
   — [service discovery](#service-discovery) · [base de données](#base-de-données-et-migrations) · [observabilité](#observabilité) · [résilience](#résilience-et-abus) · [GraphQL](#api-graphql) · [authentification](#authentification) · [front web](#front-web) · [ajouter un microservice](#ajouter-un-microservice)

| Fichier | Rôle |
| --- | --- |
| `docker-compose.dev.yml` | stack de développement : ports ouverts, GraphiQL, Zipkin, données de test |
| `docker-compose.prod.yml` | stack de production : Caddy en HTTPS devant tout, une seule instance Postgres, mémoire plafonnée |
| `.env.example` / `.env.prod.example` | modèles de configuration dev / prod (les vrais `.env` ne sont jamais commités) |
| `keycloak/import/bagbuddy-realm.json` | realm Keycloak, commun au dev et à la prod |
| `seed/` | données de test, **dev uniquement** (comptes Keycloak + contenu des bases) |
| `deploy/` | fichiers de prod : `Caddyfile`, script de création des bases, script de sauvegarde, supervision |
| `.github/workflows/ci.yml` | CI : tests de chaque service, validation des deux compose, build des images |
| `CLAUDE.md` | les mêmes décisions, écrites pour un assistant de code |

Chaque service est un **projet Maven indépendant** (Java 21, Spring Boot 3.5.6,
son propre wrapper `./mvnw`, sa propre base Postgres) dans son dossier :
`eurekaserver`, `apigateway`, `tripservice`, `transactionservice`,
`reviewservice`, `stripeservice`, `userservice`. Tous suivent le même découpage
interne : `controller/` (les resolvers GraphQL, volontairement minces) →
`service/` (toute la logique) → `repository/` → `model/`, plus le schéma dans
`src/main/resources/graphql/schema.graphqls` et les migrations dans
`src/main/resources/db/migration`.

---

# Développement local

## Prérequis

- [Docker Desktop](https://www.docker.com/products/docker-desktop/) lancé, avec
  au moins 6 Go de mémoire alloués (Settings > Resources)

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

Les clés Stripe peuvent rester vides pour commencer : seul `stripe-service`
refusera de démarrer (voir [Stripe en local](#stripe-en-local)).

## Tout lancer

```bash
docker compose -f docker-compose.dev.yml up --build -d
```

Le premier run télécharge/build toutes les images, ça peut prendre quelques
minutes. Une fois debout :

| Service                 | URL                              |
| ----------------------- | -------------------------------- |
| API gateway             | http://localhost:8080            |
| Keycloak                | http://localhost:8000            |
| Console admin Keycloak  | http://localhost:8000/admin (`KEYCLOAK_ADMIN` / `KEYCLOAK_ADMIN_PASSWORD` du `.env`) |
| Dashboard Eureka        | http://localhost:8761            |
| Zipkin (traces)         | http://localhost:9411            |
| Mailpit (emails sortants) | http://localhost:8025          |
| trip-service            | http://localhost:8082            |
| transaction-service     | http://localhost:8083            |
| review-service          | http://localhost:8084            |
| stripe-service          | http://localhost:8085            |
| user-service            | http://localhost:8086            |

Keycloak importe le realm `bagbuddy` depuis `keycloak/import/bagbuddy-realm.json`
**quand il n'existe pas encore** (base Keycloak neuve) : les clients
`bagbuddy-web` (front Angular) et `bagbuddy-mobile` (app Expo historique) sont
prêts sans configuration manuelle. Sur une base existante l'import est ignoré —
une modification du realm s'applique alors par la console admin, ou en repartant
de zéro.

## Données de test

Une base neuve démarre **déjà remplie** : 5 utilisateurs avec leurs profils,
18 annonces, 21 transactions qui couvrent tous les statuts et 8 avis. Tous les
comptes ont le mot de passe **`Test1234!`** :

| Compte (email = identifiant) | Profil | Pour tester |
| --- | --- | --- |
| `camille.martin@bagbuddy.local` | voyageuse Paris ⇄ New York | **vendeuse** : une demande à accepter/refuser, une en attente de paiement, une payée ; **acheteuse** : une réservation à payer, une demande refusée à relancer, une affaire terminée à noter |
| `moussa.diop@bagbuddy.local` | Marseille ⇄ Dakar | une annonce **complète** (0 kg restant), une demande reçue, un paiement à faire, une affaire terminée à noter |
| `lea.fontaine@bagbuddy.local` | Montréal ⇄ Paris | **vendeuse** : une réservation en attente de paiement, une demande refusée ; **acheteuse** : une demande en attente, une réservation payée ; des avis reçus et donnés |
| `hugo.tremblay@bagbuddy.local` | Montréal ⇄ Tokyo / Barcelone | une demande à traiter, une réservation en attente de paiement, des annulations |
| `ines.benali@bagbuddy.local` | Toulouse ⇄ Alger / Casablanca | surtout **acheteuse** : demandes en attente, refusée, à payer ; une affaire terminée sans avis |
| `testuser` | compte vierge | parcours d'un nouvel inscrit (aucune annonce, aucun profil) |

Ce que contient le jeu de données :

- **Annonces** : 12 à venir (réservables), 5 dont la date est passée, 1 complète.
  Les dates sont calculées **par rapport au jour où la base a été créée**, pour
  que les annonces « à venir » le restent.
- **Transactions** : chaque paire de statuts de la machine à états apparaît
  plusieurs fois (demande reçue, refusée, acceptée/à payer, confirmée/payée,
  terminée, annulée), et chaque utilisateur est tour à tour acheteur et vendeur.
  Le poids restant des annonces tient compte des réservations acceptées.
- **Avis** : uniquement sur des affaires terminées, dont certaines restent à noter.

Où ça vit, et comment ça s'applique :

| Fichier | Chargé par |
| --- | --- |
| `seed/keycloak/bagbuddy-users-0.json` | Keycloak, juste après l'import du realm (donc uniquement sur une base Keycloak neuve) |
| `seed/<service>/afterMigrate.sql` | Flyway, après les migrations de chaque service, **seulement si la table est vide** |

Les `sub` Keycloak de ces comptes sont fixes (`5eed0000-…-000000000001` à `…05`),
c'est ce qui permet aux quatre bases de s'y référencer. Les fichiers de `seed/`
ne sont montés que par `docker-compose.dev.yml` (variable
`SPRING_FLYWAY_LOCATIONS`) : ils ne sont pas dans les jars et la production ne
les voit jamais.

### Repartir de zéro (et récupérer les données de test)

Les données de test ne sont chargées que dans des bases vides. Si ta stack
tournait déjà avant leur ajout, ou pour remettre tout à l'état initial :

```bash
docker compose -f docker-compose.dev.yml down -v   # ⚠️ efface toutes les bases locales
docker compose -f docker-compose.dev.yml up --build -d
```

Vérifie que `down -v` affiche bien des lignes `Volume bagbuddy-back_…_pgdata Removed`.
Sans elles, rien n'a été effacé : `up` redémarre alors les anciens conteneurs
Postgres avec leurs données, et le seed ne se charge pas puisque les tables ne
sont pas vides. Le cas le plus courant : Docker Desktop n'était pas encore
démarré au moment du `down`. Pour contrôler après coup :

```bash
docker volume inspect -f '{{.CreatedAt}}' bagbuddy-back_trip_pgdata   # doit dater de la remise à zéro
```

## Stripe en local

`stripe-service` fait partie de la stack de dev. Il lui faut les **clés de
test** du [dashboard Stripe](https://dashboard.stripe.com/test/apikeys)
(Développeurs > Clés API) :

```bash
# dans .env
STRIPE_SECRET_KEY=sk_test_...
STRIPE_PUBLISHABLE_KEY=pk_test_...
```

Deux façons de gérer la confirmation du paiement :

**1. Paiement réel en mode test (par défaut, `PAYMENTS_REQUIRE_STRIPE=true`).**
Une transaction ne passe « confirmée » qu'après le webhook signé de Stripe. Stripe
ne pouvant pas joindre ton `localhost`, le Stripe CLI relaie les webhooks vers la
gateway :

```bash
# 1. Récupère le secret de signature du CLI et mets-le dans .env (STRIPE_WEBHOOK_SECRET)
docker run --rm stripe/stripe-cli listen --api-key sk_test_... --print-secret

# 2. Lance la stack avec le relais de webhooks
docker compose -f docker-compose.dev.yml --profile stripe-webhooks up --build -d
```

Paye ensuite avec la carte de test `4242 4242 4242 4242` (date future, CVC
quelconque). `docker compose -f docker-compose.dev.yml logs -f stripe-cli`
montre les événements relayés.

**2. Paiement simulé (`PAYMENTS_REQUIRE_STRIPE=false` dans `.env`).**
Le passage « à payer » → « confirmée » est accepté sans Stripe. Pratique pour
travailler sur le front sans clés.

**Remboursements et versements.** Quand une transaction payée se termine ou
s'annule, l'argent est réparti automatiquement :

| Situation | Remboursé à l'acheteur | Commission | Versé au voyageur |
| --- | --- | --- | --- |
| terminée | 0 | 10 % | le reste |
| annulée par le voyageur | 100 % | 0 | 0 |
| annulée par l'acheteur plus de 24 h avant le départ | 100 % | 0 | 0 |
| annulée par l'acheteur moins de 24 h avant | 50 % | 10 % de la part gardée | le reste |

Ces règles se règlent par `PLATFORM_FEE_PERCENT`, `LATE_CANCELLATION_WINDOW` et
`LATE_CANCELLATION_REFUND_PERCENT` (sur `transaction-service`). Pour être payé, un
voyageur configure ses versements depuis son compte (onboarding Stripe Connect
Express) ; d'ici là, sa part attend sur la plateforme et part automatiquement une
fois son compte prêt. Il faut pour cela **activer Connect** dans le dashboard
Stripe (mode test : [dashboard.stripe.com/test/connect](https://dashboard.stripe.com/test/connect/accounts/overview)).
En paiement simulé, la répartition est calculée et affichée, sans aucun appel à Stripe.

Après un changement du `.env` :
`docker compose -f docker-compose.dev.yml up -d` (recrée les conteneurs concernés).

## Commandes du quotidien

Toutes prennent `-f docker-compose.dev.yml`. Les noms de service sont ceux du
compose : `api-gateway`, `eureka-server`, `trip-service`, `transaction-service`,
`review-service`, `stripe-service`, `user-service`, `keycloak`, `mailpit`,
`redis`, `zipkin`.

```bash
C="docker compose -f docker-compose.dev.yml"    # raccourci pour les lignes qui suivent

$C up --build -d                # tout lancer (ou relancer après un git pull)
$C up --build -d trip-service   # reconstruire et redémarrer un seul service
$C restart trip-service         # redémarrer sans reconstruire
$C up -d                        # appliquer un changement de .env (recrée les conteneurs concernés)
$C ps                           # qui tourne, qui est healthy
$C logs -f trip-service         # suivre les logs d'un service
$C logs -f --tail=200           # ... ou de toute la stack
$C down                         # tout arrêter, en gardant les bases
$C down -v                      # tout arrêter ET effacer les bases (relance = données de test neuves)
$C exec tripservice-db psql -U tripservice -d tripservice   # console SQL (identifiants du .env)

$C --profile stripe-webhooks up -d   # + le relais de webhooks Stripe (voir Stripe en local)
$C --profile monitoring up -d        # + Prometheus (:9090) et Grafana (:3000)
```

Lancer un service hors Docker, le reste de la stack tournant dans Docker — utile
pour attacher un débogueur ou profiter du rechargement à chaud :

```bash
cd tripservice
./mvnw spring-boot:run     # démarre sur le port de son application.yml (8082 ici)
./mvnw test                # ses tests
./mvnw clean package       # son jar
```

Il faut alors lui donner les mêmes variables que son bloc de
`docker-compose.dev.yml` (`DATABASE_URL`, `PORT`, …) — et arrêter le conteneur
correspondant, sinon les deux se disputent le port. Sans registre joignable, le
service démarre quand même : l'échec d'enregistrement Eureka n'est qu'un
avertissement dans les logs.

## Tests

Chaque service se teste indépendamment ; les tests tournent sur une base H2 en
mémoire, sans Docker ni Keycloak :

```bash
cd tripservice && ./mvnw test
```

Quelques tests ont besoin d'un vrai PostgreSQL, parce qu'ils vérifient ce qu'H2
n'émule pas fidèlement :

- `TripCapacityConcurrencyTest` : le `SELECT ... FOR UPDATE` qui empêche deux
  réservations simultanées de survendre une annonce, et le rejeu concurrent d'une
  même réservation (double-clic) qui ne doit décompter le poids qu'une fois ;
- `ReviewMigrationTest` et `ReviewSchemaValidationTest` : les vraies migrations
  Flyway, et les entités validées contre elles.

Ils démarrent un conteneur via Testcontainers si Docker est disponible et
**s'ignorent d'eux-mêmes** sinon — le reste de la suite continue de tourner sans
Docker. Testcontainers est épinglé en 1.21.4 : la 1.21.3 fournie par Spring Boot
est refusée par Docker Engine 29, et ces tests étaient alors ignorés sans bruit.

La CI (`.github/workflows/ci.yml`) lance `./mvnw verify` sur chaque service à
chaque push et pull request, tests Postgres compris, valide les deux fichiers
compose et, sur les push, construit toutes les images.

Les cinq services métier embarquent des tests de sécurité qui vérifient
concrètement les règles décrites plus bas, en tapant sur le vrai endpoint GraphQL à
travers toute la chaîne de filtres : accès anonyme refusé, propriété, masquage
des coordonnées, montant non falsifiable, et refus par le schéma lui-même des
champs qu'un client ne doit pas pouvoir écrire (`userId`, `total`, `sellerId`…).

---

# Le domaine métier

BagBuddy met en relation un **voyageur**, qui a de la place dans ses bagages, et
un **expéditeur**, qui a quelque chose à faire transporter. Le voyageur publie
une annonce, l'expéditeur y réserve du poids, la réservation avance dans une
machine à états jusqu'à la remise du colis, puis chacun note l'autre.

Le vocabulaire du code suit ce partage : dans une transaction, le **vendeur**
(`seller`) est le voyageur qui vend du poids, l'**acheteur** (`buyer`) est celui
qui expédie. Tout le reste en découle.

Ce que le backend gère, service par service :

| Service | Ce qu'il détient |
| --- | --- |
| `tripservice` | annonces, capacité restante, recherche, réservations de poids, alertes de trajet |
| `transactionservice` | réservations et leur machine à états, messagerie, code de remise, notifications, calcul du règlement |
| `reviewservice` | avis, un par personne et par transaction |
| `stripeservice` | paiements, remboursements, versements, onboarding Stripe Connect |
| `userservice` | comptes (inscription, mot de passe, email), profils, favoris, signalements |

## Annonces

Une annonce (`Trip`) décrit un vol : aéroports de départ et d'arrivée (codes
IATA, stockés en majuscules), date de départ, poids disponible et prix au kilo.
Elle appartient au `sub` du jeton — `TripInput` n'a volontairement aucun champ
`userId`, on ne peut donc pas publier sous l'identité d'un autre.

- **Réservable** veut dire : poids restant > 0 **et** date de départ future.
  C'est calculé à la lecture, jamais lu dans un drapeau `active` qui deviendrait
  faux tout seul le jour où la date passe.
- **`searchTrips`** filtre et trie côté serveur : trajet, jour de départ ±
  `flexDays` (0 à 30), fourchette de prix, poids restant minimum, six tris
  possibles. La réponse porte aussi `totalCount`, `totalRemainingWeight` et
  `averagePricePerKg` calculés sur **tout le filtre**, pas sur la page.
- **La capacité ne se modifie pas à la main.** Le poids restant est décrémenté
  par le service quand le voyageur accepte une demande, et rendu à l'annulation
  (voir [Réservations](#réservations)). Baisser le poids total d'une annonce
  sous ce qui est déjà réservé est refusé, et une annonce qui porte une
  réservation active ne se supprime pas.

**Alertes de trajet.** Un membre enregistre une recherche (`createTripAlert`) :
route, fenêtre de dates avec tolérance, prix maximum, poids minimum. À chaque
publication d'annonce, celles qui correspondent déclenchent un email — jamais à
l'auteur de l'annonce lui-même. Dix alertes par membre (`ALERTS_MAX_PER_MEMBER`),
l'adresse est prise dans le jeton et non dans la requête.

## Réservations

L'acheteur réserve un poids sur une annonce (`createTransaction`). Deux choses
sont vraies dès la création :

- **Le prix n'est jamais fourni par le client.** `transactionservice` lit
  l'annonce chez `tripservice` et calcule `total = prix au kilo × poids`. Les
  champs `total`, `sellerId`, `paidAt` et les colonnes Stripe n'existent pas dans
  les types d'entrée du schéma : les envoyer est une erreur de validation, pas un
  champ silencieusement ignoré.
- **Le contenu doit être déclaré** : `contentDescription` (1 à 500 caractères) et
  `prohibitedItemsAccepted: true` sont obligatoires. Le voyageur lit cette
  déclaration avant d'accepter. L'app mobile Expo historique ne les envoie pas
  encore : ses réservations sont refusées tant qu'elle n'est pas mise à jour.

### Le cycle de vie

Les deux colonnes de statut (côté vendeur, côté acheteur) avancent toujours
ensemble : une transition est une arête entre deux paires, et chaque arête dit
**qui** a le droit de la franchir. Une paire inconnue du graphe est un
`BAD_REQUEST`, jamais une écriture silencieuse.

| Départ | Arrivée | Qui | Effet |
| --- | --- | --- | --- |
| demande reçue | acceptée / à payer | le voyageur | **le poids quitte l'annonce** |
| demande reçue | refusée | le voyageur | — |
| refusée | demande reçue | l'expéditeur | **retarifée** : il redemande avec un autre poids |
| acceptée / à payer | confirmée (payée) | l'expéditeur | paiement enregistré |
| confirmée | terminée | l'expéditeur | déclenche le règlement |
| demande reçue, refusée | annulée | les deux | — |
| acceptée, confirmée | annulée | les deux | **le poids revient à l'annonce** |

Deux chemins mènent à « terminée » : l'expéditeur confirme la réception, ou le
voyageur saisit le **code de remise** (voir plus bas). L'arête
`confirmée → terminée` du tableau est réservée à l'expéditeur, précisément pour
que le voyageur ne puisse pas clore seul, sans ce code.

Ce qui tient du poids ne disparaît pas non plus en silence : une transaction
acceptée ou payée doit d'abord être annulée pour être supprimée.

### Ce que garantit la capacité

- **Un double-clic sur « accepter » ne décompte le poids qu'une fois.** Chaque
  réservation est une ligne `trip_reservation` identifiée par la transaction ;
  rejouer la même réservation ne change rien, la rejouer avec un autre poids est
  refusé.
- **Une réservation qui n'aboutit pas est compensée.** Si le poids est pris puis
  que l'acceptation échoue (l'acheteur a annulé entre-temps, le poids a changé),
  il est rendu — sauf si un autre clic a déjà fait aboutir la transaction.
- **Un échec de restitution ne casse pas l'annulation.** L'annulation reste
  valide, l'échec est journalisé avec la commande à rejouer et compté
  (`bagbuddy.capacity.release.failures`, alerte `CapacityNotReleased`). Le poids
  qui reste pris est le sens sûr de l'erreur.

### Expiration

Toutes les 15 minutes, les transactions dont le vol est parti sans avoir été
payées — en attente de réponse, refusées, ou acceptées sans paiement — sont
annulées et rendent leur poids. L'acteur du changement est `SYSTEM`, un acteur
qu'aucune arête n'autorise : les emails et le règlement reconnaissent ainsi un
changement décidé par le service, au lieu de le deviner. **Une transaction payée
n'expire jamais** : l'argent est engagé, son sort ne se décide pas tout seul.

### Paiement, remboursement, versement

L'acheteur paie **la plateforme**, qui garde l'argent jusqu'à la fin de la
transaction, puis rembourse et/ou verse sa part au voyageur (modèle *separate
charges and transfers* de Stripe Connect). Le barème — commission, annulation
tardive — est celui du tableau de [Stripe en local](#stripe-en-local), et il est
réglable.

Le découpage se fait en deux temps, façon *outbox* :

- **La décision** est prise dans la transaction d'écriture qui mène à
  « terminée » ou « annulée » : les montants et deux statuts `PENDING` sont
  écrits en même temps que le statut. `remboursement + commission + versement`
  tombe juste au centime près.
- **L'exécution** suit le commit, hors du fil de la requête : les appels à Stripe
  peuvent s'enchaîner, le membre ne les attend pas. La réponse de la mutation
  montre donc encore `PENDING`, et le front relit.

Un échec ne défait jamais la transition : une panne laisse `PENDING` et un
planificateur réessaie toutes les 10 minutes ; un refus définitif passe en
`FAILED`, est compté et attend un humain ; un voyageur sans compte de versement
prêt passe en `AWAITING_ACCOUNT`, et son argent part tout seul une fois son
onboarding terminé. Côté Stripe, rien n'est payé deux fois : un remboursement ou
un virement existant est retrouvé avant d'en créer un, et chaque création porte
une clé d'idempotence dérivée de la transaction.

Une transaction jamais payée n'est jamais réglée : il n'y a rien à répartir.

### Code de remise

Quand une transaction passe « confirmée », un code à six chiffres est généré et
n'est montré **qu'à l'expéditeur**. À la remise du colis, il le donne au
voyageur, qui clôt la transaction avec `confirmHandover(id, code)`.

- Chaque essai est compté dans sa propre écriture — un code faux incrémente le
  compteur même si l'appel échoue.
- Cinq essais ratés bloquent le code (`handover_locked`), ce qui rend une
  recherche exhaustive sans intérêt.

### Messagerie

Chaque transaction porte un fil réservé à ses deux participants
(`transactionMessages` / `sendTransactionMessage`) : 1 à 2000 caractères,
20 messages par minute et par expéditeur, `afterId` pour ne relire que la suite.
Une transaction annulée garde son fil lisible mais n'accepte plus de message.

## Avis

Un avis se rattache à une transaction terminée. `reviewservice` relit la
transaction **avec le jeton de l'appelant** : `transactionservice` refuse déjà de
la servir à qui n'y a pas pris part, ce qui est exactement le contrôle voulu, et
évite de dupliquer la règle. `CreateReviewInput` ne porte ni `reviewerId` ni
`revieweeId` — l'auteur vient du jeton, le noté vient de la transaction. Une
contrainte unique `(reviewer_id, transaction_id)` fait le reste : deux envois
simultanés ne créent pas deux avis, le second est un `BAD_REQUEST`.

## Favoris, signalements

- **Favoris** : `userservice` ne stocke que des identifiants d'annonces (200 au
  plus), relus ensuite dans `tripservice` par `tripsByIds`, dans l'ordre demandé
  et sans faire échouer la liste sur une annonce supprimée. Ajouter ou retirer
  est idempotent.
- **Signalements** : `reportMember` écrit en base et envoie un email à
  `MODERATION_EMAIL` après le commit. L'auteur est toujours l'appelant, on ne se
  signale pas soi-même, et dix signalements par 24 h suffisent.

## Les emails envoyés

Tous partent **après** le commit et de façon asynchrone : un envoi lent ou un
SMTP en panne n'échoue jamais l'opération, et une opération refusée n'envoie
rien. En dev ils arrivent dans Mailpit (http://localhost:8025), sans aucune
configuration.

| Email | Service | Destinataire |
| --- | --- | --- |
| nouvelle demande | transaction | le voyageur |
| demande acceptée / refusée | transaction | l'expéditeur |
| paiement confirmé | transaction | le voyageur |
| transaction terminée / annulée | transaction | celui qui n'a pas fait le geste |
| transaction expirée | transaction | les deux |
| annonce correspondant à une alerte | trip | l'auteur de l'alerte |
| lien de réinitialisation du mot de passe | user | l'adresse du compte |
| lien de vérification d'adresse | user | l'adresse du compte |
| signalement d'un membre | user | `MODERATION_EMAIL` |

Les emails de transaction sont en texte brut et **bilingues (français puis
anglais)** : la langue du membre vit dans son navigateur, aucun service ne la
connaît. Aucun montant n'y figure (la conversion de devise est une affaire de
front) et le lien ouvre le détail de la transaction sur `BAGBUDDY_FRONT_URL`.
`NOTIFICATIONS_ENABLED=false` coupe ceux des transactions,
`ALERTS_ENABLED=false` ceux des alertes.

## Comptes

Le front web ne renvoie jamais vers les pages de Keycloak : il a ses propres
écrans. Ce qu'un navigateur ne peut pas porter — les droits d'administration du
realm — est relayé par `userservice` (détail dans [Front web](#front-web)).

- **Inscription** : `register`, sans jeton, crée un compte Keycloak activé dont
  l'identifiant est l'email. Le profil applicatif, lui, apparaît tout seul à la
  première requête `me`.
- **Mot de passe oublié** : le lien est **le nôtre**, pas celui de Keycloak, qui
  ouvrirait une page Keycloak. `requestPasswordReset` répond `true` dans tous les
  cas et avant même de chercher le compte : ni la réponse ni son délai ne disent
  qui est inscrit. Le jeton n'est stocké qu'en SHA-256, vaut 30 minutes, un email
  par minute au plus, et un nouveau lien remplace le précédent. Il n'est consommé
  qu'une fois Keycloak satisfait : un mot de passe refusé par la politique du
  realm ne coûte pas le lien. La réinitialisation ferme toutes les sessions du
  compte.
- **Vérification d'adresse** : même table de jetons, mais un usage distinct — un
  lien de réinitialisation ne peut pas vérifier une adresse. Valable 24 h, un
  email par minute. L'envoi du mail et l'écriture du jeton sont dans la même
  transaction : si le SMTP tombe, le jeton n'existe pas et le membre peut
  réessayer tout de suite.
- **Changement d'email ou de mot de passe** : le mot de passe actuel est exigé, et
  il est vérifié **en demandant un jeton à Keycloak avec** — jamais par un
  endpoint d'administration — pour que la protection anti-force brute du realm
  compte l'essai. Un changement d'email repasse par une vérification.

Toutes ces opérations agissent sur le `sub` du jeton. Aucune ne prend
d'identifiant d'utilisateur en argument : un bug ne peut pas devenir la
modification du compte d'autrui.

## Les réglages métier

Rien de tout cela n'est en dur : ces valeurs sont des variables d'environnement,
avec ces défauts.

| Variable | Défaut | Ce qu'elle règle |
| --- | --- | --- |
| `PLATFORM_FEE_PERCENT` | `10` | commission de la plateforme sur une transaction terminée |
| `LATE_CANCELLATION_WINDOW` | `PT24H` | délai avant départ en deçà duquel l'annulation de l'acheteur est tardive |
| `LATE_CANCELLATION_REFUND_PERCENT` | `50` | part remboursée lors d'une annulation tardive |
| `PAYMENTS_REQUIRE_STRIPE` | `true` | `false` simule le paiement, sans aucun appel à Stripe |
| `bagbuddy.transaction.expiry.interval` | `PT15M` | fréquence de la passe d'expiration (`…expiry.enabled=false` la coupe) |
| `bagbuddy.payments.settlement.interval` | `PT10M` | fréquence des relances de règlement |
| `PASSWORD_RESET_VALIDITY` / `…_MIN_INTERVAL` | `30m` / `60s` | durée d'un lien de mot de passe, délai entre deux envois |
| `EMAIL_VERIFICATION_VALIDITY` / `…_MIN_INTERVAL` | `24h` / `60s` | idem pour la vérification d'adresse |
| `FAVORITES_MAX` | `200` | favoris par membre |
| `REPORTS_MAX_PER_DAY` | `10` | signalements par membre et par 24 h |
| `MODERATION_EMAIL` | `moderation@bagbuddy.local` | boîte qui reçoit les signalements |
| `ALERTS_MAX_PER_MEMBER` | `10` | alertes de trajet par membre |
| `NOTIFICATIONS_ENABLED`, `ALERTS_ENABLED` | `true` | coupent les emails correspondants |
| `RATE_LIMIT_PER_SECOND` / `RATE_LIMIT_BURST` | `10` / `20` | débit autorisé par IP sur `/users/**` |
| `bagbuddy.transaction.status.*` | voir `application.yml` | le vocabulaire des statuts, à garder aligné sur le front |

---

# Production

## Architecture de production

Tout le backend tourne sur **une seule machine Linux** avec Docker Compose. La
cible visée est une VM gratuite *Oracle Cloud Always Free* (Ampere A1, ARM,
2 OCPU / 12 Go), mais n'importe quel VPS de 8 Go ou plus convient, en ARM comme
en x86 : les images de base sont multi-architectures.

```
Internet ──443──> Caddy (HTTPS Let's Encrypt automatique)
                   ├─ API_DOMAIN  → api-gateway:8080 → Eureka → trip / transaction / review / user / stripe
                   └─ AUTH_DOMAIN → keycloak:8080          (/admin fermé au public)
                  réseau Docker interne, aucun autre port publié :
                  Postgres (6 bases) · Redis · Eureka
Front Angular → hébergé ailleurs (Cloudflare Pages, Vercel…), appelle API_DOMAIN et AUTH_DOMAIN
```

Ce que `docker-compose.prod.yml` fait différemment du dev :

| | Dev | Prod |
| --- | --- | --- |
| Ports publiés | tous | seulement 80/443 (Caddy) ; Keycloak sur `127.0.0.1:8081` pour la console admin |
| HTTPS | non | Caddy, certificats automatiques |
| Postgres | un conteneur par service | une instance, une base + un utilisateur par service (`deploy/postgres/init-databases.sh`) |
| Keycloak | `start-dev`, comptes de test | `start` (mode production), realm seul, sans aucun compte |
| Données de test | oui (`seed/`) | jamais |
| GraphiQL, Zipkin | activés | désactivés |
| Mémoire | libre | plafonnée : 640 Mo par service Java, 1 Go Keycloak, 1 Go Postgres (~6,5 Go au total) |
| Paiement | au choix | toujours confirmé par le webhook Stripe signé |
| Eureka | cadences de 5 s | cadences de 10 s (renouvellement, lecture du registre) et expiration à 30 s |

Consommation mesurée à vide : ~320 Mo par service Java, ~500 Mo pour Keycloak.

## Déploiement pas à pas

### 1. La machine

Sur Oracle Cloud (ou ton hébergeur) : une VM **Ubuntu 24.04**, 2 cœurs / 12 Go
(Ampere A1 Flex), disque de ~100 Go, avec ta clé SSH. Note son IP publique.

Ouvre les ports **80 et 443 (TCP)** à deux endroits :

1. **Dans le pare-feu de l'hébergeur** — sur Oracle : *Networking > Virtual Cloud
   Networks > ton VCN > Security Lists > Default* : deux règles d'entrée
   `0.0.0.0/0`, TCP, ports 80 et 443.
2. **Sur la VM elle-même** — les images Ubuntu d'Oracle bloquent tout par défaut
   avec iptables (piège classique : les ports semblent ouverts dans la console
   mais rien ne répond) :

   ```bash
   sudo iptables -I INPUT -p tcp -m multiport --dports 80,443 -m conntrack --ctstate NEW -j ACCEPT
   sudo netfilter-persistent save
   ```

### 2. Docker et swap

```bash
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker $USER      # puis déconnecte-toi / reconnecte-toi

# 4 Go de swap : absorbe les pics mémoire des builds Maven
sudo fallocate -l 4G /swapfile && sudo chmod 600 /swapfile
sudo mkswap /swapfile && sudo swapon /swapfile
echo '/swapfile none swap sw 0 0' | sudo tee -a /etc/fstab
```

### 3. Les noms de domaine

Il faut deux noms qui pointent sur l'IP de la VM : un pour l'API, un pour
Keycloak. Gratuitement avec [DuckDNS](https://www.duckdns.org) : crée par
exemple `api-bagbuddy` et `auth-bagbuddy`, et renseigne l'IP de la VM pour les
deux. Avec un vrai domaine, deux enregistrements `A` font la même chose.

Vérifie avant d'aller plus loin (Caddy échouera à obtenir ses certificats sinon) :

```bash
dig +short api-bagbuddy.duckdns.org   # doit afficher l'IP de la VM
```

### 4. Le code et la configuration

```bash
git clone <url-du-repo> bagbuddy-back && cd bagbuddy-back
cp .env.prod.example .env.prod
```

Dans `.env.prod`, renseigne `API_DOMAIN`, `AUTH_DOMAIN`, `BAGBUDDY_FRONT_URL`
(URL exacte du front, sans `/` final) et les clés Stripe. Les mots de passe et
secrets se génèrent d'un coup :

```bash
for key in POSTGRES_PASSWORD KEYCLOAK_DB_PASSWORD TRIP_SERVICE_PASSWORD \
           TRANSACTION_SERVICE_PASSWORD REVIEW_SERVICE_PASSWORD USER_SERVICE_PASSWORD \
           KEYCLOAK_ADMIN_PASSWORD KEYCLOAK_SERVICE_CLIENT_SECRET KEYCLOAK_ACCOUNTS_CLIENT_SECRET; do
  sed -i "s/^$key=$/$key=$(openssl rand -hex 32)/" .env.prod
done
chmod 600 .env.prod
```

Garde une copie de `.env.prod` dans un gestionnaire de mots de passe : sans lui,
les sauvegardes de la base restent lisibles mais les services ne s'y connectent
plus.

Le webhook Stripe se déclare dans le dashboard (*Développeurs > Webhooks >
Ajouter un endpoint*) :

- URL : `https://<API_DOMAIN>/stripe/webhook`
- événement : `payment_intent.succeeded`
- le **secret de signature** affiché (`whsec_…`) va dans `STRIPE_WEBHOOK_SECRET`.

### 5. Build

Les images se construisent sur la VM. Un par un, pour ne pas lancer sept builds
Maven en parallèle sur deux cœurs (compte 15 à 25 minutes la première fois,
beaucoup moins ensuite grâce au cache) :

```bash
for s in eureka-server api-gateway trip-service transaction-service review-service user-service stripe-service; do
  docker compose -f docker-compose.prod.yml --env-file .env.prod build "$s"
done
```

### 6. Démarrage

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
docker compose -f docker-compose.prod.yml --env-file .env.prod ps
```

Au premier démarrage, Postgres crée les bases, Keycloak importe le realm puis
les services démarrent quand leurs dépendances sont saines : compte **3 à
5 minutes** avant que tout soit `healthy`. Pendant l'enregistrement dans Eureka,
la gateway peut répondre `503` quelques secondes.

### 7. Vérification

```bash
curl https://<API_DOMAIN>/actuator/health
# {"status":"UP"}

curl https://<AUTH_DOMAIN>/realms/bagbuddy/.well-known/openid-configuration
# "issuer":"https://<AUTH_DOMAIN>/realms/bagbuddy"
```

Le realm de production ne contient **aucun utilisateur** : crée ton compte par
l'inscription du front (mutation `register`).

### 8. Le front

Côté `bagbuddy-front`, configure la production pour appeler
`https://<API_DOMAIN>` (gateway) et `https://<AUTH_DOMAIN>` (Keycloak, realm
`bagbuddy`, client `bagbuddy-web`). L'origine du front doit être **exactement**
`BAGBUDDY_FRONT_URL` : c'est la seule autorisée par le CORS de la gateway et par
le client Keycloak.

## Exploitation

### Mettre à jour après un `git push`

```bash
cd ~/bagbuddy-back && git pull
docker compose -f docker-compose.prod.yml --env-file .env.prod build <service>
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d <service>
```

Les migrations Flyway s'appliquent au redémarrage du service. Pour tout
reconstruire, relance la boucle de l'étape 5 puis `up -d`.

### Logs

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod logs -f --tail=200 <service>
```

### Console d'administration Keycloak

Fermée au public (Caddy répond 404 sur `/admin`). On y accède par un tunnel SSH
depuis ta machine :

```bash
ssh -L 8081:127.0.0.1:8081 ubuntu@<IP-de-la-VM>
# puis ouvre http://localhost:8081/admin (KEYCLOAK_ADMIN / KEYCLOAK_ADMIN_PASSWORD)
```

**Le realm n'est importé qu'une fois**, au tout premier démarrage. Ensuite, toute
modification (client, URL du front, politique de mot de passe…) se fait dans
cette console — et doit aussi être reportée dans
`keycloak/import/bagbuddy-realm.json` pour que le dépôt reste la référence.
Changer `BAGBUDDY_FRONT_URL` après coup demande donc les deux : le client
`bagbuddy-web` dans la console, et `up -d api-gateway` pour le CORS.

### Supervision

Prometheus et Grafana ne démarrent pas par défaut (environ 500 Mo à eux deux) :

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod --profile monitoring up -d
ssh -L 3000:127.0.0.1:3000 ubuntu@<IP-de-la-VM>
# puis ouvre http://localhost:3000 (admin / GRAFANA_ADMIN_PASSWORD)
```

La source Prometheus est déjà configurée dans Grafana. Pour les tableaux de bord,
importe par identifiant **4701** (JVM Micrometer) et **19004** (Spring Boot 3).
Les règles d'alerte de `deploy/monitoring/alerts.yml` s'affichent dans Grafana et
dans l'onglet Alerts de Prometheus. Deux d'entre elles signalent une action à
faire à la main :

- `PaymentNotRecorded` : Stripe a encaissé un paiement qu'aucune transaction n'a
  enregistré. Le PaymentIntent est dans les logs de `stripe-service` ; rembourse-le
  depuis le dashboard Stripe.
- `CapacityNotReleased` : une annulation n'a pas rendu son poids à l'annonce. La
  commande à rejouer (`POST /trips/internal/{id}/release`) est dans les logs de
  `transaction-service`.

En dev, le même couple se lance avec
`docker compose -f docker-compose.dev.yml --profile monitoring up -d`
(Prometheus sur http://localhost:9090, Grafana sur http://localhost:3000).

### Sauvegardes

`deploy/backup.sh` écrit un dump compressé de toutes les bases (Keycloak compris)
dans `backups/` et supprime ceux de plus de 14 jours. À planifier chaque nuit :

```bash
crontab -e
# ajouter :
0 3 * * * cd /home/ubuntu/bagbuddy-back && ./deploy/backup.sh >> backups/backup.log 2>&1
```

Une sauvegarde qui reste sur la VM ne protège pas de la perte de la VM : copie
régulièrement `backups/` ailleurs (ta machine avec `scp`, ou un stockage objet
comme Cloudflare R2 avec `rclone`).

Restaurer (écrase les bases existantes) :

```bash
docker compose -f docker-compose.prod.yml --env-file .env.prod stop api-gateway trip-service transaction-service review-service user-service stripe-service keycloak
gunzip -c backups/bagbuddy_AAAA-MM-JJ_HHMM.sql.gz \
  | docker compose -f docker-compose.prod.yml --env-file .env.prod exec -T postgres psql -U postgres
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d
```

Deux messages sont attendus pendant la restauration et sans conséquence :
`current user cannot be dropped` et `role "postgres" already exists`.

## Dépannage

| Symptôme | Cause probable |
| --- | --- |
| Caddy boucle sur « obtaining certificate » | le nom ne pointe pas encore sur la VM, ou 80/443 fermés (hébergeur **et** iptables, étape 1) |
| `503` sur toute l'API | services encore en cours d'enregistrement dans Eureka ; attendre 30 s, sinon `ps` pour voir lequel n'est pas `healthy` |
| Un service redémarre en boucle sans erreur Java | tué pour dépassement mémoire : `docker inspect <conteneur> --format '{{.State.OOMKilled}}'`, puis augmenter son `mem_limit` |
| `required variable … is missing a value` | variable vide dans `.env.prod`, ou `--env-file .env.prod` oublié |
| Le front reçoit une erreur CORS | son origine diffère de `BAGBUDDY_FRONT_URL` (schéma, `www`, slash final) |
| Tous les appels répondent `401` | jeton émis par un autre Keycloak (dev ?), ou mapper d'audience `bagbuddy-api-audience` perdu sur le client (voir Authentification) |
| Paiement jamais confirmé | webhook Stripe mal déclaré : vérifier l'URL, l'événement et `STRIPE_WEBHOOK_SECRET` ; le dashboard Stripe affiche la réponse reçue |
| Démarrages redevenus lents après une modif de config | l'entraînement CDS a échoué au build (nouvelle propriété obligatoire sans valeur factice dans le `RUN` du Dockerfile) : chercher `CDS training failed` dans la sortie du build |
| `deleteTrip` refusé (« accepted bookings ») | une réservation acceptée ou payée tient encore du poids : annuler la transaction d'abord |

---

# Architecture

## Service discovery

Les services s'enregistrent auprès d'Eureka au démarrage, et l'`apigateway`
résout ses routes à travers le registre : il ne connaît aucune URL de service,
seulement des `lb://trip-service`, `lb://user-service`, etc. Le dashboard
`http://localhost:8761` liste ce qui est réellement enregistré — c'est le
premier endroit où regarder quand une route répond mal.

Deux comportements à connaître :

- **Un service non enregistré renvoie `503`**, et non un refus de connexion.
  Par exemple `/stripe/**` répond 503 tant que `stripe-service` n'a pas démarré
  (clés Stripe absentes) : c'est normal.
- **L'enregistrement n'est pas instantané.** Les cadences sont volontairement
  serrées en dev (renouvellement et rafraîchissement toutes les 5 s, éviction
  toutes les 10 s, auto-préservation coupée, cache du load-balancer à 5 s) : un
  service redémarré redevient routable en ~4 s, là où les valeurs par défaut
  d'Eureka demanderaient 30 à 90 s. `docker-compose.prod.yml` les élargit
  (10 s / 30 s). L'auto-préservation y reste coupée : sur une seule machine elle
  ne protège d'aucune coupure réseau, et avec six instances le redémarrage d'une
  seule passerait sous son seuil de 85 % — le registre garderait alors l'ancienne
  IP du conteneur.

Les trois appels service-à-service (`transactionservice` -> `tripservice`,
`stripeservice` -> `transactionservice`) restent volontairement sur des URLs
statiques : ils sont peu nombreux, figés, et sur le chemin du paiement.

## Base de données et migrations

Le schéma appartient à **Flyway**, plus à Hibernate : les migrations vivent dans
`<service>/src/main/resources/db/migration` et `ddl-auto` est passé à `validate`,
ce qui fait échouer le démarrage si les entités et les migrations ont divergé.
C'est voulu — mieux vaut ne pas démarrer que découvrir l'écart sur une requête.

`V1__baseline.sql` décrit le schéma tel qu'Hibernate l'avait créé. Sur une base
existante, Flyway la marque comme déjà appliquée (`baseline-on-migrate`) au lieu
de la rejouer : les données de dev survivent à la bascule. Sur une base neuve,
elle est jouée normalement.

Pour ajouter une colonne : écris la migration suivante (`V<n+1>__...sql`), ne touche
jamais à une migration déjà appliquée, et mets l'entité JPA en face. Pense aussi
au fichier de `seed/<service>/afterMigrate.sql` si la colonne doit y être
renseignée : il est rejoué contre le nouveau schéma à chaque base neuve.

## Observabilité

- **Sondes** : `/actuator/health` sur chaque service (et sur le gateway et
  Eureka). Docker s'en sert : `depends_on` attend `service_healthy` et non le
  simple démarrage du conteneur. Le détail (base, disque, registre) n'est
  affiché qu'à un appelant authentifié.
- **Traces** (dev) : un `traceId` commun traverse le gateway et les services, et se
  retrouve dans chaque ligne de log. Zipkin les collecte sur
  `http://localhost:9411` — c'est là qu'on voit où une requête a passé son temps.
  Le taux d'échantillonnage est à 100 % en dev (`TRACING_SAMPLE_RATE`). En
  production le tracing est coupé (`MANAGEMENT_TRACING_ENABLED=false`) pour
  économiser la mémoire d'un collecteur.
- **Métriques** : `/actuator/prometheus` sur chaque JVM (mémoire, requêtes HTTP,
  pool de connexions, coupe-circuits), plus des compteurs métier : transitions de
  transaction, expirations, capacité non rendue, paiements non enregistrés.
  L'endpoint est ouvert sans jeton pour le scrape, et n'est donc joignable que
  depuis la machine : Caddy renvoie 404 sur `/actuator` hors `health`. Voir
  *Supervision* pour Prometheus et Grafana.

## Performance au démarrage et à l'exécution

- **Threads virtuels** (Java 21) dans les cinq services métier : une requête passe
  l'essentiel de son temps à attendre Postgres, un autre service ou Keycloak, et
  un thread virtuel ne retient aucun thread système pendant cette attente.
  `VIRTUAL_THREADS_ENABLED=false` les coupe.
- **Pools de connexions** : 5 par service (`DB_POOL_MAX_SIZE`), 20 pour Keycloak,
  pour tenir dans les 100 connexions de l'unique Postgres de production.
- **Archive CDS** : chaque image est construite avec un démarrage d'entraînement
  dont la JVM archive les classes chargées. Mesuré sur un cœur : 6,7 s → 4,5 s
  jusqu'au contexte prêt, ce qui compte quand huit JVM démarrent ensemble sur deux
  cœurs ARM. Le build garde aussi un cache Maven d'une fois sur l'autre.

## Comment la capacité tient

Les règles sont décrites dans [Réservations](#réservations) ; voici comment elles
sont tenues.

Le poids restant d'une annonce n'est écrit que sous **verrou de ligne**
(`SELECT … FOR UPDATE`), aussi bien quand une acceptation le décrémente que
quand le propriétaire modifie son annonce — sans ce second verrou, une
acceptation validée pendant l'édition était écrasée par la valeur périmée que le
formulaire renvoyait.

Chaque acceptation crée une ligne `trip_reservation` portant un
`transaction_id` **unique** : c'est elle qui rend la réservation idempotente
(double-clic sans effet, même transaction avec un autre poids refusée) et la
restitution rejouable sans risque. La restitution n'est appelée qu'**après** le
commit de l'annulation : rendue trop tôt, la place pourrait être revendue pour
une annulation qui, finalement, n'aboutit pas. `TripCapacityConcurrencyTest`
vérifie tout cela sur un vrai PostgreSQL, avec quatre rejeux concurrents.

L'expiration relit et revérifie chaque transaction sous verrou, une par
transaction d'écriture : plusieurs instances peuvent faire la passe en même
temps sans se marcher dessus (au prix d'un travail fait deux fois, qu'un verrou
partagé type ShedLock supprimerait).

Côté lecture, la pagination passe par un `OffsetPageRequest` qui honore un offset
arbitraire — `PageRequest` ne connaît que des numéros de page, et `offset=30,
limit=20` servait donc silencieusement les lignes 20 à 39. Chaque tri se termine
par `id`, pour que deux dates égales gardent le même ordre d'une page à l'autre.

## Résilience et abus

Les appels sortants vers un autre service passent par un **coupe-circuit**
(Resilience4j) et des délais courts (2 s de connexion, 5 s de lecture). Deux
choix à connaître :

- **Aucun repli ne fabrique de valeur.** On ne tarife pas une réservation contre
  une annonce qu'on n'a pas lue : un prix par défaut serait pire qu'une erreur.
  Le client reçoit `classification: INTERNAL_ERROR` avec
  `extensions.code = service_unavailable`, qui dit que réessayer a un sens.
- **Aucun rejeu automatique.** Réserver et rendre du poids sont idempotents par
  transaction, donc un rejeu à la main est sans danger ; mais rejouer reste une
  décision du code appelant, jamais un effet de bord du client HTTP.

Un 404 ou un refus d'accès ne comptent pas comme des pannes : sans cela, des
clients demandant des choses inexistantes finiraient par ouvrir le circuit.

La route `/users/**` est **limitée à 10 requêtes/seconde par IP** (rafale de 20),
compteurs dans Redis. C'est la seule route portant une opération anonyme
(`register`), et cette opération déclenche des appels à l'API d'administration de
Keycloak. Conséquence : **le gateway a besoin de Redis pour démarrer**. La clé de
comptage est `X-Forwarded-For` : en production, la gateway n'est joignable qu'à
travers Caddy, qui écrase cet en-tête avec l'IP réelle du client.

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

Un appel ressemble à ceci, avec un compte de test :

```bash
TOKEN=$(curl -s http://localhost:8000/realms/bagbuddy/protocol/openid-connect/token \
  -d grant_type=password -d client_id=bagbuddy-web \
  -d username=camille.martin@bagbuddy.local --data-urlencode 'password=Test1234!' \
  | python3 -c 'import sys, json; print(json.load(sys.stdin)["access_token"])')

curl http://localhost:8080/trips/graphql \
  -H "Authorization: Bearer $TOKEN" \
  -H 'Content-Type: application/json' \
  -d '{"query":"{ activeTrips { id departureAirport arrivalAirport pricePerKg userInfo { name } } }"}'
```

Trois scalaires maison complètent les types de base de GraphQL, qui n'en a que
cinq : `DateTime` (ISO-8601 local), `BigDecimal` (prix et poids, en décimal
exact — sérialiser un montant en `Float` perdrait de la précision) et `Long`.

### Le catalogue des opérations

La source de vérité reste `<service>/src/main/resources/graphql/schema.graphqls`
— chaque opération y est commentée. Vue d'ensemble :

**trip-service** — `/trips/graphql`

| Opération | Effet |
| --- | --- |
| `trips` · `activeTrips` · `inactiveTrips` | listes d'annonces, paginées (200 max) |
| `searchTrips(filter, limit, offset)` | recherche filtrée et triée côté serveur, avec totaux et agrégats |
| `trip(id)` · `tripsByIds(ids)` | une annonce, ou plusieurs dans l'ordre demandé (ids inconnus ignorés) |
| `tripsByUser(userId)` | les annonces d'un membre |
| `payoutAccount(userId)` | compte de versement, lisible par son seul propriétaire |
| `createTrip` · `updateTrip` · `deleteTrip` | publier, modifier, retirer **ses** annonces |
| `myTripAlerts` · `createTripAlert` · `deleteTripAlert` | alertes email sur les nouvelles annonces |

**transaction-service** — `/transactions/graphql`

| Opération | Effet |
| --- | --- |
| `myTransactions` | achats et ventes de l'appelant |
| `transaction(id)` | réservée aux deux participants |
| `transactionsBySeller` · `transactionsByBuyer` · `transactionCount` | réservées à l'intéressé lui-même |
| `totalEarned` · `totalSpent` | sommes des transactions terminées |
| `transactionMessages(transactionId, afterId)` | le fil de discussion, en lecture incrémentale |
| `createTransaction` | réserve du poids ; prix calculé côté serveur, contenu déclaré obligatoire |
| `updateTransaction` | fait avancer la machine à états (statuts, poids, drapeaux d'avis seulement) |
| `confirmHandover(id, code)` | le voyageur clôt avec le code à six chiffres |
| `sendTransactionMessage` | écrit dans le fil |
| `deleteTransaction` | refusé tant que la transaction tient du poids |

**review-service** — `/reviews/graphql`

| Opération | Effet |
| --- | --- |
| `reviews` · `review(id)` | lecture, paginée (200 max) |
| `reviewsByReviewee` · `reviewsByReviewer` · `reviewsByTransaction` | avis reçus, donnés, ou d'une transaction |
| `averageRating(revieweeId)` | moyenne reçue, `null` tant qu'il n'y a aucun avis |
| `createReview` · `updateReview` · `deleteReview` | l'auteur vient du jeton, jamais de la requête |

**user-service** — `/users/graphql`

| Opération | Jeton | Effet |
| --- | --- | --- |
| `me` | utilisateur | profil de l'appelant, créé à la volée |
| `user(sub)` | utilisateur | profil public : ni email, ni téléphone, ni compte de paiement |
| `favoriteListingIds` · `addFavoriteListing` · `removeFavoriteListing` | utilisateur | annonces mises de côté |
| `updateProfile` | utilisateur | champs libres (bio, ville, téléphone) |
| `updateIdentity` · `changePassword` | utilisateur | identité Keycloak, mot de passe actuel exigé |
| `sendVerificationEmail(language)` | utilisateur | renvoie le lien de vérification |
| `reportMember` | utilisateur | signalement à la modération |
| `register` | **aucun** | inscription |
| `requestPasswordReset` · `resetPassword` | **aucun** | mot de passe oublié |
| `verifyEmail(token)` | **aucun** | valide l'adresse depuis le lien reçu |

**stripe-service** — `/stripe/graphql`

| Opération | Effet |
| --- | --- |
| `stripeConfig` | clé publiable, celle que le front passe à Stripe.js |
| `payoutAccount` | où en est le compte Stripe Connect de l'appelant |
| `createPaymentIntent(transactionId)` | prépare le paiement ; prend une transaction, jamais un montant |
| `startPayoutOnboarding` | lien d'onboarding Connect, à usage unique |

### Ce qui reste volontairement en REST

| Endpoint | Pourquoi |
| --- | --- |
| `POST /stripe/webhook` | c'est Stripe qui appelle, avec le corps brut nécessaire à la vérification de signature ; l'appelant ne choisit pas ses champs |
| `/trips/internal/**`, `/transactions/internal/**` | appels service-à-service, un seul appelant et une seule forme de réponse ; les garder en REST évite d'ouvrir `/graphql` au jeton de service. En production, Caddy ne les expose pas du tout |
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

Un refus métier porte en plus un code stable dans `errors[].extensions.code`,
fait pour que le front affiche le bon message plutôt que d'analyser un texte :

| Code | Quand |
| --- | --- |
| `service_unavailable` | un service appelé n'a pas répondu — réessayer a un sens |
| `content_description_required` · `prohibited_items_not_accepted` | déclaration de contenu manquante à la création d'une réservation |
| `invalid_handover_code` · `handover_locked` · `handover_not_expected` | code de remise faux, bloqué après 5 essais, ou transaction pas au bon stade |
| `invalid_message` · `conversation_closed` · `too_many_messages` | messagerie : longueur, transaction annulée, 20 par minute |
| `invalid_reset_token` · `invalid_verification_token` | lien inconnu, expiré, déjà utilisé, ou envoyé à une adresse que le compte n'a plus |
| `verification_email_throttled` | moins d'une minute depuis le dernier envoi |
| `too_many_favorites` · `too_many_reports` · `cannot_report_self` | limites de `userservice` |
| `too_many_alerts` · `alert_needs_email` · `alert_invalid_route` · `alert_invalid_date` · `alert_invalid_flex` | alertes de trajet |

Le seul code HTTP qui reste porteur de sens est le `401` : sans jeton, la chaîne
de sécurité rejette la requête avant qu'elle n'atteigne le schéma. Une exception,
`userservice`, détaillée plus bas.

## Authentification

Tous les services sont des **resource servers OAuth2** : chaque requête doit
porter un `Authorization: Bearer <access_token>` Keycloak valide, sinon c'est
401. Le front web obtient ce token auprès de Keycloak depuis son propre écran de
connexion (direct access grant), puis appelle la gateway avec.

Chaque service vérifie lui-même le jeton : signature contre le JWKS de Keycloak,
audience `bagbuddy-api`, et émetteur dans une liste blanche (`JWT_ISSUER_URIS`) —
le même realm s'appelle `http://localhost:8000` ou `http://keycloak:8080` en dev,
`https://<AUTH_DOMAIN>` en production.

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
| `POST /trips/internal/{id}/release`            | transaction-service  | rendre le poids d'une transaction annulée |
| `POST /transactions/internal/{id}/payment`     | stripe-service       | enregistrer un paiement confirmé par webhook signé |
| `POST /stripe/internal/refunds`, `/transfers`  | transaction-service  | exécuter le remboursement et le versement décidés par le règlement |
| `GET`/`PUT /users/internal/{sub}/payout-account` | stripe-service     | lire / enregistrer le compte Stripe Connect d'un membre |

Les deux autres lectures entre services, elles, sont bien en GraphQL — et faites
**avec le jeton de l'appelant**, justement pour que le service appelé applique sa
propre règle : `reviewservice` et `stripeservice` lisent une transaction sur
`/transactions/graphql`, qui refuse déjà de la servir à qui n'y a pas pris part.

À noter pour le front : le poids restant d'une annonce n'est plus à décrémenter
côté client après une réservation — `transaction-service` s'en charge quand le
vendeur accepte la demande, sous verrou, ce qui évite de survendre la capacité.

Le montant d'un paiement n'est jamais fourni par le client : `stripe-service`
lit la transaction, qui a elle-même été tarifée côté serveur à partir de
l'annonce. Et le passage en « payé » n'est accepté que depuis un webhook Stripe
dont la signature est vérifiée (`STRIPE_WEBHOOK_SECRET`).

Attention en modifiant un client Keycloak par l'API d'administration : un `PUT`
d'un client récupéré via `/clients?clientId=` perd ses protocol mappers, dont
`bagbuddy-api-audience` — et chaque jeton échoue alors la vérification
d'audience.

## Front web

Le front web ne renvoie pas vers les pages de Keycloak : il a ses propres
écrans de connexion, d'inscription et de compte. Le client `bagbuddy-web` est
donc un client public avec le **grant `password`** (direct access grant) activé
et le flux redirection désactivé, et c'est `userservice` qui relaie vers l'API
d'administration ce qu'un navigateur ne peut pas porter :

| Opération (sur `/users/graphql`) | Jeton | Effet |
| --- | --- | --- |
| `register(input:)` | aucun | crée le compte Keycloak (email = identifiant, mot de passe permanent) |
| `requestPasswordReset(input:)` | aucun | envoie un lien `/reset-password#<token>` si l'email correspond à un compte actif ; répond toujours `true` |
| `resetPassword(input:)` | aucun | pose le mot de passe depuis le lien, le brûle, ferme les sessions du compte |
| `verifyEmail(token:)` | aucun | marque l'adresse vérifiée, une seule fois |
| `updateIdentity(input:)` | utilisateur | prénom, nom, email — un nouvel email exige `currentPassword` et repasse en non vérifié |
| `changePassword(input:)` | utilisateur | revérifie l'actuel auprès de Keycloak, puis le remplace |
| `sendVerificationEmail(language:)` | utilisateur | renvoie le lien `/verify-email#<token>` (24 h) à l'adresse actuelle |

Celles qui portent un jeton agissent sur son `sub` : aucune ne prend
d'identifiant d'utilisateur en argument, pour qu'un bug ne puisse pas devenir la
modification du compte d'autrui. Les trois opérations anonymes portent un
`@PreAuthorize("permitAll()")` explicite, pour qu'une opération **sans**
annotation se lise toujours comme un oubli.

Les droits d'administration du realm vivent dans un client Keycloak à part,
`bagbuddy-accounts` (`KEYCLOAK_ACCOUNTS_CLIENT_SECRET`) : le client `bagbuddy`,
qui sert la tarification et les appels machine-à-machine, n'en porte aucun. Deux
secrets, deux rayons d'explosion.

L'origine du front est réglée à deux endroits qui doivent rester alignés :

- **`BAGBUDDY_FRONT_URL`** — injectée dans le client `bagbuddy-web` (redirect
  URIs, web origins) à l'import du realm. Sans web origin correcte, Keycloak
  refuse les appels token / userinfo / logout du front, qui sont de simples
  `fetch` cross-origin. Défaut en dev : `http://localhost:4200`.
- **`CORS_ALLOWED_ORIGINS`** — le CORS de la gateway (plusieurs origines
  possibles, séparées par des virgules). En production il reprend
  automatiquement `BAGBUDDY_FRONT_URL`.

## Ajouter un microservice

N'oublie pas d'ajouter un Dockerfile et le bloc de service correspondant dans
`docker-compose.dev.yml` **et** `docker-compose.prod.yml` quand tu en crées un —
avec ses variables `PORT`, `JWT_ISSUER_URIS`, `JWT_JWK_SET_URI`, `JWT_AUDIENCE`
et `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE` (en prod, l'ancre `*java-env` les
fournit), et son `mem_limit`.

Côté service, il lui faut aussi les dépendances
`spring-cloud-starter-netflix-eureka-client`, `spring-boot-starter-actuator`,
`micrometer-tracing-bridge-brave` et `zipkin-reporter-brave` ; les blocs
`eureka:` et `management:` de son `application.yml` (recopiables depuis n'importe
quel service existant) ; `eureka.client.enabled=false` et
`management.tracing.enabled=false` dans ses propriétés de test ; et son chemin
GraphQL préfixé (`spring.graphql.http.path`). S'il a une base : Flyway,
`ddl-auto: validate`, un `V1__baseline.sql`, et `spring.flyway.enabled=false`
dans ses propriétés de test ; en prod, sa base et son utilisateur dans
`deploy/postgres/init-databases.sh` et `.env.prod.example` ; en dev, un
`seed/<service>/afterMigrate.sql` s'il doit avoir des données de test. Dans les
deux compose, donne-lui son `healthcheck` et un `depends_on` en `service_healthy`.
Enfin, ajoute sa route `lb://<nom>` dans
`apigateway/src/main/resources/application.yaml`, où `<nom>` est son
`spring.application.name`.

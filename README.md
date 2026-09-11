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

**Sommaire**

1. [Développement local](#développement-local)
   — [lancer](#tout-lancer) · [données de test](#données-de-test) · [Stripe en local](#stripe-en-local) · [commandes](#commandes-du-quotidien) · [tests](#tests)
2. [Production](#production)
   — [architecture](#architecture-de-production) · [déploiement pas à pas](#déploiement-pas-à-pas) · [exploitation](#exploitation) · [dépannage](#dépannage)
3. [Architecture](#architecture)
   — [service discovery](#service-discovery) · [base de données](#base-de-données-et-migrations) · [observabilité](#observabilité) · [résilience](#résilience-et-abus) · [GraphQL](#api-graphql) · [authentification](#authentification) · [front web](#front-web) · [ajouter un microservice](#ajouter-un-microservice)

| Fichier | Rôle |
| --- | --- |
| `docker-compose.dev.yml` | stack de développement : ports ouverts, GraphiQL, Zipkin, données de test |
| `docker-compose.prod.yml` | stack de production : Caddy en HTTPS devant tout, une seule instance Postgres, mémoire plafonnée |
| `.env.example` / `.env.prod.example` | modèles de configuration dev / prod (les vrais `.env` ne sont jamais commités) |
| `keycloak/import/bagbuddy-realm.json` | realm Keycloak, commun au dev et à la prod |
| `seed/` | données de test, **dev uniquement** (comptes Keycloak + contenu des bases) |
| `deploy/` | fichiers de prod : `Caddyfile`, script de création des bases, script de sauvegarde |

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

Après un changement du `.env` :
`docker compose -f docker-compose.dev.yml up -d` (recrée les conteneurs concernés).

## Commandes du quotidien

Rebuild et redémarrer un service après une modif de code :
```bash
docker compose -f docker-compose.dev.yml up --build -d <service_name>
```

Suivre les logs d'un service :
```bash
docker compose -f docker-compose.dev.yml logs -f <service_name>
```

Tout arrêter (ajoute `-v` pour effacer aussi les bases et repartir des données de test) :
```bash
docker compose -f docker-compose.dev.yml down
```

Lancer un service hors Docker (le reste de la stack tournant dans Docker) :
```bash
cd tripservice && ./mvnw spring-boot:run
```
Il faut alors exporter les mêmes variables que son bloc dans
`docker-compose.dev.yml` (`DATABASE_URL`, `PORT`, …).

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

## Réservations, capacité et recherche

La capacité d'une annonce est rattachée aux transactions : chaque acceptation crée
une réservation (`trip_reservation`) identifiée par la transaction.

- **Un double-clic sur « accepter » ne décompte le poids qu'une fois** : réserver
  à nouveau pour la même transaction ne change rien.
- **Annuler une transaction acceptée ou payée rend son poids** à l'annonce, une
  fois l'annulation écrite. Si l'appel à `trip-service` échoue, l'annulation reste
  valide et l'échec est journalisé et compté (voir *Supervision*).
- **Ce qui tient du poids ne disparaît pas en silence** : une transaction acceptée
  ou payée doit être annulée avant d'être supprimée, une annonce avec des
  réservations actives ne se supprime pas, et sa capacité totale ne descend pas
  sous le poids déjà réservé.
- **Les demandes jamais payées expirent** : toutes les 15 minutes, les
  transactions en attente de réponse, refusées ou acceptées sans paiement dont le
  vol est parti sont annulées (et leur poids rendu). Une transaction payée n'est
  jamais annulée automatiquement.

`searchTrips` filtre et trie les annonces réservables côté serveur (trajet, jour
± tolérance, prix, poids restant) et renvoie aussi le total et les agrégats du
filtre entier ; les listes (`tripsByUser`, `reviewsByReviewee`…) sont paginées à
200 éléments au plus.

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
| `POST /transactions/internal/{id}/payment`     | stripe-service       | enregistrer un paiement confirmé par webhook signé |

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
| `mutation { register(input: …) }` | aucun | crée le compte Keycloak (email = identifiant) |
| `mutation { updateIdentity(input: …) }` | utilisateur | prénom, nom, email |
| `mutation { changePassword(input: …) }` | utilisateur | vérifie l'actuel, puis le remplace |

Les deux dernières agissent sur le `sub` du jeton : aucune ne prend
d'identifiant d'utilisateur en argument, pour qu'un bug ne puisse pas devenir la
modification du compte d'autrui.

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

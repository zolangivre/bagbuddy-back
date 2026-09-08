# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Spring Boot microservices backend for BagBuddy. This repo is backend-only: the web front (Angular) lives in a separate repo, `bagbuddy-front`, and the historical Expo mobile app in the `BagBuddy` monorepo. Nothing here imports front code, and no path in this repo resolves into a front repo. Each service is an independent Maven project (Java 17, Spring Boot 3.5.6, own Postgres database) under its own directory: `eurekaserver`, `apigateway`, `tripservice`, `transactionservice`, `reviewservice`, `stripeservice`, `userservice`. Keycloak is the identity provider; every service validates the resulting JWT itself as an OAuth2 resource server (see Auth).

## Commands

Local dev runs everything through Docker Compose — see [README.md](README.md) for the full setup/testing walkthrough. Quick reference:

```bash
cp .env.example .env                                   # fill in local DB/Keycloak passwords
docker compose -f docker-compose.dev.yml up --build -d # start everything
docker compose -f docker-compose.dev.yml restart <service_name>
docker compose -f docker-compose.dev.yml down -v        # wipe DBs and start fresh
```

Per-service, outside Docker (each service has its own Maven wrapper):
```bash
cd tripservice        # or transactionservice / reviewservice / stripeservice / userservice / apigateway / eurekaserver
./mvnw spring-boot:run
./mvnw test
./mvnw clean package
```
Running a service this way still needs its Postgres DB and `DATABASE_URL`/`PORT` env vars — either keep the rest of the stack up via docker-compose and only rebuild+restart the one service you're iterating on, or export the same env vars docker-compose would (see the service's block in `docker-compose.dev.yml`).

`docker-compose.yml` (no `.dev` suffix) exists alongside `docker-compose.dev.yml` but is not the one used for local dev — check it before assuming it's current if you need it.

## Architecture

### Service layout

Every service follows the same layered package structure: `controller/` (REST endpoints, `@RequestMapping` per resource) → `service/` (business logic) → `repository/` (Spring Data JPA) → `model/` (JPA entities). Controllers are thin; look in `service/` for actual logic.

### Routing: static URLs, not service discovery

`eurekaserver` runs but is effectively inert — its `application.yaml` sets `register-with-eureka: false` and `fetch-registry: false`, and each downstream service's own Eureka client config is commented out (leftover from an earlier Heroku-hosted setup, see the dead `*.herokuapp.com` hostnames in `eurekaserver`/`*service` `application.yml` files). Actual routing goes through `apigateway`'s Spring Cloud Gateway config (`apigateway/src/main/resources/application.yaml`), which maps path prefixes to **literal** service URLs read from env vars:

```
/trips/**        -> ${TRIP_SERVICE_URL}
/transactions/** -> ${TRANSACTION_SERVICE_URL}
/reviews/**      -> ${REVIEW_SERVICE_URL}
/stripe/**       -> ${STRIPE_SERVICE_URL}
/users/**        -> ${USER_SERVICE_URL}
```

Those env vars are set on the `api-gateway` container in `docker-compose.dev.yml` to the other containers' docker-compose service names (e.g. `http://trip-service:8082`). `userservice` is now part of the routed stack (`/users/**` -> `http://user-service:8086`) and enabled in `docker-compose.dev.yml`.

The gateway does **not** validate tokens — it only routes. Each downstream service validates the bearer token itself (see Auth), so hitting a service directly on its published port is no weaker than going through the gateway.

The gateway also declares a global CORS config (`spring.cloud.gateway.globalcors`) allowing `${CORS_ALLOWED_ORIGINS}` (default `http://localhost:4200`, comma-separated for several origins) — required now that a browser front calls the gateway, unlike the native mobile app which needed none.

Most services hardcode their `server.port` default to `8082` in `application.yml` regardless of which service it is (`userservice` defaults to `8086`) — this only works in Docker because `docker-compose.dev.yml` overrides it per-container with an explicit `PORT` env var. Don't remove those `PORT` overrides or add a new service without one, along with its `JWT_ISSUER_URIS` / `JWT_JWK_SET_URI` / `JWT_AUDIENCE`.

### Data model: front-owned state machine, denormalized user/listing info

`Transaction.sellerStatus` / `buyerStatus` are plain `String` columns and the status *vocabulary* still lives in the front app (`TRANSACTION_STATUS` enum) — the backend does not validate the values, so adding or renaming a status means changing both repos by hand. What the backend does enforce is *who* may move *which* field: `update()` lets the buyer set only `buyerStatus`/`buyerReview` and the seller only `sellerStatus`/`sellerReview`. The initial value on creation is server-set from `bagbuddy.transaction.initial-status` (`TRANSACTION_INITIAL_STATUS`, default `pending`) — align it with the front's initial status.

Money is never client-supplied. `TransactionService.create()` fetches the listing from tripservice and computes `total = pricePerKg x weight` itself; `weight`, `total`, `listingInfo`, `paidAt` and the `stripe*` columns are not writable through `PUT /transactions/{id}`. `paidAt`/`stripe*` are only ever written by `markPaid()`, reached through the service-role internal endpoint from a signature-verified Stripe webhook.

`Trip`, `Transaction`, and `Review` all embed snapshots of user info (`@Embeddable UserInfo`: email, name, phone, bio, etc., keyed by Keycloak `sub`) and, for transactions, listing info (`@Embeddable ListingInfo`) directly on the record via `@Embedded`/`@AttributeOverrides`, rather than joining to a users table — there is no shared user table these services read from. `userservice` is the only service with its own `User` JPA entity, and it is the place to add profile data rather than widening the embedded snapshots.

`Trip.remainingWeight` is decremented server-side by `TripService.reserveCapacity()` (row-locked via `findByIdForUpdate`) when transactionservice creates a booking — the front must not decrement it, and could not anyway now that `PUT /trips/{id}` is owner-only. Deleting a transaction does not give the capacity back; that is a known gap, not an oversight to fix silently.

`Trip.active` is computed automatically in `TripListener` (a JPA `@PrePersist`/`@PreUpdate` entity listener), based on `remainingWeight > 0` and `departureDate` being in the future — don't set it directly, update `remainingWeight`/`departureDate` instead.

### Auth

Every service is an OAuth2 **resource server**: `spring-boot-starter-oauth2-resource-server` plus a `config/SecurityConfig.java` that requires a valid Keycloak access token on every request (`anyRequest().authenticated()`), with only `/actuator/health/**` and the Stripe webhook left open. Anonymous calls get 401. The front authenticates against Keycloak directly (OIDC/PKCE) and calls the gateway with the bearer token.

Three deliberate configuration choices in `SecurityConfig`:

- **`jwk-set-uri`, not `issuer-uri`.** `issuer-uri` makes Spring fetch the OIDC discovery document at bean-creation time, so a service would refuse to boot whenever Keycloak is not up yet. `NimbusJwtDecoder.withJwkSetUri(...)` is lazy.
- **The issuer is an allow-list** (`bagbuddy.auth.issuer-uris`, `JWT_ISSUER_URIS`). The same realm is reached under two names — `http://localhost:8000` from the browser, `http://keycloak:8080` from inside Docker — and the `iss` claim differs accordingly. Both are the same realm signing with the same keys; the pinned JWKS endpoint is what actually establishes trust.
- **The audience is checked** (`bagbuddy-api`), populated by the `bagbuddy-api-audience` protocol mapper declared on each client in the realm export.

Beyond authentication, each service enforces its own ownership rules in the `service/` layer: identity is always read from the token (`CallerIdentity`), never from the request body, so a client cannot publish a trip, book, or review under someone else's identity. Reads that would expose PII are filtered — trip browse endpoints return a `UserInfoView` without email/phone for non-owners, and transactions are participant-only.

Two endpoints are unreachable with a user token and require the `service` realm role, held only by the confidential `bagbuddy` client (`hasRole("SERVICE")` matchers in `SecurityConfig`):

| Endpoint | Caller | Why |
| --- | --- | --- |
| `GET /trips/internal/{id}` | transactionservice | price a booking against the real listing, and read the seller's contact snapshot |
| `POST /trips/internal/{id}/reserve` | transactionservice | decrement `remainingWeight` under a row lock |
| `POST /transactions/internal/{id}/payment` | stripeservice | record a payment confirmed by a signed webhook |

The `bagbuddy` client secret comes from `KEYCLOAK_SERVICE_CLIENT_SECRET` — injected into Keycloak at realm import via `${KEYCLOAK_SERVICE_CLIENT_SECRET}` in `bagbuddy-realm.json`, and read by transactionservice/stripeservice for their client-credentials grant. It is never committed. That service account holds only the `service` realm role; it deliberately does **not** have `realm-management`/`realm-admin`.

`userservice` owns application-side profiles (bio, location, phone, Stripe account) keyed by the Keycloak `sub`, and mirrors identity claims from the token on each `/users/me` call. It has no Keycloak admin client and no account-lifecycle endpoints: creating accounts, passwords, and email verification stay in Keycloak. `GET /users/{sub}` returns a `PublicUserProfile` with no email, phone or payout account.

The local Keycloak realm (`bagbuddy` realm, two public PKCE clients — `bagbuddy-web` for the Angular front on `http://localhost:4200`, `bagbuddy-mobile` for the Expo app — plus a seeded `testuser`/`Test1234!` account) auto-imports from `keycloak/import/bagbuddy-realm.json` on every `docker compose up` — see [README.md](README.md). Changing the front's origin/port means updating both `bagbuddy-web`'s redirect URIs/web origins here and `CORS_ALLOWED_ORIGINS`. That file is the source of truth for local Keycloak config; edit it (or re-export after changing the realm via the admin console) rather than reconfiguring Keycloak by hand each time. Keep redirect URIs and web origins exact — no `*` wildcards on a public client, which would let an attacker have the authorization code delivered to a host they control.

### Payments

`stripeservice` wraps the Stripe Java SDK. `POST /stripe/create-payment-intent` takes a `transactionId`, not an amount: it reads the transaction with the caller's own token (so transactionservice enforces the participant check), verifies the caller is the buyer, and derives the charge from the stored `total`. Metadata is built server-side. `POST /stripe/webhook` is the only path that marks a transaction paid, authenticated by `Webhook.constructEvent` against `STRIPE_WEBHOOK_SECRET` rather than a bearer token. It's commented out in `docker-compose.dev.yml` by default since it needs real Stripe test keys (`STRIPE_SECRET_KEY`/`STRIPE_PUBLISHABLE_KEY`/`STRIPE_WEBHOOK_SECRET` in `.env`) to be useful — uncomment its block once you have them.

### Deployment

Each service has a `Procfile` and `system.properties` (Heroku buildpack config) alongside its `Dockerfile` — this backend has historically been deployed to Heroku (see hardcoded `*.herokuapp.com` hostnames mentioned above), separate from the Docker Compose path used for local dev.

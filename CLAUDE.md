# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project overview

Spring Boot microservices backend for BagBuddy. **The public API is GraphQL**: each business service exposes its own schema under its own path prefix (`/trips/graphql`, `/transactions/graphql`, `/reviews/graphql`, `/stripe/graphql`, `/users/graphql`) — see GraphQL API below. REST survives only where GraphQL does not fit: the Stripe webhook, the service-to-service `/internal/**` endpoints, and actuator health. This repo is backend-only: the web front (Angular) lives in a separate repo, `bagbuddy-front`, and the historical Expo mobile app in the `BagBuddy` monorepo. Nothing here imports front code, and no path in this repo resolves into a front repo. Each service is an independent Maven project (Java 21, Spring Boot 3.5.6, own Postgres database) under its own directory: `eurekaserver`, `apigateway`, `tripservice`, `transactionservice`, `reviewservice`, `stripeservice`, `userservice`. Keycloak is the identity provider; every service validates the resulting JWT itself as an OAuth2 resource server (see Auth).

## Commands

Local dev runs everything through Docker Compose — see [README.md](README.md) for the full setup/testing walkthrough. Quick reference:

```bash
cp .env.example .env                                   # fill in local DB/Keycloak passwords
docker compose -f docker-compose.dev.yml up --build -d # start everything
docker compose -f docker-compose.dev.yml restart <service_name>
docker compose -f docker-compose.dev.yml down -v        # wipe DBs and start fresh (re-seeds test data)
docker compose -f docker-compose.dev.yml --profile stripe-webhooks up -d  # also relay Stripe test webhooks

# production (on the VM, see README "Production")
docker compose -f docker-compose.prod.yml --env-file .env.prod up -d --build
```

Per-service, outside Docker (each service has its own Maven wrapper):
```bash
cd tripservice        # or transactionservice / reviewservice / stripeservice / userservice / apigateway / eurekaserver
./mvnw spring-boot:run
./mvnw test
./mvnw clean package
```
Running a service this way still needs its Postgres DB and `DATABASE_URL`/`PORT` env vars (it will also look for a registry on `http://localhost:8761/eureka/` and merely log a warning if none answers — registration failing does not stop a service from booting or serving) — either keep the rest of the stack up via docker-compose and only rebuild+restart the one service you're iterating on, or export the same env vars docker-compose would (see the service's block in `docker-compose.dev.yml`).

## Architecture

### Service layout

Every service follows the same layered package structure: `controller/` (`@Controller` with `@QueryMapping`/`@MutationMapping` resolvers) → `service/` (business logic) → `repository/` (Spring Data JPA) → `model/` (JPA entities). Resolvers are thin; look in `service/` for actual logic — the migration to GraphQL deliberately did not move any rule out of the service layer.

Each service also carries, in `src/main/resources/graphql/schema.graphqls`, the schema that defines its public surface. Treat that file as part of the contract: what an input type does not expose cannot be written, and several ownership rules are now enforced by the type system rather than by silently ignoring fields (`TripInput` has no `userId`, `CreateTransactionInput` has no `total`/`sellerId`/`paidAt`, `CreateReviewInput` has no `reviewerId`/`revieweeId`).

`config/GraphQlScalarConfig.java` (duplicated across the services whose schema actually declares a custom scalar — `tripservice`, `transactionservice`, `reviewservice`; `stripeservice` and `userservice` declare none and carry no such class) registers the scalars GraphQL lacks: `DateTime` (ISO-8601 local, the format Jackson already produced), `BigDecimal` (money and weights, in exact decimal — a `Float` would lose precision) and `Long`. `web/GraphQlExceptionResolver.java` replaces the REST `@RestControllerAdvice` for GraphQL: it maps `AccessDeniedException` → `FORBIDDEN`/`UNAUTHORIZED`, `NoSuchElementException` → `NOT_FOUND`, `IllegalArgumentException`/`ConstraintViolationException` → `BAD_REQUEST`, and leaves everything else as a generic `INTERNAL_ERROR` so nothing internal leaks. In GraphQL the transport stays `200`: the meaning is in `errors[].extensions.classification`, which is what tests assert on.

### Routing: Eureka service discovery

`eurekaserver` is a real, working registry: the five business services **and** the gateway register with it, and `apigateway` resolves its routes through it. Routing goes through `apigateway`'s Spring Cloud Gateway config (`apigateway/src/main/resources/application.yaml`), which maps path prefixes to `lb://` targets named after each service's `spring.application.name`:

```
/trips/**        -> lb://trip-service
/transactions/** -> lb://transaction-service
/reviews/**      -> lb://review-service
/stripe/**       -> lb://stripe-service
/users/**        -> lb://user-service
```

The gateway therefore holds no service URL at all. Two consequences worth knowing before debugging a routing problem:

- **A service that is not registered gives a 503, not a connection refusal.** `stripe-service` refuses to boot while `STRIPE_SECRET_KEY` is empty, so on a fresh `.env` `/stripe/**` answers 503 — that is expected, not a bug.
- **Registration is not instantaneous.** Client and server timings are deliberately tuned down for dev (`lease-renewal-interval-in-seconds: 5`, `registry-fetch-interval-seconds: 5`, server eviction every 10s, self-preservation off), which brings a restarted service back into rotation in about 4 seconds instead of the 30–90 the Eureka defaults would cost. These values are dev settings: they generate a lot of control traffic and turn off the safety net that keeps a registry stable during a network blip. `docker-compose.prod.yml` widens them through env vars (renewal/fetch 10s, expiration 30s, eviction 15s, load-balancer cache 10s) but deliberately **keeps self-preservation off**: prod is a single host, so there is no network partition for it to guard against, and with six instances restarting a single one drops renewals below its 85% threshold — the registry would then keep the old container IP and the gateway would route to it.

One easily-missed knob lives on the gateway rather than on Eureka: `spring.cloud.loadbalancer.cache.ttl` is 35 seconds by default, which would have silently cancelled out the tuning above. It is set to `5s`.

`register-with-eureka: false` / `fetch-registry: false` on `eurekaserver` itself is **not** a mistake to fix — that is the normal configuration of a standalone registry, which must not try to register with itself.

A single `/graphql` endpoint per service would have broken this path-prefix routing, so each service sets `spring.graphql.http.path` to its own prefix (`/trips/graphql`, …) instead of the default `/graphql`. The gateway therefore needed no rewrite filter, and hitting a container directly uses the exact same URL. `spring.graphql.graphiql` follows the same convention (`/trips/graphiql`, …) and is off unless `GRAPHIQL_ENABLED=true` — `docker-compose.dev.yml` sets it. Don't add a new service without giving it this prefixed path, or its schema will answer on `/graphql` and be unroutable.

Every service reaches the registry through `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`, set to `http://eureka-server:8761/eureka/` on each container in `docker-compose.dev.yml` and defaulting to `http://localhost:8761/eureka/` outside Docker. Instances register by IP (`eureka.instance.prefer-ip-address: true`) because a container hostname only resolves inside the compose network, while its IP always does.

The three service-to-service calls deliberately **do not** go through discovery: `transactionservice` → `tripservice` and `stripeservice` → `transactionservice` keep their literal `TRIP_SERVICE_URL` / `TRANSACTION_SERVICE_URL` env vars. They are few, fixed, and sit on the pricing and payment path, where a registry gap would turn into a failed booking. Discovery earns its place at the gateway, which would otherwise have to know every service URL.

Tests set `eureka.client.enabled=false` and `management.tracing.enabled=false` in each service's `src/test/resources/application.properties`: a context-load test must not require a registry or a trace collector to be up.

### Observability and rate limiting

Every service (plus the gateway and Eureka) exposes `/actuator/health`. It used to be permitted in `SecurityConfig` but did not exist — only the gateway carried the actuator dependency, so the permit rule was dead and the endpoint answered 401 (404 on userservice, whose `ERROR` dispatch is permitted). `docker-compose.dev.yml` now uses it: `depends_on` waits on `service_healthy` rather than on a container merely having started. Health details are `when-authorized`, so an anonymous caller gets `UP` and nothing about the infrastructure.

Micrometer Tracing (Brave) propagates a single trace id from the gateway through each service and into the log lines; Zipkin collects on `http://localhost:9411`. Sampling is 100% in dev via `TRACING_SAMPLE_RATE` — lower it anywhere else.

The `/users/**` route is rate limited to 10 req/s per IP (burst 20) with counters in Redis, because it is the only route carrying an anonymous operation (`register`) and that operation drives Keycloak admin API calls. The key is `X-Forwarded-For` when present, falling back to the socket address — behind a proxy every request would otherwise share one quota. **The gateway now needs Redis to start.**

The gateway does **not** validate tokens — it only routes. Each downstream service validates the bearer token itself (see Auth), so hitting a service directly on its published port is no weaker than going through the gateway.

The gateway also declares a global CORS config (`spring.cloud.gateway.globalcors`) allowing `${CORS_ALLOWED_ORIGINS}` (default `http://localhost:4200`, comma-separated for several origins) — required now that a browser front calls the gateway, unlike the native mobile app which needed none.

Each service defaults `server.port` to its own published port (`8082` trip, `8083` transaction, `8084` review, `8085` stripe, `8086` user, `8080` gateway, `8761` eureka), so `./mvnw spring-boot:run` outside Docker binds where the README says it will. `docker-compose.dev.yml` still overrides it per-container with an explicit `PORT` env var. Don't remove those `PORT` overrides or add a new service without one, along with its `JWT_ISSUER_URIS` / `JWT_JWK_SET_URI` / `JWT_AUDIENCE` / `EUREKA_CLIENT_SERVICEURL_DEFAULTZONE`. A new service also needs the `spring-cloud-starter-netflix-eureka-client` dependency, an `eureka:` block copied from any existing service, `eureka.client.enabled=false` in its test properties, its prefixed `spring.graphql.http.path`, and an `lb://<spring.application.name>` route on the gateway.

### Schema: Flyway, not `ddl-auto`

The schema belongs to **Flyway** (`<service>/src/main/resources/db/migration`), and `spring.jpa.hibernate.ddl-auto` is `validate`. A service refuses to boot when its entities and its migrations disagree — deliberately, since the alternative is discovering the drift on a query.

`V1__baseline.sql` is the schema as Hibernate had built it under the old `ddl-auto: update`. `spring.flyway.baseline-on-migrate: true` means an existing database (dev volumes created before the switch) is marked at V1 rather than having it replayed, so only later migrations apply to it; a fresh database runs V1 normally. `V2__indexes.sql` adds the indexes that were missing entirely — every repository query used to scan its table.

Adding a column means a new `V<next>__...sql` plus the matching entity change. Never edit an applied migration.

**Dev test data** lives in `seed/`, outside the jars: `seed/<service>/afterMigrate.sql` is a Flyway SQL callback that `docker-compose.dev.yml` loads by mounting the folder at `/seed` and setting `SPRING_FLYWAY_LOCATIONS=classpath:db/migration,filesystem:/seed`; prod never sets it. Each script is a `DO` block that returns early if its table already has a row, inserts explicit ids, then `setval`s the identity sequence. The data is cross-database: the Keycloak `sub`s are fixed (`5eed0000-0000-4000-8000-00000000000N`, from `seed/keycloak/bagbuddy-users-0.json`), trip ids are referenced by transactions, transaction ids by reviews, and `trip.remaining_weight` / the `buyer_review`/`seller_review` flags are written to agree with the other files — change one file, check the others. Dates are relative to `current_date` so upcoming trips stay upcoming, and `trip.active` is computed in the INSERT because `TripListener` does not run on raw SQL. A schema change that touches a seeded column must update the matching seed script, or a fresh dev database fails at boot.

One index deliberately absent: `trip.active`. That column is only recomputed by `TripListener` on write, so it goes stale the moment a departure date passes without the row being touched — which is why `TripService.getActiveTrips()` filters on `remainingWeight`/`departureDate` (now in SQL, previously loading the whole table into memory) rather than on `active`.

### Data model: server-side state machine, denormalized user/listing info

`Transaction.sellerStatus` / `buyerStatus` are plain `String` columns, but the state machine is **server-side**: `service/TransactionStateMachine.java` holds the allowed moves as pair-to-pair edges (both columns always travel together — accepting a request sets the seller to `awaiting_payment` *and* the buyer to `payment_required`), each edge carrying its `allowedActors` and an `Effect` (`NONE`, `REPRICE`, `RESERVE_CAPACITY`, `SETTLE_PAYMENT`). `resolve()` throws `IllegalArgumentException` on any move that is not an edge, so an invalid transition is a `BAD_REQUEST`, not a silent write. What is enforced per side is the *review flags*: a buyer may set only `buyerReview`, a seller only `sellerReview`. The status vocabulary lives in `config/TransactionStatusProperties.java` (`@ConfigurationProperties("bagbuddy.transaction.status")`, nine names, overridable per environment) and the initial pair comes from `initialPair()` — the *strings* must still match the front's `TRANSACTION_STATUS` enum, so renaming one means changing both repos by hand.

Money is never client-supplied. `TransactionService.create()` fetches the listing from tripservice and computes `total = pricePerKg x weight` itself; `total`, `listingInfo`, `paidAt` and the `stripe*` columns are absent from `UpdateTransactionInput` altogether, so the schema rejects them outright instead of silently ignoring them — `TransactionSecurityTest` asserts exactly that. `weight` is accepted there but only honoured when the requested transition calls for a re-pricing, itself computed from the listing. `paidAt`/`stripe*` are only ever written by `markPaid()`, reached through the service-role internal endpoint from a signature-verified Stripe webhook.

`Trip`, `Transaction`, and `Review` all embed snapshots of user info (`@Embeddable UserInfo`: email, name, phone, bio, etc., keyed by Keycloak `sub`) and, for transactions, listing info (`@Embeddable ListingInfo`) directly on the record via `@Embedded`/`@AttributeOverrides`, rather than joining to a users table — there is no shared user table these services read from. `userservice` is the only service with its own `User` JPA entity, and it is the place to add profile data rather than widening the embedded snapshots.

`Trip.remainingWeight` is decremented server-side by `TripService.reserveCapacity()` (row-locked via `findByIdForUpdate`) when transactionservice creates a booking — the front must not decrement it, and could not anyway now that the `updateTrip` mutation is owner-only. Deleting a transaction does not give the capacity back; that is a known gap, not an oversight to fix silently.

`Trip.active` is computed automatically in `TripListener` (a JPA `@PrePersist`/`@PreUpdate` entity listener), based on `remainingWeight > 0` and `departureDate` being in the future — don't set it directly, update `remainingWeight`/`departureDate` instead.

### Auth

Every service is an OAuth2 **resource server**: `spring-boot-starter-oauth2-resource-server` plus a `config/SecurityConfig.java` that requires a valid Keycloak access token on every request (`anyRequest().authenticated()`), with only `/actuator/health/**`, the GraphiQL console page and the Stripe webhook left open. Anonymous calls get 401 — the security chain rejects them before the schema is reached, so an anonymous GraphQL call is a genuine HTTP 401 and not a `200` carrying an error.

**`userservice` is the deliberate exception, and the one spot to be careful in.** Sign-up belongs to the same schema as everything else and GraphQL exposes a single URL, so the path-based `permitAll` that used to cover `POST /users/register` is no longer expressible. `POST /users/graphql` is therefore open, and authentication is carried operation by operation by `@PreAuthorize("isAuthenticated()")` on each resolver of `UserGraphQlController` (with `@EnableMethodSecurity` on the config). **Any operation added to that controller must carry `@PreAuthorize` or it becomes anonymous.** `UserProfileSecurityTest.anonymousCallersAreRejected` walks every operation of the schema without a token and asserts `UNAUTHORIZED`, which is what keeps this honest. A token that is present but invalid is still rejected with 401 by the bearer filter, before the schema. The web front authenticates against Keycloak directly (direct access grant from its own sign-in form) and calls the gateway with the bearer token; the Expo app still uses OIDC/PKCE.

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

These three stayed REST on purpose: one caller, one response shape, and keeping them off `/graphql` means the service-role token never gets a foothold on the public schema. They live in `TripInternalController` / `TransactionInternalController`, and the `web/ApiExceptionHandler` still present in those two services now covers only them — a `@RestControllerAdvice` never sees a GraphQL resolver.

All outgoing calls to another service go through a **Resilience4j circuit breaker** with short timeouts (`spring.http.client.connect-timeout: 2s`, `read-timeout: 5s`). Two decisions are load-bearing:

- **No fallback ever invents a value.** A booking cannot be priced against a listing that was never read, so the fallback rethrows rather than substituting a default price. Business outcomes (`NoSuchElementException`, `AccessDeniedException`, `IllegalArgumentException`) propagate untouched; everything else becomes `ServiceUnavailableException`, surfaced to the client as `INTERNAL_ERROR` with `extensions.code = service_unavailable`.
- **`reserve()` is never retried.** It removes weight from a listing; replaying it after an ambiguous timeout would double-book. There is no retry configured anywhere, on purpose.

Those same business exceptions are listed under `ignore-exceptions` in the circuit breaker config: a run of 404s is clients asking for things that do not exist, not a failing dependency, and counting them would open the circuit for no reason.

The other two service-to-service reads did move to GraphQL, because they are made *with the caller's own token* precisely so the downstream service applies its own participant check: `reviewservice` and `stripeservice` both read a transaction through `HttpSyncGraphQlClient` against `/transactions/graphql`, asking only for the fields they need rather than pulling the whole transaction. Their `TransactionClient` translates `errors[].extensions.classification` back into `AccessDeniedException` / `NoSuchElementException`, since a refusal arrives as a `200`.

The `bagbuddy` client secret comes from `KEYCLOAK_SERVICE_CLIENT_SECRET` — injected into Keycloak at realm import via `${KEYCLOAK_SERVICE_CLIENT_SECRET}` in `bagbuddy-realm.json`, and read by transactionservice/stripeservice for their client-credentials grant. It is never committed. That service account holds only the `service` realm role; it deliberately does **not** have `realm-management`/`realm-admin`.

`userservice` owns application-side profiles (bio, location, phone, Stripe account) keyed by the Keycloak `sub`, and mirrors identity claims from the token on each `me` query — so a token missing a claim blanks the matching field, which is intended. The `user(sub:)` query returns a `PublicUserProfile` type that has no email, phone or payout account field at all: asking for one is a schema error, not a quietly omitted value.

It also carries the **account lifecycle** (folded into `UserGraphQlController` alongside the profile operations — GraphQL exposes one endpoint per service, so the split into a separate `AccountController` no longer had anything to split), because the web front serves its own sign-up and account screens instead of Keycloak's pages. Creating a user, changing an email and setting a password are admin operations that a browser can never hold the credentials for, so they are proxied here:

| Operation (on `/users/graphql`) | Token | What it does |
| --- | --- | --- |
| `register(input:)` | none | creates an enabled Keycloak user, email as username, password permanent |
| `updateIdentity(input:)` | user's | first/last name, email — a new email resets `emailVerified` |
| `changePassword(input:)` | user's | re-checks the current password, then resets it |

Three rules hold this together, and breaking any of them turns a profile service into a user-administration API:

- **Every operation acts on `jwt.getSubject()`.** No method here takes a user id: a caller can only ever edit its own account.
- **The admin credentials are a separate client.** `bagbuddy-accounts` (secret `KEYCLOAK_ACCOUNTS_CLIENT_SECRET`) holds `realm-management`'s `manage-users`/`view-users`; the `bagbuddy` pricing client deliberately still holds none of that. Two secrets, two blast radii.
- **The current password is verified by asking Keycloak for a token with it** (`passwordMatches`), not by any admin endpoint — that way the realm's brute force protection counts the attempt.

Every `SecurityConfig` permits the `ERROR` dispatch. Without it a validation failure (400) is re-filtered on the internal forward to `/error`, arrives without a bearer token, and reaches the client as a puzzling 401.

The local Keycloak realm (`bagbuddy` realm; `bagbuddy-web` is public with the **direct access grant** enabled and the redirect flow off, since the Angular front never leaves the site to sign in; `bagbuddy-mobile` stays a public PKCE client for the Expo app; `bagbuddy-accounts` is the confidential client behind the account endpoints above; and a `length(8)` password policy) imports from `keycloak/import/bagbuddy-realm.json` when the realm does not exist yet — see [README.md](README.md). The same file serves dev and prod: both composes mount it as a single file, and only the dev one also mounts `seed/keycloak/bagbuddy-users-0.json`, which Keycloak's directory import picks up right after the realm (the five demo accounts and `testuser`, all `Test1234!`; the realm file itself holds no human user). The front's origin is the `${BAGBUDDY_FRONT_URL}` placeholder in `bagbuddy-web`'s redirect URIs, web origins, root URL and post-logout URIs, substituted at import (dev defaults it to `http://localhost:4200`; prod also feeds it to the gateway's `CORS_ALLOWED_ORIGINS`). Changing the front's origin therefore means both that value and `CORS_ALLOWED_ORIGINS` — the web origins are what makes Keycloak answer the front's token, userinfo and logout calls at all, since they are now plain cross-origin fetches rather than redirects. That file is the source of truth for local Keycloak config; edit it (or re-export after changing the realm via the admin console) rather than reconfiguring Keycloak by hand each time. Note that the import only runs when the realm does not exist yet: on a stack that already has one, the same change has to be applied through the admin API as well, and a `PUT` of a client fetched from `/clients?clientId=` will silently drop its protocol mappers (that is how the `bagbuddy-api-audience` mapper gets lost, and every token then fails the audience check). Keep redirect URIs and web origins exact — no `*` wildcards on a public client, which would let an attacker have the authorization code delivered to a host they control.

### Payments

`stripeservice` wraps the Stripe Java SDK. The `createPaymentIntent(transactionId:)` mutation takes a transaction id, not an amount — no amount appears anywhere in its schema: it reads the transaction with the caller's own token (so transactionservice enforces the participant check), verifies the caller is the buyer, and derives the charge from the stored `total`. Metadata is built server-side. `POST /stripe/webhook` stays REST and is the only path that marks a transaction paid, authenticated by `Webhook.constructEvent` against `STRIPE_WEBHOOK_SECRET` rather than a bearer token. It runs in `docker-compose.dev.yml` but only boots once `STRIPE_SECRET_KEY` is set in `.env`; nothing depends on it. Locally, Stripe cannot reach the webhook, so the optional `stripe-cli` service (profile `stripe-webhooks`) relays test events to `api-gateway:8080/stripe/webhook`. `PAYMENTS_REQUIRE_STRIPE` defaults to `true` in both composes; setting it to `false` in the dev `.env` simulates payment. Prod never sets it.

### Deployment

The Heroku path is gone. It had stopped working long before it was removed: the six `.github/workflows/deploy-*.yml` triggered only on a `cd/back` branch that no longer exists, and they passed `usedocker: true`, which builds from each `Dockerfile` and ignores the `Procfile` / `system.properties` entirely. Deployment today is `docker-compose.prod.yml` on a single Linux host (target: an Oracle Cloud Always Free Ampere A1 VM, 2 OCPU / 12 GB ARM), building each service's `Dockerfile` on the machine; there is no CI in this repo. What differs from dev, and why:

- **Only Caddy publishes ports** (80/443, `deploy/Caddyfile`, automatic Let's Encrypt for `API_DOMAIN` and `AUTH_DOMAIN`). The gateway is reachable only through Caddy, which overwrites `X-Forwarded-For` — that is what makes the rate-limit key trustworthy. Caddy also 404s `/admin` on the Keycloak host and `/trips/internal/*` / `/transactions/internal/*` on the API host. Keycloak is additionally bound to `127.0.0.1:8081` for the admin console over an SSH tunnel (`KC_HOSTNAME_ADMIN`).
- **One Postgres instance** with a database and owner per consumer, created by `deploy/postgres/init-databases.sh` on first volume init (`REVOKE CONNECT ... FROM PUBLIC`, so a service cannot open another's database). Services get credentials through `SPRING_DATASOURCE_USERNAME`/`PASSWORD` rather than inside `DATABASE_URL`, so generated passwords need no URL escaping.
- **Keycloak runs `start`** with `KC_HOSTNAME=https://${AUTH_DOMAIN}`, `KC_PROXY_HEADERS=xforwarded`, `KC_HTTP_ENABLED=true`. With a fixed hostname every token's `iss` is the public URL, even when a service fetches it on `http://keycloak:8080`; `JWT_ISSUER_URIS` still lists both. Its healthcheck is a bash `/dev/tcp` request to `/health/ready` on management port 9000 — the image has no curl/wget.
- **Memory is capped**: `mem_limit: 640m` per Java service with `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=65` (the JVM then picks SerialGC), 1g Keycloak, 1g Postgres. Measured idle: ~320 MB per service. No Zipkin (`MANAGEMENT_TRACING_ENABLED=false`), no GraphiQL, no `seed/`. `start_period` is 120–180s because eight JVMs share two ARM cores.
- Required variables use `${VAR:?}` so a missing secret stops `compose up` instead of booting half-configured. `deploy/backup.sh` does `pg_dumpall --clean --if-exists` into `backups/` (gitignored); restoring prints two harmless errors about the `postgres` role.

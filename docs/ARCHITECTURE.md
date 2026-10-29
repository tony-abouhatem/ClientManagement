# Architecture — ClientManagement

Status: **planned**. The repo is still the Spring Initializr skeleton. Items marked TBD get decided during implementation.

## Components
| Component | Tech | Role |
|-----------|------|------|
| `client-management` (app) | Spring Boot 4.1.2, Java 21, Maven | REST API, JPA, Resource Server, AMQP publisher and consumer, Score client |
| PostgreSQL 16 | container | customers, outbox, processed events |
| RabbitMQ (management image) | container | `CUSTOMER_CREATED` out, `CUSTOMER_STATUS_CHANGE` in, DLQ |
| Keycloak | container, realm imported at startup | Issues JWTs (realm `client-management`) |
| WireMock | container plus `mappings/` | Simulates the external Score API `GET /scores/{cpf}` |

```mermaid
flowchart LR
  Client[Postman / Swagger UI] -- "1. token (password grant)" --> KC[Keycloak]
  Client -- "2. REST + Bearer JWT" --> App[client-management]
  App -- "JWKS (issuer-uri)" --> KC
  App -- "JPA / Flyway" --> PG[(PostgreSQL)]
  App -- "GET /scores/{cpf}\n timeout + retry + CB" --> WM[WireMock Score]
  App -- "publish CUSTOMER_CREATED\n(after commit, confirms)" --> EX{{exchange customer.events}}
  EX -- customer.created --> QC[[queue customer.created]]
  Upstream[Upstream / rabbitmq UI] -- "CUSTOMER_STATUS_CHANGE\nrk customer.status.change" --> EX
  EX --> QS[[queue customer.status-change]]
  QS -- consume --> App
  QS -. "rejected / retries exhausted" .-> DLX{{customer.dlx}} --> DLQ[[customer.status-change.dlq]]
  App -- "relay job: retry failed publishes" --> PG
```

## Package layout (`com.datum.clientmanagement`)
- `customer`: entity, repository, service, controller, DTOs, CPF validator/normalizer, and the Score integration: `ScoreClient` (RestClient + Resilience4j) and the `GET /customers/{id}/score` endpoint.
- `messaging`: AMQP topology config, `CustomerCreated` publisher, outbox entity and relay, status-change listener, `processed_events`.
- `security`: `SecurityFilterChain` and the Keycloak role → `ROLE_*` converter.
- `common.error`: `@RestControllerAdvice` (validation `errors[]`, generic 500). Business errors are thrown as `ResponseStatusException`.
- `config`: typed `@ConfigurationProperties` (Score, messaging, outbox, pagination).

## Data model
Source of truth: `src/main/resources/db/migration` (Flyway). Hibernate runs with `ddl-auto=validate`.

- **customers**: `id` bigserial PK, `name` varchar(150) not null, `name_normalized` varchar(150) not null (lowercased, accents stripped; indexed), `cpf` char(11) **unique** not null, `email` varchar(254) not null, `status` varchar(20) not null (`ACTIVE|INACTIVE`), `created_at`, `updated_at` timestamptz, `version` bigint (optimistic lock).
- **outbox_events**: `id` bigserial PK, `event_id` uuid unique, `event_type`, `aggregate_id` (customer id), `payload` jsonb, `created_at`, `attempts` int, `last_error` text, `last_error_type` varchar(20) and `last_failed_at` (V2), `next_attempt_at`, `sent_at` (null = pending; dead once `attempts` reaches the relay's max-attempts).
- **processed_events**: `event_id` uuid PK, `event_type`, `processed_at`.

Relations: none enforced between the event tables and `customers`. They are logs keyed by id or uuid.

## REST API (all under `/customers`, JSON)
| Method | Path | Role | Success | Notable errors |
|--------|------|------|---------|----------------|
| POST | `/customers` | ADMIN | 201 + `Location` | 400 validation, 409 duplicate CPF |
| PUT | `/customers/{id}` | ADMIN | 200 | 400 (incl. missing `version`), 404, 409 `version` mismatch, 422 CPF change |
| DELETE | `/customers/{id}` | ADMIN | 204 (also for an unknown or already-deleted id: idempotent) | — |
| GET | `/customers/{id}` | USER, ADMIN | 200 | 404 |
| GET | `/customers?status=&name=&page=&size=&sort=` | USER, ADMIN | 200 page | 400 invalid status |
| GET | `/customers/search?name=` (paged) | USER, ADMIN | 200 page | 400 blank name |
| GET | `/customers/{id}/score` | USER, ADMIN | 200 `{customerId,cpf,score,classification}` | 404 customer, 404 score, 502, 503, 504 |

Customer response: `{id,name,cpf,email,status,version,createdAt,updatedAt}`. PUT body: `{name,email,status,version (required),cpf?}`.

Pagination: defaults `page=0`, `size=20`, configurable through `app.pagination.default-page` / `default-size` (env `PAGINATION_DEFAULT_PAGE` / `PAGINATION_DEFAULT_SIZE`). `page<0`, `size<1` or `size>100` returns 400 with `errors[]`. No clamping.

All customer-not-found cases return 404 (standard title `Not Found`, detail `No customer exists with id <id>.`), except DELETE, which is idempotent and returns 204.

Page response shape: `{ "content": [...], "page": { "size", "number", "totalElements", "totalPages" } }` (Spring Data `PagedModel` via DTO serialization).

## Auth
- Spring Security OAuth2 Resource Server. JWTs are validated against `spring.security.oauth2.resourceserver.jwt.issuer-uri`, which points at the Keycloak realm.
- Keycloak realm `client-management` is imported from a JSON file under `docker/keycloak/`. It contains realm roles `USER` and `ADMIN`, users `user/user` (USER) and `admin/admin` (USER + ADMIN), and a public client with direct-access grants. The client id is `client-management-cli` (file `docker/keycloak/client-management-realm.json`).
- A custom `JwtAuthenticationConverter` maps `realm_access.roles` to `ROLE_<role>`.
- Issuer mismatch between the host (`localhost`) and the compose network (`keycloak`): Keycloak runs with `KC_HOSTNAME=http://localhost:8081` (plus `KC_HOSTNAME_BACKCHANNEL_DYNAMIC=true`), so every token carries `iss=http://localhost:8081/realms/client-management`, whether it was requested from the host or from inside compose. The app validates that issuer (`ISSUER_URI`) but fetches signing keys through `JWK_SET_URI`, which inside compose is `http://keycloak:8080/realms/client-management/protocol/openid-connect/certs`. Spring Boot uses the JWK set URI for keys and still checks the `iss` claim against `issuer-uri`, so no OIDC discovery call to `localhost` is made from inside the container.
- 401 and 403 responses are ProblemDetail, produced by a custom entry point and access-denied handler.

## External integration: Score
- Spring `RestClient`, base URL `SCORE_BASE_URL`. Connect timeout 1 s, read timeout 2 s.
- Resilience4j `retry` (max 2 attempts, exponential backoff, only on 5xx/IO/timeout) wrapped in a `circuitbreaker` (50% failure rate, window 10, open 10 s).
- Error mapping (PRODUCT NFR-5): connection refused, connect timeout, 5xx after retries or circuit open → 503. Read timeout only → 504. Score 404 → 404 `Score not found`. Malformed body or other 4xx → 502.
- WireMock mappings, keyed by CPF, cover OK, 404, 500 and delay > read timeout. The specific CPFs are decided during the ticket 05 implementation and documented in the README.

## Messaging (RabbitMQ)
- Topic exchange `customer.events`.
  - Routing key `customer.created`. Queue `customer.created` is declared so events can be inspected.
  - Routing key `customer.status.change` → queue `customer.status-change`, with DLX `customer.dlx` → `customer.status-change.dlq`.
- JSON with the Jackson message converter. Publisher confirms are on (`correlated`), plus mandatory returns.
- **CUSTOMER_CREATED (hybrid after-commit + outbox fallback, user decision)**
  1. The service saves the customer and raises a Spring application event.
  2. `@TransactionalEventListener(AFTER_COMMIT)` publishes and waits for the confirm (timeout configurable, ~5 s).
  3. On nack, timeout, return or exception, the event is stored in `outbox_events` (new transaction).
  4. A `@Scheduled` relay (every 5 s) resends pending rows, ordered by `created_at`, sets `sent_at` on confirm, and increments `attempts` and `last_error` on failure, with backoff via `next_attempt_at`. It retries until it succeeds.
  - Payload: `{eventId, eventType:"CUSTOMER_CREATED", customerId, createdAt}`. Delivery is at-least-once, so consumers must dedupe by `eventId`.
  - Implementation (ticket 06): `CustomerService` raises `CustomerCreatedEvent`; `messaging.CustomerCreatedPublisher` handles it after commit. `ConfirmedPublisher` sends a persistent JSON message (`message_id` = `eventId`) and waits `app.messaging.confirm-timeout`; a nack, a timeout, a mandatory return (unroutable) or an AMQP exception all count as failure. The outbox row is written in a `REQUIRES_NEW` transaction with the exact payload that was attempted, so the relay resends the same `eventId`. If even that write fails, the event is logged at ERROR and lost (the same gap ADR-5 accepts).
  - `messaging.OutboxRelay` runs every `app.outbox.relay-interval` (fixed delay, first run after one interval). Each row gets its own short transaction: lock the oldest due row (`next_attempt_at <= now`) with `SKIP LOCKED`, publish it, mark it sent or failed, commit. A run repeats this for up to `app.outbox.batch-size` rows and stops early when no due row is left. So the longest transaction (and the time a DB connection and row lock are held) is about one confirm timeout, not batch-size × confirm timeout. A failed row gets a future `next_attempt_at`, so the same run does not pick it again, and one failure does not block the other rows. The wait after the n-th failure comes from the Resilience4j retry instance `outbox` in `application.yaml` (`wait-duration` 1 s × `exponential-backoff-multiplier` 2^(n-1), capped at `exponential-max-wait-duration` 15 s): the relay reads its interval function (`retryRegistry.retry("outbox").getRetryConfig().getIntervalBiFunction()`) and stores now + delay in `next_attempt_at`. Nothing sleeps in-thread. The cap plus the 5 s interval keeps delivery within 30 s of a RabbitMQ recovery (NFR-7).
  - **Failure details (AC-6)**: every failure stores `last_error` (message), `last_error_type` and `last_failed_at`. `ConfirmedPublisher` classifies it through a reason on `PublishFailedException`: `BROKER_DOWN` (connection or other AMQP exception), `CONFIRM_TIMEOUT` (no confirm in time; also an interrupted wait or a failed confirm future), `NACK`, `UNROUTABLE` (mandatory return). The after-commit fallback stores the first failure's details too, with `attempts` still 0: `attempts` counts relay failures only.
  - **Giving up (AC-6, NFR-7): a DB-side dead-letter set.** The relay reads `max-attempts` once from the same Resilience4j `outbox` instance (`OUTBOX_RETRY_MAX_ATTEMPTS`, default 5760 ≈ 24 h at the 15 s cap). Every failure type counts the same (no retry/ignore exception lists). The lock query only takes rows with `attempts < max-attempts`; the failure that brings `attempts` to the limit logs one ERROR (eventId, attempts, error type; no CPF) and the row becomes dead: `sent_at IS NULL AND attempts >= max-attempts`. There is no status column, so raising `max-attempts` later brings dead rows back into retry by itself, and a single row is replayed by resetting `attempts` to 0 and `next_attempt_at` to now. A RabbitMQ DLQ is not an option here: these are events we could not get into RabbitMQ in the first place.
  - Outbox access is JPA, like `Customer`: entity `OutboxEventEntity` (payload `String` mapped as `@JdbcTypeCode(SqlTypes.JSON)` → `jsonb`, timestamps as `Instant`) and Spring Data `OutboxEventRepository`. The lock query uses `@Lock(PESSIMISTIC_WRITE)` plus the hint `jakarta.persistence.lock.timeout = -2`, which Hibernate on PostgreSQL renders as `… order by created_at, id fetch first ? rows only for no key update of … skip locked` (checked in `OutboxRelayTransactionTest`).
  - The topology (exchange, queue, binding) lives in `messaging.MessagingTopology`, which also declares it at `ApplicationReadyEvent` (an `ApplicationListener` bean calling `AmqpAdmin.initialize()`). If RabbitMQ is down then, the app still starts and `RabbitAdmin` declares it on the next connection.
- **CUSTOMER_STATUS_CHANGE consumer**
  - Payload: `{eventId, eventType, customerId, status}`.
  - One transaction: insert into `processed_events` → if the PK already exists, ack and skip (duplicate) → else update the customer status.
  - Malformed JSON, missing fields, unknown `eventType`, invalid status or unknown customer: reject without requeue → DLQ immediately.
  - Transient errors (e.g. DB down): stateless retry with `maxAttempts=3` (first delivery + 2 retries, exponential backoff), then `RejectAndDontRequeueRecoverer` → DLQ.
  - Implementation (ticket 07): `messaging.MessagingTopology` also declares the direct exchange `customer.dlx`, the queue `customer.status-change` (args `x-dead-letter-exchange=customer.dlx`, `x-dead-letter-routing-key=customer.status-change.dlq`) bound with `customer.status.change`, and the DLQ bound to `customer.dlx`. `StatusChangeListener` takes the raw `Message`, so the body is parsed as JSON by `StatusChangeMessageParser` whatever the `content_type` (or without one). Unprocessable messages throw `InvalidStatusChangeException` (an `AmqpRejectAndDontRequeueException`). `StatusChangeProcessor` runs one transaction: `INSERT … ON CONFLICT (event_id) DO NOTHING` into `processed_events` (JdbcTemplate, `ProcessedEventStore`); 0 rows means a duplicate, acked without changes. Otherwise `CustomerService.changeStatus` updates the customer through JPA (same status = no-op, no version bump); an unknown id raises the same 404 `ResponseStatusException` as `findById`, which the processor (404 only; other statuses propagate) turns into `InvalidStatusChangeException`, which rolls back the `processed_events` row. `ON CONFLICT` (instead of catching the PK violation, which would abort the PostgreSQL transaction) also makes concurrent duplicates safe: the second insert waits for the first transaction and then inserts nothing.
  - Retry is Spring Boot's listener retry (`spring.rabbitmq.listener.simple.retry`, `max-retries` 2, 1 s × 2^n backoff up to 10 s, env `STATUS_CHANGE_RETRY_*`) with `default-requeue-rejected=false`. A `RabbitListenerRetrySettingsCustomizer` (`StatusChangeRetryConfiguration`) excludes `AmqpRejectAndDontRequeueException` from retry (there is no YAML property for this; Spring's `DefaultRetryPolicy` matches excludes against the whole cause chain, so the container's wrapper doesn't hide it). Those messages are not retried, everything else (including optimistic-lock conflicts with a concurrent API update) is.

## Errors
`@RestControllerAdvice` returns `ProblemDetail` (`application/problem+json`) with `title`, `status`, `detail`, `instance`. Validation errors add `errors: [{field, message}]`.

## Configuration
`application.yaml` holds defaults, and env vars override them (DB URL/user/password, RabbitMQ host/port/credentials, `ISSUER_URI`, `SCORE_BASE_URL`, timeouts, retry, outbox interval). There is a `test` profile for Testcontainers. The full list goes in README "Configurações".

## Environments and deployment
- **local**: `docker compose up --build` (multi-stage `Dockerfile`; app + 4 dependencies with healthchecks). Alternative: run dependencies with compose and the app with `./mvnw spring-boot:run`.
- **test**: Testcontainers (Postgres, RabbitMQ) and WireMock (in-JVM). Keycloak is not needed in tests (mock JWTs via `spring-security-test`).
- staging/prod: none (out of scope).

## Key decisions (ADR-lite)
- **ADR-1 PostgreSQL 16**: realistic relational DB with `jsonb` for the outbox. H2 is not used, to avoid dialect drift.
- **ADR-2 Flyway + `ddl-auto=validate`**: versioned, reviewable schema. Hibernate never mutates it.
- **ADR-3 Keycloak**: real OAuth2 issuer with an importable realm, so evaluators get tokens from Postman without extra code.
- **ADR-4 RestClient + Resilience4j**: synchronous code fits Spring MVC. Timeouts, retry and circuit breaker cover the "slow" and "unavailable" scenarios.
- **ADR-5 Hybrid publish (after commit, outbox only on failure)**: the user's decision. It gives low latency in the happy path and survives RabbitMQ outages. Trade-off: if the app crashes between commit and the outbox write, the event is lost. This is accepted and documented. Duplicates are possible (confirm timeout after the broker accepted), so delivery is at-least-once.
- **ADR-6 `processed_events` table for idempotency**: dedupe lives in the same transaction as the state change, so it is exact across restarts.
- **ADR-7 `name_normalized` column**: accent- and case-insensitive search that is portable and indexable, without needing the `unaccent` extension.
- **ADR-8 RFC 7807 ProblemDetail**: standard error format, native to Spring 6+.
- **ADR-9 Hard delete**: simplest semantics. Status already models inactivity.
- **ADR-10 Java 21**: allowed by the PDF ("17 or higher") and the LTS that Boot 4 targets. Requires changing `java.version` in `pom.xml` from 17 to 21 (ticket 01).
- **ADR-11 Client-supplied `version` on PUT**: the client must send the `version` it last read. A mismatch returns 409, so lost updates are detected across requests, not only within concurrent transactions.
- **ADR-12 Score is optional in health (user decision, NFR-10)**: the Score service must never cause an outage. Its state is exposed in `/actuator/health` through Resilience4j's own circuit breaker health indicator, configured only: `management.health.circuitbreakers.enabled=true`, and on the `score` instance `register-health-indicator=true`, `allow-health-indicator-to-fail=false`. With the circuit open, the breaker reports the custom status `CIRCUIT_OPEN` (half open: `CIRCUIT_HALF_OPEN`). Spring Boot's status aggregation ignores statuses that are not in `management.health.status.order` (default `DOWN, OUT_OF_SERVICE, UP, UNKNOWN`), so the overall status stays `UP` (HTTP 200) while db and rabbit are `UP`; the `circuitBreakers` component itself shows `UNKNOWN` when its only breaker is open. Verified against Resilience4j 2.4.0 and Spring Boot 4.1.2 (`AvailabilityNfrTest`). Seeing the per-breaker state needs `management.endpoint.health.show-details=always`, so the public health endpoint now also shows component details (database product, RabbitMQ version, disk space, breaker metrics). Trade-off: a little more information on a public endpoint, accepted for this MVP. RabbitMQ and the database stay critical: either one DOWN makes health 503.

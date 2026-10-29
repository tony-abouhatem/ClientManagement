# Product — ClientManagement

Source of requirements: `Teste técnico - senior.pdf` (senior Java technical test, in Portuguese). This doc restates it plus the decisions taken by the user.

## Vision
A production-grade customer-management microservice that shows senior-level engineering: clean REST API, secure by default, resilient external integration, reliable messaging, and a one-command local environment.

## Problem
Evaluators need to see how a candidate handles a realistic service: CRUD, security, an unreliable external dependency, at-least-once messaging, and containerised delivery.

## Target users / personas
- **Evaluator (primary)**: clones the repo, runs `docker compose up`, gets a token, calls the APIs from Postman/Swagger, reads the README and the tests.
- **API consumer, role USER**: read-only access to customers and scores.
- **API consumer, role ADMIN**: creates, updates and deletes customers (also reads).
- **Upstream system**: sends `CUSTOMER_STATUS_CHANGE` messages through RabbitMQ.
- **Downstream system**: consumes `CUSTOMER_CREATED` events.

## Goals and success metrics
- Every endpoint, behaviour scenario and README section listed in the PDF is implemented and documented (see "PDF coverage checklist" below).
- `docker compose up` starts the whole environment from a clean clone with no manual steps.
- `./mvnw verify` passes (Docker is required for Testcontainers).
- Each failure scenario in the PDF has at least one automated test.
- Manual checks (not automated): the JaCoCo report is generated, all compose containers start, and NFR-11 startup time is met.

## Non-goals
- No web UI and no login page. Tokens come from Keycloak through the token endpoint.
- No real Score service. WireMock stands in for it.
- No CI and no GitHub issues for now. Work is local only, and the project is delivered to a Git repo later.
- No `CUSTOMER_STATUS_CHANGED` event is emitted after the consumer updates a status.
- No customer statuses beyond `ACTIVE` and `INACTIVE`.
- No coverage threshold. JaCoCo produces a report only.
- No multi-tenancy, no audit-history tables, no caching of scores.

## MVP scope (= the whole test)
1. Customer CRUD: `POST/PUT/DELETE/GET /customers[/{id}]`.
2. Listing `GET /customers` (paginated, optional `status` and `name` filters) and `GET /customers/search?name=` (case- and accent-insensitive contains).
3. `GET /customers/{id}/score`, which calls the external `GET /scores/{cpf}` with timeouts, retry and a circuit breaker.
4. OAuth2 Resource Server with JWTs from Keycloak. USER can read. ADMIN can read and write.
5. Publishing `CUSTOMER_CREATED` after commit, with an outbox fallback when the publish fails.
6. Consuming `CUSTOMER_STATUS_CHANGE` idempotently, with retry and a DLQ.
7. Validation (including CPF check digits) and RFC 7807 errors.
8. Docker image plus `docker compose up` (app, Postgres, RabbitMQ, Keycloak, WireMock).
9. Tests in JUnit 5 with Testcontainers and WireMock.
10. A README in Portuguese with the 10 sections the PDF requires, Swagger UI, a Postman collection, and actuator health.

## Business rules
- **CPF**
  - Input may be masked (`123.456.789-09`) or raw. It is normalized to 11 digits.
  - The check digits must be valid. Sequences of one repeated digit (`11111111111`) are rejected.
  - CPF is unique. A duplicate returns **409**.
  - CPF is immutable. A PUT with a different CPF returns **422**.
- **Name**: required, not blank, max 150 characters.
- **Email**: required, valid format (`@Email`), max 254 characters, not unique.
- **Status**: `ACTIVE` | `INACTIVE`. Defaults to `ACTIVE` on create when omitted.
- **Version** is returned in every customer response and required on PUT (optimistic locking, NFR-12).
- **Not found**: every customer-not-found response is a 404 with the standard title `Not Found` and detail `No customer exists with id <id>.`.
- **Delete** is a hard delete and is idempotent: it returns 204 whether the customer existed or not (already deleted or unknown id).
- **Search** is case- and accent-insensitive: `joao` matches `João da Silva`.

## Roadmap (later, not planned)
- TBD. Candidates: CI pipeline, push to a remote Git repo, `CUSTOMER_UPDATED/DELETED` events, score caching.

## Non-functional requirements (testable targets)
| ID | Area | Target |
|----|------|--------|
| NFR-1 | Security | Every `/customers/**` request without a valid JWT returns **401** with `application/problem+json`. A USER calling POST/PUT/DELETE gets **403**. An expired token or one with a bad signature or wrong issuer gets 401. |
| NFR-2 | Security | Only `/actuator/health/**`, `/v3/api-docs/**`, `/swagger-ui/**` and `/swagger-ui.html` (the redirect to the Swagger UI) are public. |
| NFR-3 | Privacy | CPF never appears unmasked in application logs. Only the last 2 digits are visible, e.g. `*********25`. |
| NFR-4 | Resilience (Score) | Connect timeout 1 s, read timeout 2 s, at most 2 attempts with backoff, retried only on 5xx and timeouts. `GET /customers/{id}/score` responds in **≤ 5 s** even if Score never answers. |
| NFR-5 | Resilience (Score) | Status mapping: Score unavailable (connection refused, **connect timeout**, 5xx after retries, circuit open) → **503**. Only a **read timeout** (slow response) → **504**. Score 404 → **404** with a distinct problem `title`. Malformed Score response → **502**. |
| NFR-6 | Resilience (Score) | The circuit breaker opens after ≥ 50% failures over the last 10 calls. While open, calls fail fast (< 100 ms) with 503. |
| NFR-7 | Messaging | With RabbitMQ up, `CUSTOMER_CREATED` is published within **1 s** of the commit. With RabbitMQ down, customer creation still returns 201, and the event is delivered within **30 s** after RabbitMQ recovers (relay interval 5 s, configurable), for outages up to about **24 h** (`max-attempts` 5760 at the 15 s backoff cap, configurable). An event still undelivered after that stays in `outbox_events` as a dead row and needs a manual replay. |
| NFR-8 | Messaging | Delivering the same `CUSTOMER_STATUS_CHANGE` (same `eventId`) N times results in exactly **one** status change and one `processed_events` row. |
| NFR-9 | Messaging | A transient processing failure gets **3 attempts in total** (first delivery + 2 retries with backoff; Spring `maxAttempts=3`), then the message goes to the DLQ. A malformed message, an unknown `customerId` or an invalid status goes straight to the DLQ (0 retries). |
| NFR-10 | Availability | The app starts even when RabbitMQ or WireMock is down. CRUD keeps working, and `/actuator/health` reports the failing component. |
| NFR-11 | Startup | From a clean clone, `docker compose up --build` gets the app to `/actuator/health` = UP once dependencies are healthy (target ≤ 120 s on a dev laptop, after images are pulled). **Manual check**, not automated. |
| NFR-12 | Consistency | Optimistic locking: every customer response includes `version`. PUT requires `version` in the body. A missing `version` returns **400** (validation). A `version` that doesn't match the stored one returns **409**. |
| NFR-13 | API | All errors use RFC 7807 `ProblemDetail`. Validation errors include `errors[]` with `field` and `message`. |
| NFR-14 | API | Pagination (`GET /customers` and `/customers/search`): defaults `page=0`, `size=20` when omitted. `page < 0`, `size < 1` or `size > 100` returns **400** with `errors[]` naming the field (e.g. "size must be between 1 and 100"). There is no clamping. Invalid `status` returns 400. |
| NFR-15 | Performance | p95 below 300 ms for CRUD/list on a local stack with ≤ 10k customers (measured informally; no load-test tooling required). |

## Backlog (GitHub issues)
Tickets live only as GitHub issues (label `ticket`) in `tony-abouhatem/ClientManagement`. Implementation order:

| Ticket | Issue | Depends on |
|--------|-------|-----------|
| 01 Project foundation and local stack | #2 | — |
| 02 Security: OAuth2 Resource Server with roles | #3 | 01 |
| 03 Customer CRUD, validation and error handling | #4 | 01, 02 |
| 04 List, search by name, filter by status | #5 | 03 |
| 05 Score integration with resilience | #6 | 03 |
| 06 Publish CUSTOMER_CREATED (after commit, outbox fallback) | #7 | 01, 03 |
| 07 Consume CUSTOMER_STATUS_CHANGE idempotently | #8 | 01, 03 |
| 08 README finalization, OpenAPI and Postman | #9 | 01–07 |

Tickets 04–07 can run in parallel once 03 is done.

### PDF coverage checklist
- Endpoints CRUD, search, status filter → 03, 04. Score → 05.
- OAuth2/JWT, USER/ADMIN → 02.
- CUSTOMER_CREATED → 06. CUSTOMER_STATUS_CHANGE plus duplicates → 07.
- Scenarios:
  - Invalid data, duplicate CPF, not found → 03.
  - 401, 403 → 02.
  - External service down or slow → 05.
  - Component temporarily down → 01 (NFR-10), 06.
  - Message processing failure, duplicate message → 07.
- Relational DB, JPA, validation, errors, externalized config, Docker → 01, 03.
- JUnit tests → every ticket.
- README with 10 sections → 08.

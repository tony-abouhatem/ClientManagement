# CLAUDE.md — agent guide for ClientManagement

Read first: `docs/PRODUCT.md` (scope, rules, NFRs), `docs/ARCHITECTURE.md` (design, ADRs); tickets are GitHub issues (label `ticket`, see the Backlog section of PRODUCT.md).

## Workflow
- Tickets live only as GitHub issues (label `ticket`) in `tony-abouhatem/ClientManagement`; ticket NN is issue #(NN+1). Read them with `gh issue view <n>`.
- Flow: ticket issue → ticket-triage → test-writer adds pending tests → human review and commit → implementer makes them pass → human review, commit, push and PR.
- Remote: private GitHub repo `tony-abouhatem/ClientManagement` (`origin`). Default branch `main`.
- Branches: `feature/NN_slug` from `main`, where NN is the ticket number (e.g. `feature/02_security-resource-server`). Merged into `main` through pull requests with `Closes #<issue>`.
- **Agents never commit, push or open PRs** unless the user asks. The implementer leaves its changes uncommitted with a suggested commit message.
- **No CI.** The merge gate is a local `./mvnw verify` with Docker running. There are no PR checks to wait for or fix.
- Acceptance criteria and NFRs marked *(manual check)* get no automated tests; the implementer runs them by hand (Docker is available) and reports the results.

## Commands (run from repo root; on Windows use `mvnw.cmd` or `./mvnw` in Git Bash)
| Task | Command |
|------|---------|
| Install / build | `./mvnw -q -DskipTests package` |
| Compile (typecheck) | `./mvnw -q compile test-compile` |
| All tests (needs Docker for Testcontainers) | `./mvnw test` |
| Single test | `./mvnw test -Dtest=CustomerControllerTest` |
| Verify + JaCoCo report | `./mvnw verify` → `target/site/jacoco/index.html` |
| Lint / format | TBD (none configured) |
| Run app (dependencies via compose) | `docker compose up -d postgres rabbitmq keycloak wiremock` then `./mvnw spring-boot:run` |
| Full stack | `docker compose up --build` |

## Folder conventions
- `src/main/java/com/datum/clientmanagement/{customer,messaging,security,common/error,config}`
- `src/main/resources/application.yaml`: defaults with `${ENV:default}` placeholders.
- `src/main/resources/db/migration/V<n>__<desc>.sql`: Flyway, the only place schema changes happen.
- `docker/keycloak/` (realm JSON), `docker/wiremock/mappings/` and `__files/`: compose assets.
- `postman/`: Postman collection and environment.
- `docs/`: PRODUCT, ARCHITECTURE.

## Tests
- JUnit 5, AssertJ, Spring Boot Test, `spring-security-test` (`jwt()` post-processor for roles), Testcontainers (Postgres, RabbitMQ), WireMock.
- **Unit tests**: next to the package they test, e.g. `src/test/java/com/datum/clientmanagement/customer/CpfValidatorTest.java`.
- **Functional (acceptance) tests**: `src/test/java/com/datum/clientmanagement/functional/`. One class per ticket/feature.
- **Non-functional tests**: `src/test/java/com/datum/clientmanagement/nonfunctional/` (timeouts, circuit breaker, idempotency, security, startup without Rabbit). Reference the NFR ids from PRODUCT.md.
- **Test names**: the method name describes the behaviour only (`createCustomer_returns201`). Don't put AC/NFR ids or `edge` in method names. Link each test to its source in a comment above it (`// AC-1 (ticket 03)`, `// NFR-9`) and describe it in `@DisplayName`.
- **Pending convention**: test-writer creates skeletons as
  ```java
  // AC-1 (ticket 03)
  // Given … When … Then …
  @Test
  @Disabled("pending: implementer")
  @DisplayName("should create the customer and return 201")
  void createCustomer_returns201() { }
  ```
  The implementer removes `@Disabled`, writes the body, and makes it pass. Never delete a pending test without saying so.
- **Finding a ticket's pending tests**: don't search only for the exact phrase `ticket NN`. NFR classes are shared across tickets and their headers list several (`tickets 02, 03, 04, 05 (GitHub issues #3, #4, #5, #6)`). List every `pending: implementer` test and check both the file header and the test's own comment for the ticket number or issue number.
- Manual checks, not automated: the JaCoCo report is generated, all compose containers start, and NFR-11 startup time (ticket 01 AC-1/AC-2). Don't write tests for these.
- Test class names end in `Test`. Never use H2. Integration tests use the Testcontainers Postgres.

## Coding conventions
- Java 21 (ADR-10). `pom.xml` currently says 17; ticket 01 changes it.
- Code, identifiers, comments and docs/ in **English**. `README.md` in **Portuguese**.
- Constructor injection only. DTOs as `record`s. No entities in controller signatures.
- Validation with Jakarta Bean Validation on DTOs. The CPF check uses a custom `@Cpf` constraint plus a normalizer.
- Errors: throw Spring's `ResponseStatusException` with the HTTP status and a client-safe message (never the CPF), e.g. `new ResponseStatusException(HttpStatus.NOT_FOUND, "No customer exists with id 7.")`. Spring renders it as a `ProblemDetail` with the standard title ("Not Found") and the message as `detail`. The `@RestControllerAdvice` only adds `errors[]` for validation failures and a generic 500. Don't build error JSON by hand in controllers. Titles are always Spring's standard ones (an unknown customer is "Not Found" on every endpoint, including `/customers/{id}/score`). The only exception is the Score 404, titled "Score not found" (NFR-5) through `exception.getBody().setTitle(...)`.
- Config through `@ConfigurationProperties` records. No hard-coded URLs, credentials or timeouts.
- Log CPF masked only, showing the last 2 digits: `*********25` (NFR-3). Library logs can leak it too: `org.hibernate.orm.jdbc.error` is `OFF` because it prints PostgreSQL's `Key (cpf)=(...)` detail, and exceptions whose message holds a Score URL are never logged. Cover new leak paths in `PrivacyLoggingNfrTest`.
- Customer responses always include `version`. PUT requires `version`: a mismatch returns 409 and a missing value returns 400.
- Pagination params are validated (page ≥ 0, 1 ≤ size ≤ 100) and violations return 400. Never clamp.
- `ProblemDetail` for every error, including 401 and 403.

## Never do
- Never change the schema outside a new Flyway migration. Never edit an applied `V*` file. Never set `ddl-auto` to anything other than `validate`.
- Never commit, push, open PRs, or create repos/issues without the user's explicit request.
- Never weaken security rules to make tests pass (use `jwt()` with the right roles).
- Never put secrets in code. Dev-only defaults live in `application.yaml` and `docker-compose.yml`.
- Never call the real network in tests. Use WireMock and Testcontainers.
- Never discard or rewrite user-written doc text without saying so.

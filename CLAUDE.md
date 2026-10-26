# CLAUDE.md — agent guide for ClientManagement

Read first: `docs/PRODUCT.md` (scope, rules, NFRs), `docs/ARCHITECTURE.md` (design, ADRs), `docs/backlog/` (tickets).

## Workflow
- No GitHub issues and no CI. Tickets are markdown files in `docs/backlog/NN-slug.md`.
- Flow: ticket file → test-writer adds failing/pending tests → implementer makes them pass → human review and commit.
- Remote: private GitHub repo `tony-abouhatem/ClientManagement` (`origin`). Default branch `main`; work happens on feature branches (e.g. `feature/01_new-project-setup`) and is merged into `main` through pull requests. **Never commit or push** unless the user asks.

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
- `src/main/java/com/datum/clientmanagement/{customer,score,messaging,security,common/error,config}`
- `src/main/resources/application.yaml`: defaults with `${ENV:default}` placeholders.
- `src/main/resources/db/migration/V<n>__<desc>.sql`: Flyway, the only place schema changes happen.
- `docker/keycloak/` (realm JSON), `docker/wiremock/mappings/` and `__files/`: compose assets.
- `postman/`: Postman collection and environment.
- `docs/`: PRODUCT, ARCHITECTURE, backlog.

## Tests
- JUnit 5, AssertJ, Spring Boot Test, `spring-security-test` (`jwt()` post-processor for roles), Testcontainers (Postgres, RabbitMQ), WireMock.
- **Unit tests**: next to the package they test, e.g. `src/test/java/com/datum/clientmanagement/customer/CpfValidatorTest.java`.
- **Functional (acceptance) tests**: `src/test/java/com/datum/clientmanagement/functional/`. One class per ticket/feature, with methods named after the ACs (e.g. `ac1_createCustomer_returns201`).
- **Non-functional tests**: `src/test/java/com/datum/clientmanagement/nonfunctional/` (timeouts, circuit breaker, idempotency, security, startup without Rabbit). Reference the NFR ids from PRODUCT.md.
- **Pending convention**: test-writer creates skeletons as
  ```java
  @Test
  @Disabled("pending: implementer")
  void ac1_createCustomer_returns201() { }
  ```
  The implementer removes `@Disabled`, writes the body, and makes it pass. Never delete a pending test without saying so.
- Manual checks, not automated: the JaCoCo report is generated, all compose containers start, and NFR-11 startup time (ticket 01 AC-1/AC-2). Don't write tests for these.
- Test class names end in `Test`. Never use H2. Integration tests use the Testcontainers Postgres.

## Coding conventions
- Java 21 (ADR-10). `pom.xml` currently says 17; ticket 01 changes it.
- Code, identifiers, comments and docs/ in **English**. `README.md` in **Portuguese**.
- Constructor injection only. DTOs as `record`s. No entities in controller signatures.
- Validation with Jakarta Bean Validation on DTOs. The CPF check uses a custom `@Cpf` constraint plus a normalizer.
- Errors: throw domain exceptions (`CustomerNotFoundException`, `DuplicateCpfException`, …) and map them to `ProblemDetail` in the `@RestControllerAdvice`. Don't build error JSON by hand in controllers.
- Config through `@ConfigurationProperties` records. No hard-coded URLs, credentials or timeouts.
- Log CPF masked only, showing the last 2 digits: `*********25` (NFR-3).
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

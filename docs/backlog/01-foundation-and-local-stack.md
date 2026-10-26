# 01 — Project foundation and local stack

## Goal
Get a buildable Java 21 app with all required dependencies and externalized config, plus a `docker compose up` that starts the app with Postgres, RabbitMQ, Keycloak and WireMock.

## Acceptance criteria
- **AC-1** *(manual check)*: Given the repo, when `./mvnw verify` runs with Docker available, then the build succeeds on Java 21 (`java.version`=21), the context-load test passes against a Testcontainers Postgres, and a JaCoCo report is generated at `target/site/jacoco/index.html`.
- **AC-2** *(manual check)*: Given a clean clone, when `docker compose up --build` runs, then the containers `app`, `postgres`, `rabbitmq`, `keycloak` and `wiremock` start, each dependency has a healthcheck, and `app` starts only after its dependencies are healthy.
- **AC-3**: Given the stack is up, when `GET /actuator/health` is called without a token, then it returns 200 with `status=UP` and includes `db` and `rabbit` components.
- **AC-4**: Given the app starts, then Flyway runs `V1__init.sql`, which creates `customers`, `outbox_events` and `processed_events` per ARCHITECTURE.md, and Hibernate runs with `ddl-auto=validate`.
- **AC-6**: Given Keycloak starts, then realm `client-management` is imported, with realm roles `USER`/`ADMIN`, users `user/user` (USER) and `admin/admin` (USER, ADMIN), and a public client with direct-access grants enabled.

## Inputs / outputs
- Output files: `pom.xml` (dependencies: web, validation, oauth2-resource-server, actuator, postgresql, flyway-core + flyway-database-postgresql, resilience4j-spring-boot, springdoc-openapi-starter-webmvc-ui, testcontainers postgresql/rabbitmq/junit-jupiter, wiremock, jacoco-maven-plugin), `Dockerfile` (multi-stage), `docker-compose.yml`, `docker/keycloak/*.json`, `docker/wiremock/mappings/`, `src/main/resources/application.yaml`, `src/main/resources/db/migration/V1__init.sql`, and a `src/test/resources` test profile.

## Edge cases
- Keycloak issuer hostname: a token fetched from the host (`localhost:8081`) must validate inside compose. Set `KC_HOSTNAME` (or equivalent) and document the approach.
- If RabbitMQ is down at startup, the app still starts (NFR-10). Health shows `rabbit` DOWN.
- Windows line endings in `mvnw` inside the Docker build stage.

## NFRs
- NFR-11 *(manual check)*: from a clean clone, health is UP within ≤ 120 s after images are pulled.
- Automated tests are only expected for AC-3, AC-4 and AC-6. AC-1, AC-2 and NFR-11 are verified by hand.

## Out of scope
- Business endpoints, security rules (ticket 02), CI.

## Dependencies
- None.

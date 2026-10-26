# 08 — README finalization, OpenAPI and Postman

## Goal
Deliverable documentation: a Portuguese README with the 10 sections the PDF requires (all TBDs resolved), Swagger UI, and a Postman collection that gets tokens automatically.

## Acceptance criteria
- **AC-1**: Given the README, then it contains these sections with content verified against the running stack: arquitetura, requisitos para execução, como iniciar a aplicação, como iniciar as dependências, como obter/usar o Access Token, como executar os testes, endpoints, configurações, exemplos de uso das APIs, funcionamento da integração com RabbitMQ. No `TBD` remains.
- **AC-2**: Given the app running, when `/swagger-ui.html` is opened, then all endpoints are listed with request/response schemas, error codes and a bearer-JWT security scheme. `/v3/api-docs` is public.
- **AC-3**: Given `postman/ClientManagement.postman_collection.json` and an environment file, when the collection is imported and run, then it fetches USER and ADMIN tokens from Keycloak and exercises every endpoint, including 401/403/404/409/422 and the score scenarios.
- **AC-4**: Given the README "Configurações" table, then every env var read by `application.yaml` is listed with its default.
- **AC-5**: Given the README, then the WireMock scenario CPFs and the steps for publishing a `CUSTOMER_STATUS_CHANGE` from the RabbitMQ UI are documented.

## Inputs / outputs
- Output: `README.md`, `postman/*.json`, OpenAPI annotations/config.

## Edge cases
- The README commands must work on Windows (Git Bash / `mvnw.cmd`) and Linux/macOS.

## NFRs
- NFR-16.

## Out of scope
- An English README, a hosted docs site.

## Dependencies
- 01–07.

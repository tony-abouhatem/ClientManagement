# Backlog (MVP) — implementation order

| # | Ticket | Depends on |
|---|--------|-----------|
| 01 | [Project foundation and local stack](01-foundation-and-local-stack.md) | — |
| 02 | [Security: OAuth2 Resource Server with roles](02-security-resource-server.md) | 01 |
| 03 | [Customer CRUD, validation and error handling](03-customer-crud-validation-errors.md) | 01, 02 |
| 04 | [List, search by name, filter by status](04-list-search-filter.md) | 03 |
| 05 | [Score integration with resilience](05-score-integration.md) | 03 |
| 06 | [Publish CUSTOMER_CREATED (after commit, outbox fallback)](06-customer-created-event-outbox.md) | 01, 03 |
| 07 | [Consume CUSTOMER_STATUS_CHANGE idempotently](07-status-change-consumer.md) | 01, 03 |
| 08 | [README finalization, OpenAPI and Postman](08-docs-openapi-postman.md) | 01–07 |

Tickets 04–07 can run in parallel once 03 is done.

## PDF coverage checklist
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

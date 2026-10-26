# 05 — Score integration with resilience

## Goal
`GET /customers/{id}/score` fetches the customer's score from the external `GET /scores/{cpf}` and handles every failure mode explicitly.

## Acceptance criteria
- **AC-1**: Given an existing customer and Score returning 200 `{cpf,score,classification}`, when `GET /customers/{id}/score` is called (role USER or ADMIN), then the response is 200 `{customerId,cpf,score,classification}`.
- **AC-2**: Given a nonexistent customer id, then the response is 404 (title `Customer not found`, same as ticket 03) and Score is **not** called.
- **AC-3**: Given Score returns 404, then the response is 404 with a distinct title ("Score not found").
- **AC-4**: Given Score returns 5xx on the first attempt and 200 on the second, then the response is 200 (retry). Given 5xx on both attempts, then the response is 503.
- **AC-5**: Given Score delays longer than the read timeout (2 s), then the response is 504 within ≤ 5 s total.
- **AC-6**: Given Score is unreachable (connection refused **or connect timeout**), then the response is 503. Only a read timeout (AC-5) produces 504.
- **AC-7**: Given Score returns 200 with a malformed body or one missing `score`, then the response is 502.
- **AC-8**: Given Score returns 4xx other than 404, then the response is 502 with no retry.
- **AC-9**: Given the circuit is open after repeated failures, then subsequent calls return 503 in < 100 ms without hitting WireMock.
- **AC-10**: Given the compose WireMock, then stubs exist for OK, 404, 500 and slow responses, keyed by CPFs that are chosen during this ticket's implementation and then listed in the README.

## Inputs / outputs
- Config: `SCORE_BASE_URL`, connect/read timeouts, retry attempts and backoff, circuit-breaker parameters.
- Output: `ScoreResponse(customerId, cpf, score, classification)`.

## Edge cases
- Retries apply only to idempotent GETs on 5xx, IO errors and timeouts. 4xx responses are never retried.
- `classification` is passed through as a string, so an unknown value still returns 200.
- The CPF in the outbound URL is the normalized 11 digits and is not logged unmasked.

## NFRs
- NFR-4, NFR-5, NFR-6, NFR-3.

## Out of scope
- Caching scores, a fallback score value, a real Score service.

## Dependencies
- 03 (customer lookup). Ticket 01 provides the WireMock container.

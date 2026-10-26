# 02 — Security: OAuth2 Resource Server with roles

## Goal
Protect the API with JWTs issued by Keycloak. USER can read and ADMIN can read and write. 401 and 403 responses are ProblemDetail.

## Acceptance criteria
- **AC-1**: Given no `Authorization` header, when any `/customers/**` endpoint is called, then the response is 401 with `Content-Type: application/problem+json`.
- **AC-2**: Given a JWT that is expired, has an invalid signature, or comes from the wrong issuer, when `/customers` is called, then the response is 401.
- **AC-3**: Given a JWT with realm role `USER` only, when `POST`, `PUT` or `DELETE /customers/**` is called, then the response is 403 (ProblemDetail).
- **AC-4**: Given a JWT with role `USER`, when `GET /customers/**` is called, then authorization passes (the response is not 401/403).
- **AC-5**: Given a JWT with role `ADMIN`, when any `GET`/`POST`/`PUT`/`DELETE /customers/**` is called, then authorization passes.
- **AC-6**: Given no token, when `/actuator/health`, `/v3/api-docs` or `/swagger-ui/**` is called, then access is allowed.
- **AC-7**: Given a Keycloak token with `realm_access.roles=["ADMIN"]`, then the authenticated principal has the authority `ROLE_ADMIN`. This is verified with a unit test on the converter.

## Inputs / outputs
- Input: `Authorization: Bearer <jwt>`. Config `ISSUER_URI`.
- Output: `SecurityFilterChain` (stateless, CSRF disabled), a `JwtAuthenticationConverter` for Keycloak realm roles, a ProblemDetail `AuthenticationEntryPoint` and an `AccessDeniedHandler`.

## Edge cases
- A token with no `realm_access` claim is authenticated but has no roles, so `/customers` returns 403.
- Role name casing: only exact `USER`/`ADMIN` grant access.
- Unknown paths with no token return 401, not 404.

## NFRs
- NFR-1, NFR-2.

## Out of scope
- Login UI, refresh-token handling, method-level ownership rules.

## Dependencies
- 01.

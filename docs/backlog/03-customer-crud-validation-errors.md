# 03 — Customer CRUD, validation and error handling

## Goal
Implement `POST`, `PUT` and `DELETE /customers/{id}` and `GET /customers/{id}`, with business validation (CPF) and RFC 7807 error responses.

## Acceptance criteria
- **AC-1**: Given an ADMIN and a valid body `{name, cpf, email}`, when `POST /customers` is called, then the response is 201 with a `Location: /customers/{id}` header and a body `{id,name,cpf,email,status,version,createdAt,updatedAt}`. `status` defaults to `ACTIVE` and `version` starts at 0. Every customer response (GET, PUT, list) includes `version`.
- **AC-2**: Given CPF `529.982.247-25`, when the customer is created, then it is stored and returned as `52998224725`.
- **AC-3**: Given a CPF with invalid check digits, a repeated-digit sequence, or a length other than 11 digits, when POST is called, then the response is 400 and `errors[]` contains `field=cpf`.
- **AC-4**: Given a blank name, a name over 150 characters, an invalid email, a missing email, or an invalid `status` value, when POST/PUT is called, then the response is 400 with one `errors[]` entry per invalid field.
- **AC-5**: Given an existing customer with a CPF, when another POST uses the same CPF (masked or raw), then the response is 409. This also holds under a concurrent race, because the DB unique constraint is mapped to 409.
- **AC-6**: Given an existing customer, when an ADMIN calls `PUT /customers/{id}` with a valid `{name, email, status, version}`, where `version` equals the stored one and CPF is omitted or unchanged, then the response is 200 with the updated fields, `version` incremented by 1, and `updatedAt` advanced.
- **AC-7**: Given an existing customer, when PUT sends a different CPF, then the response is 422 and nothing changes.
- **AC-8**: Given a nonexistent id, when GET, PUT or DELETE is called, then the response is 404 (ProblemDetail) with title `Customer not found`. The same title is used for every customer-not-found response, including ticket 05.
- **AC-9**: Given an existing customer, when DELETE is called, then the response is 204 and a subsequent GET returns 404.
- **AC-10**: Given malformed JSON or a non-numeric id, then the response is 400 (ProblemDetail, no stack trace).
- **AC-11**: Given a stored customer at `version` N, when PUT sends a `version` other than N (e.g. a second client reusing a stale value after another update), then the response is 409 (ProblemDetail) and nothing changes. Two concurrent PUTs with the same `version` result in exactly one 200 and one 409.
- **AC-12**: Given a PUT body without `version`, then the response is 400 and `errors[]` contains `field=version`.

## Inputs / outputs
- Request DTOs: `CreateCustomerRequest(name, cpf, email, status?)` and `UpdateCustomerRequest(name, email, status, version [required], cpf?)`. Response: `CustomerResponse(id, name, cpf, email, status, version, createdAt, updatedAt)`.
- Errors: `ProblemDetail` `{type,title,status,detail,instance[,errors[{field,message}]]}`.

## Edge cases
- CPF with surrounding spaces or mixed punctuation is normalized before validation.
- Email is not unique.
- Name is trimmed, and `name_normalized` is filled for ticket 04.
- An unexpected exception returns 500 with a generic detail. Internals are not leaked.

## NFRs
- NFR-3 (CPF masked in logs, last 2 digits only: `*********25`), NFR-12, NFR-13, NFR-15.

## Out of scope
- List and search (04), events (06).

## Dependencies
- 01, 02.

# 04 — List, search by name, filter by status

## Goal
`GET /customers` is paginated with optional `status` and `name` filters. `GET /customers/search?name=` does a case- and accent-insensitive contains search.

## Acceptance criteria
- **AC-1**: Given 25 customers, when `GET /customers` is called with no parameters, then the response is 200 with 20 items and `page={size:20,number:0,totalElements:25,totalPages:2}`.
- **AC-2**: Given customers with both statuses, when `GET /customers?status=ACTIVE` is called, then only ACTIVE customers are returned.
- **AC-3**: Given `GET /customers?status=FOO`, then the response is 400 (ProblemDetail).
- **AC-4**: Given a customer "João da Silva", when `GET /customers/search?name=joao` (also `JOÃO`, `silva`, `ão d`) is called, then that customer is returned.
- **AC-5**: Given `GET /customers/search` with a missing or blank `name`, then the response is 400.
- **AC-6**: Given `GET /customers?name=joao&status=INACTIVE`, then both filters apply (AND).
- **AC-7**: Given `page=-1`, `size=0` or `size=101` on `GET /customers` or `/customers/search`, then the response is 400 (ProblemDetail) with `errors[]` naming the field (e.g. `field=size`, "size must be between 1 and 100"). There is no clamping. `size=100` is accepted. When `page`/`size` are omitted, `page=0` and `size=20` apply.
- **AC-8**: Given `sort=name,asc`, then results are ordered by name. The default sort is `id,asc`.
- **AC-9**: Given no matches, then the response is 200 with empty `content`.

## Inputs / outputs
- Query params: `status`, `name`, `page`, `size`, `sort`. Output: `PagedModel<CustomerResponse>`.

## Edge cases
- `name` with SQL wildcard characters (`%`, `_`) is matched literally.
- Unicode normalization (NFD) strips accents for both stored and query values.
- Sorting by an unknown property returns 400.

## NFRs
- NFR-14, NFR-15 (uses the `name_normalized` index).

## Out of scope
- Full-text or fuzzy search, filtering by CPF or email.

## Dependencies
- 03.

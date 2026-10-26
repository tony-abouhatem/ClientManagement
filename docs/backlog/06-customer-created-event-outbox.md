# 06 — Publish CUSTOMER_CREATED (after commit, outbox fallback)

## Goal
After a customer is created, publish `CUSTOMER_CREATED` to RabbitMQ. If the publish fails, persist the event to `outbox_events`, and a scheduled relay retries until it succeeds (hybrid design, ADR-5).

## Acceptance criteria
- **AC-1**: Given RabbitMQ is up, when `POST /customers` succeeds, then within 1 s exactly one message is on queue `customer.created` (exchange `customer.events`, routing key `customer.created`) with JSON `{eventId (UUID), eventType:"CUSTOMER_CREATED", customerId, createdAt (ISO-8601 UTC)}` and `content_type=application/json`.
- **AC-2**: Given the POST fails (validation, duplicate CPF, or a rollback), then no message is published and no outbox row is written.
- **AC-3**: Given the publish happens only after the DB transaction commits, then a consumer that receives the event can always `GET` the customer.
- **AC-4**: Given RabbitMQ is down, or a publish gets a nack or confirm timeout, when POST is called, then the response is still 201 and one `outbox_events` row exists with `sent_at=null` and the same `eventId`.
- **AC-5**: Given pending outbox rows and RabbitMQ back up, when the relay runs (every 5 s), then each row is published with its original `eventId` and marked `sent_at`. This happens within 30 s of recovery.
- **AC-6**: Given a relay publish failure, then `attempts` increments, `last_error` is set, and `next_attempt_at` is pushed out with backoff. The row stays pending.
- **AC-7**: Given multiple app instances or overlapping relay runs, then a row is not sent concurrently twice (e.g. `SELECT … FOR UPDATE SKIP LOCKED`).
- **AC-8**: Given the topology, then the app declares exchange `customer.events` and queue `customer.created` at startup (durable).

## Inputs / outputs
- Input: committed customer creation. Output: AMQP message, `outbox_events` rows.
- Config: confirm timeout, relay interval (`OUTBOX_RELAY_INTERVAL`, default 5 s), batch size.

## Edge cases
- A confirm timeout after the broker actually accepted the message produces a duplicate on relay. This is accepted (at-least-once; consumers dedupe by `eventId`).
- An app crash between commit and the outbox write loses the event. This is accepted per ADR-5 and documented in the README.
- An unroutable message (mandatory return) is treated as a failure, so it goes to the outbox.

## NFRs
- NFR-7, NFR-10.

## Out of scope
- Events for update or delete, purging sent outbox rows (TBD).

## Dependencies
- 01, 03.

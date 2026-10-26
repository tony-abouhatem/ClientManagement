# 07 — Consume CUSTOMER_STATUS_CHANGE idempotently

## Goal
Consume status-change requests from RabbitMQ and apply each `eventId` exactly once. Bad messages and exhausted retries go to the DLQ.

## Acceptance criteria
- **AC-1**: Given an ACTIVE customer 123, when `{eventId, eventType:"CUSTOMER_STATUS_CHANGE", customerId:123, status:"INACTIVE"}` is published to `customer.events` with routing key `customer.status.change`, then the customer becomes INACTIVE and `processed_events` has that `eventId`.
- **AC-2**: Given the same message delivered 3 times, then the status change is applied once, `processed_events` has one row, and every delivery is acked. Nothing goes to the DLQ.
- **AC-3**: Given a duplicate `eventId` arrives after a newer message changed the status again, then the old message does **not** revert the status.
- **AC-4**: Given malformed JSON, missing `eventId`/`customerId`/`status`, an invalid status, or `eventType` other than `CUSTOMER_STATUS_CHANGE`, then the message goes to `customer.status-change.dlq` without retry.
- **AC-5**: Given an unknown `customerId`, then the message goes straight to the DLQ and no `processed_events` row is written.
- **AC-6**: Given a transient failure (e.g. a DB exception), then processing gets **3 attempts in total** (first delivery + 2 retries with backoff; Spring `maxAttempts=3`) and is then sent to the DLQ. If an attempt succeeds, the change is applied once. Poison messages (AC-4, AC-5) get 0 retries.
- **AC-7**: Given the status already equals the requested one, then the message is recorded as processed and acked (no-op).
- **AC-8**: Given startup, then queue `customer.status-change` (with DLX `customer.dlx`) and `customer.status-change.dlq` are declared and bound.

## Inputs / outputs
- Input: AMQP JSON message. Output: customer status update and a `processed_events` row in the same transaction.

## Edge cases
- Two duplicate deliveries processed concurrently: the PK on `processed_events.event_id` makes exactly one win, and the other acks as a duplicate.
- An optimistic-lock conflict with a concurrent API update is treated as transient (retry).
- Missing `content_type` header: still parsed as JSON.

## NFRs
- NFR-8, NFR-9.

## Out of scope
- Emitting `CUSTOMER_STATUS_CHANGED`, DLQ reprocessing tooling, ordering guarantees across different eventIds.

## Dependencies
- 01, 03.

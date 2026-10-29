-- Ticket 06 AC-6: when and why the last publish of an outbox event failed.
-- last_error_type is one of BROKER_DOWN, CONFIRM_TIMEOUT, NACK, UNROUTABLE.
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS last_failed_at TIMESTAMPTZ;
ALTER TABLE outbox_events ADD COLUMN IF NOT EXISTS last_error_type VARCHAR(20);

CREATE TABLE customers (
    id              BIGSERIAL    PRIMARY KEY,
    name            VARCHAR(150) NOT NULL,
    -- Lowercased, accent-stripped copy of name for portable case/accent-insensitive search (ADR-7).
    name_normalized VARCHAR(150) NOT NULL,
    cpf             CHAR(11)     NOT NULL,
    email           VARCHAR(254) NOT NULL,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL,
    updated_at      TIMESTAMPTZ  NOT NULL,
    version         BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uk_customers_cpf UNIQUE (cpf),
    CONSTRAINT ck_customers_status CHECK (status IN ('ACTIVE', 'INACTIVE'))
);

CREATE INDEX idx_customers_name_normalized ON customers (name_normalized);
CREATE INDEX idx_customers_status ON customers (status);

CREATE TABLE outbox_events (
    id              BIGSERIAL   PRIMARY KEY,
    event_id        UUID        NOT NULL,
    event_type      VARCHAR(50) NOT NULL,
    aggregate_id    BIGINT      NOT NULL,
    payload         JSONB       NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    attempts        INTEGER     NOT NULL DEFAULT 0,
    last_error      TEXT,
    next_attempt_at TIMESTAMPTZ,
    -- NULL means the event is still pending delivery.
    sent_at         TIMESTAMPTZ,
    CONSTRAINT uk_outbox_events_event_id UNIQUE (event_id)
);

-- The relay only scans pending rows, oldest first.
CREATE INDEX idx_outbox_events_pending ON outbox_events (created_at) WHERE sent_at IS NULL;

CREATE TABLE processed_events (
    event_id     UUID        PRIMARY KEY,
    event_type   VARCHAR(50) NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

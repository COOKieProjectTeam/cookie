CREATE TABLE outbox_events (
    event_id uuid PRIMARY KEY,
    event_type varchar(160) NOT NULL,
    event_version integer NOT NULL CHECK (event_version > 0),
    aggregate_type varchar(100) NOT NULL,
    aggregate_id varchar(255) NOT NULL,
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL,
    available_at timestamptz NOT NULL,
    claimed_until timestamptz,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error varchar(1000)
);

CREATE INDEX idx_outbox_events_pending
    ON outbox_events(available_at, occurred_at)
    WHERE published_at IS NULL;

CREATE TABLE inbox_events (
    consumer varchar(160) NOT NULL,
    event_id uuid NOT NULL,
    event_type varchar(160) NOT NULL,
    processed_at timestamptz NOT NULL,
    PRIMARY KEY (consumer, event_id)
);

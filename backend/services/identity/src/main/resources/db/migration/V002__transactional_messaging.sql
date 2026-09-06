CREATE TABLE outbox_events (
    event_id uuid PRIMARY KEY,
    event_type varchar(160) NOT NULL CHECK (char_length(event_type) > 0),
    event_version integer NOT NULL CHECK (event_version > 0),
    aggregate_type varchar(100) NOT NULL CHECK (char_length(aggregate_type) > 0),
    aggregate_id varchar(255) NOT NULL CHECK (char_length(aggregate_id) > 0),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    occurred_at timestamptz NOT NULL,
    available_at timestamptz NOT NULL,
    correlation_id uuid,
    causation_id uuid,
    trace_id varchar(128),
    claim_id uuid,
    claimed_until timestamptz,
    published_at timestamptz,
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    last_error varchar(1000),
    CONSTRAINT ck_outbox_claim_lease CHECK (
        (claim_id IS NULL) = (claimed_until IS NULL)
    ),
    CONSTRAINT ck_outbox_published_state CHECK (
        published_at IS NULL OR (claim_id IS NULL AND claimed_until IS NULL)
    ),
    CONSTRAINT ck_outbox_published_time CHECK (
        published_at IS NULL OR published_at >= occurred_at
    )
);

CREATE INDEX idx_outbox_events_pending
    ON outbox_events(available_at, occurred_at)
    WHERE published_at IS NULL;

CREATE INDEX idx_outbox_events_expired_claims
    ON outbox_events(claimed_until)
    WHERE published_at IS NULL AND claimed_until IS NOT NULL;

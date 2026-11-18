CREATE TABLE account_tombstones (
    user_id uuid PRIMARY KEY,
    deletion_request_id uuid NOT NULL,
    deleted_at timestamptz NOT NULL
);
CREATE TABLE inbox_events (
    consumer_name text NOT NULL,
    event_id uuid NOT NULL,
    event_type text NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer_name, event_id)
);
CREATE TABLE message_quarantine (
    message_hash text PRIMARY KEY,
    subject text NOT NULL,
    reason text NOT NULL,
    stream_sequence bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

REVOKE CREATE ON SCHEMA public FROM PUBLIC;
DO $grant$
BEGIN
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='health_app') THEN
        REVOKE ALL ON ALL TABLES IN SCHEMA public FROM health_app;
        GRANT USAGE ON SCHEMA public TO health_app;
        GRANT SELECT, INSERT ON account_tombstones TO health_app;
        GRANT INSERT ON inbox_events, message_quarantine TO health_app;
        -- PostgreSQL ON CONFLICT reads the respective conflict columns.
        GRANT SELECT(consumer_name,event_id) ON inbox_events TO health_app;
        GRANT SELECT(message_hash) ON message_quarantine TO health_app;
    END IF;
END
$grant$;

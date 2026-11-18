CREATE TABLE connections (
    user_id uuid NOT NULL,
    id uuid NOT NULL,
    platform text NOT NULL CHECK (platform IN ('APPLE_HEALTH','HEALTH_CONNECT')),
    timezone text NOT NULL,
    status text NOT NULL CHECK (status IN ('ACTIVE','DISCONNECTED','PURGING','PURGED')),
    generation bigint NOT NULL DEFAULT 1 CHECK (generation > 0),
    version bigint NOT NULL DEFAULT 1 CHECK (version > 0),
    next_seq bigint NOT NULL DEFAULT 1 CHECK (next_seq > 0),
    initial_from date NOT NULL,
    initial_to date NOT NULL CHECK (initial_to >= initial_from),
    PRIMARY KEY (user_id,id)
);
CREATE TABLE primary_connection_periods (
    user_id uuid NOT NULL,
    effective_from date NOT NULL,
    timezone text NOT NULL,
    effective_at timestamptz NOT NULL,
    connection_id uuid NOT NULL,
    revision bigint NOT NULL CHECK (revision > 0),
    PRIMARY KEY (user_id,revision),
    FOREIGN KEY (user_id,connection_id) REFERENCES connections(user_id,id)
);
CREATE INDEX primary_connection_date ON primary_connection_periods (user_id,effective_from DESC,revision DESC);
CREATE TABLE activity_source_days (
    user_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    local_date date NOT NULL,
    timezone text NOT NULL,
    steps integer CHECK (steps >= 0),
    active_kcal numeric(12,3) CHECK (active_kcal >= 0),
    deleted boolean NOT NULL DEFAULT false,
    CHECK (NOT deleted OR (steps IS NULL AND active_kcal IS NULL)),
    PRIMARY KEY (user_id,connection_id,local_date),
    FOREIGN KEY (user_id,connection_id) REFERENCES connections(user_id,id)
);
CREATE TABLE activity_day_versions (
    user_id uuid NOT NULL,
    local_date date NOT NULL,
    connection_id uuid,
    timezone text NOT NULL,
    version bigint NOT NULL DEFAULT 0 CHECK (version >= 0),
    PRIMARY KEY (user_id,local_date),
    FOREIGN KEY (user_id,connection_id) REFERENCES connections(user_id,id)
);
CREATE TABLE weight_measurements (
    user_id uuid NOT NULL,
    id uuid NOT NULL,
    source text NOT NULL CHECK (source IN ('MANUAL','APPLE_HEALTH','HEALTH_CONNECT')),
    connection_id uuid,
    external_id text CHECK (length(external_id) BETWEEN 1 AND 200),
    first_weight boolean NOT NULL DEFAULT false,
    version bigint NOT NULL CHECK (version > 0),
    value_kg numeric(5,2) CHECK (value_kg > 0),
    measured_at timestamptz,
    deleted boolean NOT NULL DEFAULT false,
    PRIMARY KEY (user_id,id),
    UNIQUE (user_id,connection_id,external_id),
    FOREIGN KEY (user_id,connection_id) REFERENCES connections(user_id,id),
    CHECK ((source='MANUAL' AND connection_id IS NULL AND external_id IS NULL)
        OR (source<>'MANUAL' AND connection_id IS NOT NULL AND external_id IS NOT NULL AND NOT first_weight)),
    CHECK ((deleted AND value_kg IS NULL AND measured_at IS NULL)
        OR (NOT deleted AND value_kg IS NOT NULL AND measured_at IS NOT NULL))
);
CREATE UNIQUE INDEX weight_one_first ON weight_measurements (user_id) WHERE first_weight;
CREATE INDEX weight_page ON weight_measurements (user_id,measured_at DESC,id DESC) WHERE NOT deleted;
CREATE TABLE import_batches (
    user_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    generation bigint NOT NULL,
    seq bigint NOT NULL,
    idempotency_key uuid NOT NULL,
    fingerprint text NOT NULL,
    response jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,connection_id,generation,seq),
    UNIQUE (user_id,connection_id,idempotency_key),
    FOREIGN KEY (user_id,connection_id) REFERENCES connections(user_id,id)
);
CREATE TABLE command_receipts (
    user_id uuid NOT NULL,
    operation text NOT NULL,
    key uuid NOT NULL,
    fingerprint text NOT NULL,
    response jsonb NOT NULL,
    PRIMARY KEY (user_id,operation,key)
);
CREATE TABLE purge_jobs (
    user_id uuid NOT NULL,
    connection_id uuid NOT NULL,
    id uuid NOT NULL,
    completed boolean NOT NULL DEFAULT false,
    retry_at timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (user_id,id),
    UNIQUE (user_id,connection_id),
    FOREIGN KEY (user_id,connection_id) REFERENCES connections(user_id,id)
);
CREATE TABLE outbox_events (
    event_id uuid PRIMARY KEY,
    user_id uuid NOT NULL,
    event_type text NOT NULL,
    connection_id uuid,
    event_version integer NOT NULL CHECK (event_version > 0),
    aggregate_type text NOT NULL,
    aggregate_id text NOT NULL,
    aggregate_version bigint NOT NULL CHECK (aggregate_version > 0),
    payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    claim_id uuid,
    claimed_until timestamptz,
    attempt_count integer NOT NULL DEFAULT 0,
    published_at timestamptz,
    last_error text
);
CREATE INDEX health_outbox_pending ON outbox_events(available_at,event_id) WHERE published_at IS NULL;

ALTER TABLE outbox_events ENABLE ROW LEVEL SECURITY;
ALTER TABLE command_receipts ENABLE ROW LEVEL SECURITY;
ALTER TABLE primary_connection_periods ENABLE ROW LEVEL SECURITY;
ALTER TABLE import_batches ENABLE ROW LEVEL SECURITY;
CREATE POLICY health_primary_read ON primary_connection_periods FOR SELECT USING (true);
CREATE POLICY health_primary_insert ON primary_connection_periods FOR INSERT WITH CHECK (true);
CREATE POLICY health_primary_erase ON primary_connection_periods FOR DELETE USING
    (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=primary_connection_periods.user_id));
CREATE POLICY health_batches_read ON import_batches FOR SELECT USING (true);
CREATE POLICY health_batches_insert ON import_batches FOR INSERT WITH CHECK (true);
CREATE POLICY health_batches_scrub ON import_batches FOR UPDATE USING (created_at < now()-interval '30 days')
    WITH CHECK (response IS NULL);
CREATE POLICY health_batches_erase ON import_batches FOR DELETE USING
    (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=import_batches.user_id)
     OR EXISTS (SELECT 1 FROM connections c WHERE c.user_id=import_batches.user_id
         AND c.id=import_batches.connection_id AND c.status='PURGING'));

ALTER TABLE activity_day_versions ENABLE ROW LEVEL SECURITY;
CREATE POLICY health_activity_day_versions_read ON activity_day_versions FOR SELECT USING (true);
CREATE POLICY health_activity_day_versions_insert ON activity_day_versions FOR INSERT WITH CHECK (true);
CREATE POLICY health_activity_day_versions_update ON activity_day_versions FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY health_activity_day_versions_erase ON activity_day_versions FOR DELETE USING (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=activity_day_versions.user_id));

ALTER TABLE connections ENABLE ROW LEVEL SECURITY;
CREATE POLICY health_connections_read ON connections FOR SELECT USING (true);
CREATE POLICY health_connections_insert ON connections FOR INSERT WITH CHECK (true);
CREATE POLICY health_connections_update ON connections FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY health_connections_erase ON connections FOR DELETE USING (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=connections.user_id));

ALTER TABLE purge_jobs ENABLE ROW LEVEL SECURITY;
CREATE POLICY health_purge_jobs_read ON purge_jobs FOR SELECT USING (true);
CREATE POLICY health_purge_jobs_insert ON purge_jobs FOR INSERT WITH CHECK (true);
CREATE POLICY health_purge_jobs_update ON purge_jobs FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY health_purge_jobs_erase ON purge_jobs FOR DELETE USING (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=purge_jobs.user_id));

ALTER TABLE weight_measurements ENABLE ROW LEVEL SECURITY;
CREATE POLICY health_weight_measurements_read ON weight_measurements FOR SELECT USING (true);
CREATE POLICY health_weight_measurements_insert ON weight_measurements FOR INSERT WITH CHECK (true);
CREATE POLICY health_weight_measurements_update ON weight_measurements FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY health_weight_measurements_erase ON weight_measurements FOR DELETE USING (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=weight_measurements.user_id) OR EXISTS (SELECT 1 FROM connections c WHERE c.user_id=weight_measurements.user_id AND c.id=weight_measurements.connection_id AND c.status='PURGING'));

ALTER TABLE activity_source_days ENABLE ROW LEVEL SECURITY;
CREATE POLICY health_activity_source_days_read ON activity_source_days FOR SELECT USING (true);
CREATE POLICY health_activity_source_days_insert ON activity_source_days FOR INSERT WITH CHECK (true);
CREATE POLICY health_activity_source_days_update ON activity_source_days FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY health_activity_source_days_erase ON activity_source_days FOR DELETE USING (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=activity_source_days.user_id) OR EXISTS (SELECT 1 FROM connections c WHERE c.user_id=activity_source_days.user_id AND c.id=activity_source_days.connection_id AND c.status='PURGING'));

CREATE FUNCTION health_apply_runtime_grants() RETURNS void LANGUAGE plpgsql AS $grant$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='health_app') THEN RETURN; END IF;
    REVOKE ALL ON ALL TABLES IN SCHEMA public FROM health_app;
    REVOKE CREATE ON SCHEMA public FROM PUBLIC,health_app;
    GRANT USAGE ON SCHEMA public TO health_app;
    GRANT SELECT,INSERT ON account_tombstones TO health_app;
    GRANT INSERT ON inbox_events,message_quarantine TO health_app;
    GRANT SELECT(consumer_name,event_id) ON inbox_events TO health_app;
    GRANT SELECT(message_hash) ON message_quarantine TO health_app;
    GRANT SELECT,INSERT,DELETE ON connections,activity_source_days,activity_day_versions,weight_measurements,purge_jobs TO health_app;
    GRANT UPDATE(status,generation,version,next_seq) ON connections TO health_app;
    GRANT UPDATE(timezone,steps,active_kcal,deleted) ON activity_source_days TO health_app;
    GRANT UPDATE(version) ON activity_day_versions TO health_app;
    GRANT UPDATE(version,value_kg,measured_at,deleted) ON weight_measurements TO health_app;
    GRANT UPDATE(completed,retry_at) ON purge_jobs TO health_app;
    GRANT SELECT,INSERT,DELETE ON primary_connection_periods,import_batches,command_receipts,outbox_events TO health_app;
    GRANT UPDATE(response) ON import_batches TO health_app;
    GRANT UPDATE(available_at,claim_id,claimed_until,attempt_count,published_at,last_error) ON outbox_events TO health_app;
END
$grant$;
REVOKE EXECUTE ON FUNCTION health_apply_runtime_grants() FROM PUBLIC;
CREATE POLICY health_outbox_read ON outbox_events FOR SELECT USING (true);
CREATE POLICY health_outbox_write ON outbox_events FOR INSERT WITH CHECK (true);
CREATE POLICY health_outbox_publish ON outbox_events FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY health_outbox_erasure ON outbox_events FOR DELETE USING
    (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=outbox_events.user_id)
     OR (published_at < now()-interval '7 days')
     OR (payload->>'deleted'='false' AND EXISTS (SELECT 1 FROM connections c WHERE c.user_id=outbox_events.user_id
         AND c.id=outbox_events.connection_id AND c.status='PURGING')));
CREATE POLICY health_receipts_read ON command_receipts FOR SELECT USING (true);
CREATE POLICY health_receipts_write ON command_receipts FOR INSERT WITH CHECK (true);
CREATE POLICY health_receipts_erasure ON command_receipts FOR DELETE USING
    (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=command_receipts.user_id));

SELECT health_apply_runtime_grants();

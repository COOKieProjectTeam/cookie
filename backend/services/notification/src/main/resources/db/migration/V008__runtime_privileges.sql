-- Invoker-only convergence hook shared by Flyway and subsequent provisioning.
-- Never expose this owner operation to application or replay credentials.
CREATE FUNCTION notification_apply_runtime_grants() RETURNS void
LANGUAGE plpgsql AS $$
DECLARE
    schema_name text := current_schema();
    table_name text;
BEGIN
    -- Developer/test databases may use their owner directly without login roles.
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='notification_app') THEN
        RETURN;
    END IF;
    EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM notification_app', schema_name);
    EXECUTE format('REVOKE ALL ON ALL SEQUENCES IN SCHEMA %I FROM notification_app', schema_name);
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='notification_migrator') THEN
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE notification_migrator IN SCHEMA %I REVOKE ALL ON TABLES FROM notification_app', schema_name);
        EXECUTE format('ALTER DEFAULT PRIVILEGES FOR ROLE notification_migrator IN SCHEMA %I REVOKE ALL ON SEQUENCES FROM notification_app', schema_name);
    END IF;
    FOREACH table_name IN ARRAY ARRAY['settings','push_devices','inbox_events','event_projections',
        'reminder_deadlines','reminder_occurrences','delivery_jobs','delivery_attempts']
    LOOP
        EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I.%I TO notification_app', schema_name, table_name);
    END LOOP;
    EXECUTE format('GRANT SELECT,INSERT ON %I.account_tombstones TO notification_app', schema_name);
    -- Runtime creates immutable quarantine evidence; only the operator updates
    -- replay bookkeeping. Runtime retention can delete only records older than 30d.
    EXECUTE format('GRANT SELECT,DELETE ON %I.message_quarantine TO notification_app', schema_name);
    EXECUTE format('GRANT INSERT(id,message_hash,event_id,subject,reason,stream_sequence) ON %I.message_quarantine TO notification_app', schema_name);
    EXECUTE format('ALTER TABLE %I.message_quarantine ENABLE ROW LEVEL SECURITY', schema_name);
    EXECUTE format('DROP POLICY IF EXISTS quarantine_read ON %I.message_quarantine', schema_name);
    EXECUTE format('CREATE POLICY quarantine_read ON %I.message_quarantine FOR SELECT TO notification_app USING (true)', schema_name);
    EXECUTE format('DROP POLICY IF EXISTS quarantine_insert ON %I.message_quarantine', schema_name);
    EXECUTE format('CREATE POLICY quarantine_insert ON %I.message_quarantine FOR INSERT TO notification_app WITH CHECK (true)', schema_name);
    EXECUTE format('DROP POLICY IF EXISTS quarantine_retention ON %I.message_quarantine', schema_name);
    EXECUTE format('CREATE POLICY quarantine_retention ON %I.message_quarantine FOR DELETE TO notification_app USING (created_at < clock_timestamp() - interval ''30 days'')', schema_name);
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='notification_replay') THEN
        EXECUTE format('GRANT UPDATE(replay_requested_at,replayed_at,replay_attempts) ON %I.message_quarantine TO notification_replay', schema_name);
        EXECUTE format('DROP POLICY IF EXISTS quarantine_replay_update ON %I.message_quarantine', schema_name);
        EXECUTE format('CREATE POLICY quarantine_replay_update ON %I.message_quarantine FOR UPDATE TO notification_replay USING (true) WITH CHECK (true)', schema_name);
    END IF;
END;
$$;
REVOKE ALL ON FUNCTION notification_apply_runtime_grants() FROM PUBLIC;
SELECT notification_apply_runtime_grants();

-- Invoker-only convergence function; migration owner provisions user_app first.
CREATE FUNCTION user_apply_runtime_grants() RETURNS void LANGUAGE plpgsql AS $$
DECLARE s text := current_schema(); t text;
BEGIN
  IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='user_app') THEN RETURN; END IF;
  EXECUTE format('REVOKE ALL ON ALL TABLES IN SCHEMA %I FROM user_app',s);
  EXECUTE format('REVOKE CREATE ON SCHEMA %I FROM PUBLIC,user_app',s);
  EXECUTE format('GRANT USAGE ON SCHEMA %I TO user_app',s);
  FOREACH t IN ARRAY ARRAY['diet_preferences','allergies','daily_goal_overrides'] LOOP
    EXECUTE format('GRANT SELECT,INSERT,UPDATE,DELETE ON %I.%I TO user_app',s,t);
  END LOOP;
  EXECUTE format('GRANT SELECT,INSERT,DELETE ON %I.profiles TO user_app',s);
  EXECUTE format('GRANT UPDATE(height_cm,activity_level,timezone,locale,version,updated_at) ON %I.profiles TO user_app',s);
  -- The parent must not bypass immutable child grants through ON DELETE CASCADE.
  EXECUTE format('ALTER TABLE %I.profiles ENABLE ROW LEVEL SECURITY',s);
  EXECUTE format('DROP POLICY IF EXISTS profile_read ON %I.profiles',s);
  EXECUTE format('CREATE POLICY profile_read ON %I.profiles FOR SELECT TO user_app USING(true)',s);
  EXECUTE format('DROP POLICY IF EXISTS profile_insert ON %I.profiles',s);
  EXECUTE format('CREATE POLICY profile_insert ON %I.profiles FOR INSERT TO user_app WITH CHECK(NOT EXISTS (SELECT 1 FROM %I.account_tombstones t WHERE t.user_id=profiles.user_id))',s,s);
  EXECUTE format('DROP POLICY IF EXISTS profile_update ON %I.profiles',s);
  EXECUTE format('CREATE POLICY profile_update ON %I.profiles FOR UPDATE TO user_app USING(true) WITH CHECK(true)',s);
  EXECUTE format('DROP POLICY IF EXISTS profile_erasure ON %I.profiles',s);
  EXECUTE format('CREATE POLICY profile_erasure ON %I.profiles FOR DELETE TO user_app USING(EXISTS (SELECT 1 FROM %I.account_tombstones t WHERE t.user_id=profiles.user_id))',s,s);
  FOREACH t IN ARRAY ARRAY['nutrition_goal_versions','hydration_goal_versions','idempotency_commands','inbox_events'] LOOP
    EXECUTE format('GRANT SELECT,INSERT ON %I.%I TO user_app',s,t);
  END LOOP;
  EXECUTE format('GRANT SELECT,INSERT ON %I.account_tombstones TO user_app',s);
  EXECUTE format('GRANT SELECT,INSERT,DELETE ON %I.outbox_events TO user_app',s);
  EXECUTE format('GRANT UPDATE(available_at,claim_id,claimed_until,attempt_count,published_at,last_error) ON %I.outbox_events TO user_app',s);
  -- Deletion can erase outbox payloads only once a lifecycle tombstone exists.
  -- Published retention is a separate, bounded owner-maintenance operation.
  EXECUTE format('ALTER TABLE %I.outbox_events ENABLE ROW LEVEL SECURITY',s);
  EXECUTE format('DROP POLICY IF EXISTS outbox_read ON %I.outbox_events',s);
  EXECUTE format('CREATE POLICY outbox_read ON %I.outbox_events FOR SELECT TO user_app USING(true)',s);
  EXECUTE format('DROP POLICY IF EXISTS outbox_insert ON %I.outbox_events',s);
  EXECUTE format('CREATE POLICY outbox_insert ON %I.outbox_events FOR INSERT TO user_app WITH CHECK(true)',s);
  EXECUTE format('DROP POLICY IF EXISTS outbox_delivery ON %I.outbox_events',s);
  EXECUTE format('CREATE POLICY outbox_delivery ON %I.outbox_events FOR UPDATE TO user_app USING(true) WITH CHECK(true)',s);
  EXECUTE format('DROP POLICY IF EXISTS outbox_erasure ON %I.outbox_events',s);
  EXECUTE format('CREATE POLICY outbox_erasure ON %I.outbox_events FOR DELETE TO user_app USING(EXISTS (SELECT 1 FROM %I.account_tombstones t WHERE t.user_id=outbox_events.user_id))',s,s);
  EXECUTE format('GRANT SELECT ON %I.message_quarantine TO user_app',s);
  EXECUTE format('GRANT INSERT(message_hash,subject,reason,stream_sequence) ON %I.message_quarantine TO user_app',s);
END $$;
REVOKE ALL ON FUNCTION user_apply_runtime_grants() FROM PUBLIC;
SELECT user_apply_runtime_grants();

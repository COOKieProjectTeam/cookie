CREATE TABLE progress_accounts (
 user_id uuid PRIMARY KEY,
 next_due_at timestamptz,
 confirmed_streak bigint CHECK (confirmed_streak >= 0),
 confirmed_best bigint NOT NULL DEFAULT 0 CHECK (confirmed_best >= 0),
 last_confirmed_date date,
 first_goal_applied boolean NOT NULL DEFAULT false
);
CREATE INDEX progress_due ON progress_accounts(next_due_at, user_id) WHERE next_due_at IS NOT NULL;
CREATE TABLE account_tombstones (
 user_id uuid PRIMARY KEY, deletion_request_id uuid NOT NULL, deleted_at timestamptz NOT NULL
);
CREATE TABLE inbox_events (
 event_id uuid PRIMARY KEY, received_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE message_quarantine (
 stream_sequence bigint PRIMARY KEY, reason text NOT NULL, event_hash text NOT NULL,
 recorded_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
CREATE TABLE source_cursors (
 user_id uuid NOT NULL REFERENCES progress_accounts ON DELETE CASCADE,
 owner text NOT NULL CHECK (owner IN ('user','nutrition')),
 applied_through bigint NOT NULL CHECK (applied_through >= 0),
 PRIMARY KEY (user_id, owner)
);
CREATE TABLE source_facts (
 user_id uuid NOT NULL REFERENCES progress_accounts ON DELETE CASCADE,
 owner text NOT NULL CHECK (owner IN ('user','nutrition','health')),
 kind text NOT NULL, entity_key text NOT NULL, version bigint NOT NULL CHECK (version > 0),
 source_seq bigint CHECK (source_seq > 0),
 event_id uuid NOT NULL UNIQUE, payload jsonb NOT NULL CHECK (jsonb_typeof(payload)='object'),
 PRIMARY KEY (user_id, kind, entity_key, version),
 CHECK ((source_seq IS NULL) = (owner='health'))
);
CREATE UNIQUE INDEX source_facts_sequence ON source_facts (user_id, owner, source_seq) WHERE source_seq IS NOT NULL;
CREATE INDEX source_facts_recent ON source_facts (user_id,kind,entity_key,version DESC);
CREATE TABLE source_groups (
 user_id uuid NOT NULL REFERENCES progress_accounts ON DELETE CASCADE,
 owner text NOT NULL CHECK (owner IN ('user','nutrition')),
 first_seq bigint NOT NULL CHECK (first_seq > 0), last_seq bigint NOT NULL CHECK (last_seq >= first_seq),
 committed_at timestamptz NOT NULL, end_lsn pg_lsn NOT NULL,
 PRIMARY KEY (user_id,owner,first_seq),
 UNIQUE (user_id,owner,end_lsn)
);
CREATE INDEX source_groups_time ON source_groups (user_id, owner, committed_at, first_seq);
CREATE TABLE source_cuts (
 user_id uuid NOT NULL REFERENCES progress_accounts ON DELETE CASCADE,
 owner text NOT NULL CHECK (owner IN ('user','nutrition')), boundary timestamptz NOT NULL,
 before_seq bigint NOT NULL CHECK (before_seq >= 0), through_seq bigint NOT NULL CHECK (through_seq >= before_seq),
 end_lsn pg_lsn NOT NULL, PRIMARY KEY (user_id, owner, boundary)
);
CREATE TABLE source_requests (
 user_id uuid NOT NULL REFERENCES progress_accounts ON DELETE CASCADE,
 key text NOT NULL, last_requested_at timestamptz NOT NULL, PRIMARY KEY (user_id,key)
);
CREATE TABLE calendar_days (
 user_id uuid NOT NULL REFERENCES progress_accounts ON DELETE CASCADE,
 local_date date NOT NULL, zone_id text NOT NULL,
 state text NOT NULL CHECK (state IN ('OPEN','CLOSING','CLOSED','TRAVEL_SKIPPED')),
 opened_at timestamptz, closes_at timestamptz,
 profile_seq bigint NOT NULL CHECK (profile_seq > 0),
 goal_seq bigint CHECK (goal_seq > 0),
 PRIMARY KEY (user_id,local_date),
 CHECK ((state='TRAVEL_SKIPPED' AND opened_at IS NULL AND closes_at IS NULL) OR
        (state<>'TRAVEL_SKIPPED' AND opened_at IS NOT NULL AND closes_at IS NOT NULL AND opened_at < closes_at))
);
CREATE UNIQUE INDEX calendar_single_in_progress ON calendar_days(user_id) WHERE state IN ('OPEN','CLOSING');
CREATE TABLE day_results (
 user_id uuid NOT NULL, local_date date NOT NULL, outcome text NOT NULL,
 goal_seq bigint, cheat_day boolean NOT NULL, macros_met boolean,
 credit integer NOT NULL CHECK (credit IN (0,1)),
 streak_before bigint CHECK (streak_before >= 0), streak_after bigint CHECK (streak_after >= 0),
 nutrition_before_seq bigint NOT NULL, user_before_seq bigint NOT NULL, facts jsonb NOT NULL,
 PRIMARY KEY (user_id,local_date),
 FOREIGN KEY (user_id,local_date) REFERENCES calendar_days ON DELETE CASCADE,
 CHECK (outcome IN ('MACROS_MET','CHEAT_DAY_CREDIT','OUT_OF_TARGET','INSUFFICIENT_DATA','NOT_APPLICABLE'))
);
CREATE TABLE notification_versions (
 user_id uuid NOT NULL REFERENCES progress_accounts ON DELETE CASCADE,
 local_date date NOT NULL, zone_id text NOT NULL, version bigint NOT NULL CHECK (version > 0),
 payload jsonb NOT NULL, PRIMARY KEY (user_id,local_date,zone_id)
);
CREATE TABLE outbox_events (
 event_id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES progress_accounts ON DELETE CASCADE,
 subject text NOT NULL, envelope jsonb NOT NULL,
 created_at timestamptz NOT NULL DEFAULT clock_timestamp(), available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 published_at timestamptz, claim_id uuid, claimed_until timestamptz,
 attempts integer NOT NULL DEFAULT 0 CHECK (attempts >= 0),
 CHECK ((claim_id IS NULL) = (claimed_until IS NULL))
);
CREATE INDEX progress_outbox_pending ON outbox_events(available_at,created_at) WHERE published_at IS NULL;

ALTER TABLE progress_accounts ENABLE ROW LEVEL SECURITY;
CREATE POLICY progress_read ON progress_accounts FOR SELECT USING (true);
CREATE POLICY progress_insert ON progress_accounts FOR INSERT WITH CHECK
 (NOT EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=progress_accounts.user_id));
CREATE POLICY progress_update ON progress_accounts FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY progress_erase ON progress_accounts FOR DELETE USING
 (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=progress_accounts.user_id));

CREATE FUNCTION progress_apply_runtime_grants() RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='progress_app') THEN RETURN; END IF;
 REVOKE ALL ON SCHEMA public FROM PUBLIC;
 REVOKE ALL ON ALL TABLES IN SCHEMA public FROM progress_app;
 GRANT USAGE ON SCHEMA public TO progress_app;
 GRANT SELECT,INSERT ON account_tombstones,inbox_events,message_quarantine,source_facts,source_groups,source_cuts,day_results TO progress_app;
 GRANT SELECT,INSERT,DELETE ON progress_accounts TO progress_app;
 GRANT UPDATE(next_due_at,confirmed_streak,confirmed_best,last_confirmed_date,first_goal_applied) ON progress_accounts TO progress_app;
 GRANT SELECT,INSERT,UPDATE ON source_cursors,source_requests,calendar_days,notification_versions TO progress_app;
 GRANT SELECT,INSERT ON outbox_events TO progress_app;
 GRANT UPDATE(available_at,published_at,claim_id,claimed_until,attempts) ON outbox_events TO progress_app;
END $$;
REVOKE ALL ON FUNCTION progress_apply_runtime_grants() FROM PUBLIC;
SELECT progress_apply_runtime_grants();

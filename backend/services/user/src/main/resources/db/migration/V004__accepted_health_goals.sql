ALTER TABLE outbox_events ADD COLUMN event_version integer NOT NULL DEFAULT 1 CHECK (event_version > 0);

CREATE TABLE legacy_profile_activity (
    user_id uuid PRIMARY KEY REFERENCES profiles ON DELETE CASCADE,
    activity_level text NOT NULL CHECK (activity_level IN ('LOW','MODERATE','HIGH'))
);
INSERT INTO legacy_profile_activity SELECT user_id,activity_level FROM profiles WHERE activity_level IS NOT NULL;
ALTER TABLE profiles DROP CONSTRAINT profiles_activity_level_check;
UPDATE profiles SET activity_level=NULL;
ALTER TABLE profiles ADD CONSTRAINT profiles_activity_level_check
    CHECK (activity_level IN ('INACTIVE','LOW_ACTIVE','ACTIVE','VERY_ACTIVE'));

CREATE TABLE legacy_custom_overrides AS SELECT * FROM daily_goal_overrides WHERE type='CUSTOM';
ALTER TABLE legacy_custom_overrides ADD FOREIGN KEY (user_id) REFERENCES profiles ON DELETE CASCADE;
DELETE FROM daily_goal_overrides WHERE type='CUSTOM';
ALTER TABLE daily_goal_overrides ADD CONSTRAINT daily_override_cheat_only CHECK (type='CHEAT_DAY');

CREATE TABLE goal_attempts (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES profiles ON DELETE CASCADE,
    purpose text NOT NULL CHECK (purpose IN ('INITIAL','RECALC')),
    receipt_id uuid NOT NULL,
    intent_hash text NOT NULL,
    result_hash text NOT NULL,
    accepted_at timestamptz NOT NULL,
    effective_from date NOT NULL,
    status text NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING','COMPLETED','NEEDS_NEW_PREVIEW')),
    encrypted_command text,
    encrypted_input text,
    response jsonb,
    next_retry_at timestamptz NOT NULL DEFAULT now() + interval '5 minutes',
    UNIQUE(user_id,receipt_id),
    UNIQUE(user_id,id),
    CHECK ((status='PENDING' AND encrypted_input IS NOT NULL AND encrypted_command IS NOT NULL AND response IS NULL)
        OR (status<>'PENDING' AND encrypted_input IS NULL AND encrypted_command IS NULL AND response IS NOT NULL))
);
CREATE UNIQUE INDEX user_one_goal_attempt ON goal_attempts(user_id) WHERE status='PENDING';
CREATE INDEX user_due_goal_attempts ON goal_attempts(next_retry_at,id) WHERE status='PENDING';

CREATE FUNCTION user_valid_goal_target(target jsonb, expected text) RETURNS boolean LANGUAGE sql IMMUTABLE
RETURN COALESCE(
    jsonb_typeof(target)='object' AND target->>'kind'=expected AND
    CASE expected
    WHEN 'RANGE' THEN jsonb_typeof(target->'low')='number' AND jsonb_typeof(target->'center')='number'
        AND jsonb_typeof(target->'high')='number' AND (target->>'low')::numeric >= 0
        AND (target->>'center')::numeric >= (target->>'low')::numeric
        AND (target->>'high')::numeric >= (target->>'center')::numeric
    WHEN 'UPPER_LIMIT' THEN jsonb_typeof(target->'high')='number' AND (target->>'high')::numeric >= 0
        AND target->>'low' IS NULL AND target->>'center' IS NULL
    WHEN 'AT_LEAST' THEN jsonb_typeof(target->'low')='number' AND (target->>'low')::numeric >= 0
        AND target->>'high' IS NULL AND target->>'center' IS NULL
    ELSE false END, false
);
CREATE TABLE accepted_goal_versions (
    id uuid PRIMARY KEY,
    user_id uuid NOT NULL REFERENCES profiles ON DELETE CASCADE,
    attempt_id uuid NOT NULL,
    revision bigint NOT NULL CHECK(revision > 0),
    effective_from date NOT NULL,
    accepted_at timestamptz NOT NULL,
    policy_version text NOT NULL,
    outcome text NOT NULL CHECK(outcome IN ('CALCULATED','NO_CALORIE_GOAL')),
    reason text,
    calorie_low integer,
    calorie_center integer,
    calorie_high integer,
    goal jsonb NOT NULL CHECK (jsonb_typeof(goal)='object'),
    UNIQUE(user_id,revision),
    UNIQUE(user_id,attempt_id),
    FOREIGN KEY(user_id,attempt_id) REFERENCES goal_attempts(user_id,id) ON DELETE CASCADE,
    CHECK (goal->>'outcome' IS NOT NULL AND goal->>'outcome'=outcome
       AND (goal->>'id')::uuid IS NOT NULL AND (goal->>'id')::uuid=id
       AND (goal->>'revision')::bigint IS NOT NULL AND (goal->>'revision')::bigint=revision
       AND (goal->'calories'->>'low')::integer IS NOT DISTINCT FROM calorie_low
       AND (goal->'calories'->>'center')::integer IS NOT DISTINCT FROM calorie_center
       AND (goal->'calories'->>'high')::integer IS NOT DISTINCT FROM calorie_high),
    CHECK (
        (outcome='CALCULATED' AND user_valid_goal_target(goal->'calories','RANGE')
            AND user_valid_goal_target(goal->'proteinG','RANGE')
            AND user_valid_goal_target(goal->'fatG','RANGE')
            AND user_valid_goal_target(goal->'carbsG',CASE WHEN goal->>'macroProfile' IN ('LOW_CARB','KETO') THEN 'UPPER_LIMIT' ELSE 'RANGE' END)
            AND user_valid_goal_target(goal->'fiberG','AT_LEAST'))
        OR (outcome='NO_CALORIE_GOAL' AND (goal->>'reason') IS NOT DISTINCT FROM reason
            AND NOT (goal ?| ARRAY['calories','proteinG','fatG','carbsG','fiberG']))
    ),
    CHECK ((outcome='CALCULATED' AND reason IS NULL AND calorie_low IS NOT NULL
            AND calorie_center IS NOT NULL AND calorie_high IS NOT NULL
            AND calorie_low >= 1500 AND calorie_center=calorie_low+100 AND calorie_high=calorie_center+100)
        OR (outcome='NO_CALORIE_GOAL' AND reason IS NOT NULL
            AND calorie_low IS NULL AND calorie_center IS NULL AND calorie_high IS NULL))
);
CREATE INDEX user_accepted_goal_current ON accepted_goal_versions(user_id,effective_from DESC,revision DESC);

ALTER TABLE goal_attempts ENABLE ROW LEVEL SECURITY;
CREATE POLICY user_attempt_read ON goal_attempts FOR SELECT USING (true);
CREATE POLICY user_attempt_insert ON goal_attempts FOR INSERT WITH CHECK (status='PENDING');
CREATE POLICY user_attempt_transition ON goal_attempts FOR UPDATE USING (status='PENDING') WITH CHECK (true);

ALTER FUNCTION user_apply_runtime_grants() RENAME TO user_apply_lifecycle_grants;
CREATE FUNCTION user_apply_runtime_grants() RETURNS void LANGUAGE plpgsql AS $$
DECLARE s text := current_schema();
BEGIN
    PERFORM user_apply_lifecycle_grants();
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname='user_app') THEN RETURN; END IF;
    EXECUTE format('GRANT UPDATE(sex,birth_date,onboarding_completed) ON %I.profiles TO user_app',s);
    EXECUTE format('REVOKE INSERT ON %I.nutrition_goal_versions FROM user_app',s);
    EXECUTE format('GRANT SELECT,INSERT ON %I.goal_attempts,%I.accepted_goal_versions TO user_app',s,s);
    EXECUTE format('GRANT UPDATE(status,response,encrypted_command,encrypted_input,next_retry_at) ON %I.goal_attempts TO user_app',s);
END $$;
REVOKE ALL ON FUNCTION user_apply_runtime_grants() FROM PUBLIC;
SELECT user_apply_runtime_grants();

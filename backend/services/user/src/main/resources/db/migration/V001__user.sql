CREATE TABLE profiles (
    user_id uuid PRIMARY KEY, sex text CHECK (sex IN ('MALE','FEMALE')), birth_date date,
    height_cm numeric(5,2) CHECK (height_cm BETWEEN 30 AND 300),
    activity_level text CHECK (activity_level IN ('LOW','MODERATE','HIGH')),
    timezone text NOT NULL DEFAULT 'UTC', locale text NOT NULL CHECK (locale IN ('ru','en')),
    onboarding_completed boolean NOT NULL DEFAULT false, version bigint NOT NULL DEFAULT 1 CHECK(version > 0),
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now()
);
CREATE TABLE diet_preferences (user_id uuid NOT NULL REFERENCES profiles ON DELETE CASCADE, diet_type text NOT NULL,
    PRIMARY KEY(user_id,diet_type));
CREATE TABLE allergies (user_id uuid NOT NULL REFERENCES profiles ON DELETE CASCADE, allergen text NOT NULL,
    PRIMARY KEY(user_id,allergen));
CREATE TABLE nutrition_goal_versions (
    id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES profiles ON DELETE CASCADE,
    revision bigint NOT NULL CHECK(revision > 0), effective_from date NOT NULL,
    calorie_target numeric(8,2) NOT NULL CHECK(calorie_target >= 0),
    protein_g numeric(8,2) NOT NULL CHECK(protein_g >= 0), fat_g numeric(8,2) NOT NULL CHECK(fat_g >= 0),
    carbs_g numeric(8,2) NOT NULL CHECK(carbs_g >= 0), fiber_g numeric(8,2) NOT NULL CHECK(fiber_g >= 0),
    target_weight_kg numeric(6,2), target_weight_change_per_week numeric(4,2),
    source text NOT NULL CHECK(source IN ('MANUAL','CALCULATED')), created_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id,revision)
);
CREATE INDEX nutrition_goal_current ON nutrition_goal_versions(user_id,effective_from DESC,revision DESC);
CREATE TABLE hydration_goal_versions (
    id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES profiles ON DELETE CASCADE,
    revision bigint NOT NULL CHECK(revision > 0), effective_from date NOT NULL, target_ml integer NOT NULL CHECK(target_ml BETWEEN 1 AND 20000),
    source text NOT NULL CHECK(source IN ('MANUAL','CALCULATED')), created_at timestamptz NOT NULL DEFAULT now(), UNIQUE(user_id,revision)
);
CREATE INDEX hydration_goal_current ON hydration_goal_versions(user_id,effective_from DESC,revision DESC);
CREATE TABLE daily_goal_overrides (
    id uuid PRIMARY KEY, user_id uuid NOT NULL REFERENCES profiles ON DELETE CASCADE, local_date date NOT NULL,
    calorie_target numeric(8,2) CHECK(calorie_target >= 0), protein_g numeric(8,2) CHECK(protein_g >= 0),
    fat_g numeric(8,2) CHECK(fat_g >= 0), carbs_g numeric(8,2) CHECK(carbs_g >= 0), fiber_g numeric(8,2) CHECK(fiber_g >= 0),
    unlimited_calories boolean NOT NULL, type text NOT NULL CHECK(type IN ('CUSTOM','CHEAT_DAY')),
    cheat_month date GENERATED ALWAYS AS (CASE WHEN type = 'CHEAT_DAY' THEN date_trunc('month',local_date::timestamp)::date END) STORED,
    created_at timestamptz NOT NULL DEFAULT now(), updated_at timestamptz NOT NULL DEFAULT now(),
    UNIQUE(user_id,local_date), UNIQUE(user_id,cheat_month),
    CHECK ((type='CUSTOM' AND calorie_target IS NOT NULL AND NOT unlimited_calories) OR
           (type='CHEAT_DAY' AND unlimited_calories AND calorie_target IS NULL AND protein_g IS NULL AND fat_g IS NULL AND carbs_g IS NULL AND fiber_g IS NULL))
);
CREATE TABLE account_tombstones (user_id uuid PRIMARY KEY, deletion_request_id uuid NOT NULL, deleted_at timestamptz NOT NULL DEFAULT now());
CREATE TABLE idempotency_commands (
    user_id uuid NOT NULL REFERENCES profiles ON DELETE CASCADE, operation text NOT NULL, key uuid NOT NULL,
    fingerprint text NOT NULL, response jsonb NOT NULL, created_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(user_id,operation,key)
);
CREATE TABLE inbox_events (consumer_name text NOT NULL, event_id uuid NOT NULL, event_type text NOT NULL,
    processed_at timestamptz NOT NULL DEFAULT now(), PRIMARY KEY(consumer_name,event_id));
CREATE TABLE outbox_events (
    event_id uuid PRIMARY KEY, user_id uuid NOT NULL, event_type text NOT NULL,
    aggregate_version bigint NOT NULL CHECK(aggregate_version > 0), payload jsonb NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT now(), available_at timestamptz NOT NULL DEFAULT now(),
    claim_id uuid, claimed_until timestamptz, attempt_count integer NOT NULL DEFAULT 0,
    published_at timestamptz, last_error text
);
CREATE INDEX outbox_pending ON outbox_events(available_at) WHERE published_at IS NULL;
CREATE TABLE message_quarantine (message_hash text PRIMARY KEY, subject text NOT NULL, reason text NOT NULL,
    stream_sequence bigint NOT NULL, created_at timestamptz NOT NULL DEFAULT now());

CREATE TABLE delivery_jobs (
    id uuid PRIMARY KEY,
    dedup_key varchar(200) NOT NULL UNIQUE,
    channel varchar(8) NOT NULL CHECK (channel IN ('EMAIL','PUSH')),
    user_id uuid,
    device_id uuid,
    registration_attempt_id uuid,
    occurrence_id uuid,
    kind varchar(40) NOT NULL,
    locale varchar(64) NOT NULL DEFAULT 'ru',
    encrypted_payload text,
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    next_attempt_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    status varchar(24) NOT NULL DEFAULT 'PENDING' CHECK (status IN
      ('PENDING','CLAIMED','SMTP_SUBMITTING','ACCEPTED','FAILED','UNKNOWN','CANCELLED','EXPIRED')),
    attempt_count integer NOT NULL DEFAULT 0 CHECK (attempt_count >= 0),
    lease_token uuid,
    lease_until timestamptz,
    provider_message_id varchar(512),
    last_code varchar(64),
    finished_at timestamptz,
    CHECK ((lease_token IS NULL) = (lease_until IS NULL)),
    CHECK ((channel = 'EMAIL' AND registration_attempt_id IS NOT NULL AND user_id IS NULL AND device_id IS NULL)
      OR (channel = 'PUSH' AND user_id IS NOT NULL AND device_id IS NOT NULL AND registration_attempt_id IS NULL))
);
CREATE INDEX delivery_jobs_pending ON delivery_jobs(next_attempt_at,created_at) WHERE status = 'PENDING';
CREATE INDEX delivery_jobs_expiry ON delivery_jobs(expires_at) WHERE status = 'PENDING';
CREATE INDEX delivery_jobs_leases ON delivery_jobs(lease_until) WHERE status IN ('CLAIMED','SMTP_SUBMITTING');
CREATE INDEX delivery_jobs_user ON delivery_jobs(user_id) WHERE user_id IS NOT NULL;
CREATE INDEX delivery_jobs_finished ON delivery_jobs(finished_at) WHERE finished_at IS NOT NULL;

CREATE TABLE delivery_attempts (
    id uuid PRIMARY KEY,
    delivery_id uuid NOT NULL REFERENCES delivery_jobs(id) ON DELETE CASCADE,
    started_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    finished_at timestamptz,
    outcome varchar(24),
    safe_code varchar(64)
);

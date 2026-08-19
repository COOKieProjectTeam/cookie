CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    status varchar(32) NOT NULL CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE')),
    created_at timestamptz NOT NULL,
    activated_at timestamptz
);

CREATE TABLE email_credentials (
    account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    email varchar(254) NOT NULL UNIQUE,
    password_hash varchar(512) NOT NULL,
    email_verified_at timestamptz,
    failed_login_count integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL
);

CREATE TABLE auth_action_tokens (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    purpose varchar(32) NOT NULL CHECK (purpose = 'EMAIL_VERIFICATION'),
    token_hash char(64) NOT NULL,
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL
);

CREATE INDEX idx_auth_action_tokens_account_purpose
    ON auth_action_tokens(account_id, purpose, created_at DESC);
CREATE INDEX idx_auth_action_tokens_expires_at ON auth_action_tokens(expires_at);

CREATE TABLE refresh_sessions (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    family_id uuid NOT NULL,
    token_hash char(64) NOT NULL,
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    device_id varchar(255),
    replaced_by_session_id uuid REFERENCES refresh_sessions(id),
    family_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz,
    revoke_reason varchar(32),
    reuse_detected_at timestamptz
);

CREATE INDEX idx_refresh_sessions_account_status ON refresh_sessions(account_id, status);
CREATE INDEX idx_refresh_sessions_family ON refresh_sessions(family_id);
CREATE INDEX idx_refresh_sessions_expires_at ON refresh_sessions(family_expires_at);

CREATE TABLE rate_limit_buckets (
    scope_key varchar(255) PRIMARY KEY,
    window_started_at timestamptz NOT NULL,
    attempt_count integer NOT NULL CHECK (attempt_count > 0),
    expires_at timestamptz NOT NULL
);

CREATE INDEX idx_rate_limit_buckets_expires_at ON rate_limit_buckets(expires_at);

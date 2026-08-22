CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    status varchar(32) NOT NULL CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE')),
    created_at timestamptz NOT NULL,
    activated_at timestamptz,
    CONSTRAINT ck_accounts_activation_state CHECK (
        (status = 'PENDING_VERIFICATION' AND activated_at IS NULL)
        OR (status = 'ACTIVE' AND activated_at IS NOT NULL)
    ),
    CONSTRAINT ck_accounts_activation_time CHECK (
        activated_at IS NULL OR activated_at >= created_at
    )
);

CREATE TABLE email_credentials (
    account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    email varchar(254) NOT NULL UNIQUE CHECK (
        char_length(email) BETWEEN 3 AND 254 AND email = lower(email)
    ),
    password_hash varchar(512) NOT NULL CHECK (char_length(password_hash) > 0),
    email_verified_at timestamptz,
    failed_login_count integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_email_credentials_timestamps CHECK (
        updated_at >= created_at
        AND (email_verified_at IS NULL OR email_verified_at >= created_at)
    )
);

CREATE TABLE auth_action_tokens (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    purpose varchar(32) NOT NULL CHECK (purpose = 'EMAIL_VERIFICATION'),
    token_hash char(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    expires_at timestamptz NOT NULL,
    consumed_at timestamptz,
    revoked_at timestamptz,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_auth_action_token_lifetime CHECK (expires_at > created_at),
    CONSTRAINT ck_auth_action_token_terminal_state CHECK (
        NOT (consumed_at IS NOT NULL AND revoked_at IS NOT NULL)
    ),
    CONSTRAINT ck_auth_action_token_timestamps CHECK (
        (consumed_at IS NULL OR consumed_at >= created_at)
        AND (revoked_at IS NULL OR revoked_at >= created_at)
    )
);

CREATE INDEX idx_auth_action_tokens_account_purpose
    ON auth_action_tokens(account_id, purpose, created_at DESC);
CREATE INDEX idx_auth_action_tokens_expires_at ON auth_action_tokens(expires_at);

CREATE TABLE refresh_sessions (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    family_id uuid NOT NULL,
    token_hash char(64) NOT NULL CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'ROTATED', 'REVOKED')),
    device_id varchar(255),
    replaced_by_session_id uuid REFERENCES refresh_sessions(id),
    family_expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    last_used_at timestamptz,
    revoked_at timestamptz,
    revoke_reason varchar(32) CHECK (
        revoke_reason IS NULL OR revoke_reason IN ('ROTATED', 'REPLAY_DETECTED', 'LOGOUT')
    ),
    reuse_detected_at timestamptz,
    CONSTRAINT ck_refresh_session_lifetime CHECK (family_expires_at > created_at),
    CONSTRAINT ck_refresh_session_replacement CHECK (
        replaced_by_session_id IS NULL OR replaced_by_session_id <> id
    ),
    CONSTRAINT ck_refresh_session_state CHECK (
        (
            status = 'ACTIVE'
            AND replaced_by_session_id IS NULL
            AND last_used_at IS NULL
            AND revoked_at IS NULL
            AND revoke_reason IS NULL
            AND reuse_detected_at IS NULL
        ) OR (
            status = 'ROTATED'
            AND replaced_by_session_id IS NOT NULL
            AND last_used_at IS NOT NULL
            AND revoked_at IS NOT NULL
            AND revoke_reason = 'ROTATED'
            AND reuse_detected_at IS NULL
        ) OR (
            status = 'REVOKED'
            AND revoked_at IS NOT NULL
            AND revoke_reason IN ('REPLAY_DETECTED', 'LOGOUT')
            AND (
                (revoke_reason = 'REPLAY_DETECTED' AND reuse_detected_at IS NOT NULL)
                OR (revoke_reason = 'LOGOUT' AND reuse_detected_at IS NULL)
            )
        )
    ),
    CONSTRAINT ck_refresh_session_timestamps CHECK (
        (last_used_at IS NULL OR last_used_at >= created_at)
        AND (revoked_at IS NULL OR revoked_at >= created_at)
        AND (reuse_detected_at IS NULL OR reuse_detected_at >= created_at)
    )
);

CREATE INDEX idx_refresh_sessions_account_status ON refresh_sessions(account_id, status);
CREATE INDEX idx_refresh_sessions_family ON refresh_sessions(family_id, created_at, id);
CREATE INDEX idx_refresh_sessions_expires_at ON refresh_sessions(family_expires_at);

CREATE TABLE rate_limit_buckets (
    scope_key varchar(255) PRIMARY KEY CHECK (char_length(scope_key) > 0),
    window_started_at timestamptz NOT NULL,
    attempt_count integer NOT NULL CHECK (attempt_count > 0),
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_rate_limit_window CHECK (expires_at > window_started_at)
);

CREATE INDEX idx_rate_limit_buckets_expires_at ON rate_limit_buckets(expires_at);

CREATE TABLE accounts (
    id uuid PRIMARY KEY,
    created_at timestamptz NOT NULL
);

CREATE TABLE email_credentials (
    account_id uuid PRIMARY KEY REFERENCES accounts(id) ON DELETE CASCADE,
    email varchar(254) NOT NULL UNIQUE CHECK (
        char_length(email) BETWEEN 3 AND 254 AND email = lower(email)
    ),
    password_hash varchar(512) NOT NULL CHECK (char_length(password_hash) > 0),
    failed_login_count integer NOT NULL DEFAULT 0 CHECK (failed_login_count >= 0),
    locked_until timestamptz,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    CONSTRAINT ck_email_credentials_timestamps CHECK (updated_at >= created_at),
    CONSTRAINT ck_email_credentials_lock_time CHECK (
        locked_until IS NULL OR locked_until >= created_at
    )
);

CREATE TABLE registration_attempts (
    id uuid PRIMARY KEY,
    email varchar(254) NOT NULL CHECK (
        char_length(email) BETWEEN 3 AND 254 AND email = lower(email)
    ),
    registration_proof_hash char(64) NOT NULL CHECK (registration_proof_hash ~ '^[0-9a-f]{64}$'),
    request_fingerprint char(64) NOT NULL CHECK (request_fingerprint ~ '^[0-9a-f]{64}$'),
    locale varchar(255) CHECK (locale IS NULL OR char_length(locale) BETWEEN 1 AND 255),
    pending_password_hash varchar(512) CHECK (
        pending_password_hash IS NULL OR char_length(pending_password_hash) > 0
    ),
    expires_at timestamptz NOT NULL,
    completed_at timestamptz,
    abandoned_at timestamptz,
    -- Logical audit reference only: account privacy deletion must neither be
    -- blocked by nor delete the bounded idempotency tombstone.
    activated_account_id uuid,
    created_at timestamptz NOT NULL,
    CONSTRAINT ck_registration_attempt_lifetime CHECK (expires_at > created_at),
    CONSTRAINT ck_registration_attempt_timestamps CHECK (
        (completed_at IS NULL OR (completed_at >= created_at AND completed_at < expires_at))
        AND (abandoned_at IS NULL OR abandoned_at >= created_at)
    ),
    CONSTRAINT ck_registration_attempt_state CHECK (
        (
            completed_at IS NULL
            AND abandoned_at IS NULL
            AND activated_account_id IS NULL
            AND pending_password_hash IS NOT NULL
        )
        OR (
            completed_at IS NOT NULL
            AND abandoned_at IS NULL
            AND activated_account_id IS NOT NULL
            AND pending_password_hash IS NULL
            AND locale IS NULL
        )
        OR (
            completed_at IS NULL
            AND abandoned_at IS NOT NULL
            AND activated_account_id IS NULL
            AND pending_password_hash IS NULL
            AND locale IS NULL
        )
    ),
    CONSTRAINT uq_registration_attempts_registration_proof UNIQUE (registration_proof_hash),
    -- Enables the child composite FK to enforce its copy of this immutable expiry.
    CONSTRAINT uq_registration_attempts_id_expiry UNIQUE (id, expires_at)
);

CREATE INDEX idx_registration_attempts_pending_expiry
    ON registration_attempts(expires_at, id)
    WHERE completed_at IS NULL AND abandoned_at IS NULL;
CREATE INDEX idx_registration_attempts_completed_at
    ON registration_attempts(completed_at, id) WHERE completed_at IS NOT NULL;
CREATE INDEX idx_registration_attempts_abandoned_at
    ON registration_attempts(abandoned_at, id) WHERE abandoned_at IS NOT NULL;
CREATE UNIQUE INDEX uq_registration_attempts_activated_account
    ON registration_attempts(activated_account_id) WHERE activated_account_id IS NOT NULL;

CREATE TABLE registration_verification_tokens (
    id uuid PRIMARY KEY,
    attempt_id uuid NOT NULL,
    attempt_expires_at timestamptz NOT NULL,
    verifier_hash char(64) NOT NULL CHECK (verifier_hash ~ '^[0-9a-f]{64}$'),
    issued_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL,
    redeemed_at timestamptz,
    CONSTRAINT fk_registration_verification_token_attempt_expiry
        FOREIGN KEY (attempt_id, attempt_expires_at)
        REFERENCES registration_attempts(id, expires_at) ON DELETE CASCADE,
    CONSTRAINT ck_registration_verification_token_lifetime CHECK (
        expires_at > issued_at AND expires_at <= attempt_expires_at
    ),
    CONSTRAINT ck_registration_verification_token_timestamps CHECK (
        redeemed_at IS NULL OR (redeemed_at >= issued_at AND redeemed_at < expires_at)
    )
);

CREATE INDEX idx_registration_verification_tokens_attempt_issued
    ON registration_verification_tokens(attempt_id, issued_at DESC, id DESC);
CREATE UNIQUE INDEX uq_registration_verification_tokens_redeemed
    ON registration_verification_tokens(attempt_id) WHERE redeemed_at IS NOT NULL;

CREATE TABLE refresh_families (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
    device_id varchar(255) CHECK (
        device_id IS NULL OR (
            char_length(device_id) BETWEEN 1 AND 255
            AND device_id !~ '^[[:space:]]*$'
            AND device_id !~ '[[:cntrl:]]'
        )
    ),
    status varchar(16) NOT NULL CHECK (status IN ('ACTIVE', 'REVOKED')),
    expires_at timestamptz NOT NULL,
    created_at timestamptz NOT NULL,
    last_activity_at timestamptz NOT NULL,
    revoked_at timestamptz,
    revoke_reason varchar(32) CHECK (
        revoke_reason IS NULL OR revoke_reason IN ('TOKEN_REUSE_DETECTED', 'LOGOUT')
    ),
    reuse_detected_at timestamptz,
    CONSTRAINT ck_refresh_family_lifetime CHECK (
        expires_at > created_at
        AND last_activity_at >= created_at
        AND last_activity_at < expires_at
    ),
    CONSTRAINT ck_refresh_family_state CHECK (
        (
            status = 'ACTIVE'
            AND revoked_at IS NULL
            AND revoke_reason IS NULL
            AND reuse_detected_at IS NULL
        ) OR (
            status = 'REVOKED'
            AND revoked_at IS NOT NULL
            AND revoke_reason IN ('TOKEN_REUSE_DETECTED', 'LOGOUT')
            AND (
                (revoke_reason = 'TOKEN_REUSE_DETECTED' AND reuse_detected_at IS NOT NULL)
                OR (revoke_reason = 'LOGOUT' AND reuse_detected_at IS NULL)
            )
        )
    ),
    CONSTRAINT ck_refresh_family_revocation_time CHECK (
        revoked_at IS NULL OR revoked_at >= last_activity_at
    ),
    CONSTRAINT ck_refresh_family_reuse_time CHECK (
        reuse_detected_at IS NULL OR reuse_detected_at = revoked_at
    )
);

CREATE INDEX idx_refresh_families_account_status ON refresh_families(account_id, status);
CREATE INDEX idx_refresh_families_expires_at ON refresh_families(expires_at);

CREATE TABLE refresh_credentials (
    id uuid PRIMARY KEY,
    family_id uuid NOT NULL REFERENCES refresh_families(id) ON DELETE CASCADE,
    verifier_hash char(64) NOT NULL CHECK (verifier_hash ~ '^[0-9a-f]{64}$'),
    created_at timestamptz NOT NULL,
    redeemed_at timestamptz,
    replaced_by_credential_id uuid,
    rotation_idempotency_key uuid,
    retry_until timestamptz,
    CONSTRAINT uq_refresh_credentials_family_id_id UNIQUE (family_id, id),
    CONSTRAINT uq_refresh_credentials_replacement UNIQUE (replaced_by_credential_id),
    CONSTRAINT fk_refresh_credentials_replacement_family FOREIGN KEY (family_id, replaced_by_credential_id)
        REFERENCES refresh_credentials(family_id, id) DEFERRABLE INITIALLY DEFERRED,
    CONSTRAINT ck_refresh_credential_replacement CHECK (
        replaced_by_credential_id IS NULL OR replaced_by_credential_id <> id
    ),
    CONSTRAINT ck_refresh_credential_state CHECK (
        (
            redeemed_at IS NULL
            AND replaced_by_credential_id IS NULL
            AND rotation_idempotency_key IS NULL
            AND retry_until IS NULL
        ) OR (
            redeemed_at IS NOT NULL
            AND replaced_by_credential_id IS NOT NULL
            AND rotation_idempotency_key IS NOT NULL
            AND retry_until IS NOT NULL
        )
    ),
    CONSTRAINT ck_refresh_credential_timestamps CHECK (
        redeemed_at IS NULL
        OR (redeemed_at >= created_at AND retry_until > redeemed_at)
    )
);

CREATE UNIQUE INDEX uq_refresh_credentials_current
    ON refresh_credentials(family_id) WHERE redeemed_at IS NULL;
CREATE INDEX idx_refresh_credentials_family_created
    ON refresh_credentials(family_id, created_at, id);

CREATE TABLE rate_limit_buckets (
    scope_key varchar(255) PRIMARY KEY CHECK (char_length(scope_key) > 0),
    window_started_at timestamptz NOT NULL,
    attempt_count integer NOT NULL CHECK (attempt_count > 0),
    expires_at timestamptz NOT NULL,
    CONSTRAINT ck_rate_limit_window CHECK (expires_at > window_started_at)
);

CREATE INDEX idx_rate_limit_buckets_expires_at ON rate_limit_buckets(expires_at);

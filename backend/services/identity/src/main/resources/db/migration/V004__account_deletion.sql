ALTER TABLE accounts
    ADD COLUMN status varchar(32) NOT NULL DEFAULT 'ACTIVE',
    ADD CONSTRAINT ck_accounts_status
        CHECK (status IN ('ACTIVE', 'DELETION_PENDING')) NOT VALID;

-- The constant default makes every pre-existing row ACTIVE. NOT VALID still
-- enforces the constraint for every subsequent INSERT/UPDATE, including old
-- binaries, while avoiding an ACCESS EXCLUSIVE validation scan during rollout.
-- Validate it in a later controlled migration with an operationally appropriate
-- statement timeout after observing the production table size.

CREATE TABLE account_deletion_requests (
    id uuid PRIMARY KEY,
    account_id uuid NOT NULL,
    idempotency_key uuid NOT NULL,
    status varchar(32) NOT NULL,
    requested_at timestamptz NOT NULL,
    CONSTRAINT fk_account_deletion_requests_account
        FOREIGN KEY (account_id) REFERENCES accounts(id) ON DELETE RESTRICT,
    CONSTRAINT ck_account_deletion_requests_idempotency_key_v4 CHECK (
        idempotency_key::text ~
            '^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$'
    ),
    CONSTRAINT ck_account_deletion_requests_status CHECK (status = 'DELETION_PENDING'),
    CONSTRAINT uq_account_deletion_requests_account_key UNIQUE (account_id, idempotency_key)
);

CREATE UNIQUE INDEX uq_account_deletion_requests_pending_account
    ON account_deletion_requests(account_id)
    WHERE status = 'DELETION_PENDING';

CREATE INDEX idx_account_deletion_requests_requested_at
    ON account_deletion_requests(requested_at, id);

-- During an expand/contract rollout an older Identity binary does not read
-- accounts.status. The application therefore also persists its existing
-- locked_until field to a far-future value in the same transaction. The new
-- status column remains additive and defaults ACTIVE for old account writers.

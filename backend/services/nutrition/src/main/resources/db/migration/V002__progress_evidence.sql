-- This owner stores only Progress-facing snapshots. The marker publication
-- is provisioned with an INSERT-only, one-column projection before writes.
CREATE TABLE progress_source_accounts (
 user_id uuid PRIMARY KEY,
 latest_seq bigint NOT NULL DEFAULT 0 CHECK (latest_seq >= 0),
 captured_seq bigint NOT NULL DEFAULT 0 CHECK (captured_seq BETWEEN 0 AND latest_seq),
 observed_at timestamptz,
 admission_boundary timestamptz,
 fenced_through timestamptz,
 evidence_fault boolean NOT NULL DEFAULT false
);
CREATE TABLE progress_commit_markers (
 marker_id uuid PRIMARY KEY,
 user_id uuid NOT NULL REFERENCES progress_source_accounts ON DELETE CASCADE,
 source_seq bigint,
 boundary timestamptz,
 expected_seq bigint,
 CHECK ((source_seq IS NOT NULL) <> (boundary IS NOT NULL)),
 CHECK ((boundary IS NULL) = (expected_seq IS NULL)),
 CHECK (expected_seq >= 0),
 CHECK (source_seq > 0),
 UNIQUE (user_id, source_seq),
 UNIQUE (user_id, boundary)
);
CREATE TABLE progress_source_facts (
 user_id uuid NOT NULL REFERENCES progress_source_accounts ON DELETE CASCADE,
 source_seq bigint NOT NULL CHECK (source_seq > 0),
 event_id uuid NOT NULL UNIQUE,
 envelope jsonb NOT NULL CHECK (jsonb_typeof(envelope) = 'object'),
 PRIMARY KEY (user_id, source_seq)
);
CREATE TABLE progress_source_commits (
 user_id uuid NOT NULL REFERENCES progress_source_accounts ON DELETE CASCADE,
 first_seq bigint NOT NULL CHECK (first_seq > 0),
 last_seq bigint NOT NULL CHECK (last_seq >= first_seq),
 committed_at timestamptz NOT NULL,
 end_lsn pg_lsn NOT NULL UNIQUE,
 PRIMARY KEY (user_id, first_seq)
);
CREATE INDEX progress_source_commits_at ON progress_source_commits(user_id, committed_at DESC, last_seq DESC);
CREATE TABLE progress_source_faults (
 user_id uuid NOT NULL REFERENCES progress_source_accounts ON DELETE CASCADE,
 end_lsn pg_lsn NOT NULL UNIQUE,
 committed_at timestamptz NOT NULL,
 marker_ids uuid[] NOT NULL,
 PRIMARY KEY (user_id, end_lsn)
);
CREATE TABLE progress_source_cuts (
 user_id uuid NOT NULL REFERENCES progress_source_accounts ON DELETE CASCADE,
 boundary timestamptz NOT NULL,
 before_seq bigint NOT NULL CHECK (before_seq >= 0),
 through_seq bigint NOT NULL CHECK (through_seq >= before_seq),
 end_lsn pg_lsn NOT NULL,
 PRIMARY KEY (user_id, boundary)
);
CREATE TABLE progress_wal_cursor (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 end_lsn pg_lsn NOT NULL
);
INSERT INTO progress_wal_cursor(end_lsn) VALUES ('0/0');

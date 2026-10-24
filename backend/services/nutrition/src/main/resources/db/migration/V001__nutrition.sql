-- Aggregate documents are owned snapshots, never foreign service records.
CREATE TABLE meals (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, client_generated_id uuid NOT NULL,
 request_hash varchar(64) NOT NULL, local_date date NOT NULL,
 version bigint NOT NULL CHECK (version > 0), document jsonb,
 UNIQUE(user_id, client_generated_id),
 CHECK(document IS NULL OR jsonb_typeof(document) = 'object')
);
CREATE INDEX meals_user_date ON meals(user_id, local_date) WHERE document IS NOT NULL;
CREATE TABLE saved_dishes (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, version bigint NOT NULL CHECK(version > 0),
 document jsonb NOT NULL CHECK(jsonb_typeof(document)='object')
);
CREATE INDEX saved_user_id ON saved_dishes(user_id,id);
CREATE TABLE water_entries (
 id uuid PRIMARY KEY, user_id uuid NOT NULL, client_generated_id uuid NOT NULL,
 request_hash varchar(64) NOT NULL, local_date date NOT NULL,
 version bigint NOT NULL CHECK(version > 0), document jsonb,
 UNIQUE(user_id,client_generated_id),
 CHECK(document IS NULL OR jsonb_typeof(document)='object')
);
CREATE INDEX water_user_date ON water_entries(user_id,local_date) WHERE document IS NOT NULL;
CREATE TABLE day_versions (
 user_id uuid NOT NULL, local_date date NOT NULL, kind varchar(16) NOT NULL CHECK(kind IN ('day','water')),
 version bigint NOT NULL CHECK(version > 0), PRIMARY KEY(user_id,local_date,kind)
);
CREATE TABLE account_tombstones (
 user_id uuid PRIMARY KEY, deletion_request_id uuid NOT NULL, deleted_at timestamptz NOT NULL
);
CREATE TABLE inbox_events (event_id uuid PRIMARY KEY, received_at timestamptz NOT NULL DEFAULT clock_timestamp());
CREATE TABLE outbox_events (
 event_id uuid PRIMARY KEY, user_id uuid NOT NULL, envelope jsonb NOT NULL,
 subject varchar(200) NOT NULL, occurred_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 available_at timestamptz NOT NULL DEFAULT clock_timestamp(),
 claim_id uuid, claimed_until timestamptz, published_at timestamptz,
 attempts integer NOT NULL DEFAULT 0,
 CHECK ((claim_id IS NULL) = (claimed_until IS NULL)),
 CHECK (published_at IS NULL OR claim_id IS NULL)
);
CREATE INDEX outbox_pending ON outbox_events(available_at,occurred_at) WHERE published_at IS NULL;
CREATE INDEX outbox_published ON outbox_events(published_at) WHERE published_at IS NOT NULL;
CREATE TABLE rejected_events (
 stream_sequence bigint PRIMARY KEY, reason varchar(64) NOT NULL,
 rejected_at timestamptz NOT NULL DEFAULT clock_timestamp()
);
-- Runtime grants are explicit. The migration owner creates nutrition_runtime first.
-- No grant on Flyway history, no schema ownership/default table privileges.

CREATE TABLE progress_broker_checkpoint (
 singleton boolean PRIMARY KEY DEFAULT true CHECK (singleton),
 stream_created_at timestamptz NOT NULL,
 ack_floor bigint NOT NULL CHECK (ack_floor >= 0),
 reconciliation_required boolean NOT NULL DEFAULT false
);

ALTER FUNCTION progress_apply_runtime_grants() RENAME TO progress_apply_projection_grants;
CREATE FUNCTION progress_apply_runtime_grants() RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 PERFORM progress_apply_projection_grants();
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='progress_app') THEN
  GRANT SELECT, INSERT ON progress_broker_checkpoint TO progress_app;
  GRANT UPDATE(ack_floor,reconciliation_required) ON progress_broker_checkpoint TO progress_app;
 END IF;
END $$;
REVOKE ALL ON FUNCTION progress_apply_runtime_grants() FROM PUBLIC;
SELECT progress_apply_runtime_grants();

ALTER TABLE progress_broker_checkpoint ADD COLUMN consumer_created_at timestamptz;
ALTER TABLE progress_broker_checkpoint ADD COLUMN ack_delivery_floor bigint NOT NULL DEFAULT 0 CHECK (ack_delivery_floor >= 0);
CREATE TABLE progress_broker_receipts (
 consumer_sequence bigint PRIMARY KEY CHECK (consumer_sequence > 0),
 stream_sequence bigint NOT NULL CHECK (stream_sequence > 0)
);

CREATE FUNCTION keep_progress_reconciliation_latch() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF current_user='progress_app' AND OLD.reconciliation_required AND NOT NEW.reconciliation_required THEN
  RAISE EXCEPTION 'Only a database operator can clear the Progress reconciliation latch' USING ERRCODE='42501';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER progress_reconciliation_latch BEFORE UPDATE ON progress_broker_checkpoint
 FOR EACH ROW EXECUTE FUNCTION keep_progress_reconciliation_latch();

ALTER FUNCTION progress_apply_runtime_grants() RENAME TO progress_apply_checkpoint_grants;
CREATE FUNCTION progress_apply_runtime_grants() RETURNS void LANGUAGE plpgsql AS $$
BEGIN
 PERFORM progress_apply_checkpoint_grants();
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='progress_app') THEN
  GRANT SELECT, INSERT, DELETE ON progress_broker_receipts TO progress_app;
  GRANT UPDATE(consumer_created_at,ack_delivery_floor) ON progress_broker_checkpoint TO progress_app;
 END IF;
END $$;
REVOKE ALL ON FUNCTION progress_apply_runtime_grants() FROM PUBLIC;
SELECT progress_apply_runtime_grants();

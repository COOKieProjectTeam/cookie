-- Execute with psql as a database operator after Flyway and before accepting writes.
-- The operator creates nutrition_progress_capture (LOGIN) and a separate
-- nutrition_progress_replication (LOGIN REPLICATION) with service-local access.
-- Refer to the service README for the privileges of the replication credential.
\set ON_ERROR_STOP on
BEGIN;
GRANT USAGE ON SCHEMA public TO nutrition_progress_capture;
GRANT SELECT ON progress_commit_markers, progress_source_accounts, progress_source_commits,
 progress_source_cuts, progress_wal_cursor TO nutrition_progress_capture;
GRANT UPDATE(observed_at,fenced_through,evidence_fault,captured_seq) ON progress_source_accounts TO nutrition_progress_capture;
GRANT INSERT ON progress_source_commits, progress_source_cuts, progress_source_faults, outbox_events TO nutrition_progress_capture;
GRANT UPDATE(end_lsn) ON progress_wal_cursor TO nutrition_progress_capture;
CREATE POLICY progress_capture_read ON progress_source_accounts FOR SELECT TO nutrition_progress_capture USING (true);
CREATE POLICY progress_capture_update ON progress_source_accounts FOR UPDATE TO nutrition_progress_capture USING (true) WITH CHECK (true);
CREATE PUBLICATION progress_commit_markers FOR TABLE public.progress_commit_markers (marker_id)
 WITH (publish = 'insert');
COMMIT;
SELECT slot_name FROM pg_create_logical_replication_slot('nutrition_progress','pgoutput');

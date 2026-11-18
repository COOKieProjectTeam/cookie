-- Execute with psql after Flyway, before serving writes. The operator provides
-- dedicated user_progress_capture (LOGIN) and user_progress_replication (LOGIN REPLICATION).
\set ON_ERROR_STOP on
BEGIN;
GRANT USAGE ON SCHEMA public TO user_progress_capture;
GRANT SELECT ON progress_commit_markers, progress_source_accounts, progress_source_commits,
 progress_source_cuts, progress_wal_cursor TO user_progress_capture;
GRANT UPDATE(observed_at,fenced_through,evidence_fault,captured_seq) ON progress_source_accounts TO user_progress_capture;
GRANT INSERT ON progress_source_commits, progress_source_cuts, progress_source_faults, outbox_events TO user_progress_capture;
GRANT UPDATE(end_lsn) ON progress_wal_cursor TO user_progress_capture;
CREATE POLICY progress_capture_read ON progress_source_accounts FOR SELECT TO user_progress_capture USING (true);
CREATE POLICY progress_capture_update ON progress_source_accounts FOR UPDATE TO user_progress_capture USING (true) WITH CHECK (true);
CREATE POLICY progress_capture_outbox ON outbox_events FOR INSERT TO user_progress_capture
 WITH CHECK (event_version=1 AND event_type IN ('user.progress_commit.recorded','user.progress_cut.confirmed'));
CREATE PUBLICATION progress_commit_markers FOR TABLE public.progress_commit_markers (marker_id)
 WITH (publish = 'insert');
COMMIT;
SELECT slot_name FROM pg_create_logical_replication_slot('user_progress','pgoutput');

ALTER TABLE weight_measurements
 ADD COLUMN measurement_offset_minutes integer CHECK (measurement_offset_minutes BETWEEN -1080 AND 1080),
 ADD COLUMN measurement_local_date date;
ALTER TABLE weight_measurements ADD CONSTRAINT weight_place_consistent
 CHECK ((measurement_offset_minutes IS NULL) = (measurement_local_date IS NULL)
     AND (NOT deleted OR measurement_local_date IS NULL));

CREATE TABLE connection_summaries (
 user_id uuid PRIMARY KEY,
 active_count bigint NOT NULL CHECK (active_count >= 0),
 version bigint NOT NULL CHECK (version > 0)
);
ALTER TABLE connection_summaries ENABLE ROW LEVEL SECURITY;
CREATE POLICY health_connection_summary_read ON connection_summaries FOR SELECT USING (true);
CREATE POLICY health_connection_summary_insert ON connection_summaries FOR INSERT WITH CHECK
 (NOT EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=connection_summaries.user_id));
CREATE POLICY health_connection_summary_update ON connection_summaries FOR UPDATE USING (true) WITH CHECK (true);
CREATE POLICY health_connection_summary_erase ON connection_summaries FOR DELETE USING
 (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=connection_summaries.user_id));

ALTER FUNCTION health_apply_runtime_grants() RENAME TO health_apply_assessment_grants;
CREATE FUNCTION health_apply_runtime_grants() RETURNS void LANGUAGE plpgsql AS $grant$
BEGIN
 PERFORM health_apply_assessment_grants();
 IF EXISTS (SELECT FROM pg_roles WHERE rolname='health_app') THEN
  GRANT UPDATE(measurement_offset_minutes,measurement_local_date) ON weight_measurements TO health_app;
  GRANT SELECT,INSERT,DELETE ON connection_summaries TO health_app;
  GRANT UPDATE(active_count,version) ON connection_summaries TO health_app;
 END IF;
END
$grant$;
REVOKE EXECUTE ON FUNCTION health_apply_runtime_grants() FROM PUBLIC;
SELECT health_apply_runtime_grants();

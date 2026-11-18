CREATE TABLE assessment_executions (
    user_id uuid NOT NULL,
    attempt_id uuid NOT NULL,
    receipt_id uuid NOT NULL,
    encrypted_result text NOT NULL,
    PRIMARY KEY (user_id,attempt_id),
    UNIQUE (user_id,receipt_id)
);
ALTER TABLE assessment_executions ENABLE ROW LEVEL SECURITY;
CREATE POLICY health_execution_read ON assessment_executions FOR SELECT USING (true);
CREATE POLICY health_execution_insert ON assessment_executions FOR INSERT WITH CHECK (true);
CREATE POLICY health_execution_erase ON assessment_executions FOR DELETE USING
    (EXISTS (SELECT 1 FROM account_tombstones t WHERE t.user_id=assessment_executions.user_id));
ALTER FUNCTION health_apply_runtime_grants() RENAME TO health_apply_measurement_grants;
CREATE FUNCTION health_apply_runtime_grants() RETURNS void LANGUAGE plpgsql AS $grant$
BEGIN
    PERFORM health_apply_measurement_grants();
    IF EXISTS (SELECT FROM pg_roles WHERE rolname='health_app') THEN
        GRANT SELECT,INSERT,DELETE ON assessment_executions TO health_app;
    END IF;
END
$grant$;
REVOKE EXECUTE ON FUNCTION health_apply_runtime_grants() FROM PUBLIC;
SELECT health_apply_runtime_grants();

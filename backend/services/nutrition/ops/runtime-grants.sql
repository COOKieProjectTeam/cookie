-- Run atomically as schema owner after migrations, for a separate non-owner LOGIN.
-- Refuse inherited/default privileges rather than silently sanitizing unrelated roles.
DO $$
BEGIN
 IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='nutrition_runtime'
     AND (rolsuper OR rolcreaterole OR rolcreatedb OR rolreplication OR rolbypassrls))
 OR EXISTS (SELECT 1 FROM pg_class WHERE relowner='nutrition_runtime'::regrole)
 OR EXISTS (SELECT 1 FROM pg_namespace WHERE nspowner='nutrition_runtime'::regrole)
 OR EXISTS (SELECT 1 FROM pg_database WHERE datname=current_database() AND datdba='nutrition_runtime'::regrole)
 OR EXISTS (SELECT 1 FROM pg_auth_members WHERE member='nutrition_runtime'::regrole)
 OR EXISTS (SELECT 1 FROM pg_default_acl d, LATERAL aclexplode(d.defaclacl) a
     WHERE a.grantee IN (0, 'nutrition_runtime'::regrole::oid)) THEN
   RAISE EXCEPTION 'nutrition_runtime requires an unprivileged non-owner role without memberships or PUBLIC/runtime default grants';
 END IF;
END $$;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO nutrition_runtime;
REVOKE ALL ON ALL TABLES IN SCHEMA public FROM nutrition_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON meals, saved_dishes, water_entries, day_versions, outbox_events TO nutrition_runtime;
GRANT SELECT, INSERT ON account_tombstones, inbox_events, rejected_events TO nutrition_runtime;
GRANT SELECT, DELETE ON progress_source_accounts TO nutrition_runtime;
GRANT INSERT(user_id,latest_seq) ON progress_source_accounts TO nutrition_runtime;
GRANT UPDATE(latest_seq,admission_boundary) ON progress_source_accounts TO nutrition_runtime;
GRANT SELECT, INSERT ON progress_source_facts, progress_commit_markers TO nutrition_runtime;
GRANT SELECT ON progress_source_commits, progress_source_cuts TO nutrition_runtime;
ALTER TABLE progress_source_accounts ENABLE ROW LEVEL SECURITY;
DROP POLICY IF EXISTS progress_runtime_read ON progress_source_accounts;
DROP POLICY IF EXISTS progress_runtime_insert ON progress_source_accounts;
DROP POLICY IF EXISTS progress_runtime_update ON progress_source_accounts;
DROP POLICY IF EXISTS progress_runtime_delete ON progress_source_accounts;
CREATE POLICY progress_runtime_read ON progress_source_accounts FOR SELECT TO nutrition_runtime USING (true);
CREATE POLICY progress_runtime_insert ON progress_source_accounts FOR INSERT TO nutrition_runtime
 WITH CHECK (NOT EXISTS (SELECT 1 FROM account_tombstones AS t WHERE t.user_id=progress_source_accounts.user_id));
CREATE POLICY progress_runtime_update ON progress_source_accounts FOR UPDATE TO nutrition_runtime USING (true) WITH CHECK (true);
CREATE POLICY progress_runtime_delete ON progress_source_accounts FOR DELETE TO nutrition_runtime
 USING (EXISTS (SELECT 1 FROM account_tombstones AS t WHERE t.user_id=progress_source_accounts.user_id));
DO $$
DECLARE relation record; permission text;
BEGIN
 IF has_schema_privilege('nutrition_runtime','public','CREATE') THEN
   RAISE EXCEPTION 'nutrition_runtime must not own or create in the service schema';
 END IF;
 FOR relation IN SELECT oid,relname FROM pg_class WHERE relnamespace='public'::regnamespace AND relkind IN ('r','p') LOOP
   FOREACH permission IN ARRAY ARRAY['SELECT','INSERT','UPDATE','DELETE','TRUNCATE','REFERENCES','TRIGGER'] LOOP
     IF (has_table_privilege('nutrition_runtime',relation.oid,permission)
       OR CASE WHEN permission IN ('SELECT','INSERT','UPDATE','REFERENCES')
          THEN has_any_column_privilege('nutrition_runtime',relation.oid,permission) ELSE false END)
       AND NOT (relation.relname IN ('meals','saved_dishes','water_entries','day_versions','outbox_events') AND permission IN ('SELECT','INSERT','UPDATE','DELETE'))
       AND NOT (relation.relname IN ('account_tombstones','inbox_events','rejected_events') AND permission IN ('SELECT','INSERT')) THEN
       IF relation.relname = 'progress_source_accounts' AND permission IN ('SELECT','INSERT','UPDATE','DELETE') THEN
         IF permission IN ('SELECT','DELETE') OR (
            NOT has_table_privilege('nutrition_runtime',relation.oid,permission)
            AND NOT EXISTS (SELECT 1 FROM pg_attribute att
              WHERE att.attrelid=relation.oid AND att.attnum>0 AND NOT att.attisdropped
                AND att.attname<>'latest_seq' AND NOT (permission='INSERT' AND att.attname='user_id')
                AND NOT (permission='UPDATE' AND att.attname='admission_boundary')
                AND has_column_privilege('nutrition_runtime',relation.oid,att.attnum,permission))
         ) THEN CONTINUE; END IF;
       END IF;
       IF relation.relname IN ('progress_source_facts','progress_commit_markers') AND permission IN ('SELECT','INSERT')
          OR relation.relname IN ('progress_source_commits','progress_source_cuts') AND permission='SELECT' THEN CONTINUE; END IF;
       RAISE EXCEPTION 'Unsafe effective runtime privilege % on %',permission,relation.relname;
     END IF;
   END LOOP;
 END LOOP;
END $$;

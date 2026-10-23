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
       RAISE EXCEPTION 'Unsafe effective runtime privilege % on %',permission,relation.relname;
     END IF;
   END LOOP;
 END LOOP;
END $$;

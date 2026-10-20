#!/bin/sh
set -eu
umask 077

# Repeatable for both new and populated clusters. No application receives the
# administrator credential. Passwords are read from files, never CLI arguments.
export PGHOST="${PGHOST:-postgres}"
export PGUSER="${PGUSER:-postgres}"
export PGDATABASE="${PGDATABASE:-postgres}"
export PGCONNECT_TIMEOUT=5
if [ "${COOKIE_DEV_DATABASE_PROVISIONING:-false}" = true ]; then
  temporary="$(mktemp -d)"
  trap 'rm -rf -- "$temporary"' EXIT HUP INT TERM
  printf '%s' notification >"$temporary/app"
  printf '%s' notification_migrator >"$temporary/migration"
  export COOKIE_NOTIFICATION_APP_PASSWORD_FILE="$temporary/app"
  export COOKIE_NOTIFICATION_MIGRATION_PASSWORD_FILE="$temporary/migration"
else
  export PGPASSWORD="$(cat /run/secrets/postgres-admin-password)"
  export COOKIE_NOTIFICATION_APP_PASSWORD_FILE=/run/secrets/postgres-notification-password
  export COOKIE_NOTIFICATION_MIGRATION_PASSWORD_FILE=/run/secrets/postgres-notification-migration-password
fi
test -s "$COOKIE_NOTIFICATION_APP_PASSWORD_FILE"
test -s "$COOKIE_NOTIFICATION_MIGRATION_PASSWORD_FILE"

psql --no-psqlrc --set=ON_ERROR_STOP=1 <<'SQL'
SELECT pg_advisory_lock(708027001);
DO $$ BEGIN
  IF EXISTS (SELECT FROM pg_database WHERE datname = 'notification'
      AND pg_get_userbyid(datdba) != 'notification_migrator') THEN
    RAISE EXCEPTION 'Existing notification database has an unexpected owner; operator review is required';
  END IF;
END $$;
\set app_password `cat "$COOKIE_NOTIFICATION_APP_PASSWORD_FILE"`
\set migration_password `cat "$COOKIE_NOTIFICATION_MIGRATION_PASSWORD_FILE"`
SELECT 'CREATE ROLE notification_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'notification_migrator') \gexec
SELECT 'CREATE ROLE notification_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION'
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'notification_app') \gexec
ALTER ROLE notification_migrator LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'migration_password';
ALTER ROLE notification_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS PASSWORD :'app_password';
SELECT 'CREATE DATABASE notification OWNER notification_migrator'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'notification') \gexec
DO $$ BEGIN
  IF (SELECT datdba FROM pg_database WHERE datname = 'notification') !=
     (SELECT oid FROM pg_roles WHERE rolname = 'notification_migrator') THEN
    RAISE EXCEPTION 'Existing notification database has an unexpected owner; operator review is required';
  END IF;
END $$;
REVOKE ALL ON DATABASE notification FROM PUBLIC;
GRANT CONNECT ON DATABASE notification TO notification_app;
\connect notification
BEGIN;
ALTER SCHEMA public OWNER TO notification_migrator;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO notification_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO notification_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO notification_app;
ALTER DEFAULT PRIVILEGES FOR ROLE notification_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO notification_app;
ALTER DEFAULT PRIVILEGES FOR ROLE notification_migrator IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO notification_app;
COMMIT;
SQL
unset PGPASSWORD

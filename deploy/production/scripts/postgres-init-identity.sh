#!/bin/sh
set -eu

identity_password="$(cat /run/secrets/postgres-identity-password)"
migration_password="$(cat /run/secrets/postgres-identity-migration-password)"

if [ -z "${identity_password}" ] || [ -z "${migration_password}" ]; then
  echo "Identity database passwords must not be empty" >&2
  exit 1
fi

psql \
  --username "${POSTGRES_USER}" \
  --dbname "${POSTGRES_DB}" \
  --set=ON_ERROR_STOP=1 \
  --set=identity_password="${identity_password}" \
  --set=migration_password="${migration_password}" <<'SQL'
BEGIN;

CREATE ROLE identity_migrator
  LOGIN PASSWORD :'migration_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE identity_app
  LOGIN PASSWORD :'identity_password'
  NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;

ALTER DATABASE identity OWNER TO identity_migrator;
ALTER SCHEMA public OWNER TO identity_migrator;
REVOKE CREATE ON SCHEMA public FROM PUBLIC;

GRANT CONNECT ON DATABASE identity TO identity_app;
GRANT USAGE ON SCHEMA public TO identity_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO identity_app;
GRANT USAGE, SELECT, UPDATE ON ALL SEQUENCES IN SCHEMA public TO identity_app;

ALTER DEFAULT PRIVILEGES FOR ROLE identity_migrator IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO identity_app;
ALTER DEFAULT PRIVILEGES FOR ROLE identity_migrator IN SCHEMA public
  GRANT USAGE, SELECT, UPDATE ON SEQUENCES TO identity_app;

COMMIT;
SQL

unset identity_password migration_password

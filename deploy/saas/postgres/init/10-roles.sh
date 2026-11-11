#!/usr/bin/env bash
# Runs once, on the first start of an empty database (the PostgreSQL image executes everything in
# /docker-entrypoint-initdb.d then). Creates the roles the SaaS application needs:
#
#   billery_owner  owns the database and the tables, runs Flyway; no superuser. BYPASSRLS, because
#                  FORCE ROW LEVEL SECURITY binds even the table owner: without it a migration that
#                  changes rows of a tenant table would fail or silently change nothing (the test
#                  suite migrates as a superuser, which hides this). It is only used by Flyway at
#                  start (docs/architecture/multi-tenancy.md: the migration role bypasses RLS).
#   billery_app    NOLOGIN group role that the migration grants the table privileges to
#   billery_login  what the application connects as: member of billery_app, no superuser, no
#                  BYPASSRLS, not the owner. Row-level security therefore binds it; PostgreSQL does
#                  not apply the policies to the owner, a superuser or a BYPASSRLS role.
#
# The migration (V2__tenant_isolation.sql) creates billery_app only if it does not exist, so creating
# it here, before the first start, is what lets the login exist from the beginning.
# Passwords arrive through the environment of the container and are handed to psql as variables
# (:'name' quotes them as literals), never pasted into the SQL text.
set -euo pipefail

: "${BILLERY_OWNER_PASSWORD:?BILLERY_OWNER_PASSWORD is not set}"
: "${BILLERY_LOGIN_PASSWORD:?BILLERY_LOGIN_PASSWORD is not set}"

psql -v ON_ERROR_STOP=1 \
  -v owner_password="$BILLERY_OWNER_PASSWORD" \
  -v login_password="$BILLERY_LOGIN_PASSWORD" \
  -v dbname="$POSTGRES_DB" \
  --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
CREATE ROLE billery_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE BYPASSRLS PASSWORD :'owner_password';
CREATE ROLE billery_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
CREATE ROLE billery_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
  PASSWORD :'login_password' IN ROLE billery_app;

-- The owner of the database may create the tables in schema public (owned by pg_database_owner).
ALTER DATABASE :"dbname" OWNER TO billery_owner;

-- Nobody else connects to the application database.
REVOKE ALL ON DATABASE :"dbname" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"dbname" TO billery_owner, billery_login;
SQL

-- The roles of a Billery SaaS database on a managed PostgreSQL (Scaleway, OVHcloud, Aiven, ...),
-- whose admin user is no superuser. Run once with psql as the provider's admin user, connected to the
-- (empty) application database, before the first start of the backend:
--
--   psql "host=… dbname=billery user=<admin> sslmode=verify-full" \
--     -v owner_password=… -v login_password=… -v dbname=billery -f managed-roles.sql
--
-- The provider's admin is not used by the backend: its CREATEROLE / CREATEDB / REPLICATION would sit
-- in the backend container. It creates the same roles as postgres/init/10-roles.sh:
--
--   billery_owner  owns the tables and runs the migrations (DB_MIGRATION_USER). No superuser, no
--                  BYPASSRLS: row-level security is not forced (migration V10), so the owner is not
--                  bound by the policies and can migrate and pg_dump all tenants.
--   billery_app    NOLOGIN group role that the migration grants the table privileges to
--   billery_login  what the application connects as (DB_USER): member of billery_app, nothing else;
--                  row-level security binds it. AppRoleGuard refuses to start otherwise.
--
-- docs/operations/saas-deployment.md, "Managed PostgreSQL", describes the steps around it.

CREATE ROLE billery_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
  PASSWORD :'owner_password';
-- The migration creates billery_app as well, if it is missing; created here so the login can exist
-- from the beginning.
SELECT 'CREATE ROLE billery_app NOLOGIN NOSUPERUSER NOBYPASSRLS'
  WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'billery_app') \gexec
CREATE ROLE billery_login LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS
  PASSWORD :'login_password' IN ROLE billery_app;

-- The owner creates the tables in schema public (owned by the database owner, the admin). The
-- database itself is not handed over: that needs the admin to be able to act as billery_owner.
GRANT USAGE, CREATE ON SCHEMA public TO billery_owner;
-- The application reaches the schema too (PostgreSQL 15+ grants USAGE to PUBLIC; some providers
-- revoke that). The owner cannot pass this grant on itself: it is not the schema's owner.
GRANT USAGE ON SCHEMA public TO billery_app;

-- Nobody else connects to the application database.
REVOKE ALL ON DATABASE :"dbname" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"dbname" TO billery_owner, billery_login;

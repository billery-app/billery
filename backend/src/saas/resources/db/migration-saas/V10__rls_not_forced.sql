-- Row-level security without FORCE (#38), so Billery runs on managed PostgreSQL offerings, whose
-- admin user is no superuser and cannot grant BYPASSRLS.
--
-- FORCE ROW LEVEL SECURITY binds the table owner as well. The owner (billery_owner, the migration
-- account) therefore needed BYPASSRLS to migrate data and to pg_dump all tenants, and only a
-- superuser can grant that. Without FORCE the owner is not bound by the policies, which is what
-- BYPASSRLS gave it before: for the owner nothing changes. The application login is not the owner
-- (AppRoleGuard refuses to start otherwise), so the policies bind it exactly as before: the tenant
-- isolation is the same.
--
-- Every table of schema public that has FORCE set is changed, so none is missed (the core tables of
-- V2, tenant, tenant_member, tenant_invitation, tenant_audit, sign_in_log, mail_account). Row-level
-- security stays enabled with the same policies. New tenant tables enable row-level security without
-- FORCE (TenantIsolationTest refuses a forced table).
DO $$
DECLARE
    t RECORD;
BEGIN
    FOR t IN
        SELECT c.relname
        FROM pg_class c
        JOIN pg_namespace n ON n.oid = c.relnamespace
        WHERE n.nspname = 'public' AND c.relkind IN ('r', 'p') AND c.relforcerowsecurity
    LOOP
        EXECUTE format('ALTER TABLE public.%I NO FORCE ROW LEVEL SECURITY', t.relname);
    END LOOP;
END
$$;

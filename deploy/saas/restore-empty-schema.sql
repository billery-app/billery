-- Empties the schema public before a restore (restore.sh --apply). The schema itself stays, with its
-- owner, grants and default privileges: a dropped and recreated schema would lose them. Objects that
-- belong to an extension (a managed PostgreSQL may keep some here) are left alone.
SET client_min_messages = warning;
DO $$
DECLARE
    item record;
BEGIN
    FOR item IN
        SELECT CASE c.relkind WHEN 'v' THEN 'VIEW' WHEN 'm' THEN 'MATERIALIZED VIEW' ELSE 'TABLE' END AS kind,
               c.oid::regclass::text AS name
        FROM pg_class c
        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind IN ('r', 'p', 'v', 'm')
          AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = c.oid AND d.deptype = 'e')
          AND NOT EXISTS (SELECT 1 FROM pg_inherits i WHERE i.inhrelid = c.oid)
    LOOP
        EXECUTE format('DROP %s IF EXISTS %s CASCADE', item.kind, item.name);
    END LOOP;
    FOR item IN
        SELECT 'SEQUENCE' AS kind, c.oid::regclass::text AS name
        FROM pg_class c
        WHERE c.relnamespace = 'public'::regnamespace AND c.relkind = 'S'
          AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = c.oid AND d.deptype = 'e')
    LOOP
        EXECUTE format('DROP SEQUENCE IF EXISTS %s CASCADE', item.name);
    END LOOP;
    FOR item IN
        SELECT p.oid::regprocedure::text AS name
        FROM pg_proc p
        WHERE p.pronamespace = 'public'::regnamespace AND p.prokind = 'f'
          AND NOT EXISTS (SELECT 1 FROM pg_depend d WHERE d.objid = p.oid AND d.deptype = 'e')
    LOOP
        EXECUTE format('DROP FUNCTION IF EXISTS %s CASCADE', item.name);
    END LOOP;
END $$;

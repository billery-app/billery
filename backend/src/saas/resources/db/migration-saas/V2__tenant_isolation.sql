-- ============================================================================
-- SaaS V2: tenant foreign keys, row-level security and the application role
-- ============================================================================
--
-- Every tenant-owned table of the core schema gets
--   * a foreign key to tenant(id),
--   * its tenant_id default taken from the session setting app.tenant_id (so an INSERT
--     without the column belongs to the active tenant), and
--   * a row-level security policy that shows and accepts only the active tenant's rows.
--
-- app.tenant_id is set per connection by the application (TenantAwareDataSource). When it
-- is missing, current_setting() raises an error: a request without a tenant fails closed.
--
-- A new tenant-owned table needs the same treatment in a new SaaS migration;
-- SaasSchemaTest fails if a table with a tenant_id column has no policy.
-- ============================================================================

ALTER TABLE owner ADD CONSTRAINT fk_owner_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE customer ADD CONSTRAINT fk_customer_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE equipment ADD CONSTRAINT fk_equipment_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE article ADD CONSTRAINT fk_article_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE service ADD CONSTRAINT fk_service_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE invoice ADD CONSTRAINT fk_invoice_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE invoice_item ADD CONSTRAINT fk_invoice_item_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE invoice_period ADD CONSTRAINT fk_invoice_period_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE invoice_template ADD CONSTRAINT fk_invoice_template_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE document_number_series ADD CONSTRAINT fk_document_number_series_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE document_history ADD CONSTRAINT fk_document_history_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE payment ADD CONSTRAINT fk_payment_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE user_settings ADD CONSTRAINT fk_user_settings_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);

ALTER TABLE owner ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE customer ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE equipment ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE article ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE service ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE invoice ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE invoice_item ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE invoice_period ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE invoice_template ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE document_number_series ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE document_history ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE payment ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE user_settings ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;

ALTER TABLE owner ENABLE ROW LEVEL SECURITY;
ALTER TABLE owner FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON owner
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE customer ENABLE ROW LEVEL SECURITY;
ALTER TABLE customer FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON customer
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE equipment ENABLE ROW LEVEL SECURITY;
ALTER TABLE equipment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON equipment
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE article ENABLE ROW LEVEL SECURITY;
ALTER TABLE article FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON article
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE service ENABLE ROW LEVEL SECURITY;
ALTER TABLE service FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON service
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE invoice ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE invoice_item ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_item FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_item
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE invoice_period ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_period FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_period
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE invoice_template ENABLE ROW LEVEL SECURITY;
ALTER TABLE invoice_template FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON invoice_template
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE document_number_series ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_number_series FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_number_series
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE document_history ENABLE ROW LEVEL SECURITY;
ALTER TABLE document_history FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON document_history
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE payment ENABLE ROW LEVEL SECURITY;
ALTER TABLE payment FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON payment
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);
ALTER TABLE user_settings ENABLE ROW LEVEL SECURITY;
ALTER TABLE user_settings FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON user_settings
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);

-- The tenant table: a tenant sees only itself.
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON tenant
    USING (id = (current_setting('app.tenant_id'))::int);

-- The application role. FORCE ROW LEVEL SECURITY above also binds the table owner (the
-- migration role); a login role that is a member of billery_app is what the backend connects
-- as. It is neither superuser nor BYPASSRLS, otherwise the policies would be skipped.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'billery_app') THEN
        CREATE ROLE billery_app NOLOGIN NOSUPERUSER NOBYPASSRLS;
    END IF;
END
$$;

GRANT USAGE ON SCHEMA public TO billery_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO billery_app;
GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO billery_app;

-- Global lookup tables are read-only for the application; Flyway's bookkeeping is off limits.
REVOKE INSERT, UPDATE, DELETE ON document_type, document_status FROM billery_app;
REVOKE ALL ON flyway_schema_history, flyway_schema_history_saas FROM billery_app;

-- Tables and sequences added by later migrations are usable for the application role.
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO billery_app;
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT USAGE, SELECT ON SEQUENCES TO billery_app;

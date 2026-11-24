-- Sending documents to customers (#13): the company SMTP accounts of core V3 become tenant data,
-- and the operator can give a company its own daily limit of customer mails.

-- mail_account: like every tenant table (V2): foreign key to tenant, default from the session,
-- forced row-level security.
ALTER TABLE mail_account ADD CONSTRAINT fk_mail_account_tenant FOREIGN KEY (tenant_id) REFERENCES tenant(id);
ALTER TABLE mail_account ALTER COLUMN tenant_id SET DEFAULT (current_setting('app.tenant_id'))::int;
ALTER TABLE mail_account ENABLE ROW LEVEL SECURITY;
ALTER TABLE mail_account FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON mail_account
    USING (tenant_id = (current_setting('app.tenant_id'))::int)
    WITH CHECK (tenant_id = (current_setting('app.tenant_id'))::int);

-- How many mails to customers the company may send in 24 hours. NULL: the default of the
-- installation (BILLERY_CUSTOMER_MAILS_PER_DAY). Set by the operator only
-- (docs/operations/saas-tenant-lifecycle.md):
--   UPDATE tenant SET mail_daily_limit = 1000 WHERE id = 42;
ALTER TABLE tenant ADD COLUMN mail_daily_limit INT CHECK (mail_daily_limit > 0);
-- The application changes only the lifecycle columns of its tenant (closing); everything else of the
-- row, the limit included, is the operator's.
REVOKE UPDATE ON tenant FROM billery_app;
GRANT UPDATE (status, closed_at) ON tenant TO billery_app;

-- Purging a tenant removes its mail accounts as well (operator function of V8, otherwise unchanged).
CREATE OR REPLACE FUNCTION purge_tenant(p_tenant INT) RETURNS VOID
LANGUAGE plpgsql
AS $$
BEGIN
    IF p_tenant = 1 THEN
        RAISE EXCEPTION 'Tenant 1 holds the seed data and is not purged';
    END IF;
    PERFORM set_config('app.tenant_id', p_tenant::text, true);

    UPDATE payment SET reverses_payment_id = NULL WHERE tenant_id = p_tenant;
    UPDATE invoice_period SET previous_period_id = NULL WHERE tenant_id = p_tenant;
    UPDATE invoice SET period_id = NULL, cancels_invoice_id = NULL, parent_invoice_id = NULL,
                       converted_to_invoice_id = NULL WHERE tenant_id = p_tenant;

    DELETE FROM payment WHERE tenant_id = p_tenant;
    DELETE FROM document_history WHERE tenant_id = p_tenant;
    DELETE FROM invoice_item WHERE tenant_id = p_tenant;
    DELETE FROM invoice_period WHERE tenant_id = p_tenant;
    DELETE FROM invoice WHERE tenant_id = p_tenant;
    DELETE FROM service WHERE tenant_id = p_tenant;
    DELETE FROM article WHERE tenant_id = p_tenant;
    DELETE FROM equipment WHERE tenant_id = p_tenant;
    DELETE FROM customer WHERE tenant_id = p_tenant;
    DELETE FROM invoice_template WHERE tenant_id = p_tenant;
    DELETE FROM document_number_series WHERE tenant_id = p_tenant;
    DELETE FROM user_settings WHERE tenant_id = p_tenant;
    DELETE FROM mail_account WHERE tenant_id = p_tenant;
    DELETE FROM owner WHERE tenant_id = p_tenant;
    DELETE FROM tenant_invitation WHERE tenant_id = p_tenant;
    DELETE FROM tenant_member WHERE tenant_id = p_tenant;
    DELETE FROM tenant_audit WHERE tenant_id = p_tenant;
    DELETE FROM sign_in_log WHERE tenant_id = p_tenant;
    DELETE FROM tenant WHERE id = p_tenant;
END
$$;

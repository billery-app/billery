-- Who did what in the administration of a tenant: invitations, members, roles, sign-up,
-- closing. Written by saas.provisioning.TenantAuditLog in the same transaction as the change; read by the
-- tenant's owners and administrators (GET /api/tenant/audit).
--
-- Append-only for the application: it may insert and read, never change or delete. That is a
-- database privilege, not tamper evidence: the owner role and the operator can still change rows.
CREATE TABLE tenant_audit (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id INT NOT NULL DEFAULT (current_setting('app.tenant_id'))::int REFERENCES tenant(id),
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    actor_id UUID DEFAULT NULLIF(current_setting('app.user_id', true), '')::uuid REFERENCES app_user(id),
    action VARCHAR(40) NOT NULL,
    details VARCHAR(500)
);
CREATE INDEX idx_tenant_audit_tenant ON tenant_audit (tenant_id, id);

ALTER TABLE tenant_audit ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_audit FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_audit_tenant ON tenant_audit
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::int)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::int);
REVOKE UPDATE, DELETE, TRUNCATE ON tenant_audit FROM billery_app;

-- The document history is append-only for the application too. Its rows are only ever removed
-- together with a draft (DocumentHistoryRepository.deleteDraftHistory), so DELETE stays.
REVOKE UPDATE, TRUNCATE ON document_history FROM billery_app;

-- Purging a tenant removes its audit rows as well (operator function of V4, otherwise unchanged).
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
    DELETE FROM owner WHERE tenant_id = p_tenant;
    DELETE FROM tenant_invitation WHERE tenant_id = p_tenant;
    DELETE FROM tenant_member WHERE tenant_id = p_tenant;
    DELETE FROM tenant_audit WHERE tenant_id = p_tenant;
    DELETE FROM tenant WHERE id = p_tenant;
END
$$;

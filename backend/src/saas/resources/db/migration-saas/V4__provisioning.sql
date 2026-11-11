-- ============================================================================
-- SaaS V4: tenant lifecycle, invitations and the tenant purge function
-- ============================================================================

-- 1. Lifecycle. ACTIVE works normally. CLOSED (the owner closed the tenant) and SUSPENDED (the
-- operator did) keep all data but are read-only for the API, so it can still be exported.
-- Removing the data is a separate, deliberate step (purge_tenant below, docs/operations).
ALTER TABLE tenant DROP CONSTRAINT tenant_status_check;
ALTER TABLE tenant ADD CONSTRAINT tenant_status_check
    CHECK (status IN ('ACTIVE', 'SUSPENDED', 'CLOSED'));
ALTER TABLE tenant ADD COLUMN closed_at TIMESTAMP;
-- Tenant rows are never deleted by the application.
REVOKE DELETE ON tenant FROM billery_app;

-- 2. Invitations. An invitation names an e-mail address; the invited person accepts it after
-- signing in with that (verified) address. No token is mailed, so there is nothing to intercept.
CREATE TABLE tenant_invitation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id INT NOT NULL DEFAULT (current_setting('app.tenant_id'))::int REFERENCES tenant(id),
    email VARCHAR(320) NOT NULL,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'ACCOUNTANT', 'READER')),
    invited_by UUID NOT NULL REFERENCES app_user(id),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL DEFAULT (CURRENT_TIMESTAMP + INTERVAL '14 days'),
    accepted_at TIMESTAMP
);
-- At most one open invitation per address and tenant.
CREATE UNIQUE INDEX uq_tenant_invitation_open
    ON tenant_invitation (tenant_id, lower(email)) WHERE accepted_at IS NULL;
CREATE INDEX idx_tenant_invitation_email ON tenant_invitation (lower(email));

ALTER TABLE tenant_invitation ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_invitation FORCE ROW LEVEL SECURITY;
-- The tenant's administrators manage the invitations of the active tenant ...
CREATE POLICY invitation_tenant ON tenant_invitation
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::int)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::int);
-- ... and the invited person sees and accepts their own open ones.
CREATE POLICY invitation_invitee_read ON tenant_invitation FOR SELECT
    USING (
        lower(email) = lower(NULLIF(current_setting('app.user_email', true), ''))
        AND accepted_at IS NULL AND expires_at > CURRENT_TIMESTAMP
    );
CREATE POLICY invitation_invitee_accept ON tenant_invitation FOR UPDATE
    USING (
        lower(email) = lower(NULLIF(current_setting('app.user_email', true), ''))
        AND accepted_at IS NULL AND expires_at > CURRENT_TIMESTAMP
    )
    WITH CHECK (lower(email) = lower(NULLIF(current_setting('app.user_email', true), '')));

-- 3. Tenants: anyone signed in may create one (sign-up); the tenant is then visible to its
-- members, to the active tenant and to a person invited to it (to show its name).
CREATE POLICY tenant_create ON tenant FOR INSERT WITH CHECK (true);
DROP POLICY tenant_visibility ON tenant;
CREATE POLICY tenant_visibility ON tenant
    USING (
        id = NULLIF(current_setting('app.tenant_id', true), '')::int
        OR id IN (
            SELECT tenant_id FROM tenant_member
            WHERE user_id = NULLIF(current_setting('app.user_id', true), '')::uuid
        )
        OR id IN (
            SELECT tenant_id FROM tenant_invitation
            WHERE lower(email) = lower(NULLIF(current_setting('app.user_email', true), ''))
              AND accepted_at IS NULL AND expires_at > CURRENT_TIMESTAMP
        )
    );

-- 4. Removing a tenant's data for good, after the retention period (an operator action: it is not
-- granted to the application role). Call as the migration / owner role:
--     SELECT purge_tenant(42);
-- It acts as the tenant (so row-level security lets the owner through), breaks the circular
-- references between documents, deletes the tenant's rows table by table and finally the tenant.
-- Users (app_user) stay: they may belong to other tenants.
CREATE FUNCTION purge_tenant(p_tenant INT) RETURNS VOID
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
    DELETE FROM tenant WHERE id = p_tenant;
END
$$;
REVOKE ALL ON FUNCTION purge_tenant(INT) FROM PUBLIC;

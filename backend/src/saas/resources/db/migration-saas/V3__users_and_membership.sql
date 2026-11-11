-- ============================================================================
-- SaaS V3: users, identities, tenant membership
-- ============================================================================
--
-- Authorization data lives here, not in the identity provider's claims, so the provider
-- can be exchanged: user_identity maps (issuer, subject) to an app_user, tenant_member says
-- which tenants a user belongs to and with which role. A user may belong to several tenants
-- (an accountant). app_user and user_identity are not tenant data and have no row-level
-- security; the application reads them only through IdentityRepository, always by identity,
-- e-mail or user id. tenant_member has a tenant_id and is protected like the tenant tables.
-- ============================================================================

CREATE TABLE app_user (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email VARCHAR(320) NOT NULL,
    display_name VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
-- E-mail addresses are matched case-insensitively when an identity is linked to a user.
CREATE UNIQUE INDEX uq_app_user_email ON app_user (lower(email));

CREATE TABLE user_identity (
    issuer VARCHAR(500) NOT NULL,
    subject VARCHAR(255) NOT NULL,
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (issuer, subject)
);
CREATE INDEX idx_user_identity_user ON user_identity(user_id);

CREATE TABLE tenant_member (
    tenant_id INT NOT NULL REFERENCES tenant(id),
    user_id UUID NOT NULL REFERENCES app_user(id) ON DELETE CASCADE,
    role VARCHAR(20) NOT NULL CHECK (role IN ('OWNER', 'ADMIN', 'ACCOUNTANT', 'READER')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (tenant_id, user_id)
);
CREATE INDEX idx_tenant_member_user ON tenant_member(user_id);

-- A user sees its own memberships (to find its tenants) and the members of the active tenant;
-- rows can only be written for the active tenant.
ALTER TABLE tenant_member ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant_member FORCE ROW LEVEL SECURITY;
CREATE POLICY member_visibility ON tenant_member
    USING (
        user_id = NULLIF(current_setting('app.user_id', true), '')::uuid
        OR tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::int
    )
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::int);

-- A user sees the tenants it belongs to (the tenant switcher), in addition to the active one.
DROP POLICY tenant_isolation ON tenant;
CREATE POLICY tenant_visibility ON tenant
    USING (
        id = NULLIF(current_setting('app.tenant_id', true), '')::int
        OR id IN (
            SELECT tenant_id FROM tenant_member
            WHERE user_id = NULLIF(current_setting('app.user_id', true), '')::uuid
        )
    );

-- Who did it: history rows take the acting user from the connection. (The repositories do not
-- pass performed_by, so the default applies; it is NULL for system tasks.)
ALTER TABLE document_history
    ALTER COLUMN performed_by SET DEFAULT NULLIF(current_setting('app.user_id', true), '');

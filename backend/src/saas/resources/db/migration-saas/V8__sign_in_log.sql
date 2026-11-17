-- Sign-ins, for the operator's investigation of a security incident (#20,
-- docs/operations/saas-incident-response.md): who signed in to which company, when and from where.
-- Written by saas.identity.SignInLog when a session at the identity provider first reaches a
-- company; one row per user, company and provider session (session_hash: SHA-256 of issuer,
-- subject and the token's auth_time or sid, never a token).
--
-- Only the operator reads it: the application may insert, not select, change or delete. Company
-- owners and administrators do not see when their members sign in (no monitoring of employees
-- through the application). Kept 90 days (prune_sign_in_log below).
CREATE TABLE sign_in_log (
    id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    occurred_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    tenant_id INT NOT NULL DEFAULT (current_setting('app.tenant_id'))::int REFERENCES tenant(id),
    -- No cascade: an account with sign-ins is not deleted before they expire (it is anonymised, like
    -- one named in tenant_audit), so the evidence of a takeover outlives the account.
    user_id UUID NOT NULL REFERENCES app_user(id),
    source_ip VARCHAR(45),
    session_hash CHAR(64) NOT NULL,
    UNIQUE (tenant_id, user_id, session_hash)
);
CREATE INDEX idx_sign_in_log_occurred ON sign_in_log (occurred_at);

ALTER TABLE sign_in_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE sign_in_log FORCE ROW LEVEL SECURITY;
CREATE POLICY sign_in_log_tenant ON sign_in_log
    USING (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::int)
    WITH CHECK (tenant_id = NULLIF(current_setting('app.tenant_id', true), '')::int);
-- The default privileges of V2 grant every new table in full; this one is write-only.
REVOKE SELECT, UPDATE, DELETE, TRUNCATE ON sign_in_log FROM billery_app;

-- The retention: rows older than 90 days go. The application calls it now and then
-- (SignInLog), so the period holds without an operator job; it cannot delete rows itself, and the
-- 90 days are fixed here, so an attacker who controls the application cannot erase recent sign-ins.
-- Runs as the owner of the table (the migration role, which bypasses row-level security).
CREATE FUNCTION prune_sign_in_log() RETURNS INTEGER
LANGUAGE sql
SECURITY DEFINER
SET search_path = public, pg_temp
AS $$
    WITH gone AS (
        DELETE FROM sign_in_log WHERE occurred_at < CURRENT_TIMESTAMP - INTERVAL '90 days'
        RETURNING 1
    )
    SELECT count(*)::int FROM gone
$$;
REVOKE ALL ON FUNCTION prune_sign_in_log() FROM PUBLIC;
GRANT EXECUTE ON FUNCTION prune_sign_in_log() TO billery_app;

-- Purging a tenant removes its sign-ins as well (operator function of V6, otherwise unchanged).
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
    DELETE FROM sign_in_log WHERE tenant_id = p_tenant;
    DELETE FROM tenant WHERE id = p_tenant;
END
$$;

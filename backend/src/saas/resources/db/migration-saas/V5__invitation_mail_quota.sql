-- How many invitation mails were sent in the current 24 hours, per inviting user, per recipient and
-- for the whole system (InvitationMailQuota). In the database so that the limits hold across
-- restarts and several backend instances. Rows whose window has passed are deleted on every use.
-- Recipients are stored as a SHA-256 hash, never as an address.
-- Not tenant data: the limits are deliberately above the tenants, so there is no tenant_id and no
-- row-level security. The hash is not anonymisation (a known address can be hashed and compared);
-- it only keeps this table free of readable addresses.
CREATE TABLE invitation_mail_quota (
    scope VARCHAR(10) NOT NULL CHECK (scope IN ('user', 'recipient', 'system')),
    quota_key VARCHAR(64) NOT NULL,
    window_start TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    mails INT NOT NULL,
    PRIMARY KEY (scope, quota_key)
);
CREATE INDEX idx_invitation_mail_quota_window ON invitation_mail_quota (window_start);

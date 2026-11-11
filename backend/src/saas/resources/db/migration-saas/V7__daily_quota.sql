-- Rolling 24-hour limits against abuse, in the database so that they hold across restarts
-- and several backend instances (DailyQuota). One row per limit and key: how often it was used since
-- window_start. Rows whose window has passed are deleted on every use, so nothing stays longer than
-- a day.
--
--   quota = 'invitation_mail': invitation mails per inviting user, recipient, source IP and system
--   quota = 'signup':          companies created per user, source IP and system
--
-- Keys are user ids, 'all' for the system, or a SHA-256 hash for addresses and IPs: the limits need
-- no readable address. Not tenant data (the limits are deliberately above the tenants), so there
-- is no tenant_id and no row-level security.
CREATE TABLE daily_quota (
    quota VARCHAR(20) NOT NULL,
    scope VARCHAR(10) NOT NULL,
    quota_key VARCHAR(64) NOT NULL,
    window_start TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    used INT NOT NULL,
    PRIMARY KEY (quota, scope, quota_key)
);
CREATE INDEX idx_daily_quota_window ON daily_quota (quota, window_start);

-- The invitation mail limits of V5 move here; their current counts are kept.
INSERT INTO daily_quota (quota, scope, quota_key, window_start, used)
    SELECT 'invitation_mail', scope, quota_key, window_start, mails FROM invitation_mail_quota;
DROP TABLE invitation_mail_quota;

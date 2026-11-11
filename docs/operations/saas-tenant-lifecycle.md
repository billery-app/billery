# SaaS: tenant lifecycle

> **Audience:** operators of a SaaS deployment (Spring profile `saas`).
> **Scope:** how a tenant is created, how people join, how a tenant is closed and how its data
> is finally removed. For the design see `docs/architecture/multi-tenancy.md`, for the database
> roles [`deployment.md`](./deployment.md#saas-database-roles). The rules behind closing and
> deleting (export window, retention, backups, requests from persons) are in
> [`saas-data-lifecycle.md`](./saas-data-lifecycle.md); what to do when something went wrong is in
> [`saas-incident-response.md`](./saas-incident-response.md).

## Creating a tenant (sign-up)

A signed-in user without a tenant sees the page "Willkommen bei Billery" and creates a company
there (`POST /api/tenants`). In one transaction the backend

1. takes the next tenant id and acts as that tenant (row-level security only lets a tenant's own
   rows through),
2. inserts the `tenant` row (status `ACTIVE`) and makes the user its `OWNER`,
3. gives the tenant its own copy of the default templates (read from tenant 1, which owns the
   seed data of the core migrations).

Nothing else needs rows: number series and prefixes are created and defaulted on first use, the
company data (the "Inhaber") are entered by the owner in the app. **Tenant 1 is the seed tenant and
must have no members**; never invite anyone to it.

### Limits against abuse

Anyone who can sign in can create a company, so sign-up is limited. The limits are checked and
counted in the transaction that creates the company: a refused or failed sign-up counts nothing.

| Limit | Default | Setting | Refused with |
|-------|---------|---------|--------------|
| companies one user owns (closed ones do not count) | 5 | `BILLERY_MAX_COMPANIES_PER_USER` | 409 |
| companies one user creates in 24 hours | 3 | `BILLERY_SIGNUPS_PER_USER_PER_DAY` | 429 |
| companies created from one source IP in 24 hours | 10 | `BILLERY_SIGNUPS_PER_IP` | 429 |
| companies created in the whole system in 24 hours | 100 | `BILLERY_SIGNUPS_PER_DAY` | 429, logged as error |

`0` switches a limit off. The answers carry a German message for the user. The 24-hour counts
live in `daily_quota` (`DailyQuota`, quota `signup`): user ids, `all` for the system, a SHA-256 of
the source IP; rows go when their window has passed. To let someone through early, delete their
row: `DELETE FROM daily_quota WHERE quota = 'signup' AND scope = 'user' AND quota_key = '<user id>';`.

**The source IP** is the client address the backend sees. Behind the reverse proxy that is the
first address of `X-Forwarded-For` (`server.forward-headers-strategy=framework`), so the proxy
**must overwrite** that header with the address it received the request from. Caddy (the kit's
`proxy` profile) does that by default; nginx needs `proxy_set_header X-Forwarded-For $remote_addr;`
(not `$proxy_add_x_forwarded_for`, which keeps what the client sent). Many people behind one
office or carrier NAT share an IP: raise `BILLERY_SIGNUPS_PER_IP` if real users hit it.

**Monitoring:** every refusal counts in `billery_quota_refused_total{quota, scope}`. The alert
`BilleryAbuseLimitReached` (critical) fires when a system-wide limit refused anything,
`BilleryAbuseSuspected` (warning) on more than 10 refusals per scope within an hour
(`deploy/saas/monitoring/alerts.yml`).

### Before opening public sign-up

Today the users come from the identity provider the operator controls. Before anybody on the
internet can register there (self-registration, Google and the like):

1. The provider is configured as in
   [`saas-identity-provider.md`](./saas-identity-provider.md#open-sign-up): verified e-mail,
   bot protection, a policy for disposable addresses.
2. The limits above and the invitation mail limits are set for the expected traffic, and the
   request limits (`BILLERY_LIMITS_*`) are on.
3. The proxy overwrites `X-Forwarded-For` (see above), so the limits per source cannot be
   dodged with a forged header.
4. Prometheus scrapes the backend and the alerts `BilleryAbuseLimitReached` and
   `BilleryAbuseSuspected` reach a person ([`ops-gotchas.md`](./ops-gotchas.md)).
5. The data lifecycle and incident procedures are in place
   ([`saas-data-lifecycle.md`](./saas-data-lifecycle.md),
   [`saas-incident-response.md`](./saas-incident-response.md)).

## Joining a tenant (invitations)

Owners and admins invite an **e-mail address** with a role (Einstellungen → Team & Zugriff,
`POST /api/tenant/invitations`). With the `MAIL_*` settings and `BILLERY_PUBLIC_URL` the invited address gets a mail
(`InvitationMailer`: company, role, who invited, the address from `BILLERY_PUBLIC_URL`, the
expiry; limits in 24 hours: 50 mails per inviting user, 5 per recipient, `BILLERY_INVITATION_MAILS_PER_IP` (100) per source IP, `BILLERY_INVITATION_MAILS_PER_DAY` for the system, counted in `daily_quota` like the sign-up limits above); without them, or when the SMTP server refuses, the invitation is stored all the same and the
inviting user is told that nobody was mailed. The mail carries no token: the invited person signs in
with that address and accepts, on the welcome page if they have no company yet, otherwise under
"Einladungen" in the account menu (`POST /api/invitations/{id}/accept`).
Only the **verified** address stored with their user matches, never an unverified claim of a token.
An invitation lapses after 14 days; there is at most one open invitation per address and tenant.

Who may do what: an owner manages every role, an admin only accountants and readers (hand out,
change, remove). Anyone may leave a tenant. The last owner can neither leave, be demoted nor be
removed. Readers only read; accountants, admins and owners also write.

## Administration log

Every invitation, acceptance and withdrawal, role change, removal and departure, the sign-up and the
closing are written to `tenant_audit` with the time, the acting user and the address and role
concerned, in the same transaction as the change (`TenantAuditLog`). Owners and administrators see
the newest 200 entries in Einstellungen → Team & Zugriff (`GET /api/tenant/audit`). The application
can only add and read entries; they stay as long as the tenant's data and go with `purge_tenant`.
Sign-ins, exports and what an operator does directly in the database are not in it.

## Closing a tenant

The owner closes the company in the app (`POST /api/tenant/close`), or the operator sets the
status by hand:

```sql
UPDATE tenant SET status = 'SUSPENDED' WHERE id = 42;   -- e.g. unpaid, abuse; reversible
UPDATE tenant SET status = 'ACTIVE', closed_at = NULL WHERE id = 42;   -- reopen
```

A `CLOSED` or `SUSPENDED` tenant keeps all its data but is **read-only for the API**: every
write answers 403, reading and the GoBD export (`GET /api/export/gobd`) still work, so the customer
can export what they need. New members cannot join. The owner cannot reopen; only the operator can.

## Removing the data

A closed tenant is **not** deleted at once: its owner gets an export window first (90 days by
default), because issued invoices have to be kept for years (in Germany eight years, § 147 AO) and
after the purge that duty is fulfilled by the customer's own GoBD export. The policy, the check
for tenants whose window has passed and the register of purges are in
[`saas-data-lifecycle.md`](./saas-data-lifecycle.md#closing-a-company); follow the procedure there.
The purge itself:

```sql
-- as the migration / owner role, never as the application role
SELECT purge_tenant(42);
```

`purge_tenant` acts as that tenant, breaks the circular references between invoices, periods and
payments, deletes the rows of every tenant table in dependency order and finally the tenant. It
refuses tenant 1. It is not granted to the application role, and the application role has no
`DELETE` on `tenant`. The user accounts (`app_user`, `user_identity`) are kept, because a user may
belong to other tenants. To remove users who belong to no tenant any more:

```sql
DELETE FROM app_user u
 WHERE u.created_at < CURRENT_TIMESTAMP - INTERVAL '30 days'
   AND NOT EXISTS (SELECT 1 FROM tenant_member m WHERE m.user_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM tenant_audit a WHERE a.actor_id = u.id)
   AND NOT EXISTS (SELECT 1 FROM tenant_invitation i WHERE i.invited_by = u.id);
```

(this also removes their identity links; run it as the migration role). It spares accounts younger
than 30 days, which may not have created a company yet, and accounts that another company's
administration log or invitations still name: the database refuses to delete those, so a statement
without the last two conditions fails as a whole. How such an account is anonymised on the person's
request: [`saas-data-lifecycle.md`](./saas-data-lifecycle.md#accounts-and-requests-from-persons).

Uploaded files (logos) live under the tenant id: a directory `<upload dir>/<tenant id>/`, or the
prefix `<tenant id>/` in the bucket with `BILLERY_FILES_STORE=s3`. `purge_tenant` does not touch
them, so remove them in the same step:

```bash
rm -r /app/uploads/42      # the tenant id, below BILLERY_FILES_DIR / FILES_PATH
# BILLERY_FILES_STORE=s3: the prefix in the bucket, with the store's own tools, for example
aws s3 rm "s3://<bucket>/42/" --recursive --endpoint-url "<BILLERY_FILES_S3_ENDPOINT>"
```

Backups still contain purged data until they expire (`KEEP` and `BACKUP_S3_KEEP_DAYS`, default
30 days at the storage), and a restore brings a purged tenant back; the
[purge register](./saas-data-lifecycle.md#the-purge-register) is how it gets purged again.

## Tested

`TenantPurgeTest` builds a tenant with documents, items, periods, payments and a reversal, purges
it and checks that nothing is left, that another tenant is untouched and that the application role
cannot purge. `SaasApiTest` covers sign-up, the invitation flow, the role rules, the last owner,
leaving, and the read-only closed tenant; it also suspends a tenant by SQL (read-only at once, the
export still works) and reopens it.

# SaaS: customer data lifecycle

> **Audience:** operators of a SaaS deployment (Spring profile `saas`).
> **Scope:** which data the service holds, where it is and who can reach it, how a customer gets
> its data out, what happens when a company is closed, and when data is deleted, including
> uploaded files and backups. Incidents: [`saas-incident-response.md`](./saas-incident-response.md).
> The technical steps of closing and purging: [`saas-tenant-lifecycle.md`](./saas-tenant-lifecycle.md).
>
> **This is an operating policy, not legal advice and not a claim of compliance.** It describes what
> the software does and the defaults this project recommends. The points a lawyer or data
> protection adviser has to confirm for your market are listed [at the end](#open-for-legal-review).

## Roles this page assumes

- A **customer** is a company (a tenant) and the people it lets in (its members). The customer
  decides what is stored about its own customers and documents. In data protection terms the
  operator handles that data on the customer's behalf.
- The **operator** runs the deployment: the server, the database, the backups, the connection to the
  identity provider. For the **account data** of the members (address, name, sign-in identity) the
  operator decides itself.
- **Providers** are the companies the operator uses: hosting, database, backup storage, identity,
  mail.

## What is stored, and where

| Data | Contents | Where |
|------|----------|-------|
| Business data of a tenant | company master data (`owner`), customers, catalog (articles, services, equipment), documents with lines, periods, payments and history, templates, number series, settings | the PostgreSQL database, every row tagged with its `tenant_id` |
| Uploaded files | logos used in templates | the upload directory of the backend, one directory per tenant: `<upload dir>/<tenant id>/` (the `uploads` volume in the kit), or with `BILLERY_FILES_STORE=s3` a private bucket, prefix `<tenant id>/` |
| Team data of a tenant | members and roles (`tenant_member`), invitations with the invited address (`tenant_invitation`), the administration log with addresses and roles (`tenant_audit`) | the database |
| Accounts | e-mail address, display name, creation time (`app_user`); the provider's issuer and subject per sign-in identity (`user_identity`) | the database, not tenant-specific: one person can belong to several companies |
| Credentials | passwords, second factors, social logins, sign-in times | **only at the identity provider.** The application stores no password and keeps the access token in the browser's memory only |
| Invitation mails | recipient address, company name, role, name of the inviting person | sent through the SMTP provider (`MAIL_*`); the application keeps only hashed counters per recipient and source IP for the limits (`daily_quota`), deleted after their window |
| Abuse limits | how often a user, a source IP (SHA-256 hash) and the whole system signed up or sent invitation mails in the last 24 hours | `daily_quota` in the database; rows go when their 24-hour window has passed |
| Backups | a dump of the whole database (all tenants) and an archive of all uploaded files | the backup directory on the server and, if configured, an S3-compatible storage, encrypted with the operator's key ([`backup-restore.md`](./backup-restore.md)) |
| Logs | request failures, stack traces, warnings; by rule no passwords, tokens, document contents or e-mail addresses, but an error message can still name a customer | the `logs` volume (30 days, at most 1 GB) and Docker's console log (5 × 10 MB per container) |
| Metrics | counters and timings without business data | the monitoring system, if `BILLERY_METRICS_PORT` is used |
| In the browser | the id of the last company used (`billery.tenant`) | the user's `localStorage` |

What the application reads from a token is listed in
[`saas-identity-provider.md`](./saas-identity-provider.md): `iss`, `sub`, `aud`, `exp`, `email`,
`email_verified`, `name`. Nothing else from the provider is stored.

## Who can reach it

| Who | What | How | Trace |
|-----|------|-----|-------|
| Members of a tenant | that tenant's data, by role: a reader only reads, the team and the log are for owners and administrators | the application; every request checks the membership in the database | `document_history` (who changed which document), `tenant_audit` (team changes). **Reading and exporting leave no trace** |
| The application's database login (`billery_login`) | only the rows of the tenant of the current request | row-level security, forced | none needed |
| The operator with access to the host or to the database as `billery_owner` / `billery_admin` | **everything of every tenant:** database, uploaded files, logs, local backups | shell, `psql` | none in the application. Keep your own record (below) |
| Whoever holds the private backup key | every backup, so every tenant | `restore.sh` | none |
| Hosting provider | the disks of the server | their infrastructure | their terms |
| Database provider (only with an external database) | the database, and its own backups if it keeps any | their infrastructure | their logs |
| Backup storage provider | encrypted backups only (`backup.sh` refuses to upload unencrypted ones) | their infrastructure | their logs |
| File storage provider (only with `BILLERY_FILES_STORE=s3`) | the uploaded files (logos) of every tenant, not encrypted by the application | their infrastructure | their logs |
| Identity provider | accounts, credentials, sign-in times; **no** business data | its own system | its sign-in log |
| Mail provider | the invitation mails | SMTP | its log |

Tenants cannot reach each other's data: [`../architecture/multi-tenancy.md`](../architecture/multi-tenancy.md)
and [`../architecture/threat-model.md`](../architecture/threat-model.md) describe how that is
enforced and tested.

### Support and operator access

The software cannot limit or log what someone with database access does, so the limit is this rule:

1. **Support looks at a customer's data only when the customer asks for it.** The customer invites
   the support person's address as **reader** (Einstellungen → Team & Zugriff). The access then goes
   through the application, cannot change anything, is written to the customer's administration
   log, and the customer ends it by removing the member.
2. **Direct database or file access is for operations only:** purging, restoring, an incident, a
   failed migration. It is done by the persons named in the [deployment record](#deployment-record),
   and each use is written down outside the system: who, when, why, which tenant.
3. **Business rows are never changed by hand.** An issued invoice is immutable for the application
   for a reason (GoBD); a correction by SQL would be an unlogged change to the customer's books.
   The statements in these runbooks change team, status and account rows only.
4. Data is not copied out of the deployment (no dumps on laptops, no screenshots in tickets). A
   backup restored for a test goes into the throwaway database of `restore.sh`, which is removed
   afterwards.

## Getting the data out

Every member of a tenant, whatever the role, can export in the application:

- **GoBD export** (`GET /api/export/gobd`, a period of up to about ten years): a ZIP with the
  tables `belege.csv`, `positionen.csv`, `zahlungen.csv`, `historie.csv`, `kunden.csv`, the
  description `index.xml` (GDPdU) and, when chosen, the PDF and the XRechnung of every document.
  One export holds at most 10 000 documents, 2 000 when the files are included, and 128 MB; larger
  archives are exported year by year. Limits: [`../reference/env-vars.md`](../reference/env-vars.md).
- Each document as **PDF** and each invoice as **XRechnung**.

**Not in any export:** the templates and logos, the catalog (articles, services, equipment), the
settings and number series, the member list and the administration log. They are visible in the
application as long as the tenant exists. The operator does not produce exports by hand from the
database; a customer who lost access is helped by restoring a member, not by a dump.

The export keeps working after a company was closed, until it is purged.

## Closing a company

1. The owner closes the company in the application (Einstellungen → Team & Zugriff), or the
   operator suspends it ([`saas-tenant-lifecycle.md`](./saas-tenant-lifecycle.md#closing-a-tenant)).
2. From that moment the tenant is **read-only**: every write is refused, reading and exporting
   work, nobody new can join. `tenant.closed_at` records the time.
3. **Export window: 90 days** from `closed_at`. This is the project's default; write the period you
   promise into your terms and into the deployment record. During the window the operator can
   reopen the company on the owner's request.
4. After the window the tenant is purged (next section). There is no automatic job: the operator
   runs the check below on a fixed day, for example once a month.

A suspended company (unpaid, abuse) is not on this clock: `closed_at` stays empty until it is
closed.

## Deleting

### What is deleted when

| Data | Deleted | By |
|------|---------|----|
| Documents, customers, catalog, templates, settings, team, invitations, administration log of a tenant | at the purge, after the export window | `purge_tenant` (operator) |
| Uploaded files of a tenant | in the same step | removing the tenant's directory or bucket prefix (operator) |
| Accounts that belong to no company any more | at the same run, if older than 30 days and not referenced by another company's log | the cleanup statement (operator) |
| The same data in backups | when the last backup from before the purge expires: 14 backups on the server (two weeks with one run per night) and 30 days at the storage by default (`KEEP`, `BACKUP_S3_KEEP_DAYS`) | `backup.sh` |
| Logs | after 30 days (files) or 50 MB of console output | Logback, Docker |
| Open invitations | they stop working after 14 days; the row stays until an administrator revokes it or the tenant is purged | members, `purge_tenant` |
| The account at the identity provider | never by the application | the user or the operator, at the provider |

With the defaults a closed company's data is gone from the live system 90 days after closing and
from the last backup about 30 days later. If the storage keeps backups longer (object lock, a
lifecycle rule, `BACKUP_S3_KEEP_DAYS=0`) or the database provider keeps its own backups, that
longer period is the real one and belongs into the deployment record.

**In an active company nothing is deleted automatically.** Members delete drafts, customers without
documents, catalog entries and templates themselves. Issued invoices and credit notes cannot be
deleted or changed by anyone in the application, and a customer with documents cannot be deleted
(see [`../architecture/data-model.md`](../architecture/data-model.md), invoice lifecycle).

### Retention duties

Issued invoices have to be kept for years (in Germany eight years for invoices, § 147 AO; other
records have other periods, the customer's tax adviser knows them). **That duty lies with the
customer, not with the service:** the service keeps the data while the company is active and for
the export window, and the customer's archive after that is the GoBD export it downloaded.
The terms have to say so, and the application's own process documentation asks for the place where
the regular exports are kept (`verfahrensdokumentation.md`, part 2). A customer that wants the
service to be its archive keeps the company open.

### Purging: the procedure

Run SQL as the migration role (`billery_owner` in the kit:
`docker exec -it billery-saas-db psql -U billery_owner -d billery`; with an external database as
`DB_MIGRATION_USER`), never as the application's login.

1. **Find the companies whose window has passed:**

   ```sql
   SELECT id, name, closed_at::date
     FROM tenant
    WHERE status = 'CLOSED' AND closed_at < CURRENT_TIMESTAMP - INTERVAL '90 days'
    ORDER BY closed_at;
   ```
2. **Check each one:** it is the company that asked to be closed (name, id, date), and nobody asked
   for more time. Purging cannot be undone except from a backup, which restores every tenant.
3. **Write the purge into the purge register first** (below).
4. **Purge**, then remove the files and the accounts that are left over:

   ```sql
   SELECT purge_tenant(42);
   ```

   ```bash
   docker exec billery-saas-backend rm -r /app/uploads/42   # the tenant id, below BILLERY_FILES_DIR
   # BILLERY_FILES_STORE=s3: the prefix 42/ in the bucket instead, with the store's own tools
   ```

   ```sql
   DELETE FROM app_user u
    WHERE u.created_at < CURRENT_TIMESTAMP - INTERVAL '30 days'
      AND NOT EXISTS (SELECT 1 FROM tenant_member m WHERE m.user_id = u.id)
      AND NOT EXISTS (SELECT 1 FROM tenant_audit a WHERE a.actor_id = u.id)
      AND NOT EXISTS (SELECT 1 FROM tenant_invitation i WHERE i.invited_by = u.id);
   ```

   The last statement removes accounts without a company together with their sign-in identities.
   It spares accounts younger than 30 days (somebody who just signed up and has not created a
   company yet) and accounts that another, still existing company's log or invitations name as
   the acting person; those go when that company is purged or on the person's request (below).
   A removed person who signs in again simply gets a new, empty account.

### The purge register

A list **outside the database and outside its backups** (the operator's own records), one line per
purge: tenant id, `closed_at`, date of the purge, who ran it. No company data beyond that.

It exists because a restore brings purged companies back: the backup from before the purge still
contains them. After every restore, go through the register and purge again every entry whose
tenant exists again **with the same id and the same `closed_at`**. Compare both: a restore also
resets the id counter, so a company created after the restore can get the id of a company that
was created, and perhaps purged, between the backup and the restore.

Keep the register as long as the longest backup retention plus the period for which you want to be
able to show that a deletion took place.

## Accounts and requests from persons

**People in a customer's data** (its customers, contact persons on documents) are the customer's
matter: forward the request to the company's owner. The company can correct master data and delete
a customer without documents; documents it has issued stay under its retention duty, and the
application will not delete them.

**Members** (people with an account) can ask the operator what is stored about them and to have
it removed. Everything below is keyed by the address or the user id.

What is stored about one person:

```sql
SELECT id, email, display_name, created_at FROM app_user WHERE lower(email) = lower('person@example.com');
-- with that id:
SELECT issuer, subject, created_at FROM user_identity WHERE user_id = '<user id>';
SELECT m.tenant_id, t.name, m.role FROM tenant_member m JOIN tenant t ON t.id = m.tenant_id
 WHERE m.user_id = '<user id>';
SELECT tenant_id, role, created_at, accepted_at FROM tenant_invitation
 WHERE lower(email) = lower('person@example.com') OR invited_by = '<user id>';
SELECT tenant_id, occurred_at, action, details FROM tenant_audit
 WHERE actor_id = '<user id>' OR position(lower('person@example.com') in lower(details)) > 0;
SELECT tenant_id, count(*) FROM document_history WHERE performed_by = '<user id>' GROUP BY tenant_id;
```

The last three belong to the companies concerned: they are those companies' records of who invited
whom and who changed which document. Tell the person which companies they are, not the contents.

Removing an account:

1. The person leaves their companies in the application, or their administrators remove them. A
   company whose **only owner** they are has to get another owner or be closed first:

   ```sql
   SELECT m.tenant_id, t.name FROM tenant_member m JOIN tenant t ON t.id = m.tenant_id
    WHERE m.user_id = '<user id>' AND m.role = 'OWNER'
      AND NOT EXISTS (SELECT 1 FROM tenant_member o
                       WHERE o.tenant_id = m.tenant_id AND o.role = 'OWNER' AND o.user_id <> m.user_id);
   ```
2. Remove what identifies the person:

   ```sql
   BEGIN;
   DELETE FROM tenant_member WHERE user_id = '<user id>';
   DELETE FROM user_identity WHERE user_id = '<user id>';
   DELETE FROM tenant_invitation WHERE lower(email) = lower('person@example.com');
   UPDATE tenant_audit SET details = '[removed]'
    WHERE position(lower('person@example.com') in lower(details)) > 0;
   UPDATE app_user SET email = 'removed-' || id || '@deleted.invalid', display_name = NULL
    WHERE id = '<user id>';
   COMMIT;
   ```

   The account row itself stays as an anonymous placeholder while a company's log, invitations or
   document history still point at it; those entries then show the placeholder instead of a name,
   and the fact that *somebody* issued a given invoice stays intact. The cleanup statement of the
   purge procedure deletes the row once nothing refers to it. A plain `DELETE FROM app_user` is
   refused by the database as long as a log entry or an invitation names the person.
3. The account **at the identity provider** is deleted there; the application cannot do it. Without
   that the person can sign in again and gets a new, empty account.
4. Backups keep the old state until they expire (see the table above).

## Deployment record

The software cannot know these; the operator writes them down once and keeps them current. Without
them the tables above are incomplete for a real deployment.

- Where the server, the database and the backup storage are: provider, country or region.
- Which providers handle which data (hosting, database, backup storage, identity, mail), and the
  data processing agreement concluded with each.
- Who has host access, who has database access, who holds the private backup key and where the
  second copy of it is. Who stands in when that person is away.
- The export window and the backup retention actually configured (`KEEP`, `BACKUP_S3_KEEP_DAYS`,
  rules at the storage, backups kept by the database provider).
- Where the record of operator access, the purge register and the incident log are kept.
- The day of the monthly purge check and of the monthly restore test.
- The contact a customer uses for data requests, and the contacts for an incident
  ([`saas-incident-response.md`](./saas-incident-response.md#roles)).

## Open for legal review

The points on which this page takes a position that only a lawyer or data protection adviser can
confirm for the markets served:

1. The role split: the operator as processor for business data and as controller for account data,
   and the agreement with customers that follows from it.
2. That the retention duty for invoices stays with the customer and a 90-day export window after
   closing is sufficient and correctly worded in the terms.
3. Whether the backup retention is an acceptable delay for deletion, and how deletion requests are
   answered during it.
4. Keeping an anonymised account row and the companies' log entries after a member's removal.
5. The list of providers, their locations and the transfer basis for each.
6. That access by support as an invited reader and the operator's own record of database access are
   an adequate control.

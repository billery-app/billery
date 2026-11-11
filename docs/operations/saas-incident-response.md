# SaaS: security incidents

> **Audience:** operators of a SaaS deployment (Spring profile `saas`).
> **Scope:** what to do when customer data may have been exposed, changed or lost: who decides,
> how to stop it, what to save before it is gone, who tells whom, and how to get back to normal.
> Which data exists and who can reach it: [`saas-data-lifecycle.md`](./saas-data-lifecycle.md).
>
> **This is an operating procedure, not legal advice.** Deadlines and duties to notify depend on
> the markets served; the positions taken here are marked and have to be confirmed
> ([`saas-data-lifecycle.md`](./saas-data-lifecycle.md#open-for-legal-review)).

## What counts

An incident is anything that may have broken the confidentiality, integrity or availability of
customer data. Treat a suspicion as an incident until it is cleared.

| Kind | Examples |
|------|----------|
| Exposure across tenants | a member sees another company's documents, files or team |
| Access from outside | somebody on the host or in the database who should not be; a public backup bucket |
| Leaked secret | `.env`, a database password, the private backup key, the storage key, `BILLERY_FILES_SECRET`, the SMTP password, an administrator account at a provider |
| Account taken over | a member's account at the identity provider is used by someone else |
| Identity provider fault | tokens for unverified addresses, a compromised or misconfigured provider |
| Loss or damage | deleted or altered data, a failed migration or restore, a destroyed server |
| Supply chain | a malicious or vulnerable image or dependency in the running release ([`supply-chain.md`](./supply-chain.md)) |

An outage without loss of data is handled as operations, but written into the same log.

## Roles

A small deployment has one or two people; the roles still have to be assigned by name in the
[deployment record](./saas-data-lifecycle.md#deployment-record), with a stand-in for each.

| Role | Does | Default |
|------|------|---------|
| Incident lead | decides (stop the service, restore, notify), keeps the incident log | the operator |
| Technical | contains, saves evidence, recovers | the person with host and database access; needs access to the private backup key |
| Communication | informs customers and, where required, the authority | the incident lead |
| Customer contact | receives the notification for a company | every **owner** of the company (query below) |
| Outside | the security or support contacts of the hosting, database, storage, identity and mail providers; legal or data protection advice | from the deployment record |

Anyone who notices something tells the incident lead at once, by a channel that does not run over
the affected system.

## The procedure

### 1. Open the incident log

Outside the affected system: when it was noticed, by whom, what is known. From then on every
finding and every action with its time. This log is the basis of the notification and of the
review, and the only trace of what the operator did in the database.

### 2. Assess

Which data, which companies, since when, and is it still going on? An answer of "unknown" is
normal at this point and no reason to wait with step 3.

### 3. Contain

Take the smallest step that stops it. **Save volatile evidence first (step 4) when the step
destroys it**: restarting or stopping a container is safe for its log, removing a container, a
volume or the server is not.

SQL runs as the migration role (`docker exec -it billery-saas-db psql -U billery_owner -d billery`
in the kit), and each statement goes into the incident log.

| Situation | Action | Effect |
|-----------|--------|--------|
| One company is abused or compromised | `UPDATE tenant SET status = 'SUSPENDED' WHERE id = 42;` | read-only from the next request; its members can still read and export. Reverse with `status = 'ACTIVE'` |
| A member's account is taken over | remove the memberships (below) **and** block the account at the identity provider | the next request of that account reaches no company's data. Tokens already issued stay valid until they expire, but without a membership they only show the account's own, now empty, profile |
| Data is exposed across tenants, or the cause is unknown | `docker compose stop backend` | the service is down, the data and the logs stay. The worst case justifies the outage |
| The identity provider issues wrong tokens | take its issuer out of `BILLERY_SECURITY_ISSUERS` and restart; with only one provider stop the backend | every token of that provider is refused |
| A secret leaked | rotate it (table below) | the old value stops working |
| A bad release | set `BILLERY_VERSION` back to the last good one, if no migration ran in between; otherwise stop and restore ([`ops-gotchas.md`](./ops-gotchas.md#recovering-from-a-failed-migration)) | |

Ending one account's access to every company:

```sql
SELECT m.tenant_id, t.name, m.role FROM tenant_member m
  JOIN tenant t ON t.id = m.tenant_id JOIN app_user u ON u.id = m.user_id
 WHERE lower(u.email) = lower('person@example.com');          -- note the result in the log
DELETE FROM tenant_member WHERE user_id = '<user id>';
```

Deleting the rows in `user_identity` does **not** lock an account out: the next sign-in with the
same verified address links it again. Blocking happens at the identity provider. When the account
is safe again, its companies invite the person anew, or the operator re-inserts the memberships
noted above.

| Secret | Rotation | Side effect |
|--------|----------|-------------|
| Database passwords | `ALTER ROLE … PASSWORD`, then `.env`, then `docker compose up -d` ([`saas-deployment.md`](./saas-deployment.md#operating)) | a short restart |
| `BILLERY_FILES_SECRET` | new value in `.env`, restart | every stored logo URL stops working; logos have to be uploaded again. Until then a leaked value lets anyone who knows a tenant id and a file name fetch that file |
| Private backup key | new key pair, new `BACKUP_AGE_RECIPIENT`, run a backup, then delete the backups made for the old key, on the server and at the storage | the old backups are readable with the leaked key for as long as they exist; deleting them shortens the time you can go back |
| Storage key | new key at the provider, `.env` | check the bucket's access log for the old key |
| SMTP password | at the mail provider, then `.env`, restart | |
| Access to the host or to a provider account | new keys or passwords, second factor, end other sessions | check what the access was used for |

The single-page app has no client secret; nothing is to rotate for it.

### 4. Save evidence

Some of it disappears by itself within hours.

| Evidence | Lifetime | How |
|----------|----------|-----|
| Console output of the containers | 5 × 10 MB each, under load a few hours | `docker compose logs --no-color --timestamps > incident/compose.log` |
| Log files of the backend | 30 days, at most 1 GB | `docker cp billery-saas-backend:/app/logs incident/logs` |
| State of the database and the files | changes with every request | `./backup.sh`, then copy the two files of that run out of the backup directory: the rotation (`KEEP`) would delete them |
| Team changes | until the tenant is purged | `tenant_audit` (query below) |
| Changes to documents | until the tenant is purged | `document_history` (query below) |
| Sign-ins | the application records none | the identity provider's sign-in log; export it before its retention ends |
| Requests (who called what, from where) | the kit's Caddy writes **no** access log | your proxy's access log if you switched one on; the hosting provider's network logs |
| Access to backups, database, mail | at the providers | their audit logs |

What cannot be reconstructed from the application: **who read or exported what.** A member with
access leaves no trace when reading. If that question matters for your service, switch on an access
log at the proxy (it records IP addresses, so give it a retention period and add it to
[`saas-data-lifecycle.md`](./saas-data-lifecycle.md#what-is-stored-and-where)).

The copies are customer data of every tenant: keep them encrypted, with the same access rule as
backups, and delete them when the incident is closed and no dispute is open. Do not rebuild or
delete a compromised server before the incident lead decided that nothing more is needed from it.

```sql
-- team changes of one company in a period
SELECT a.occurred_at, a.action, a.details, u.email AS actor
  FROM tenant_audit a LEFT JOIN app_user u ON u.id = a.actor_id
 WHERE a.tenant_id = 42 AND a.occurred_at >= TIMESTAMP '2026-01-31 00:00' ORDER BY a.id;

-- what one account did to documents, in every company
SELECT h.tenant_id, h.action_date, h.action, h.invoice_id, i.document_number
  FROM document_history h JOIN invoice i ON i.tenant_id = h.tenant_id AND i.id = h.invoice_id
 WHERE h.performed_by = '<user id>' AND h.action_date >= TIMESTAMP '2026-01-31 00:00' ORDER BY h.id;

-- sign-in identities linked in a period (a new identity on an old account can be a takeover)
SELECT i.created_at, i.issuer, i.subject, u.email, u.created_at AS user_since
  FROM user_identity i JOIN app_user u ON u.id = i.user_id
 WHERE i.created_at >= TIMESTAMP '2026-01-31 00:00' ORDER BY i.created_at;
```

Times are stored without a zone, in the time zone of the backend: UTC in the kit's containers.

A backup can be read without touching the live data: `restore.sh` without `--apply` loads it into
a throwaway PostgreSQL to test it and removes that again; to run queries on a backup, load the
dump the same way by hand (an empty PostgreSQL without network, `CREATE ROLE billery_app NOLOGIN`,
then `pg_restore --no-owner --no-acl`), and remove the container afterwards.

### 5. Notify

Who owns which notification (the split follows the roles in
[`saas-data-lifecycle.md`](./saas-data-lifecycle.md#roles-this-page-assumes) and is one of the
points for legal review):

| Data concerned | Who informs whom |
|----------------|------------------|
| A company's business data (documents, its customers, files, team) | **The operator informs the owners of every affected company without undue delay** once the exposure, change or loss is established, even if details are still missing. **The company** decides whether it has to inform its authority and the people in its data; the operator supplies the facts for that |
| Account data of members (addresses, names, identities) | **The operator** assesses and, where required, informs its authority (in the EU within 72 hours of becoming aware) and the members concerned |
| An incident at a provider | the provider informs the operator; the operator then acts as above |

The notification says what happened and when, which data of that company is concerned, what was
done, what the company should do now (for example: check documents created in a period, have
members change their password at the provider), and whom to ask. It names no other company. It
goes by e-mail from outside the application, which has no channel of its own for this:

```sql
SELECT t.id, t.name, u.email
  FROM tenant t
  JOIN tenant_member m ON m.tenant_id = t.id AND m.role = 'OWNER'
  JOIN app_user u ON u.id = m.user_id
 WHERE t.id IN (42, 43)            -- the affected companies; leave the line out for all
 ORDER BY t.id;
```

If the database is gone, the addresses come from the newest backup, loaded as described in step 4.
The incident lead decides on the wording and the time; the decision **not** to notify is written
into the log with its reasons.

### 6. Recover

1. **Remove the cause first:** the fixed release, the rotated secret, the closed access. Restoring
   onto a system that is still open repeats the incident.
2. **Restore only when data was destroyed or altered**, with [`backup-restore.md`](./backup-restore.md#restoring).
   A restore replaces the database and the files of **all** companies with the state of the backup;
   there is no restore of a single company. Everything since that backup is gone:
   - **Issued invoices and their numbers.** The number series are back at the backup's state, so
     numbers that were already sent to customers will be assigned again to other documents. Every
     company that issued documents after the backup has to be told which ones, so it can book them
     again in the same order or explain the difference to its tax adviser. The list comes from the
     safety backup that `restore.sh --apply` takes before it replaces anything, or from the evidence
     backup of step 4, loaded as described there:

     ```sql
     SELECT h.tenant_id, i.document_number, h.action_date
       FROM document_history h JOIN invoice i ON i.tenant_id = h.tenant_id AND i.id = h.invoice_id
      WHERE h.action = 'ISSUED' AND h.action_date >= TIMESTAMP '2026-01-31 03:17'   -- the restored backup
      ORDER BY h.tenant_id, h.id;
     ```
   - **Companies and accounts created since** do not exist any more; their people still have their
     account at the identity provider and start again.
   - **Purges, suspensions and removals done since** are undone. Repeat them: the purge register
     ([`saas-data-lifecycle.md`](./saas-data-lifecycle.md#the-purge-register)), the incident log
     (suspended companies, removed memberships) and removed accounts.
3. **Check:** `./check.sh`, sign in, open documents in two companies, download a PDF. `check.sh`
   also verifies that row-level security is in force on every tenant table.
4. **Reopen** what was suspended or stopped for the incident, and tell the companies that were
   informed.

### 7. Close

A short review within two weeks: timeline, cause, which companies and which data, what was done,
what was missing (evidence, access, a contact). Each gap becomes an issue; a new threat or a changed
control goes into [`../architecture/threat-model.md`](../architecture/threat-model.md), a changed
step into this page. The incident log is kept with the operator's records; the evidence copies are
deleted as decided in step 4.

## Practice

Once a year, and after a change of the persons involved, walk through one scenario on a test
deployment (the kit starts one in minutes, [`saas-deployment.md`](./saas-deployment.md)): suspend a
company, remove a membership, save the evidence, restore last night's backup, find the owners'
addresses. The restore itself is tested monthly ([`backup-restore.md`](./backup-restore.md#testing-a-backup)).

## Tested

The statements on this page and in `saas-data-lifecycle.md` were run against the schema the
migrations create (PostgreSQL 17). What the application enforces is covered by its own tests:
`SaasApiTest` suspends a company by SQL (read-only at once, the export still works), removes an
account's memberships by SQL (its next request is refused although the token is still valid) and
reopens the company; it also covers the closed company. The purge is covered by `TenantPurgeTest`,
the log by `TenantAuditTest`, backup and restore of the kit by `.github/workflows/saas-stack.yml`.
Not tested: the shell commands for saving evidence, the rotation of each secret, and the procedure
as a whole, which has not been exercised in a real incident.

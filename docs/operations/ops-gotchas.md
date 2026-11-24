# Operational Gotchas

> **Audience:** operators and self-hosters.
> **Scope:** runtime gotchas that don't fit into the
> [`deployment.md`](./deployment.md) or
> [`../reference/env-vars.md`](../reference/env-vars.md) reference.
> One section per gotcha. For the env var reference itself, see
> the linked doc.

## Database migrations

Every start brings the schema up to date **before** the server starts
(`MainApp.prepareDatabase()`):

- **PostgreSQL** (Docker, server): Flyway applies the versioned files in
  `db/migration/` (`SchemaMigrator`, under an advisory lock so several
  instances do not race). The SaaS build additionally applies
  `db/migration-saas/`. The legacy `DatabaseMigration` steps are skipped.
- **H2** (desktop): an empty database gets `scheme-h2.sql`, then the
  idempotent `DatabaseMigration` steps run on every start.

**A failing migration aborts the start.** The exception propagates out of
`MainApp.main`, so the backend exits before Spring starts and never serves
the API on a half-migrated schema. The log shows `Schema migration failed`
(PostgreSQL) or `Database migration failed` (H2) with the cause. In Docker
the backend container exits (the bundled compose files set no restart policy;
with one, it fails the same way on every restart); the desktop app shows "Der Server ist nicht
gestartet. Details stehen in backend-err.log im Programmdatenordner."

### Recovering from a failed migration

1. **Read the cause** in the backend log (`docker compose logs backend`, on the
   desktop `backend-err.log` / `app.log` in the app-data folder). Do not
   delete or edit migration files to get past the error.
2. **Either restore the backup** taken before the upgrade (see
   [Backups](#backups)) and start the previous version again, **or fix the
   cause** (for example a connection or permission problem, or data that
   violates a new constraint) and start the new version again.
   - PostgreSQL runs each Flyway migration in a transaction, so a failed file
     is rolled back as a whole and is retried on the next start.
   - The H2 steps are not one transaction: steps before the failing one stay
     applied. They are idempotent, so starting again after the fix completes
     the rest.
3. **Verify the schema** before handing the system back:
   - PostgreSQL: `SELECT version, description, success FROM
     flyway_schema_history ORDER BY installed_rank;` (also
     `flyway_schema_history_saas` in the SaaS build) must list every
     migration of the release with `success = true`, and the start log shows
     `db/migration is at version <n> (<k> migration(s) applied)`.
   - Both: the backend reaches `Started MainApp` and `GET /api/health`
     answers `UP`; then open a document and download its PDF.

Never edit an applied Flyway file: Flyway checks its checksum on every start
and refuses to start when it changed (see
[`../architecture/data-model.md`](../architecture/data-model.md#migration-system)).

The H2-only `migratePaidToPaidDate` migration
is a v1 → v2 migration that recreates the `invoice` table with only
the v1 columns (`id, customer_id, invoice_date, due_date,
total_price, tax_rate, paid_date, notes`). If it ever ran against a
v2 install it would silently drop all v2 columns
(`document_number`, `version`, `is_latest`, `version_group_id`,
etc.). It only fires when an old `paid` column exists and no
`paid_date` column exists, so on a fresh install it is a no-op.

**Back up before every upgrade** that may change the schema; it is the only
way back, because neither migration system has down-migrations.

## Frontend rebuild on backend URL change

`VITE_API_BASE_URL` is baked into the JS bundle at build time. The
default is **empty** (same origin): the SPA calls the host it was
loaded from, which is right for the standard image (the backend serves
the SPA) and for every reverse-proxy setup. Only a frontend on a
different origin than the API needs a value, and changing it requires a
rebuild. Never bake `http://localhost:8080` into a deployed bundle: in a
visitor's browser that is the visitor's own machine (Local Network
Access prompts, "Network Error", mixed content behind https).

## CORS in production

Behind a reverse proxy you need **no CORS configuration**: leave
`CORS_ALLOWED_ORIGINS` empty. What you do need is the proxy's
`X-Forwarded-Proto` / `X-Forwarded-Host` headers (Caddy and Traefik send
them by default; nginx needs `proxy_set_header`, see
[`deployment.md`](./deployment.md#behind-a-reverse-proxy)). Without them
the backend sees `http://...` while the browser sends
`Origin: https://...`, treats even the app's own requests as
cross-origin and answers **403 "Invalid CORS request"**; logo uploads
would also get `http://` URLs.

`CORS_ALLOWED_ORIGINS` is only for a frontend on another origin
(comma-separated exact origins, no wildcard). The API has no
credentials, so `CorsConfig` does not send `Allow-Credentials`.

## Slow clients: a write timeout in the proxy

Give the reverse proxy an **absolute** limit for sending one response.
Tomcat's own write timeout starts again with every byte, so a client that
reads a trickle keeps a backend thread busy for as long as it likes
(downloads: PDF, XRechnung, document export, GoBD export). The GoBD export
frees its database connection, lock and slot at its own time limit, but
not the thread. The proxy closes such a connection at its limit, and the
backend request ends with it.

- Caddy: both shipped Caddyfiles (`deploy/saas/Caddyfile`,
  `deploy/Caddyfile.example`) set `servers { timeouts { write 35m } }`.
- nginx: `send_timeout` only counts the time between two writes. Keep
  `proxy_buffering on` (the default): nginx then takes the response from
  the backend at full speed, into memory and temporary files up to
  `proxy_max_temp_file_size` (default 1 GiB, the size limit of a GoBD
  export), and a slow client only costs nginx a connection.
- Traefik: `entryPoints.<name>.transport.respondingTimeouts.writeTimeout: 35m`.

Keep the limit above `EXPORT_MAX_DURATION_MINUTES` (default 30): a large
GoBD export may take that long, and a proxy that cuts it earlier breaks
legitimate exports off.

## File uploads

`spring.servlet.multipart.max-file-size` is 2 MB. The frontend
`fileValidation.js` checks the same 2 MB before a template import. If users
report upload failures, check both the backend log and the
reverse-proxy limit (`client_max_body_size` for nginx, `request_body`
for Caddy).

In the SaaS profile each tenant has its own directory below that path (`<id>/`), and the URLs are signed; see [`saas-tenant-lifecycle.md`](./saas-tenant-lifecycle.md).

Uploaded files are stored in `FILES_PATH` (Docker: `/app/uploads`, the
`uploads` volume in the root compose; desktop: `<app-data>/files`). Back
this directory up together with the database: templates reference logos
by URL. `POST /api/upload` accepts only PNG, JPEG and GIF and checks the
magic bytes against the filename extension (the stored extension comes from
the detected content); SVG uploads are rejected. Everything under
`/uploads/**` is served with `X-Content-Type-Options: nosniff` and
`Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`
(`WebConfig`), so an SVG uploaded before this change cannot run script when
opened directly; as a logo in an `<img>` it still renders in the browser.

## Mail

Every mail opens its own SMTP connection (no pooling); mail is sent rarely.

- **System mail** (`MAIL_*`, the operator's account) sends only the SaaS invitations
  (`InvitationMailer`); in the self-host build `MAIL_*` has no effect. The settings are read once
  at startup. An invalid `MAIL_PORT` or `MAIL_SECURITY` stops the start; a wrong login or host only
  shows at the first send, as "keine E-Mail verschickt" in Einstellungen → Team & Zugriff, a
  warning in the backend log and the alert `BillerySystemMailFailing`.
- **Mail to customers** goes through each company's own accounts (Einstellungen →
  Mail-Einstellungen). Their passwords need `BILLERY_SECRETS_KEY` (`openssl rand -base64 32`); the
  desktop app creates `mail-secrets.key` next to its database instead. Keep the key stable: a new
  one makes the stored passwords unreadable (the companies enter them again; "Anmeldung abgelehnt"
  until then). Back it up with the other secrets, not with the database.
- **Outgoing ports.** Many hosting providers block outgoing SMTP (25, often 465 / 587) by default.
  Then every company's test mail fails with "nicht erreichbar" and `BilleryCompanyMailsFailing`
  fires with `reason="connect"`; ask the provider to open 465 and 587. In SaaS a company account
  may only name public servers on 25 / 465 / 587 / 2525 with encryption (`MailHostPolicy`).
- **Sent but not in the history.** Every mail to a customer writes two INFO lines to the backend
  log, without addresses: `Mail zu Dokument <id> über Mailkonto <id> versendet` once the server
  accepted it, `Versand von Dokument <id> vermerkt` once `MAIL_SENT` (and an offer's "Gesendet")
  is stored. If storing fails, the user is told not to send again, the log has an ERROR
  `… versendet, aber nicht in der Historie vermerkt` and `billery_mail_dispatch_unrecorded_total`
  raises `BilleryMailDispatchUnrecorded`. If the backend stopped between the two lines (no ERROR,
  no counter: it starts at 0 again), only the log shows it: a "versendet" line without its
  "vermerkt" line, e.g. `grep -hE 'versendet$|vermerkt$' logs/billery*.log` in the `logs` volume after a crash. Note such a
  dispatch by hand in the incident log; the document itself is complete.
- **Limits.** SaaS: `BILLERY_CUSTOMER_MAILS_PER_DAY` per company (default 200), a company's own
  value in `tenant.mail_daily_limit` ([`saas-tenant-lifecycle.md`](./saas-tenant-lifecycle.md)).

## Backups

`./deploy/saas/backup.sh --config .env` backs up the database and the
uploaded files of the root compose stack; see
[`backup-restore.md`](./backup-restore.md). The root compose requires `DB_PASSWORD`
from the runtime environment (or a protected `.env` next to it), and refuses
known development passwords. PostgreSQL applies its initial password only
when the volume is first created: changing `DB_PASSWORD` later does not
change the stored role. Rotate the role with `psql`'s hidden `\password`
prompt, then update the runtime secret and recreate the backend; see
[`deployment.md`](./deployment.md#database-credential-rotation). Never put
password values in command arguments or logs.

The desktop app keeps everything in its app-data folder (`database.mv.db`,
`files/`, `app.log`, `backend-err.log`) and backs it up itself: Einstellungen → Datensicherung.

- **When:** when the app is closed (on by default) and with "Jetzt sichern". The settings page
  shows the last backup and warns when there is none or it is older than 7 days.
- **Where:** `<app-data>/backups` unless another folder is chosen. Choose an external drive or a
  cloud-sync folder: a backup in the app-data folder does not survive the loss of the computer.
  The newest N backups are kept (10 by default); other files in the folder are never touched.
- **Encryption:** optional, with a password (at least 8 characters). The password is **not
  stored**: setting it creates an RSA key pair; the database keeps the public key and the private
  key locked with the password (PBKDF2, AES-256-GCM). Each backup is encrypted with a random
  AES-256 key wrapped with the public key, and carries the locked private key, so the backup on
  exit needs no password and the password alone opens a backup on another computer. Whoever gets
  the database file cannot open the backups. Without the password an encrypted backup cannot be
  restored.
- **Restore:** choose a backup in the list (from another computer: put the file into the backup
  folder first); it is checked (and decrypted) at once, its database is opened read-only to make
  sure H2 can read it, and restored at the **next start**. If
  documents were issued, payments recorded, documents changed or files (logos, attachments)
  uploaded or changed after the backup, the app says
  how many and needs an explicit "Trotzdem wiederherstellen". At the start the backup is unpacked
  completely first (limits: 10 000 entries, 4 GiB), then the state before is saved as
  `billery-…-vor-wiederherstellung.zip[.enc]` in the backup folder (encrypted like the backups),
  then the files are swapped, and a line is appended to `wiederherstellungen.log` in the app-data
  folder and next to the safety copy. What a restore loses is counted from the snapshot time
  stored in the backup (`backup.json`), not from the time in the file name.
  Safety copies are **never** deleted automatically (they hold the later records; GoBD,
  `verfahrensdokumentation.md` §8). A backup of an older version is brought up to date by the
  migrations of that start. "Wiederherstellung abbrechen" withdraws it before the restart.
  **Until the restart nothing is saved** (`RestorePendingFilter`: changing API requests answer 409,
  the backup settings and "Jetzt sichern" too; only choosing / withdrawing the restore and the
  shutdown pass;
  choosing the restore waits for the saves already under way, so the confirmed count is final),
  and the restart counts again what the restore takes out and logs those numbers next to the
  confirmed ones. The number counters of the replaced database are carried over where higher, so
  no document number is given twice; the numbers given after the backup remain a documented gap
  (they are in the safety copy). The snapshot time in `backup.json` is signed, together with the SHA-256 of the
  backed-up database and of its file inventory (name and SHA-256 of every upload), with the installation's key (`<app-data>/backup-signing.key`, HMAC; neither
  in the database nor in any backup); an unsigned, foreign or changed one, or one after the backup's name or after now, is not trusted
  and everything counts (a backup from a lost computer restored into a new, empty installation
  has nothing to lose anyway).
- **Access:** the backup endpoints need the window's per-launch token (`X-Desktop-Token`, from the
  Tauri shell via the URL fragment); other programs on the computer cannot use them. The rest of
  the desktop API has no authentication (it binds to 127.0.0.1 only).
- While a backup copies the database and the uploaded files, saving pauses for that moment
  (database and files are one state). A backup beyond 10 000 files or 4 GiB is refused when it
  is made (it could not be restored).
- A backup that was cut short (the computer shut down) leaves a `.part` file or a working folder
  (`.billery-backup-work-*`) in the app-data folder; the next start removes them, and only them. A file named like a backup but with an
  impossible date is ignored.

## Logging

Logback (`src/main/resources/logback.xml`) writes to the console and to
`logs/` under the working directory (Docker: `/app/logs`). Application
code logs via `java.util.logging`, which Spring Boot bridges into Logback
once the context starts; the database preparation in `MainApp` runs before
that and logs to the console only. A failed start therefore shows its cause
on stdout / `docker logs`.

**Retention:** the file logs keep 30 days, at most 1 GB (`logback.xml`); in Docker they are in the
`logs` volume. Docker keeps at most 5 × 10 MB of each container's console output (`x-logging` in
both compose files). Nothing else rotates them.

**What is in them:** request failures, stack traces of server errors, and warnings such as a
failed mail or readiness check. Code must not log passwords, tokens, document contents or e-mail
addresses; where an exception may carry an address (SMTP), only a redacted summary is logged
(`InvitationMailer.failureSummary`). Treat the logs as confidential anyway: they can still name
customers in error messages. Only the operator of the host reads them.

## Health checks, monitoring and resources

- **`/api/health`** is the liveness check: the process answers. It never touches the database, and
  the desktop shell uses it while the backend starts.
- **`/api/health/ready`** is the readiness check: a pooled database connection is valid. It answers
  `503 {"status":"DOWN"}` within 3 seconds when the database is gone and logs the cause. The Docker
  health checks of both compose files use it. Docker does not restart an unhealthy container, so a
  database outage marks the backend unhealthy without a restart loop, and it turns healthy again
  when the database is back.
- **Alerting:** point an external uptime monitor (for example Uptime Kuma or healthchecks.io) at
  `https://<host>/api/health/ready` and alert after two failed checks. That covers an outage of the
  application, the database, TLS or DNS. Let the same monitor warn about the TLS certificate's
  expiry (most can; `check.sh` warns below 14 days too): the proxy renews it itself, and a failed
  renewal otherwise shows only when the certificate has expired.
- **Identity provider:** a second uptime check on the issuer's discovery URL
  (`<issuer>/.well-known/openid-configuration`). When the provider is down nobody can sign in,
  while `/api/health/ready` still answers 200: the backend itself is fine.
- **Heartbeats (dead man's switches)** for what must happen regularly; each reports success, and
  `/fail` appended on failure, so a check at healthchecks.io or similar also notices a run that
  never happened:
  - the nightly backup: `BACKUP_HEALTHCHECK_URL` (`backup.sh`; [`backup-restore.md`](./backup-restore.md));
  - the monthly restore test: `RESTORE_TEST_HEALTHCHECK_URL` (`restore.sh` in test mode, period
    about 35 days); `check.sh` also warns when `.last-restore-test` in the backup folder is older
    than `RESTORE_TEST_MAX_AGE_DAYS` (35);
  - the alerting itself: the alert `BilleryWatchdog` always fires; route it to a dead man's switch
    (`deploy/saas/monitoring/alertmanager.example.yml`), so a stopped Prometheus or Alertmanager is
    noticed.
  The monthly purge check of closed companies has no job to report: `check.sh` warns about closed
  companies past their export window (`PURGE_AFTER_DAYS`, 90) that are not purged yet
  ([`saas-data-lifecycle.md`](./saas-data-lifecycle.md)).
- **Disk space:** a full disk stops the database, uploads, logs and backups. The backend reports
  the free space of its own volumes (`disk_free_bytes` / `disk_total_bytes` by `path`: the working
  directory, `logs`, the uploads; `management.metrics.system.diskspace.paths`), alerts
  `BilleryDiskSpaceLow` (below 15 %) and `BilleryDiskSpaceCritical` (below 5 %). The database
  volume and the local backups are outside the backend: run node_exporter on the host for the
  rule `HostDiskSpaceLow`, and `check.sh` checks the backup folder, the uploads, the logs and the
  kit's database volume (warning below `DISK_MIN_FREE_PERCENT`, 15, failure below 5).
- **Metrics:** with `BILLERY_METRICS_PORT` (for example `9464`) the backend serves Prometheus
  metrics at `http://<backend container>:<port>/prometheus`. That is a port of its own and is not
  published by the compose files: only containers on the stack's network reach it, the
  application's port never serves metrics. The metrics are requests by URI pattern, status and
  latency (`http_server_requests_seconds`, with buckets), the database pool (`hikaricp_*`), JVM
  memory and garbage collection, CPU, free disk space of the backend's volumes, the failures of
  the store for uploaded files (`billery_files_store_errors_total{operation}`, SaaS: the uploads
  directory or the bucket) and the refusals of the SaaS abuse limits
  (`billery_quota_refused_total{quota, scope}`). They carry no business data: a URI is the pattern
  (`/api/invoices/{id}`), never an id. `deploy/saas/monitoring/alerts.yml` has the always-firing
  `BilleryWatchdog` and alerts on a missing scrape, more than 5 % server errors, a slow API, a
  waiting database pool, a full heap, low disk space, failures of the file store, a reached
  system-wide sign-up or invitation mail limit and many refusals per source, plus
  `HostDiskSpaceLow` for node_exporter; `alerts.test.yml` tests the rules (`promtool test rules`,
  also in CI); `prometheus.example.yml` shows the scrape and Alertmanager config,
  `alertmanager.example.yml` the routing (watchdog to the dead man's switch, the rest to you;
  checked with `amtool` in CI). Route the alerts through your Alertmanager or Grafana. A request
  to another path on the metrics port gets the application's public start page, never another
  Actuator endpoint.
- **Resources:** both compose files limit memory (`BACKEND_MEMORY_LIMIT`, default `1g`;
  `DB_MEMORY_LIMIT`, default `512m`), the backend's CPUs (`BACKEND_CPUS`, default `2`) and the number
  of processes. The Java heap takes 75 % of the backend's limit (`-XX:MaxRAMPercentage=75` in
  `backend/Dockerfile`). Measured: about 220 MB idle and 350 MB after a full GoBD export of 1 400
  documents. Raise the limit if the backend is killed for memory (`docker inspect` shows
  `OOMKilled`).
- **GoBD export and the database:** a running export reads in one read-only transaction (one
  consistent state) and holds one pooled connection for up to `EXPORT_MAX_DURATION_MINUTES`
  (default 30), at most `EXPORT_MAX_CONCURRENT` (default 2) per instance. Meanwhile PostgreSQL's
  `VACUUM` cannot remove rows that changed after the export began. Do not set
  `idle_in_transaction_session_timeout` or `statement_timeout` for the application's role below
  that limit, or exports break off. One export per company is **produced** at a time across all
  instances (PostgreSQL advisory lock, held while the export reads), so several instances need no
  extra coordination; the download of a finished export no longer holds the lock (it is bounded by
  the proxy's write timeout, see *Slow clients* above).
- **Hardening:** the backend runs with a read-only root filesystem (writable: the `uploads` and
  `logs` volumes and a 256 MB tmpfs at `/tmp`), without any Linux capability and with
  `no-new-privileges`. PostgreSQL keeps only the capabilities it needs for its data directory
  (`CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `SETGID`, `SETUID`).

## JVM headless flag

`Dockerfile` sets `java.awt.headless=true`. The flag prevents AWT
from trying to open a display. This is sufficient for the current
backend (Spring Boot, Flying Saucer PDF rendering, OpenPDF) — none
of the runtime libraries require a display.

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

`SmtpMailProvider` opens a new SMTP connection per send (no pooling).
For high-volume use, this is a small efficiency hit.

Only the SaaS invitations send mail (`InvitationMailer`, see
[`../architecture/document-pipeline.md`](../architecture/document-pipeline.md)); the self-host
build sends nothing, so `MAIL_*` has no effect there. The settings are read once at startup. An
invalid `MAIL_PORT` or `MAIL_SECURITY` stops the start; a wrong login or host only shows at the
first send, as "keine E-Mail verschickt" in Einstellungen → Team & Zugriff and a warning in the
backend log.

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
- **Encryption:** optional, with a password (at least 8 characters; AES-256-GCM, key from
  PBKDF2). Without the password an encrypted backup cannot be restored; it is stored in the
  local database so the automatic backup can use it, and never shown again.
- **Restore:** choose a backup in the list (from another computer: put the file into the backup
  folder first); it is checked (and decrypted) at once and restored at the **next start**. If
  documents were issued, payments recorded or documents changed after the backup, the app says
  how many and needs an explicit "Trotzdem wiederherstellen". At the start the backup is unpacked
  completely first (limits: 10 000 entries, 4 GiB), then the state before is saved as
  `billery-…-vor-wiederherstellung.zip[.enc]` in the backup folder (encrypted like the backups),
  then the files are swapped, and a line is appended to `wiederherstellungen.log` in the app-data
  folder and next to the safety copy. What a restore loses is counted from the snapshot time
  stored in the backup (`backup.json`), not from the time in the file name.
  Safety copies are **never** deleted automatically (they hold the later records; GoBD,
  `verfahrensdokumentation.md` §8). A backup of an older version is brought up to date by the
  migrations of that start. "Wiederherstellung abbrechen" withdraws it before the restart.
- **Access:** the backup endpoints need the window's per-launch token (`X-Desktop-Token`, from the
  Tauri shell via the URL fragment); other programs on the computer cannot use them. The rest of
  the desktop API has no authentication (it binds to 127.0.0.1 only).
- A backup that was cut short (the computer shut down) leaves a `.part` file or a working folder
  in the app-data folder; the next start removes them. A file named like a backup but with an
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
  application, the database, TLS or DNS.
- **Metrics:** with `BILLERY_METRICS_PORT` (for example `9464`) the backend serves Prometheus
  metrics at `http://<backend container>:<port>/prometheus`. That is a port of its own and is not
  published by the compose files: only containers on the stack's network reach it, the
  application's port never serves metrics. The metrics are requests by URI pattern, status and
  latency (`http_server_requests_seconds`, with buckets), the database pool (`hikaricp_*`), JVM
  memory and garbage collection, CPU, and the refusals of the SaaS abuse limits
  (`billery_quota_refused_total{quota, scope}`). They carry no business data: a URI is the pattern
  (`/api/invoices/{id}`), never an id. `deploy/saas/monitoring/alerts.yml` alerts on a missing
  scrape, more than 5 % server errors, a slow API, a waiting database pool, a full heap, a
  reached system-wide sign-up or invitation mail limit and many refusals per source;
  `alerts.test.yml` tests the rules (`promtool test rules`, also in CI); `prometheus.example.yml`
  shows the scrape config. Route the alerts through your Alertmanager or Grafana. A request
  to another path on the metrics port gets the application's public start page, never another
  Actuator endpoint.
- **Resources:** both compose files limit memory (`BACKEND_MEMORY_LIMIT`, default `1g`;
  `DB_MEMORY_LIMIT`, default `512m`), the backend's CPUs (`BACKEND_CPUS`, default `2`) and the number
  of processes. The Java heap takes 75 % of the backend's limit (`-XX:MaxRAMPercentage=75` in
  `backend/Dockerfile`). Measured: about 220 MB idle and 350 MB after a full GoBD export of 1 400
  documents. Raise the limit if the backend is killed for memory (`docker inspect` shows
  `OOMKilled`).
- **Hardening:** the backend runs with a read-only root filesystem (writable: the `uploads` and
  `logs` volumes and a 256 MB tmpfs at `/tmp`), without any Linux capability and with
  `no-new-privileges`. PostgreSQL keeps only the capabilities it needs for its data directory
  (`CHOWN`, `DAC_OVERRIDE`, `FOWNER`, `SETGID`, `SETUID`).

## JVM headless flag

`Dockerfile` sets `java.awt.headless=true`. The flag prevents AWT
from trying to open a display. This is sufficient for the current
backend (Spring Boot, Flying Saucer PDF rendering, OpenPDF) — none
of the runtime libraries require a display.

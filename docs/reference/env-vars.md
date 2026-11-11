# Environment Variables

> **Audience:** operators and self-hosters.
> **Scope:** every environment variable the system reads, with
> defaults, the layer that consumes it, and the env file to put it
> in. For how the variables are used at runtime see
> [`../operations/deployment.md`](../operations/deployment.md).

The Docker / production deployment reads everything from environment
variables. The local dev mode additionally loads `backend/.env`
via `java-dotenv` (see `config/AppConfig.java`).

## Database

| Variable | Default | Notes |
|----------|---------|-------|
| `DB_URL` | — | Full JDBC URL override. It replaces the host / port / database components; PostgreSQL still requires `DB_USER` / `DB_PASSWORD` separately. Used by the H2 `desktop` path (when `MainApp` detects a `jdbc:h2:` URL and runs `scheme-h2.sql` from `main/resources`) and by the test suite via `TestDatabaseConfig` (`jdbc:h2:mem:testdb;MODE=MariaDB`). Not the normal production path — production is PostgreSQL. |
| `DB_HOST` | `localhost` | PostgreSQL host. Set to `postgres` in Docker Compose. |
| `DB_PORT` | `5432` | |
| `DB_NAME` | `billery` | |
| `DB_USER` | *(required for PostgreSQL outside explicit `dev` profile)* | PostgreSQL login; desktop H2 does not use it. |
| `DB_PASSWORD` | *(required for PostgreSQL outside explicit `dev` profile)* | Supplied from the runtime environment or local `.env`; known development passwords are rejected outside `dev`. Desktop H2 does not use it. Never include this value in logs or image build arguments. |
| `BILLERY_SECURITY_ISSUERS` | *(required with the `saas` profile)* | Comma-separated issuer URLs of the OpenID providers whose bearer tokens the backend accepts (the OIDC discovery document must be reachable). Listing two is how the provider is exchanged. |
| `BILLERY_FILES_SECRET` | *(required with the `saas` profile)* | At least 32 characters; signs the URLs of uploaded files (`/uploads/<tenant>/<file>?sig=...`). Keep it stable (rotating it invalidates every stored logo URL) and secret. |
| `BILLERY_FILES_DIR` | `FILES_PATH` | SaaS with `BILLERY_FILES_STORE=local`: base directory of the per-tenant upload directories. |
| `BILLERY_FILES_STORE` | `local` | SaaS: where uploads are kept. `local`: a directory (`BILLERY_FILES_DIR`), for one backend instance or several on a shared volume. `s3`: a private bucket of an S3-compatible store (AWS S3, Supabase Storage, SeaweedFS, MinIO, ...), key `<tenant id>/<file>`, for several instances without a shared disk. Browsers never talk to the bucket: they load the signed `/uploads/...` URL from the backend. Any other value stops the start. |
| `BILLERY_FILES_S3_BUCKET` | *(required with `s3`)* | The bucket. Keep it private. |
| `BILLERY_FILES_S3_ENDPOINT` | *(empty = AWS S3)* | Endpoint of an S3-compatible service, e.g. `https://<project>.supabase.co/storage/v1/s3`. |
| `BILLERY_FILES_S3_REGION` | `us-east-1` | Region; most S3-compatible services accept any, Supabase wants the project's region. |
| `BILLERY_FILES_S3_ACCESS_KEY`, `BILLERY_FILES_S3_SECRET_KEY` | *(empty = AWS default credentials)* | Access key of the bucket; set both or neither. A secret: only in the runtime environment. |
| `BILLERY_FILES_S3_PATH_STYLE` | `true` | `<endpoint>/<bucket>/<key>` addressing, which most S3-compatible services need; `false` for virtual-host style. |
| `BILLERY_OIDC_CLIENT_ID` | *(required with the `saas` profile)* | The single-page app's client id at the identity provider (a public client, authorization-code flow with PKCE, redirect URI `<origin>/auth/callback`). Served to the app at `GET /api/config`. |
| `BILLERY_OIDC_ISSUER` | *(first of `BILLERY_SECURITY_ISSUERS`)* | The provider the app signs in at. |
| `BILLERY_OIDC_SCOPE` | `openid profile email offline_access` | Requested scopes; `offline_access` lets the provider issue refresh tokens for the silent renewal. |
| `BILLERY_SECURITY_AUDIENCE` | *(required in the `saas` profile)* | The audience (`aud`) a token must carry; the start fails without it. |
| `DB_MIGRATION_USER`, `DB_MIGRATION_PASSWORD` | *(unset)* | Optional pair for SaaS: the schema owner Flyway runs as, when `DB_USER` is the restricted application role (see [`deployment.md`](../operations/deployment.md#saas-database-roles)). Unset = migrations use `DB_USER`, as in self-host. |

`Database.reloadFromEnv()` builds the JDBC URL from these variables on
class load; `DbConfig` creates the HikariCP pool from the same settings.
Changing them requires a backend restart.

## Metrics

| Variable | Default | Purpose |
|----------|---------|---------|
| `BILLERY_METRICS_PORT` | *(off)* | Port of the Prometheus endpoint `/prometheus` (Spring Boot Actuator, its own port, never the application's). Only this endpoint exists. Unset or `-1`: no further port. The application's port is refused at startup (`MetricsPortGuard`): the metrics would be public behind the proxy. The compose files pass it but do not publish it. Alert rules: `deploy/saas/monitoring/`. |

## Request limits (SaaS)

Per tenant and backend instance (`RequestLimits`, `RequestLimitFilter`); a request without a tenant
counts for its user. A refused request gets 429 with `Retry-After` and a message the app shows.

| Variable | Default | Purpose |
|----------|---------|---------|
| `BILLERY_LIMITS_REQUESTS_PER_SECOND` | `20` | API requests per second and tenant, refilled continuously. `0` switches the rate limit off. |
| `BILLERY_LIMITS_BURST` | `200` | The most requests a tenant may make at once (a page that loads several lists, a quick series of clicks). |
| `BILLERY_LIMITS_HEAVY_PER_TENANT` | `2` | Concurrent PDF, preview, XRechnung, export and import requests per tenant. `0` switches it off. The GoBD export also keeps its own instance-wide limit (`EXPORT_MAX_CONCURRENT`). |

With several instances each one counts for itself, so the effective limit grows with them.
The limiter's memory is bounded, however many tenants there are: idle tenants take none (a full
bucket is dropped, a slot only exists while its request runs), and above 100 000 tenants active at
the same moment all buckets are dropped rather than kept (everyone starts with a full burst again).

## CORS

| Variable | Default | Notes |
|----------|---------|-------|
| `SPRING_PROFILES_ACTIVE` | *(empty)* | `saas` switches on the multi-tenant SaaS behaviour (SaaS migrations, security) and needs a JAR built with `mvn -Psaas`; without that code the start fails. `desktop` is set by the Tauri shell. Both may be listed, comma-separated. |
| `CORS_ALLOWED_ORIGINS` | *(empty)* = same origin only | Comma-separated exact origins (`https://app.example.com`, no path, no wildcard) of a frontend served from **another** origin than the API. Not needed behind a reverse proxy or with the bundled SPA. The proxy must forward `X-Forwarded-Proto` / `-Host` (`server.forward-headers-strategy=framework`), otherwise same-origin requests get 403. The Spring `desktop` profile (`application-desktop.properties`) extends this to include `tauri://localhost`; the profile must be activated explicitly via `spring.profiles.active=desktop` (or `SPRING_PROFILES_ACTIVE=desktop`). The Maven `desktop` profile is **not** the same thing — it only widens the H2 dependency scope. |

## Frontend build

| Variable | Default | Notes |
|----------|---------|-------|
| `VITE_API_BASE_URL` | *(empty)* = same origin | Baked into the JS bundle at build time (Docker: build arg). Empty → `window.location.origin` (see `src/services/baseService.js`), which is what the backend image, the single-JAR deploy and every reverse-proxy setup need. Set it only for an API on another origin (and add that frontend origin to `CORS_ALLOWED_ORIGINS`). |

## Docker Compose (root `docker-compose.yml`, read from `.env`)

Copy [`.env.example`](../../.env.example) to `.env`.

| Variable | Default | Notes |
|----------|---------|-------|
| `DB_PASSWORD` | *(required)* | PostgreSQL password of the `billery` user; Compose rejects missing or empty values, and backend startup rejects known development defaults. |
| `BIND_ADDRESS` | `127.0.0.1` | Host interface the backend (and the optional frontend) is published on. Loopback = only a proxy on the host can reach it. `0.0.0.0` = all interfaces. |
| `APP_PORT` | `8080` | Published port of the backend (SPA + API). |
| `CORS_ALLOWED_ORIGINS` | *(empty)* | Passed to the backend, see above. |
| `VITE_API_BASE_URL`, `FRONTEND_PORT` | *(empty)*, `5173` | Only for the optional `split` profile (SPA in its own container). |

## Mail (SMTP)

Environment variables (Docker, the SaaS kit's `.env`) or, for local development, `backend/.env`.
Spring maps `MAIL_HOST` to `mail.host` and so on (`mail.MailSettings`); `SmtpMailProvider` sends
through Simple Java Mail. **Mail is optional:** without `MAIL_HOST` and `MAIL_FROM` nothing is sent
and nothing fails. The SaaS invitation mail also needs `BILLERY_PUBLIC_URL`. Today only the SaaS invitations send mail (`InvitationMailer`).

| Variable | Default | Notes |
|----------|---------|-------|
| `BILLERY_PUBLIC_URL` | — | SaaS: the address users open, for example `https://app.example.com`; the invitation mail links to it (never to the request's host). Empty: no invitation mail. Not a plain `https` URL (`http` only for `localhost`, `127.0.0.1`, `[::1]`): the start fails. |
| `BILLERY_INVITATION_MAILS_PER_DAY` | `1000` | SaaS: emergency brake, how many invitation mails the whole system sends in 24 hours. When reached the backend logs an error; invitations are still stored. Below 1: the start fails. |
| `BILLERY_INVITATION_MAILS_PER_IP` | `100` | SaaS: invitation mails from one source IP in 24 hours (many accounts behind one source). `0` = off. |
| `BILLERY_MAX_COMPANIES_PER_USER` | `5` | SaaS: companies one user may own; closed ones do not count. `0` = off. Above it, sign-up answers 409. |
| `BILLERY_SIGNUPS_PER_USER_PER_DAY` | `3` | SaaS: companies one user may create in 24 hours. `0` = off. Above it: 429. |
| `BILLERY_SIGNUPS_PER_IP` | `10` | SaaS: companies created from one source IP in 24 hours. The source is the first address of `X-Forwarded-For`, which the proxy must overwrite (`docs/operations/saas-tenant-lifecycle.md`). `0` = off. |
| `BILLERY_SIGNUPS_PER_DAY` | `100` | SaaS: emergency brake, companies created in the whole system in 24 hours; logged as error and raises the alert `BilleryAbuseLimitReached`. `0` = off. |
| `MAIL_HOST` | — | SMTP server. Empty: mail is off. |
| `MAIL_PORT` | usual port of `MAIL_SECURITY` | `587` for `starttls`, `465` for `ssl`, `25` for `none`; empty = that default. Not a number: the start fails. |
| `MAIL_USERNAME` | — | Login; empty for a server without authentication. |
| `MAIL_PASSWORD` | — | Password of the login. |
| `MAIL_FROM` | — | Sender address. Empty: mail is off. |
| `MAIL_SECURITY` | `starttls` | `starttls`, `ssl` or `none` (only for a server on the same host or in tests; together with `MAIL_USERNAME` or `MAIL_PASSWORD` the start fails, the login would travel unencrypted). Anything else stops the start with the value named. |

One delivery may take 15 s (connect, TLS, send) before it counts as failed. Settings are read at
startup; restart the backend after a change.

## GoBD export limits

Read by `GobdExportService.configureLimits` from the `export.*` properties of `application.properties`.
One export builds its whole ZIP in memory, so these bound heap and CPU per request. An export that
would exceed a limit is refused with HTTP 413 and a message naming the limit (never truncated); a
second export while all slots are taken gets HTTP 429 with `Retry-After`. Raise a limit only
together with the JVM heap.

| Variable | Default | Notes |
|----------|---------|-------|
| `EXPORT_MAX_PERIOD_DAYS` | `3660` | Longest allowed span between `from` and `to`, in days. |
| `EXPORT_MAX_DOCUMENTS` | `10000` | Most documents (issued invoices and credit notes) in one export. |
| `EXPORT_MAX_DOCUMENTS_WITH_FILES` | `2000` | Most documents when the PDF and XRechnung of each are rendered too (`documents=true`); rendering is the expensive part. |
| `EXPORT_MAX_OUTPUT_BYTES` | `134217728` (128 MB) | Largest ZIP held in memory; the export aborts as soon as it grows beyond it. |
| `EXPORT_MAX_CONCURRENT` | `1` | Exports allowed to run at the same time per instance. |

## Files and startup

Read by `config/AppConfig.java` and `data/DatabaseInitializer.java`.

| Variable | Default | Notes |
|----------|---------|-------|
| `FILES_PATH` | `./uploads` (relative to the JVM working directory) | Upload directory (logos), served under `/uploads/**`. The PDF renderer reads logos from here directly (it loads no other files and nothing from the network). The Docker image sets `/app/uploads` (mount the `uploads` volume); the desktop shell sets `<app-data>/files`. |
| `LOAD_DEMO_DATA` | `false` | `true` loads `dummies.sql` (100 customers, 1 420 documents, number-series counters) into a database without customers on startup. Development only: never set it for a real install. |

## Desktop shell (set by `app-desktop/src/lib.rs`, not by hand)

| Variable | Notes |
|----------|-------|
| `SPRING_PROFILES_ACTIVE=desktop` | Binds the server to `127.0.0.1` and enables `POST /api/desktop/shutdown`. |
| `DB_URL` | `jdbc:h2:file:<app-data>/database;MODE=MariaDB` |
| `DESKTOP_INSTANCE_ID` | Random per launch; echoed by `GET /api/health` so the shell can tell its own backend from a foreign server on port 8080. |
| `DESKTOP_SHUTDOWN_TOKEN` | Random per launch; required as `X-Shutdown-Token` by `POST /api/desktop/shutdown`. |

# SaaS: deployment on a server

> **Audience:** operators of a SaaS deployment (Spring profile `saas`).
> **Scope:** run the multi-tenant application on one server with Docker Compose, TLS and an
> identity provider you already have. The files are in [`deploy/saas/`](../../deploy/saas/). For
> the design see [`../architecture/multi-tenancy.md`](../architecture/multi-tenancy.md), for the
> provider settings [`saas-identity-provider.md`](./saas-identity-provider.md), for tenants
> [`saas-tenant-lifecycle.md`](./saas-tenant-lifecycle.md), for what happens to customer data
> (access, export, deletion) [`saas-data-lifecycle.md`](./saas-data-lifecycle.md), for security
> incidents [`saas-incident-response.md`](./saas-incident-response.md).

## What runs where

```
 browser ──HTTPS──► Caddy (optional) ──► billery-saas  ──► PostgreSQL
                                           │  (SPA + API)
                                           └─ HTTPS ──► your identity provider (external)
```

| Part | What | In `deploy/saas` |
|------|------|------------------|
| Application | the image `ghcr.io/billery-app/billery-saas` (SPA and API, one origin, profile `saas` built in) | `docker-compose.yml`, service `backend` |
| Database | PostgreSQL 17, three roles (below) | service `postgres`, `postgres/init/10-roles.sh` |
| TLS, proxy | Caddy with automatic certificates; skip it if a proxy on the host terminates TLS | service `caddy`, profile `proxy` |
| Identity provider | **any OpenID Connect provider, not part of the kit**: three settings in `.env` | `.env.example` |

The application stores no passwords. It knows the provider through `BILLERY_SECURITY_ISSUERS`,
`BILLERY_SECURITY_AUDIENCE` and `BILLERY_OIDC_CLIENT_ID`, so the provider can be exchanged
([`saas-identity-provider.md`](./saas-identity-provider.md)); where it runs does not matter, only that the
**backend container can reach its issuer URL over HTTPS**.

**Database roles** (created once by `10-roles.sh` when the empty database is initialised):

| Role | Used by | Attributes |
|------|---------|------------|
| `billery_admin` | the init script and the backup | bootstrap superuser, never the application |
| `billery_owner` | Flyway at start (`DB_MIGRATION_USER`) | owns the tables, no superuser, `BYPASSRLS` (row-level security is forced even on the owner) |
| `billery_login` | the running application (`DB_USER`) | member of `billery_app`, no superuser, **no** `BYPASSRLS`: row-level security binds it |

## What you need

- A server with Docker and Docker Compose v2, and ports 80 and 443 reachable (for the optional Caddy).
- A DNS name (`A`/`AAAA` record) pointing to the server.
- An OpenID Connect provider and the right to create an application there ([step 1](#1-register-the-app-at-the-provider)).
- If the image package is private: a GitHub token with `read:packages` to pull it (`docker login ghcr.io`).
- A release of the image: the tag `v<version>` of the repository ([`supply-chain.md`](./supply-chain.md)).

## 1. Register the app at the provider

Create one **public client** (single-page app) with:

| Setting | Value |
|---------|-------|
| Flow | authorization code with **PKCE**, no client secret |
| Redirect URI | `https://<your host>/auth/callback` (exactly) |
| Post-logout redirect URI | `https://<your host>/` |
| Scopes | `openid profile email offline_access` (the last one gives the silent renewal its refresh tokens) |
| Access token | a **JWT**, signed with an asymmetric key (RS256); `iss`, `sub`, `aud`, `exp`, **`email` and `email_verified` in the access token** |
| Audience | the value you will put into `BILLERY_SECURITY_AUDIENCE` (often the client id) |
| Users | created by you, with a **verified** e-mail address (the first sign-in is refused otherwise) |

Note three values for `.env`: the **issuer URL exactly as the provider writes it in `iss`** (a trailing slash
counts), the audience and the client id. Provider specifics are in the [appendix](#appendix-provider-notes).

## 2. Put the kit on the server

Only the folder `deploy/saas/` is needed; the image comes from the registry.

```bash
# on the server, for example:
git clone --depth 1 --filter=blob:none --sparse https://github.com/billery-app/billery.git /opt/billery-src
git -C /opt/billery-src sparse-checkout set deploy/saas
cp -r /opt/billery-src/deploy/saas /opt/billery-saas && cd /opt/billery-saas
# or copy the folder from your machine: rsync -a deploy/saas/ server:/opt/billery-saas/
```

## 3. Configure

```bash
./generate-secrets.sh      # creates .env (mode 600) with fresh database passwords and the file URL secret
$EDITOR .env               # BILLERY_VERSION, BILLERY_DOMAIN and the three provider values
```

The generator refuses to overwrite an existing `.env`. **The database passwords are fixed when the database
is first initialised** (the init script reads them once); changing them in `.env` afterwards does not change
the roles, see [Operating](#operating).

## 4. Start

```bash
echo "$GITHUB_TOKEN" | docker login ghcr.io -u <your github user> --password-stdin
docker compose --profile proxy up -d          # without --profile proxy: your own proxy to 127.0.0.1:8080
docker compose logs -f backend                # Flyway migrates, then "Started MainApp"
```

The first start creates the schema; the application is healthy after about a minute.

## 5. Check

```bash
./check.sh
```

It prints `ok`, `warn` and `FAIL` lines and exits non-zero on a failure. It checks the containers, the
database roles, that row-level security binds the application role and that every table with a `tenant_id` has it enabled, forced and a policy, that the **backend** can fetch the
provider's discovery document and keys and that the issuer string matches exactly, and the public URL: health,
mode `saas`, **no data without a token (401)**, no open API documentation, the security headers, HTTPS.

To check what the provider puts into its tokens, take an **access token** (browser developer tools, network
tab, the `Authorization: Bearer …` header of any `/api` request after you signed in) and run
`BILLERY_TOKEN=<token> ./check.sh` (or `./check.sh --token-stdin < file`; not as an argument, which every
user of the host can read in the process list): it shows `iss`, `aud`, `exp`, `email`, `email_verified` and what
`GET /api/me` answers, with the reason from the server if it refuses. It does not print the answer's body.
From another machine: `./check.sh --url https://<host> --no-docker`.

## 6. First use

Open `https://<your host>`, sign in at the provider, and create a company ("Firma anlegen"): the first user of
a company is its owner. Invite colleagues by e-mail address (they sign in with that **verified** address and
accept). The roles and what they may do: [`../architecture/multi-tenancy.md`](../architecture/multi-tenancy.md).
Closing and removing a company: [`saas-tenant-lifecycle.md`](./saas-tenant-lifecycle.md).

## Operating

**Update.** Take a backup first (below), set `BILLERY_VERSION` to the new release, then
`docker compose pull && docker compose up -d`. The schema migration runs at start; migrations only go forward,
so going back to an older image after a migration means restoring the backup.

**Backup and restore.** `./backup.sh` backs up the database and the uploaded files, wherever they are (the container of this kit or an external PostgreSQL). Encryption and a copy off the server are switched on with the `BACKUP_*` settings. `./restore.sh` tests a backup in a throwaway database or restores it. Setup, schedule, key handling and the recovery procedure: [`backup-restore.md`](./backup-restore.md).

**Secrets.**
- `BILLERY_FILES_SECRET`: rotating it invalidates the URLs of uploaded files stored in templates.
- `BILLERY_FILES_STORE`: `local` keeps the uploads in the `uploads` volume, which is enough for one backend instance. Running several instances without a shared disk needs `s3` and the `BILLERY_FILES_S3_*` settings (a private bucket of any S3-compatible store, [`env-vars.md`](../reference/env-vars.md)); `check.sh` checks them and `backup.sh` backs the bucket up. Switching an existing installation copies the files first, keeping the keys: `<upload dir>/<tenant>/<file>` becomes the object `<tenant>/<file>` (for example `rclone copy /app/uploads <remote>:<bucket>`); the stored URLs stay valid.
- Database passwords: `ALTER ROLE billery_login PASSWORD '…'` (and `billery_owner`) in `psql` as `billery_admin`,
  then the same value in `.env`, then `docker compose up -d`. Editing `.env` alone changes nothing in the database.

**Logs.** `docker compose logs backend` (rotated, 5 × 10 MB).

**Exchanging the provider**: [`saas-identity-provider.md`](./saas-identity-provider.md).

**Customer data and incidents.** Before the first customer, fill in the
[deployment record](./saas-data-lifecycle.md#deployment-record) (who the operator is, where the
data is, which periods you promise) and name the people for the roles in
[`saas-incident-response.md`](./saas-incident-response.md#roles). Closed companies are purged by
hand after their export window; put the monthly check into your calendar.

## What this setup does not have yet

One server is one point of failure. Monitoring is an external uptime check of `/api/health/ready` plus, optionally, Prometheus metrics with
ready-made alert rules (`BILLERY_METRICS_PORT`, `monitoring/`; see
[`ops-gotchas.md`](./ops-gotchas.md#health-checks-monitoring-and-resources)). There are no
tariffs, trial period or billing; per-tenant request limits exist (`BILLERY_LIMITS_*`, see `docs/reference/env-vars.md`). The kit's
Caddy writes no access log, and the application does not record sign-ins, reads or exports (see
[`saas-incident-response.md`](./saas-incident-response.md#4-save-evidence)). `check.sh` and the automated run in CI
(`.github/workflows/saas-stack.yml`) prove the kit works against a mock provider; **the first run against
your real provider and server is the real test**, and what it shows belongs in this page.

## Troubleshooting

| Symptom | Likely cause | Check |
|---------|--------------|-------|
| The application does not start: "the profile 'saas' is not active" | the environment sets `SPRING_PROFILES_ACTIVE` to something else | remove it from `.env` and the compose file; the image has `saas` built in |
| It does not start: "audience" or "issuers" | `BILLERY_SECURITY_AUDIENCE` or `_ISSUERS` empty | `./check.sh` (configuration) |
| Sign-in works at the provider, the app then shows no tenant or an error | the access token lacks `email` or `email_verified`, or the `aud` does not match | `BILLERY_TOKEN=… ./check.sh` |
| `401` on every call after sign-in | `iss` differs from the configured issuer (a trailing slash), wrong audience, expired, or the token is opaque (not a JWT) | `BILLERY_TOKEN=… ./check.sh`; the `WWW-Authenticate` header names the reason |
| `check.sh`: discovery cannot be fetched | the container cannot resolve or reach the provider's URL (DNS, firewall, private CA) | `docker exec billery-saas-backend wget -O - <issuer>/.well-known/openid-configuration` |
| `403` "You are not a member of this tenant" | the `X-Tenant-Id` names a company the user is not in; the SPA re-reads the tenants | sign out and in; check `GET /api/me` |
| The browser blocks the PDF preview or images | a proxy changed or removed the security headers | compare `curl -I https://<host>/` with the application's headers |
| Caddy gets no certificate | DNS does not point here, or ports 80/443 are closed | `docker compose logs caddy` |

## Appendix: provider notes

The application only needs the values above, so the kit has nothing provider-specific. What differs is
where you click. Whatever the notes say, **`check.sh` with a token is the authority**: provider versions change.

**authentik**
- Create a provider *OAuth2/OpenID Provider*, **client type public**, and an application with a slug. The
  issuer is `https://<authentik host>/application/o/<slug>/` **with the trailing slash**.
- Redirect URIs: strict match `https://<your host>/auth/callback`.
- **Signing key:** choose an RSA certificate. Without one authentik signs with the client secret (HS256), which
  this application cannot validate.
- **Subject mode:** the hashed user id (the default) or the user id; not the e-mail or the user name, so the
  identity survives a change of address.
- **Scopes:** `openid`, `email`, `profile` and `offline_access`; check the *scope mappings*.
- **Audience:** authentik uses the client id; set `BILLERY_SECURITY_AUDIENCE` to it.
- authentik reports `email_verified` as `true` in its default scope mapping whether or not the address was
  verified. With users created by you that is accurate; if you ever open registration, add an e-mail
  verification stage to the enrollment flow.
- Whether `email` and `email_verified` end up in the **access** token depends on the version and the
  mappings: `check.sh` with a token tells.

**Keycloak**: realm → client with *Client authentication off* and *Standard flow*, *PKCE method S256*,
a mapper for the audience (audience mapper, included in the access token), the `email` scope. The issuer is
`https://<host>/realms/<realm>`.

**Auth0, Entra ID, others**: a "single-page application" client with PKCE, an API (audience) so the access
token is a JWT, and the e-mail claim added to the access token (a custom claim in Auth0, an optional claim in
Entra). The issuer of Auth0 ends with a slash.

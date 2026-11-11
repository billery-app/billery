# Billery Documentation

Developer documentation for **Billery**, a self-hosted
German invoice management system with a block-based template editor.

> Looking to *use* the system? See the [project README](../README.md)
> and the Swagger UI at `http://localhost:8080/swagger-ui.html`
> once the backend is running.

Day-to-day commands, architecture gotchas and the documentation
policy live in [`AGENTS.md`](../AGENTS.md) at the repo root.

## Contents

| Section | Files |
|---------|-------|
| [`architecture/`](./architecture/) | [`overview.md`](./architecture/overview.md) → [`backend.md`](./architecture/backend.md) → [`frontend.md`](./architecture/frontend.md) → [`data-model.md`](./architecture/data-model.md) → [`template-system.md`](./architecture/template-system.md) → [`document-pipeline.md`](./architecture/document-pipeline.md) → [`multi-tenancy.md`](./architecture/multi-tenancy.md) (design for the SaaS profile) → [`threat-model.md`](./architecture/threat-model.md) (assets, actors, threats, gaps) |
| [`guides/`](./guides/) | [`getting-started.md`](./guides/getting-started.md), [`testing.md`](./guides/testing.md), [`extending.md`](./guides/extending.md) |
| [`reference/`](./reference/) | [`editor.md`](./reference/editor.md), [`block-types.md`](./reference/block-types.md), [`variables.md`](./reference/variables.md), [`document-types.md`](./reference/document-types.md), [`env-vars.md`](./reference/env-vars.md) |
| [`operations/`](./operations/) | [`deployment.md`](./operations/deployment.md), [`ops-gotchas.md`](./operations/ops-gotchas.md), [`backup-restore.md`](./operations/backup-restore.md), [`saas-tenant-lifecycle.md`](./operations/saas-tenant-lifecycle.md) (SaaS: sign-up, invitations, closing, purge), [`saas-data-lifecycle.md`](./operations/saas-data-lifecycle.md) (SaaS: what is stored, who reaches it, export window, deletion, backups, requests from persons), [`saas-incident-response.md`](./operations/saas-incident-response.md) (SaaS: containing, evidence, notifying, recovering), [`saas-identity-provider.md`](./operations/saas-identity-provider.md) (SaaS: provider settings and how to exchange it), [`supply-chain.md`](./operations/supply-chain.md) (vulnerability scanning, severity policy, SBOMs, what a release contains), [`saas-deployment.md`](./operations/saas-deployment.md) (SaaS on a server: kit, provider, check script, backup, troubleshooting), [`verfahrensdokumentation.md`](./operations/verfahrensdokumentation.md) (GoBD, German, for the business and its tax auditor) |

A new contributor should read `architecture/overview.md`, then
`guides/getting-started.md`, then `AGENTS.md`.

Generated references are not duplicated here: the class/package
index is the Doxygen output, the REST API is the Swagger UI, and the
CI behaviour is defined by `.github/workflows/*.yml`.

## Source-of-truth files

| Fact | Where the code is |
|------|-------------------|
| Versions (Spring Boot, Vue, JUnit, Vite, etc.) | `backend/pom.xml`, `frontend/package.json` |
| Controllers and their paths | `backend/src/main/java/de/billery/api/controller/` |
| Database schema | `backend/src/main/resources/db/migration/` (PostgreSQL, Flyway) + both `scheme-h2.sql` files |
| Block types and their fields | `frontend/src/utils/blockRegistry.js` |
| Variable registry | `frontend/src/utils/variableRegistry.js` |
| Document type strategy | `backend/src/main/java/de/billery/document/` |
| Environment variables | `backend/src/main/java/de/billery/data/Database.java`, `config/AppConfig.java`, `mail/MailSettings.java`, `backend/env.example` |
| CI workflows | `.github/workflows/*.yml` |
| Docker topology | `docker-compose.yml` (root) + `backend/docker-compose.yml` |

When in doubt, the code wins.

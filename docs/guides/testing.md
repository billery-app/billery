# Testing

> **Audience:** contributors writing or running tests.
> **Scope:** the backend and frontend test stacks, how to run them,
> how they are organised, and how to add a new test. For the
> day-to-day commands see [`AGENTS.md`](../../AGENTS.md).

## At a glance

| | Backend | Frontend |
|---|---------|----------|
| Framework | JUnit 5 (Jupiter 6.1.0) | Vitest ^4.1.5 |
| Environment | H2 in MariaDB mode (`jdbc:h2:mem:testdb;MODE=MariaDB`), or a real PostgreSQL with `POSTGRES_TEST_URL` | jsdom |
| Run | `mvn test` (from `backend/`) | `npm run test:run` (from `frontend/`) |
| Watch | `mvn test` | `npm run test` |
| Coverage | JaCoCo (`target/site/jacoco/`), CI artifact + Pages | V8, in `coverage/` |
| CI | `maven.yml` (H2 suite, PostgreSQL job, SaaS job) | `frontend.yml` |

## Backend

### Stack

- **JUnit 5** (`junit-jupiter:6.1.0`) — JUnit Jupiter API + engine.
- **H2** (`MODE=MariaDB`, the default) — in-memory database set up by
  `TestDatabaseConfig` for every test run.
- **PostgreSQL** — with `POSTGRES_TEST_URL` set, `TestDatabaseConfig` points
  the **whole suite** at a real PostgreSQL instead (it drops and recreates the
  `public` schema, runs Flyway and applies `dummies-postgres.sql`, and empties
  the tables with `TRUNCATE ... RESTART IDENTITY CASCADE`). The CI job
  `postgres` does this on every push; `DatabaseInitializerTest` (H2 schema)
  is skipped there.
- **JaCoCo** — coverage, output in `target/site/jacoco/`.

### Layout

Tests mirror the main packages (`data/`, `repository/`, `service/`,
`pdf/`, `api/`, `api/controller/`). The ones worth knowing about:

| Test | What it protects |
|------|------------------|
| `MainAppDatabaseTest` | The real startup sequence on an H2 **file** DB (the desktop setup), run twice: schema creation, idempotent migrations, repair of a pre-GoBD schema (`LegacySchema`), the one-time VAT-per-line backfill, no demo data unless `LOAD_DEMO_DATA`, fail-fast on an unreachable DB. |
| `ArchitectureTest` | ArchUnit: the core never depends on `de.billery.saas..` or Spring Security. Vacuous in the default build (there is no SaaS code), checked for real in the `-Psaas` build. |
| `AppProfilesTest` | `spring.profiles.active` is read as a comma-separated list, case-insensitively, before Spring starts. |
| `saas/TenantIsolationTest` (`src/saas-test`) | Isolation inside the database as the application role, plain SQL: no superuser / `BYPASSRLS`; every table with a `tenant_id` has forced RLS and a policy; a tenant sees, changes and deletes only its own rows and cannot write or move rows to another tenant; an `INSERT` without the column belongs to the active tenant; no tenant means an error; a reference to another tenant's row fails; Flyway history and lookup tables are protected; a pooled connection never carries a tenant to the next borrower. |
| `saas/SaasApiTest` (`src/saas-test`) | The real application in the `saas` profile on a random port, connected as the application role, with tokens signed by `MockIdp` instances (own key, OIDC discovery): no/garbage/foreign-issuer/wrong-audience/expired token is 401; the first sign-in needs a verified e-mail; a user sees only the tenant it names and only if it is a member (403 otherwise, 400 without header for two tenants, header optional for one); a reader cannot write; a second provider reaches the same user; `/uploads` and the API docs are closed. |
| `saas/IdentityServiceTest` | Token to user: first and repeat sign-in, the provider switch by verified e-mail, unverified addresses never link, same subject at another issuer is another identity, eight parallel first sign-ins create one user, memberships and roles, members written only for the active tenant. |
| `saas/SaasIsolationTest` | **Tenant isolation over HTTP for every `/api` endpoint** (two tenants, each with owner data, customers, articles, services, equipment (with a service billed on a fully paid invoice, so usage and summary carry data), drafts, issued invoices with a payment, templates, a member and an invitation). A guard test lists the application's endpoints from Spring and fails for any that is not classified, so a new endpoint cannot go in without an isolation test. For each of ~55 endpoints Alice acts in her own tenant with the real ids of Bob's data and must get the answer she gets for ids that exist nowhere (no existence leak), nothing of Bob's data in the body (also inside a ZIP), Bob's data unchanged, and the same request must work for Bob himself (a well-formed attack); naming Bob's tenant in `X-Tenant-Id` is 403 everywhere; what a tenant creates stays in that tenant. Disabling RLS on a table makes it fail with the leaking endpoints named. `equipmentUsageAndRevenueOfAnotherTenantStayOutOfReach` checks the owner's usage line and revenue and that Alice gets neither. |
| `saas/SaasRoleMatrixTest` | **Roles inside one tenant for every `/api` endpoint**: one tenant with an owner, an admin, an accountant and a reader, every endpoint called with each; the table (READ, WRITE, TEAM, OWNER_ONLY) says who may call it, an allowed role must not get 401/403, a denied one exactly 403. A guard fails for any endpoint that is in no group. |
| `saas/IdpSwitchTest` | The identity provider switch of `docs/operations/saas-identity-provider.md` with real signed tokens: old only, both, new only; same user and membership; old tokens refused at the end; an unverified address does not link; a token for another application is refused. |
| `saas/UploadStorageTest` | Tenant-scoped uploads without a database, against the directory store and, with `S3_TEST_URL` (`http://<key>:<secret>@<host>:<port>/<bucket>`, CI: a SeaweedFS container in the `saas` job), an S3-compatible store: the secret is mandatory, a signature fits exactly one tenant and file, files are stored under the active tenant with a signed URL, the renderer's `read` returns only the active tenant's files (not another tenant's, not with a traversal, not without a tenant), the endpoint needs the matching signature; two instances with clients of their own serve each other's uploads; an unreadable store is an error, never "no such file"; `BILLERY_FILES_STORE` and an incomplete S3 configuration. `SaasApiTest` adds the HTTP side (with `S3_TEST_URL` the whole SaaS suite keeps its uploads in the bucket): upload per tenant, the unauthenticated fetch with the safety headers, tampered signature / swapped tenant / other name = 403, readers and non-members cannot upload, and a PDF shows the tenant's own logo but never another tenant's. |
| `saas/TenantPurgeTest` | `purge_tenant` removes a tenant with documents, periods, payments and a reversal completely, leaves another tenant untouched, refuses tenant 1, and the application role can neither run it nor delete a tenant. |
| `saas/RoleTest` | A reader only reads. |
| `saas/SuiteRunsAsAppRoleTest` | The premise of the `-Psaas` suite: it connects as the application login role, no superuser, with tenant 1. |
| `saas/SaasSchemaTest` (`src/saas-test`) | The SaaS migrations run after the core ones with their own history table; a second run does nothing; new tenants continue the identity after tenant 1. Compiled only with `-Psaas`. |
| `PostgresStartupTest` | Startup twice plus issue, PDF and Storno against a real PostgreSQL. Resets the `public` schema first; skipped unless `POSTGRES_TEST_URL` is set (the `postgres` job in `maven.yml` sets it). |
| `SchemaMigratorTest` | Flyway on a real PostgreSQL: empty database gets `V1`, a second run does nothing, a database without Flyway history is baselined, four concurrent starts apply `V1` once, and the real `dummies.sql` loads once and reads like real data (unique names, umlauts, no placeholder texts, a history consistent with each document's status). Skipped unless `POSTGRES_TEST_URL` is set. |
| `api/controller/DocumentLifecycleTest` | Numbering on issue (no gaps from deleted drafts, year of the invoice date), mandatory fields, server-side totals, rollback, status rules, locking of issued invoices, payments and reversals, Storno, frozen PDF snapshot, statistics, archive, PDF download, delete semantics, offer versions, Kleinunternehmer rule. |
| `document/OfferStatusTransitionsTest` | The offer / cost calculation lifecycle: every pair of statuses against `src/test/resources/status-parity/transitions.json`, which `useDocumentType.spec.js` reads too, so backend rules and SPA buttons cannot drift apart. |
| `api/controller/CustomerPageTest` | `GET /api/customers/page`: every searched field case-insensitive, "first last", literal `%`/`_`, order by id vs. name, paging without overlap, the 400 limits. SPA side: `components/CustomerPicker.spec.js` (search, keyboard, Enter does not submit, existing customer by id, stale results) and `views/CustomersView.spec.js`. |
| `api/controller/InvoicePageTest` | `GET /api/invoices/page`: every filter of the document list (tab, archive, year, search with literal `%`/`_`, status incl. overdue / expired), paging without gaps or overlap, drafts first, page items equal to the single-document DTO, the 400 limits, and the dashboard's date range across the archive with `issued` / `paid` / `open`. The SPA side is `views/InvoicesView.spec.js` (requests, pager, debounce, stale responses, unknown filter keys from links) and `views/DashboardView.spec.js` (statistics and first five rows per card and period, no unpaged list). Statistics: `DocumentLifecycleTest` (counted documents, credit notes, gross, the 400 cases). |
| `api/controller/OfferStatusFlowTest` | `PATCH /status` for offers and cost calculations: the full flows, accepting from a draft, setting the current status again is a no-op, a refused change names both statuses and what is possible. |
| `api/controller/VatAndEInvoiceTest` | VAT per line (rounding per rate, payments, PDF lines and "USt." column), allowed rates, Kleinunternehmer / reverse charge / tax-exempt rules and notes, XRechnung (frozen parties, credit note, missing data). Its `generatedXRechnungsPassTheKositValidator` runs only with `XRECHNUNG_VALIDATOR_DIR` (the `xrechnung` job in `maven.yml` sets it). |
| `api/controller/ZugferdTest` | The PDF of an issued invoice is a ZUGFeRD invoice: for every tax case (19 %/7 % with a line note, reverse charge, tax-exempt, Kleinunternehmer, Stornorechnung) the attached `xrechnung.xml` equals the UBL XRechnung in 36 business terms (every term must occur in some case) and the page shows its number, buyer, net, gross, VAT amounts and line totals; the attachment is declared as PDF/A-3 associated file with the Factur-X XMP; the same XML on every download; no attachment without complete data, for drafts and offers (still PDF/A). With `XRECHNUNG_VALIDATOR_DIR` the XMLs go through KoSIT, with `PDFA_SAMPLES_DIR` the PDFs are written for veraPDF. |
| `api/controller/GobdExportTest` | GoBD export: period, contents, CSV escaping, CSV headers match `index.xml`, `index.xml` valid against the official GDPdU DTD (`src/test/resources/gdpdu/`, provenance in its `README.md`); limits (period, document count with and without files, ZIP size, concurrency, slot released after a failure) and their 413 / 429 mapping. |
| `tax/VatCalculatorTest` | Grouping, rounding once per group, sub-items, notes, treatments. |
| `api/controller/MasterDataControllersTest` | CRUD for customers, articles, services, owner, equipment; search. |
| `api/controller/EquipmentUsageTest` | Equipment amortisation: summary and usage list count only service lines on fully paid invoices, agree with each other and with the former per-service queries, real `paybackPercent` above 100 %, 404 for unknown equipment. |
| `data/DatabaseTransactionTest` | The application's transaction configuration: commit, rollback of every repository write, nesting, handled nested failures, plain JDBC inside a transaction, number reservations released on failure. |
| `repository/UserSettingsRepositoryTest` | Settings defaults and the `INSERT ... ON DUPLICATE KEY UPDATE` upsert. |
| `pdf/TemplateParityTest` | Renders every case of `src/test/resources/template-parity/cases.json` with the PDF renderer; `frontend/src/utils/templateParity.spec.js` renders the same cases with the editor preview. Both must show the same texts and styles. |
| `pdf/RestrictedUserAgentTest` | The PDF renderer only loads uploads (any host/port), its own classpath stylesheet and `data:` images — no network, `file:` or traversal URIs; template CSS cannot close `<style>`. |
| `api/controller/InvoiceTemplateControllerTest` | Per-document overrides (clone on save, cleanup) and `POST /api/templates/preview` (sample PDF, nothing stored, no CSS printed as text). |

Controller tests call the controller classes directly (no Spring
context, no HTTP) against H2. The object graph comes from `TestWiring`
(test sources), which constructs repositories, services and controllers
the way Spring injects them in production: repositories get a
`JdbcClient` on `Database.dataSource()`, transactional classes get
`TestWiring.transactions()` (same configuration as `DbConfig`). When a
constructor changes, only `TestWiring` has to follow.

### Conventions

- Call `TestDatabaseConfig.setupH2Database()` in `@BeforeAll` and
  `TestDatabaseConfig.clearData()` in `@BeforeEach` for test isolation;
  `resetDatabaseConfig()` in `@AfterAll`. Note that `clearData()` also
  empties `invoice_template`, so tests that render PDFs must create the
  templates they need.
- Build objects with `TestWiring` **after** `setupH2Database()`: a
  `JdbcClient` captures the DataSource that is current when it is created.
- Amounts are `BigDecimal`: build them with `TestMoney.bd(...)` and compare
  with `TestMoney.assertAmount(...)` (numeric comparison). Never use
  `assertEquals` on two `BigDecimal`s, which also compares the scale
  (`19.0` ≠ `19.00`), or on a `double` and a `BigDecimal` (always fails).
- Rendering needs an owner row (there is no placeholder owner).
- Use `assertEquals(expected, actual)` with the **expected** value first.
- Do not start a Spring context in unit tests.

### Running

```bash
# from backend/
mvn test                                # all tests
mvn verify                              # CI equivalent: tests + JaCoCo + Spotless + PMD
mvn -Dtest=CustomerTest test             # single class
mvn -Dtest=CustomerTest#someMethod test  # single method

# the whole suite against a real PostgreSQL (throwaway container; the test
# database is wiped, never point this at data you want to keep)
docker run -d --rm --name rp-test-db -p 127.0.0.1:55432:5432 \
  -e POSTGRES_USER=rp -e POSTGRES_PASSWORD=rp -e POSTGRES_DB=rp postgres:17
POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:55432/rp?user=rp&password=rp' mvn test

# the SaaS build: SaaS code, SaaS migrations, the Spring profile "saas" for the whole suite
# (switching between the default and the saas build needs `mvn clean` in between)
POSTGRES_TEST_URL='jdbc:postgresql://127.0.0.1:55432/rp?user=rp&password=rp' mvn clean -Psaas test

# generated XRechnungs against the KoSIT validator (fetched from its official release, checksummed)
../scripts/fetch-kosit-validator.sh /tmp/kosit
XRECHNUNG_VALIDATOR_DIR=/tmp/kosit PDFA_SAMPLES_DIR=/tmp/pdfa \
  mvn test -Dtest='VatAndEInvoiceTest#generatedXRechnungsPassTheKositValidator,ZugferdTest'
# the PDFs ZugferdTest wrote, as PDF/A-3b (every line must start with PASS)
docker run --rm -v /tmp/pdfa:/data:ro verapdf/cli:v1.30.3 --flavour 3b --format text /data
```

JaCoCo coverage report: `backend/target/site/jacoco/index.html`.

## Frontend

### Stack

- **Vitest ^4.1.5** with the `jsdom` environment
  (`vitest.config.js`).
- **V8 coverage** via `@vitest/coverage-v8`.
- **`@vue/test-utils`** for component tests.
- **DOMPurify** is wrapped in `utils/sanitize.js`; the editor
  preview path uses it to sanitise HTML before `v-html`.

### Layout

```
frontend/src/
├── components/
│   ├── TemplateEditor.spec.js         # editor: state model, validation, preview, guard
│   ├── AccountMenu.spec.js            # tenant switcher, sign-out
│   ├── NoTenantNotice.spec.js         # accept an invitation, create a company
│   ├── TeamSettings.spec.js           # members, invitations, role limits, closing
│   ├── DocumentModal.spec.js
│   ├── DocumentViewModal.spec.js      # action bar per document type and status, lifecycle strip and its caption, "Mehr" menu, payment / duplicate form, history
│   ├── editor/                        # editor sub-components
│   │   ├── BlockConfigPanel.spec.js
│   │   ├── BlockLibrary.spec.js
│   │   ├── BlockOutline.spec.js
│   │   ├── DesignPanel.spec.js
│   │   └── VariableTextInput.spec.js  # chips, @-picker, serialisation
│   └── template/
│       └── TemplateStartModal.spec.js # start gallery + thumbnails
├── styles/
│   └── colorLiterals.spec.js          # design-system guard: no new colour literals in .vue files
├── services/
│   ├── authService.spec.js            # SaaS sign-in: redirect, callback, tenants, in-memory token, renewal
│   └── baseService.auth.spec.js       # the apiClient interceptors: headers, 401 renew + retry once, no loops
├── composables/                       # useHistory, useUnsavedChangesGuard, useDocumentType (+ offer status parity with the backend)
└── utils/                             # pure JS modules
    ├── blockModel.spec.js             # order, migration, layout rows
    ├── blockRegistry.spec.js          # schema completeness, render cases
    ├── blockValidation.spec.js
    ├── designActions.spec.js          # simple mode + presets
    ├── previewData.spec.js
    ├── scopeCss.spec.js
    ├── templateParity.spec.js         # shared parity cases with the Java renderer
    ├── variableText.spec.js
    └── …
```

All spec files use the `.spec.js` extension. The vitest glob
(`src/**/*.{test,spec}.{js,…}`) also matches `.test.js`, but no
`.test.js` files exist today.

### Conventions

- Spec files live **next to the source**: `Foo.vue` ⇒
  `Foo.spec.js`, `foo.js` ⇒ `foo.spec.js`.
- Use Vitest's `describe` / `it` (or `test`) blocks.
- For Vue components, mount with `@vue/test-utils`'s `mount` and
  assert on the rendered output (e.g. `wrapper.text()`,
  `wrapper.find(...)`).
- For pure utility modules, import the named export and call it
  directly.
- The `v-html` Semgrep rule will flag any new `v-html` bindings
  that don't run through `sanitize.js` (or aren't generated by a
  trusted block renderer).

### Running

```bash
# from frontend/
npm run test:run                        # single run (CI equivalent)
npm run test                            # watch mode
npm run test:coverage                   # V8 coverage, output in coverage/
```

Coverage report: `frontend/coverage/index.html`.
The coverage badge is auto-generated on every deploy of `pages.yml`
and lives at `https://billery-app.github.io/billery/badges/frontend-coverage.svg`;
the repo no longer holds a generated SVG for it. See
`.github/workflows/pages.yml` for the deploy flow.

## Adding a new test

### Backend

1. Identify the layer:
   - **Data / entity** → `src/test/java/de/billery/data/`
   - **Repository** → `src/test/java/de/billery/repository/`
   - **Service** → `src/test/java/de/billery/service/`
2. Create `XxxTest.java` next to the existing tests in that layer.
3. Use `TestDatabaseConfig.setupH2Database()` if the test touches
   the database, then get repositories / services from `TestWiring`.
4. Run with `mvn -Dtest=XxxTest test`.
5. Run `mvn spotless:apply` to format.

### Frontend

1. Identify the source file (`Foo.vue` or `foo.js`).
2. Create `Foo.spec.js` next to it.
3. Use `describe` / `it` and the appropriate testing utility
   (`@vue/test-utils` for components, direct calls for utilities).
4. Run with `npm run test:run` (single) or `npm run test`
   (watch).
5. Make sure the test surfaces a meaningful failure — if the
   source file has a bug, the test should fail.

## What is **not** covered today

- **E2E tests** — only a smoke suite (`frontend/e2e/`, Playwright, CI job
  `e2e-smoke` in `.github/workflows/e2e.yml`, details in
  [`frontend/e2e/README.md`](../../frontend/e2e/README.md)): it runs the
  packaged app (SPA inside the backend JAR, empty H2), loads the main screens,
  opens the template editor's PDF preview and a document, searches a customer
  in the document form, and fails on console errors, CSP violations and
  `/api` errors. Saving documents through the form, the lifecycle (issue,
  payments, cancel), import/export and the SaaS build are not covered in the
  browser.
- **DOCX / ODT** has unit tests in `service/DocumentConversionServiceTest`
  (sections round trip in both formats, Word heading styles and tables,
  an ODT with LibreOffice-style markup built in the test, change tracking,
  nesting depth, the empty document, export limits),
  `api/controller/DocumentControllerTest` (export → import, refusals, file
  name), `FileSecurityServiceTest` (archive limits) and, for the mapping to
  blocks, `utils/documentExchange.spec.js`, `TemplateStartModal.spec.js` and
  `TemplatesView.spec.js`. The fixtures are hand-made or written by the app,
  not by Word or LibreOffice.
- **Mail** is a preparation for a future feature and has no tests yet.
- **Desktop shell** (`app-desktop/src/lib.rs`) has no automated test; CI
  does not even compile it. Run `cargo check` in `app-desktop/` after
  changes.

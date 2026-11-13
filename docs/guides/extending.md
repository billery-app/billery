# Extension Points

> **Audience:** contributors adding new functionality.
> **Scope:** concrete recipes for the five most common extension
> tasks. Each recipe names the files you have to touch, in order,
> with the invariants to preserve.
> For the higher-level system view see
> [`../architecture/overview.md`](../architecture/overview.md). For
> background on the template engine see
> [`../architecture/template-system.md`](../architecture/template-system.md).
> For the editor UI, block types, and variables see
> [`../reference/editor.md`](../reference/editor.md),
> [`../reference/block-types.md`](../reference/block-types.md), and
> [`../reference/variables.md`](../reference/variables.md).

## Recipe 1 — Add a new block type

The block-based template editor is the most likely thing you'll want to
extend. A block type exists twice — editor preview (JS) and PDF (Java) —
and a parity test keeps both in step.

### Files to edit (in order)

1. **`frontend/src/utils/blockRegistry.js`** — add an entry to
   `BLOCK_TYPES`:
   - `type`, `name` (German), `description` (one line for the library),
     `icon` (add the SVG to `BlockLibrary.vue` for a new key).
   - `limits`: `{ max, required }` — enforced by `blockValidation.js`
     and the library.
   - `defaultData`: text fields default to `{{variables}}` or empty
     strings, **never** to sample values (the PDF prints what is
     stored). `rolePresets` for role-dependent defaults.
   - `defaultConfig` incl. `format` (`{ fontFamily, fontSize, fontColor,
     fontWeight }`) and `formatDefaults` (the same defaults as the Java
     renderer).
   - `fields` / `settings`: the config panel's form, one entry per
     `defaultData` / `defaultConfig` key (label, `kind`, options,
     `help`, `showIf`). Add `WIDTH_SETTING` unless the block must be
     full width. `blockRegistry.spec.js` fails when a key has no entry.
   - `render(data, config, ctx)`: returns an HTML string. `data` is
     already variable-resolved and escaped; escape anything taken from
     `ctx` or `config` labels (`escapeHtml`, `label()`), validate
     colors (`safeColor`). Use tables, not flexbox — the PDF renderer
     has none. Do **not** invent values for missing data (see
     [`../architecture/template-system.md`](../architecture/template-system.md)).
   The config panel, simple mode and validation need no changes.

2. **`backend/src/main/java/de/billery/pdf/TemplateBasedPdfGenerator.java`** —
   add a `case` in `renderBlock` and a `render*` method that:
   - reads `data` / `config` with `getStringValue` & co.,
   - resolves text fields with `substituteVariables(text, vars)`
     (resolves every variable, escapes the result),
   - escapes config labels, uses `buildBodyStyle` / `getFormat*`
     (validated colors and fonts),
   - emits the same markup as the JS `render`, as well-formed XHTML.

3. **`backend/src/test/resources/template-parity/cases.json`** — add at
   least one case with the texts and style declarations both renderers
   must produce. `TemplateParityTest` (Java) and
   `frontend/src/utils/templateParity.spec.js` both run it.

4. **Optional — defaults for new templates:** add the block to
   `createDefaultBlocks` (`utils/blockModel.js`) and/or the presets in
   `utils/templatePresets.js`; the SQL seeds (`V1__baseline.sql`, both
   `scheme-h2.sql`) and `TemplateBlocksBackfill.getDefaultBlocksJson`
   (the layout a template stored without blocks gets) hold their own copy.
   A new text field is carried by the DOCX/ODT exchange automatically
   (`utils/documentExchange.js` reads the `fields` of the registry).

### Tests to add

- `blockRegistry.spec.js` — render cases for the new block.
- The parity case from step 3 (covers both renderers).
- `TemplateBasedPdfGeneratorTest` for Java-only behaviour (e.g. data
  that only exists server side).

### Invariants

- **The block's `data` and `config` objects are the only thing the
  user can edit**; every key has a field / setting definition.
- **The block's `id` must be unique within a template.** The editor
  generates ids with `generateId()`; preserve that contract if you
  generate blocks yourself.
- **The render path must be idempotent.** Re-rendering the same
  block twice should produce the same HTML.

## Recipe 2 — Add a new `{{variable}}`

Adding a variable is much cheaper than adding a block.

### Files to edit

1. **`frontend/src/utils/variableRegistry.js`** —
   - Add the variable to `VARIABLE_REGISTRY` (`name`, `label`, `type`,
     `category`) and, if the label alone is ambiguous, a chip label in
     `CHIP_LABELS`.
   - Add a sample value to `SAMPLE_DATA` (`utils/templatePreview.js`)
     and, when it comes from the company data, map it in
     `previewData.js > ownerVariables`; for the per-document editor
     add it in `invoiceVariables.js > buildPreviewVariables`.
2. **`backend/src/main/java/de/billery/pdf/TemplateBasedPdfGenerator.java`** —
   add the replacement to `replaceVariables(text, owner, customer, invoice)`
   in the block for its data source. Without it the PDF **removes**
   the placeholder.
3. Optionally add a parity case that uses the variable.

### Invariants

- Variable names are lowercase German with underscores; spaces inside
  the braces are ignored by both renderers.
- The category in `VARIABLE_REGISTRY` must be one of the existing
  categories (`Company`, `Client`, `Invoice`, `Totals`, `Payment`) —
  they map to the picker's German groups (`CATEGORY_LABELS`).

## Recipe 3 — Add a new entity (e.g. "Project")

This is the most invasive change. A new entity touches every layer.

### Files to edit

1. **Schema** —
   - **A new Flyway migration** `backend/src/main/resources/db/migration/V<next>__add_<table>.sql`
     (PostgreSQL) — the `CREATE TABLE` for the new table. Never edit an
     already applied migration. Pick a name that matches the Java class
     (singular, lower_snake_case in SQL, PascalCase in Java). Add foreign
     keys and indexes consistent with `V1__baseline.sql`.
   - **`backend/src/main/resources/scheme-h2.sql`** — add the
     same `CREATE TABLE`, with H2-compatible syntax. This file is the
     one used by the H2 production path (when `DB_URL=jdbc:h2:...`).
   - `backend/src/test/resources/scheme-h2.sql` — add the
     same `CREATE TABLE` for the test classpath. (The two H2 scripts
     are nearly identical; mirror the change in both.)
   - **Existing H2 installs** do not re-run the schema scripts. Add a
     `migrateXxx(conn)` method to `DatabaseMigration.java` (e.g.
     `CREATE TABLE IF NOT EXISTS ...`, or `ALTER TABLE ... ADD COLUMN IF
     NOT EXISTS`) and call it from `runMigrations`. It runs on every start,
     on H2 (desktop) only; PostgreSQL gets the change from the Flyway file
     above. `MainAppDatabaseTest` shows whether the H2 migration is idempotent.

2. **Java entity** — `backend/src/main/java/de/billery/data/<Name>.java`:
   - Plain POJO with fields, default constructor, full-args
     constructor, getters, setters.
   - Match the column order in the `INSERT INTO` of the repository
     for clarity (not strictly required but it makes SQL/POJO diffs
     easier to read).

3. **Repository** —
   `backend/src/main/java/de/billery/repository/<Name>Repository.java`:
   - `@Repository`, extends `BaseRepository`, takes the `JdbcClient` by
     constructor (and calls `super()` first).
   - Implement `findAll()`, `findById(int id)`, `save(entity)`,
     `update(entity)`, `delete(int id)` with named parameters and a
     `RowMapper` constant; read generated ids with `generatedId(keys)` and
     money columns as `BigDecimal` (`decimalOrZero`).
   - Wrap every `JdbcClient` call in `run("operation", () -> ...)` — never
     return `null` / empty for a failure.
   - If an operation writes several rows, the *caller* wraps it in the
     injected `TransactionTemplate` (`transactions.execute(status -> ...)`).
   - Add a factory for it to `TestWiring` (test sources).
   - Use `DatabaseUtils.emptyToNull(...)` for nullable string fields
     on the way in and `nullToEmpty(...)` on the way out, to match
     the rest of the codebase.

4. **DTO** — `backend/src/main/java/de/billery/api/dto/<Name>Dto.java`:
   - Plain class mirroring the entity's public surface.
   - Add a static factory or keep the `convertToDto` /
     `convertToEntity` mapping in the controller, consistent with the
     existing controllers.

5. **Controller** —
   `backend/src/main/java/de/billery/api/controller/<Name>Controller.java`:
   - `@RestController`, `@RequestMapping("/api/<name>s")`,
     `@CrossOrigin(origins = "http://localhost:5173")` (the global
     CORS config covers it, but the per-controller annotation is
     consistent with the existing controllers).
   - Standard CRUD endpoints: `GET /`, `GET /{id}`, `POST /`,
     `PUT /{id}`, `DELETE /{id}`.
   - Map between entity and DTO in the controller. Don't return
     entities directly.
   - Take repositories and services by constructor; never `new` them.

6. **Frontend service** —
   `frontend/src/services/<name>Service.js`:
   - Extend `BaseService` and expose named methods (`getAll`,
     `getById`, `create`, `update`, `remove`).

7. **Frontend view** — `frontend/src/views/<Name>sView.vue`:
   - List view with a button to open the create / edit modal.

8. **Frontend modal** — `frontend/src/components/<Name>CreateModal.vue` (or
   edit the existing modal if you have one entity modal that
   covers many entity types — see how `ArticleServiceCreateModal`
   covers both articles and services).

9. **Router** — `frontend/src/router/index.js`:
   - Add a route for the new view.

10. **Navbar** — `frontend/src/App.vue`:
    - Add a link to the new view if it's a top-level destination.

### Invariants

- **SQL in three places.** Forgetting any of them (a Flyway migration in
  `db/migration/`, `scheme-h2.sql` in `main/resources`, and
  `scheme-h2.sql` in `test/resources`) is the most common bug; existing
  installs additionally need a `migrateXxx` method.
- **DTOs never expose entity internals.** Internal flags, derived
  fields — keep them out of the wire format unless the SPA needs them.
- **Repositories return `null` on `findById` miss** (the convention
  established by the existing repos). Controllers turn this into a
  `ResponseEntity.notFound().build()`.

## Recipe 4 — Add a new API endpoint

If the new endpoint fits an existing controller, the change is
local: add a method to the controller, add a method to the matching
service. If the endpoint needs a new controller, follow Recipe 3
steps 4 and 5.

### Files to edit

1. **Controller** — add a method to the appropriate controller in
   `api/controller/`. Use the existing DTOs; add a new DTO in
   `api/dto/` if the wire shape doesn't fit.
2. **Service / repository** — add the supporting method if the
   endpoint does more than a simple CRUD lookup. Use the existing
   service layer pattern; do not skip it and put business logic
   directly in the controller.
3. **Frontend service** — add a method to the matching
   `services/<resource>Service.js` to call the new endpoint.
4. **Frontend view / modal** — call the new service method from the
   view or modal that needs the new behaviour.

### Invariants

- **Path conventions** — REST-style paths, pluralised resource
  names, kebab-case for multi-word paths (e.g.
  `/api/invoices/by-customer/{id}`).
- **HTTP verbs** — `GET` for reads, `POST` for creates, `PUT` for
  updates, `DELETE` for deletes. The existing controllers follow
  this strictly.
- **CORS** — covered globally; per-controller annotations are
  redundant but harmless.
- **Error responses** — use `ResponseEntity.notFound().build()` for
  misses, `ResponseEntity.badRequest().build()` for validation
  failures, and let exceptions bubble to Spring's default error
  handling for everything else.

## Recipe 5 — Wire up a new document type

The system has five document types out of the box
(`INVOICE`, `OFFER`, `COST_CALCULATION`, `PARTED_INVOICE`,
`FINAL_INVOICE`). Adding a new one — e.g. `CREDIT_NOTE` — touches
the strategy registry and the lookup tables.

### Files to edit

1. **A new Flyway migration** (`backend/src/main/resources/db/migration/V<next>__...sql`) —
   add a new row to the `document_type` lookup table
   (`INSERT INTO document_type (id, name) VALUES (...) ON CONFLICT DO NOTHING`).
   Pick an `id` that isn't already in use.
2. **Both `scheme-h2.sql` files** (in `src/main/resources/` and
   `src/test/resources/`) — same insert in each.
3. **`backend/src/main/java/de/billery/document/DocumentStrategyRegistry.java`** —
   add a new strategy class to the constructor's `register(...)`
   calls, mapping the new `documentTypeId` to a
   `DocumentTypeStrategy` implementation.
4. **`backend/src/main/java/de/billery/document/<Name>Strategy.java`** —
   create the new strategy. The simplest case extends
   `BaseDocumentTypeStrategy` and provides the status transition
   table. Look at `InvoiceStrategy.java` for a 22-line example.
5. **`frontend/src/data/documentTypes.js`** — add
   the new document type to the frontend's lookup so the UI
   shows the correct label.
6. (Optional) **Frontend view** — add a tab or section for the new
   document type in `InvoicesView.vue` or wherever the user picks a
   document type.

### Invariants

- The `documentTypeId` is the only thing the strategy registry
  cares about. Keep IDs stable; renumbering is a migration.
- The new strategy must define `isValidTransition(from, to)` based
  on the document type's actual workflow (which statuses can follow
  which). The `BaseDocumentTypeStrategy.isAllowed` helper is the
  shared checker.
- The frontend label and the backend name should match. Use
  `Doku-style` snake case for the database name and a German
  display label in the UI.

## Common pitfalls

- **Forgetting `scheme-h2.sql` (or the Flyway migration).** The default test DB is
  H2, the server is PostgreSQL. A schema change that works in one but
  fails in the other (e.g. `ENUM`, `AUTO_INCREMENT`) breaks the build
  without warning; CI runs the suite on both.
- **Changing only one renderer.** A block option honoured in
  `blockRegistry.js` but not in `TemplateBasedPdfGenerator` (or the
  other way round) makes the preview lie. Add a parity case; the
  config panel itself is generated from the registry.
- **Inserting a template without `blocks`.** The PDF path refuses
  a template without blocks. `TemplateBlocksBackfill` gives such
  templates the default layout, but only at the next startup; a
  template inserted by hand (SQL, a new seed) carries its `blocks`.
- **Forgetting CORS.** Covered globally, but if you add a new
  controller and your browser complains, check that the
  `cors.allowed-origins` env var includes the dev frontend URL.
- **Trusting the `data` field.** Block renderers receive user-supplied
  data. Text fields arrive escaped; escape everything else you insert
  (config labels, document data from `ctx`) and validate colors. For the
  legacy HTML path, `DocumentController` does not sanitise the HTML
  before saving it — the editor's preview runs it through
  `sanitize.js` only for display.

**Next:** For deployment, see
[`../operations/deployment.md`](../operations/deployment.md). The
top-level [`../README.md`](../README.md) maps the full docs tree.
variables, dev vs prod, and operational gotchas.

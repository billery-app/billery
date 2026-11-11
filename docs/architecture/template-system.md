# Template System

> **Status:** chapter 5 of the `docs/architecture/` series.
> **Audience:** contributors and maintainers.
> **Scope:** the block-based template engine, the variable registries, the
> render path, and the contract that keeps the editor, the renderer, and
> the database in sync. This is the most distinctive part of the system
> and the one most likely to be extended.
> For the higher-level system view see [`overview.md`](./overview.md). For
> the PDF / XRechnung / mail output see
> [`document-pipeline.md`](./document-pipeline.md). For "how do I add
> a block" see [`../guides/extending.md`](../guides/extending.md).

## Two template systems (and why)

The project has two parallel template representations. They exist for
historical reasons and the plan is to converge on the block-based
representation, but right now they are both load-bearing.

| System | Where it lives | Variable syntax | Used by |
|--------|----------------|-----------------|---------|
| **Block-based** (current) | `invoice_template.blocks` (JSON) | `{{firma}}`, `{{kunde}}`, `{{rechnungsnummer}}`, `{{netto}}`, `{{iban}}` | `TemplateEditor.vue` (frontend), `TemplateBasedPdfGenerator` (backend). The default for every template. |
| **HTML-template** (legacy) | `invoice_template.html_content` + `css_content` | **Lowercase**: `{{firma}}`, `{{kunde}}`, `{{rechnungsnummer}}`, `{{positionen}}`, `{{netto}}`, etc. The hard-coded `getAvailableVariables` list in `DocumentController` exposes these under uppercase English aliases (`OWNER_COMPANY`, `CUSTOMER_FIRST_NAME`, `INVOICE_NUMBER`, `NET_AMOUNT`, …) for the DOCX/ODT import picker. | `DocumentController` import / export (DOCX, ODT), `DocumentConversionService`. |

The block system is the **authoritative** source for the editor and for
the invoice PDF pipeline. The HTML system is the **authoritative** source
for the import / export endpoints and for users who hand-edit a
template's HTML directly.

When both are present for the same template, the block JSON wins at
render time. **`TemplateBasedPdfGenerator.getTemplateForDocumentType`
does *not* fall back to the HTML path** — it walks the templates
looking for one with valid blocks and throws `IllegalStateException`
if none is found (`TemplateBasedPdfGenerator.java:86-106`). There is no
HTML fallback: a template without blocks (an imported DOCX/ODT) cannot
render until `TemplateBlocksBackfill` has given it the default layout
at the next startup.

## Block-based system: end to end

### Storage

`invoice_template.blocks` is a JSON-serialised array of block objects:

```js
[
  {
    "id": "uuid",
    "type": "header",        // or address, invoice-meta, line-items, totals, payment, footer
    "order": 0,
    "data":   { ... },       // block-specific
    "config": { ... }        // block-specific
  },
  ...
]
```

`V1__baseline.sql` seeds the database with **four** default templates
(`Standardrechnung`, `Standardangebot`, `Standardkostenberechnung`,
`Standardteilrechnung` — document type ids 1, 2, 3, 4). The fifth
document type, `FINAL_INVOICE` (id 5), has no seed template. Each
row carries a hand-written block JSON; the block JSON is identical in
shape across the four rows — this is the seed format the editor
understands.

### Authoring

`TemplateEditor.vue` is the only authoring tool (UI details in
[`../reference/editor.md`](../reference/editor.md)):

1. Open `/templates` → `TemplatesView` lists the templates with
   thumbnails.
2. "Neue Vorlage" → `TemplateStartModal` (document type + preset) →
   the editor opens with a complete, valid template; or edit an
   existing one.
3. Simple mode ("Design") changes the look of the whole document;
   advanced mode ("Blöcke") adds, orders and configures single blocks.
4. Save → `POST /api/templates` / `PUT /api/templates/{id}` with the
   whole `InvoiceTemplateDto`. Saving is refused while a required block
   is missing or a block exceeds its limit.

The block JSON is the editor's source of truth. `css_content` is edited
under "Für Fortgeschrittene: eigenes CSS"; `html_content` is generated
from the blocks (placeholders kept) for the DOCX/ODT round trip.

### Block model and registry (frontend)

- `utils/blockModel.js` — array order is the block order while
  editing; `normalizeBlocks` (load: sort by stored `order`, migrate
  legacy data) / `serializeBlocks` (save: `order` = index),
  `createBlock` (registry defaults + role presets), `layoutRows`
  (half-width pairing).
- `utils/blockRegistry.js` — `BLOCK_TYPES`, one complete description
  per block:

| Field | Purpose |
|-------|---------|
| `type`, `name`, `description`, `icon` | Key and German display texts. |
| `limits` | `{ max, required }`, enforced by `blockValidation.js`. |
| `defaultData` / `rolePresets` | Initial `data`; text fields hold `{{variables}}`, never sample literals. |
| `defaultConfig` | Initial `config` incl. `format`. |
| `formatDefaults` | Font defaults when `config.format` lacks a key (same as Java). |
| `fields` / `settings` | The config panel's form (label, kind, options, help, `showIf`). |
| `render(data, config, ctx)` | Preview HTML. `data` arrives variable-resolved and escaped (`renderBlockPreview.js`); `ctx` carries document data (items, VAT totals, number label, variables). Markup mirrors the Java renderer. |

The per-block reference (fields, settings, defaults) is
[`../reference/block-types.md`](../reference/block-types.md).

### Variables

`utils/variableRegistry.js` lists the lowercase German variables, their
German chip labels, groups and accepted English aliases
([`../reference/variables.md`](../reference/variables.md)). In the
editor a variable is a chip (`VariableTextInput`); the field value is a
string that may contain `{{variable}}` placeholders, resolved at render
time — in the preview against the preview context, in the PDF against
the document.

### Backend render path

`pdf/TemplateBasedPdfGenerator` is the main entry point:

1. Load the invoice, its customer, its line items, and the owner.
2. Pick the template (`loadTemplateForInvoice`: per-document override,
   else the type default with valid blocks).
3. `generateHtmlFromBlocks` renders each block (`renderHeader`,
   `renderAddress`, …) with one `VariableContext` (owner, customer,
   invoice) so every variable resolves in every block, then arranges
   the blocks (`layoutBlocks`: half-width pairs share a table row).
4. `convertHtmlToPdf` runs Flying Saucer with `RestrictedUserAgent`:
   only uploaded files (`/uploads/<name>`, read from `FILES_PATH`),
   `data:` images and the renderer's own classpath stylesheet are
   loaded — no network, no other files.

`POST /api/templates/preview` renders an unsaved draft through the
same steps with a built-in sample document or a given invoice (the
editor's "PDF-Vorschau").

The Java renderer and `blockRegistry.js`'s `render` are separate
implementations of the same markup. They are kept in sync by the
renderer parity test: `backend/src/test/resources/template-parity/cases.json`
is rendered by `TemplateParityTest` (Java) and
`frontend/src/utils/templateParity.spec.js` with the same document data,
and both must show the same texts and style declarations. Adding or
changing a block means changing both renderers and adding a case (see
[`../guides/extending.md`](../guides/extending.md)).

## The "no hardcoded fallbacks" rule

This is the single most important invariant in the template system, and
it appears in `AGENTS.md` and in the block renderers:

> If no template exists in the database, throw an exception. Never
> substitute placeholder data.
>
> If a template field is empty, don't render it. Don't fall back to
> owner / customer data.

The consequences:

- `TemplateBasedPdfGenerator.getTemplateForDocumentType` walks the
  templates and looks for the first one with valid blocks. It does
  **not** fall back to inline HTML. If no block template is found, the
  PDF generation fails with an exception.
- Block renderers (frontend and backend) render a field only if it has
  a value. There is no "fall back to the customer's name if the field
  is empty" code. The one deliberate exception is the invoice-meta
  block: an empty number / date field shows the document's own value,
  because a document without its number or date is invalid.
- New blocks start with `{{variables}}`, never with sample values: the
  editor's old literal defaults (`INV-2026-001`, fixed dates) were
  printed on real invoices; `migrateTemplateSampleLiterals` (backend)
  and `migrateBlock` (editor) replace them.

The editor's sample data (`SAMPLE_DATA`) only feeds the preview.

## Variable substitution

- `{{ name }}` with spaces equals `{{name}}`.
- **Unknown variables are removed from the PDF** and flagged in the
  editor (red chip, warning) — a typo never reaches a customer, and the
  author still sees it.
- Resolved values are HTML-escaped before they enter the markup; config
  labels and titles are escaped too, colors must be hex colors and font
  names plain names.

The legacy HTML (`html_content`) uses **lowercase**
`{{variable}}` names (`{{firma}}`, `{{kunde}}`, `{{positionen}}`,
`{{netto}}`, etc.). The uppercase
English aliases (`OWNER_COMPANY`, `CUSTOMER_FIRST_NAME`,
`INVOICE_NUMBER`, `NET_AMOUNT`, …) appear only in the hard-coded
`DocumentController.getAvailableVariables` list, which is the picker
shown during DOCX/ODT import. The two registries are not bridged at
runtime — the uppercase names are a legacy naming surface for the
import flow.

## Where the editor meets the renderer

| Concern | Frontend | Backend |
|---------|----------|---------|
| Block types | `blockRegistry.js` (`BLOCK_TYPES`, preview `render`) | `TemplateBasedPdfGenerator.render*` |
| Layout | `blockModel.layoutRows` / `composePageHtml` | `TemplateBasedPdfGenerator.layoutBlocks` |
| Variables | `variableRegistry.js`, `renderBlockPreview.js` | `TemplateBasedPdfGenerator.replaceVariables` |
| Config UI | `BlockConfigPanel.vue` (generated from `fields` / `settings`) | n/a — the backend trusts the JSON it receives, but escapes and validates what it prints |
| Template CSS | `scopeCss.js` (preview only) | `cssForStyleElement` + `RestrictedUserAgent` |
| Parity | `templateParity.spec.js` | `TemplateParityTest` (shared `template-parity/cases.json`) |
| Default templates | `templatePresets.js` (new templates) | SQL seeds; `TemplateBlocksBackfill` fills templates without blocks |

## Limits and known issues

- The PDF renderer only has the standard PDF fonts (Helvetica, Times,
  Courier); other fonts print in the font of their generic family.
- No repeating page header / footer and no fixed DIN 5008 address
  window: blocks flow from top to bottom.
- The two variable registries (block path and DOCX/ODT import) use
  different naming conventions and there is no migration path between
  them. New work should use the block system.

**Next:** [`document-pipeline.md`](./document-pipeline.md) — the PDF
generation, XRechnung output, and the mail subsystem.

## Per-document layout override

Global templates serve most invoices, but occasionally a single document
needs a one-off layout — a custom header banner, a tweaked footer, a
different column set on the line-items table. To support this without
forcing the user to author a new global template, an `invoice` can carry
its own template reference that overrides the type-default picker.

### Data model

Two columns participate:

- `invoice.template_id INT NULL` — the per-document override. `NULL`
  means "use the type default" (the pre-existing behaviour).
- `invoice_template.is_document_specific TINYINT(1) DEFAULT 0` — marks a
  template row as a clone scoped to one document. Per-document clones
  are hidden from the global `/templates` list by default.

The override is not enforced as a foreign key (matches the
`document_type_id` convention). The lifecycle hooks described in
[Per-document override cleanup](#per-document-override-cleanup) below
make up for the missing FK by nulling references and removing orphan
clone rows on delete.

### Three user flows

The `DocumentModal` (`frontend/src/components/DocumentModal.vue`)
exposes a "Layout" collapsible section with three controls:

1. **Standardvorlage für diesen Dokumenttyp** (radio) — clears the
   override (`template_id = null`). This is the default.
2. **Gespeicherte Vorlage verwenden** (radio + dropdown) — picks a
   global template for the document's type. The dropdown is fed by
   `GET /api/templates/by-type/{documentTypeId}`, which excludes
   per-document clones from other documents.
3. **Layout für dieses Dokument anpassen…** (button) — opens the
   full `TemplateEditor` against the document's currently-effective
   template as a draft (the type default if no override is set, or
   the existing clone if one is). The button is disabled for unsaved
   documents (a server-side clone needs an existing `invoice.id` to
   attach to). The clone row is **not** created when the editor
   opens — see [Editor save flow](#editor-save-flow-spa-must-persist-editor-edits).

The "Layout" section collapses by default to keep the form uncluttered
for users who don't need it.

### Render path

`TemplateBasedPdfGenerator.loadTemplateForInvoice(Invoice)` (package
`de.billery.pdf`) is the single entry point. If
`invoice.templateId != null`, it loads that template directly; if the
template is missing or has no usable block JSON, it logs a warning and
falls back to the type-default picker. Otherwise, the existing
`getTemplateForDocumentType` path runs unchanged. Both the live PDF
generation (`generatePdfInvoice`) and the save-time snapshot
(`DocumentService.generateAndStoreRenderedHtml`) go through this helper,
so the override is honoured on download **and** the
`invoice.rendered_html` snapshot is regenerated consistently when the
template changes.

### Clone semantics

`InvoiceTemplateController.cloneForDocument` (via its private
`buildCloneFromSource`) performs a deep copy of the source's content columns (`blocks`,
`html_content`, `css_content`, `header_html`, `footer_html`, all four
margins), prefixes the name with `[doc #<docId>]` for traceability,
resets `is_default = 0` and sets `is_document_specific = 1`. The
controller endpoint is `POST /api/templates/from-document/{documentId}`
with body `{"sourceTemplateId": int | null, "templateData": InvoiceTemplateDto?}`.
A null `sourceTemplateId` resolves to the document's type default
server-side. When `templateData` is provided, the controller applies it
on top of the source's content (its `id` is ignored — the new row always
gets a fresh id) so the SPA can submit a freshly-edited template in
one round trip. The "Customize…" button works for both new overrides
and "edit the override I already have" flows — re-clicking it on a
document that already has an override just reopens the existing clone
in the editor (no new row is created); saving the editor then forwards
to `templateAPI.updateTemplate(...)`.

### Editor save flow (SPA must persist editor edits)

`TemplateEditor` is a pure presentational component: it owns its own
form, emits a `save` event with the edited template, and otherwise
has no awareness of the SPA's services. **The parent component is
responsible for forwarding the save event to the server.** For the
global editor at `/templates`, that's `TemplatesView.handleSave`,
which calls `templateAPI.updateTemplate(editingTemplate.id, templateData)`.
For the per-document editor mounted from `DocumentModal`, that's
`DocumentModal.onCustomTemplateSaved`, which dispatches on
`editingIsNewOverride`:

- **New override (`editingIsNewOverride === true`)** — the user opened
  the editor on a document with no override yet. On save, the modal
  calls `templateAPI.cloneForDocument(invoice.id, sourceId, savedTemplate)`.
  The server creates a fresh `invoice_template` row, applies the
  editor's content on top of the source, sets
  `is_document_specific = 1`, persists `invoice.template_id`, and
  regenerates `rendered_html` — all in one call.
- **Editing existing override (`editingIsNewOverride === false`)** —
  the document already had a per-document clone. On save, the modal
  calls `templateAPI.updateTemplate(form.value.templateId, savedTemplate)`.
  The editor's edits land on the existing row in place; no new clone
  is created.

**The clone / `template_id` write is deferred to editor save.** Opening
the editor does not bind the document to a clone — only saving does.
Cancelling the editor (clean or dirty, via "Abbrechen" or "Verwerfen")
leaves the document and the database untouched, so a user can open
the editor, change their mind, and back out without leaving a half-
adopted override behind.

The editor's local form has no `id` field. The clone's id is owned by
the modal (in the new-override case, the server returns it in the
`cloneForDocument` response; in the edit case, it lives in
`form.value.templateId`). The endpoint uses the URL path parameter for
the id (`InvoiceTemplateController.updateTemplate` does
`template.setId(id)`), so the editor's id-less body is harmless.

Forgetting the `updateTemplate` call in `onCustomTemplateSaved` is a
silent no-op — the editor's edits are dropped, the clone's `blocks`
column stays byte-for-byte equal to the source, and the PDF keeps
rendering with the global look. This was the 2026-06-20 bug: the
handler only re-fetched the template, never persisted the edits.
Diagnosed via repeated rounds of `console.log` instrumentation
(now removed); the fix is the `updateTemplate` call inside the
`else` branch of `onCustomTemplateSaved`.

**The editor's `form` must also carry the `documentSpecific` flag
through the save payload.** Without it, the controller's
`convertFromDto` defaults the flag to `false` and the first save
silently demotes a per-document clone to a global one (it would then
appear in `GET /api/templates` for every user, not just the toggle
view). `TemplateEditor.loadTemplate` copies `this.template.documentSpecific`
into `this.form.documentSpecific`; `buildPayload` spreads `form`
into the emitted payload so the flag reaches the server.

### Clone inheritance

`DocumentService.cloneDocument` carries `source.templateId` over to the
clone. The clone uses the same override template; the override-template
row itself is **not** duplicated. This keeps a one-off override scoped
to its document, not the family of clones that may descend from it.

### Per-document override cleanup

The `invoice.template_id` column is not a foreign key, so without
explicit cleanup both directions of the lifecycle leak rows:

- **Deleting a template via `DELETE /api/templates/{id}`** — the
  controller first calls `InvoiceRepository.nullTemplateIdForTemplate(id)`,
  which sets `template_id = NULL` on every invoice that referenced the
  template. The renderer then falls back to the type default at PDF
  time (with a warning from `loadTemplateForInvoice`). The number of
  affected invoices is logged.
- **Deleting a document via `DELETE /api/invoices/{id}`** — the
  controller first looks up the document, deletes the `invoice` row,
  and only then checks whether `template_id` pointed at a
  document-specific clone (`is_document_specific = 1`). If so, that
  clone row is also deleted. Global templates that happen to be
  referenced are left alone (the only way that can happen is data
  corruption, and leaving the global template intact is the safer
  behaviour).

The combined effect: deleting a document does not leave an orphan
clone row behind, and deleting a template does not leave dangling
`template_id` integers on referencing invoices.

### Visibility

`/templates` calls `GET /api/templates` which by default filters out
per-document clones. A "Dokument-spezifische Vorlagen anzeigen" toggle
sends `?includeDocumentSpecific=true` to reveal them, each tagged with
an orange "Dokument-spezifisch" badge in the grid
(`frontend/src/views/TemplatesView.vue`). Per-document
clones can be edited, deleted, or re-marked-as-default just like global
templates — they're real `invoice_template` rows.

### PDF download path and snapshot

The PDF download endpoint
(`api/controller/InvoiceController.java:195`) branches on
`invoice.templateId`. If the override is set, the endpoint calls
`pdfGenerator.generatePdfInvoice(invoice)` (which uses
`loadTemplateForInvoice` and picks up the current override template).
Otherwise it uses the save-time `rendered_html` snapshot — the existing
behaviour, which freezes the document at save time so the PDF is
consistent if the *global* template is later edited.

The "Customize layout for this document…" endpoint
(`InvoiceTemplateController.cloneForDocument`) persists the
`template_id` association and regenerates the snapshot when the user
**saves** the editor (the clone is deferred to save — see
[Editor save flow](#editor-save-flow-spa-must-persist-editor-edits)),
so the override takes effect without a second document save.

### XRechnung

The XRechnung generator (`xmlgenerator/XRechnungGenerator.java`) works on
the invoice, its items and the frozen seller / buyer data and does **not**
consult the block template, so the override has no effect on the XML output. That
is correct: XRechnung is a fixed-schema data format, not a layout.

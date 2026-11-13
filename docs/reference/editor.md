# Template Editor

> **Audience:** template authors and contributors extending the editor.
> **Scope:** the editor UI (simple and advanced mode), the block
> outline and library, the config panel, variables, validation, the PDF
> preview and saving. For what each block does see
> [`block-types.md`](./block-types.md). For the variables you can
> bind see [`variables.md`](./variables.md). For the data flow and
> persistence see
> [`../architecture/template-system.md`](../architecture/template-system.md)
> and [`../architecture/frontend.md`](../architecture/frontend.md).

The editor is opened from `/templates` (`TemplatesView`) and from the
document modal's "Layout" section (`DocumentModal`). Users are not
expected to be technical, so the editor never shows `{{curly braces}}`,
offers a simple mode first and refuses templates the PDF renderer could
not print. The component tree:

```
TemplatesView
├─ TemplateStartModal         "Neue Vorlage": document type + style preset (thumbnails)
├─ TemplateThumbnail          responsive A4 card thumbnails used in both selection screens
└─ TemplateEditor             top bar: name, document type, default, undo/redo, PDF-Vorschau, save
   ├─ DesignPanel             "Design": simple mode (collapsible tools on wide screens)
   ├─ BlockOutline            "Blöcke": order of the blocks (layers)
   ├─ BlockLibrary            "Blöcke": add a block
   ├─ (page preview)          primary workspace, rendered with renderBlockPreview()
   └─ BlockConfigPanel        settings of the selected block
      └─ VariableTextInput    text fields with variable chips
         └─ VariablePicker    searchable variable list
```

Drag-and-drop is **native HTML5**. The `vuedraggable` dependency is
declared in `package.json` but has zero imports.

---

## State model

- `form.blocks` is an **array; its order is the block order**. The
  stored JSON keeps an `order` field because the PDF renderer sorts by
  it: `blockModel.normalizeBlocks` reads it once on load (stable sort,
  legacy migration) and `serializeBlocks` writes `order = index` on
  save. Never keep both in sync while editing — that caused moved blocks
  to jump back.
- The selected block is referenced by **id** (`selectedBlockId`);
  `selectedBlock` is derived from `form.blocks`, so there is no stale
  copy. The config panel emits a changed copy of the block on every
  edit (`update`), which replaces the block in `form.blocks`.
- Every change goes through `form`, which feeds the undo history
  (`composables/useHistory.js`, typing is merged into one step) and the
  unsaved-changes guard.

---

## Simple mode ("Design")

`components/editor/DesignPanel.vue` changes the whole document with a
few controls; each is a pure function in `utils/designActions.js` that
writes the same per-block settings the advanced mode edits:

| Control | Effect |
|---------|--------|
| Logo | `header.data.logoUrl` (adds a header when there is none) |
| Akzentfarbe | `format.fontColor` of header and totals |
| Schrift / Textgröße | `format.fontFamily` of every block / `format.fontSize` = type default × 0.9 / 1 / 1.15 |
| Anordnung | "Nebeneinander": receiver address and document data get `config.width = 'half'` and are placed next to each other |
| Inhalte | adds or removes the sender address, notes, payment and footer blocks |
| Seitenränder | presets 10 / 20 / 25 mm or custom values (0–50) |

The PDF renderer has three embedded fonts: a font is printed in
Liberation Sans, Serif or Mono (the widths of Helvetica, Times and
Courier) according to its generic family (`genericFontFamily`, same
rule in Java).

"Für Fortgeschrittene: eigenes CSS" edits `cssContent`. In the preview
it is confined to the page by `utils/scopeCss.js` (selectors prefixed,
`body` / `.invoice-box` rebased onto the page, `@import` and remote
`url()` removed); the PDF renderer escapes `<` / `&` and loads no
external resources.

---

## Advanced mode ("Blöcke")

- **Add** — click a block type in the library or drag it into the
  preview or the outline. Types that reached their limit (e.g. a
  second totals table) are disabled.
- **Select** — click the block in the preview or the outline. The
  selected block shows a toolbar (↑ ↓ duplicate remove) and opens the
  config panel. On wide screens the preview remains the main workspace
  and the tool sidebar starts collapsed. At 900px and below, the editor
  switches between full-width Preview, Werkzeuge, and selected-block
  settings workspaces; selecting a block opens its settings, and closing
  the settings returns to the preview. Save and Cancel remain in the
  editor's top bar.
- **Reorder** — drag in the preview or the outline, the arrows, or
  Alt+↑/↓.
- **Width** — every block except the line items has a "Breite"
  setting; two consecutive half-width blocks share a row
  (`blockModel.layoutRows`, same rule as
  `TemplateBasedPdfGenerator.layoutBlocks`).
- **Keyboard** — Strg+Z / Strg+Y (or Strg+Shift+Z) undo/redo, Strg+D
  duplicate, Entf remove, Esc deselect, Strg+S save. Text fields keep
  their own keys.
- **Zoom** — follows the preview width ("Seitenbreite", at most 100 %)
  until the user zooms with − / + (30–150 %). A red line marks every
  A4 page end.

### Config panel

`BlockConfigPanel.vue` is generated from the block type's `fields`
(tab "Inhalt") and `settings` + font format (tab "Darstellung") in
`utils/blockRegistry.js`. Field kinds: `text`, `multiline` (both with
variable chips), `select`, `image` (logo upload via
`services/uploadService.js`), `columns` (line-item columns: visible,
label, order). Setting kinds: `toggle`, `select`, `text`, `color`;
`showIf` hides a setting while another one is off. Changes apply
immediately; there is no separate save button in the panel. Switching
an address between Absender and Empfänger swaps the defaults the user
has not changed.

---

## Variables

Text fields are `VariableTextInput` components: each `{{name}}` is shown
as a chip with an unambiguous German label ("Kundenname",
"Kunde: E-Mail"); the stored value stays the plain `{{name}}` string.

- **Insert** — "+ Platzhalter" or typing `@` opens `VariablePicker`
  (search, grouped by Ihre Firma / Kunde / Dokument / Beträge /
  Zahlung, each entry with its preview value). The chip lands at the
  cursor; with `@` the typed `@` is replaced (closing the picker keeps
  it, so e-mail addresses still work).
- **Delete** — Backspace removes a chip as one unit.
- **Unknown names** — red, struck-through chips plus a warning in the
  issues bar. The PDF renderer removes unknown placeholders.

Labels, groups and accepted English aliases come from
`utils/variableRegistry.js` (`getPickerGroups`, `getChipLabel`,
`isKnownVariable`); see [`variables.md`](./variables.md).

---

## Validation

`utils/blockValidation.js` checks the block list on every change:

- **Errors** (saving disabled, the reason is the save button's tooltip):
  a required block is missing (`invoice-meta`, `line-items`, `totals`)
  or a block occurs more often than its `limits.max`.
- **Warnings** (shown, saving allowed): unknown variables, no receiver
  address, two addresses with the same role.

Issues are listed below the preview; clicking one selects its block.
Blocks with issues are marked in the preview and the outline.

---

## Preview and PDF preview

The page preview renders each block with
`utils/renderBlockPreview.js` from a preview context
(`utils/previewData.js > buildPreviewContext`): sample document →
the company data (owner; empty owner fields stay empty, like in the
PDF) → the real document when `liveData` is given. Amounts, dates and
VAT groups are formatted like the PDF; line items include sub-items,
note rows and the automatic VAT column for mixed rates; totals use the
server's VAT groups and print the legal note for small businesses /
reverse charge / tax-exempt documents.

The block markup mirrors the Java renderer (tables instead of flexbox,
same sizes). `TemplateParityTest` (Java) and
`utils/templateParity.spec.js` render the shared cases in
`backend/src/test/resources/template-parity/cases.json` and must show
the same texts and styles; add a case there when a block changes.

**PDF-Vorschau** posts the unsaved draft to `POST /api/templates/preview`
(sample document, or the live document in the per-document editor) and
shows the PDF from the real pipeline; the dialog offers a download for
webviews without a PDF viewer. The embedded viewer requests whole-page fit;
on phones the preview uses a compact, viewport-bounded dialog so the page
scales down to the available height as well as width. Nothing is stored.

---

## Configuration contract (the editor–registry invariant)

A block type is described completely in `BLOCK_TYPES`
(`utils/blockRegistry.js`); the config panel, validation, simple mode
and library read it from there.

- **When you add a data field** — add it to `defaultData` *and*
  `fields` (label, kind, optional `placeholder` / `help`). New text
  fields default to a `{{variable}}`, never to a sample literal: the
  PDF prints what is stored.
- **When you add a config option** — add it to `defaultConfig` *and*
  `settings`, honour it in `render(data, config, ctx)` **and** in the
  matching `render*` method of `TemplateBasedPdfGenerator`, then add a
  parity case.
- `blockRegistry.spec.js` fails when a `defaultData` / `defaultConfig`
  key has no field / setting definition.

See [`../guides/extending.md`](../guides/extending.md) for the full
block-extension recipe.

---

## New templates

"+ Neue Vorlage" opens `TemplateStartModal`: the user picks the document
type and one of the presets in `utils/templatePresets.js` (Modern,
Klassisch, Minimal, Leer), each shown as a live thumbnail. The preset
(without `id`) seeds the editor; the invoice-meta title is the document
type's name and follows a later type change while it is unchanged.

`TemplateThumbnail` renders the first A4 page at a fixed logical page size,
then scales the complete page to the available card width while preserving
the 210:297 aspect ratio. Its maximum width is capped so the previews remain
recognizable in the template list and the preset gallery on narrow screens.

---

## Save / cancel

- **Save** — emits `save` with the `InvoiceTemplateDto`: metadata,
  margins, `cssContent`, `blocks` (JSON string, `order` = position) and
  `htmlContent` (blocks rendered with placeholders kept; a legacy column
  nothing reads, neither the PDF nor the DOCX/ODT exchange). The parent
  posts it (`TemplatesView.handleSave` / `DocumentModal.onCustomTemplateSaved`).
- **Cancel** — emits `cancel` to the parent view; no network call.

### Unsaved-changes guard

The editor tracks a "dirty" state from the moment its form is first
populated. Closing attempts (the in-header **Abbrechen** button or the
parent modal's backdrop click) are routed through
`requestClose()`, which is exposed by the editor and handled by
`src/composables/useUnsavedChangesGuard.js`. When the form is dirty,
the editor renders a confirmation dialog (rendered inside
`TemplateEditor.vue` at `z-index: 1100`, above the parent modal):

- **Speichern** — emits `save` (the parent's existing save handler
  runs and closes the editor on success).
- **Verwerfen** — emits `cancel` (closes the editor, changes are
  lost).
- **Abbrechen** — hides the dialog; the user stays in the editor.

ESC and backdrop-click on the dialog itself map to **Abbrechen**.

Parents that wrap the editor in a modal **must** forward their
cancel / backdrop-click handlers to the editor's exposed
`requestClose()` method instead of closing the modal directly:

```html
<TemplateEditor ref="editorRef" ... @cancel="onCancelRequest" />
<div class="modal" @click.self="onCancelRequest"> ... </div>
```

```js
const editorRef = ref(null)
function onCancelRequest() {
  editorRef.value?.requestClose()
}
```

Both production call sites follow this pattern:
`src/views/TemplatesView.vue` (global editor) and
`src/components/DocumentModal.vue` (per-document clone editor).

---

## Per-document overrides (the "Layout" section in the document modal)

`TemplateEditor.vue` is reused as the editor for per-document template
clones. The flow is initiated from the document modal's "Layout"
section (`frontend/src/components/DocumentModal.vue`):

1. The user clicks **Layout für dieses Dokument anpassen…** in the
   document modal (the button is disabled until the document has been
   saved at least once).
2. `DocumentModal.openCustomize` picks a template to open the editor
   against: if the document already has a per-document override
   (`form.templateId` set), the modal fetches the existing clone via
   `GET /api/templates/{id}`. If not, the modal fetches the document's
   type default via `GET /api/templates/default/{documentTypeId}`.
   **No clone is created at this point.** The chosen template is
   passed to the editor as a draft; a flag (`editingIsNewOverride`)
   records whether the document already had an override.
3. The editor copies the template's `documentSpecific` flag into the
   form when it is created (`loadTemplate`) so it round-trips on save.
   With `liveData` the editor hides the document-type select and the
   "Standard" checkbox (the server forces both for clones).
4. On save, `DocumentModal.onCustomTemplateSaved` dispatches on
   `editingIsNewOverride`:
   - **New override** — calls
     `templateService.cloneForDocument(invoice.id, sourceId, savedTemplate)`.
     The endpoint is `POST /api/templates/from-document/{id}` with body
     `{"sourceTemplateId": int, "templateData": InvoiceTemplateDto}`.
     The backend creates a new `invoice_template` row with
     `is_document_specific = 1`, `is_default = 0`, a name prefixed
     with `[doc #<id>]`, and the editor's `templateData` applied on
     top of the source's content. The new id is returned; the modal
     pins it into `form.value.templateId`. The endpoint also persists
     `invoice.template_id` and regenerates `rendered_html` server-side.
   - **Edit existing override** — calls
     `templateService.updateTemplate(form.value.templateId, savedTemplate)`.
     The editor's edits land on the existing clone row in place; no
     new clone is created.
5. Cancelling the editor (clean or dirty) leaves the document
   untouched — no clone row is created, no `template_id` is bound.
6. The clone appears in `/templates` only when the user toggles
   "Dokument-spezifische Vorlagen anzeigen" on; it is otherwise hidden
   to keep the global list uncluttered.

A clone can be deleted from the global templates view like any other
template. The controller nulls `invoice.template_id` on every
referencing invoice before deleting the row, so the renderer falls
back to the type default at PDF time (with a warning from
`loadTemplateForInvoice`). The reverse direction — deleting a document
— also cleans up: the invoice controller deletes the per-document
clone row (if any) alongside the invoice, so no orphan clone rows
survive (see
[`../architecture/template-system.md`](../architecture/template-system.md#per-document-override-cleanup)).

### Guards against accidental override loss

The per-document override is durable on the server, but easy to throw
away by accident from the SPA's "Layout" section. Two confirmation
guards in `DocumentModal.vue` prevent that:

- **Guard A — `onLayoutModeDefault`** (the "Standardvorlage" radio click).
  When the document already has a per-document override
  (`props.invoice.templateId != null`), the radio click is intercepted
  and a `confirm()` asks the user to confirm. On cancel, the form's
  `templateId` is reverted to the existing override so the radio state
  reflects the actual server state.
- **Guard D — `submit()`** (safety net). Right before the save, if the
  form has no override (`form.value.templateId == null`) but the document
  on the server does (`props.invoice.templateId != null`), a `confirm()`
  asks the user to confirm. This catches every other path that could
  silently null `templateId` — stale modal resets, dropdown deselects,
  or a path the radio guard didn't cover. The guard is suppressed for
  new documents (`props.invoice == null`), offer→invoice conversions
  (`convertFromOffer`), and cost-calculation→offer conversions
  (`convertFromCostCalc`).

Both guards are no-ops when the document has no override, so the
common case (no customisation) is unaffected. The backend
(`DocumentService.updateDocument`) just persists what the SPA sends —
the guards are purely UX.

The corresponding test suite is
`frontend/src/components/DocumentModal.spec.js`.

### Live preview data (only for the "Customize…" flow)

When `DocumentModal` opens the editor via the per-document flow, it
builds a `liveData` object and passes it as the `:live-data` prop:

```js
{
  variables: { ... }, // {{...}} values (customer, invoice header, totals, payment reference)
  totals:    { ... }, // net, gross, VAT groups (server VatCalculator), legal note
  items:     [ ... ], // line-item tree incl. per-line VAT rate and note rows
  invoiceId: 42       // for "PDF-Vorschau" with this document
}
```

`buildPreviewVariables` / `buildLineItemsData` (`utils/invoiceVariables.js`)
produce it from the invoice DTO (`vatGroups`, `taxTreatment`, items with
`taxRate`) and the customer. The preview toolbar switches between
"Dieses Dokument" and "Beispieldaten".

**Critical invariant:** live data only enters the preview context; the
saved `blocks` JSON is **never** mutated with the real-document values,
so a per-document clone doesn't leak its source document's data into the
stored template (`TemplateEditor.spec.js > shows the real document in live
mode without saving its data`). The global editor (opened from
`/templates`) does not pass `liveData`; it shows the sample document with
the real company data.

The customer is fetched on click (`DocumentModal.openCustomize`),
reusing the modal's `customers` prop if the customer is already
loaded.

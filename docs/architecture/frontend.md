# Frontend

> **Status:** chapter 3 of the `docs/architecture/` series.
> **Audience:** contributors and maintainers.
> **Scope:** Vue 3 application structure, router, service layer, the editor
> subsystem, and the two variable registries that drive the template engine.
> For the higher-level system view see [`overview.md`](./overview.md). For
> the block-by-block template mechanics see
> [`template-system.md`](./template-system.md).

## Source layout

Source root: `frontend/src/`.

```
main.js                 createApp + router + mount; imports the three stylesheets below
styles/                 design system: tokens.css, base.css, components.css
App.vue                 nav bar, global Cmd+K search, <router-view>
router/                 Vue Router config
views/                  one component per top-level route
components/             shared components
  editor/               block editor pieces (DesignPanel, BlockOutline, BlockLibrary, BlockConfigPanel,
                        VariableTextInput, VariablePicker; InvoiceItemRow for the document editor)
  template/             template management pieces (TemplateStartModal, TemplateThumbnail)
  SpotlightSearch.vue   Cmd+K search modal
  *Modal.vue            create / edit dialogs (customer, article, owner, document, …)
  TemplateEditor.vue    orchestrates the block editor
services/               axios API clients (one per resource + baseService)
utils/                  pure JS — registries, sanitisation, preview; vat.js mirrors the
                        backend VAT rules for editor totals (display only, the server recomputes)
composables/            reusable composition functions (useDocumentType, useUnsavedChangesGuard, useHistory)
data/                   static lookup tables (documentTypes)
```

There is no TypeScript. The `@` import alias maps to `./src` (see
`jsconfig.json`); use it for any non-trivial import path.

## Application bootstrap

`main.js` first calls `initAuth()` (see "Sign-in (SaaS mode)" below), then creates the
app, creates the router (`createAppRouter()`) and mounts. The router must be created after
`initAuth`: the browser history reads the address when it is created, and `initAuth` replaces
the sign-in callback URL (`/auth/callback?code=…`) with the address the user came from. A router
created at import time navigated back to the callback URL, which has no route (an empty page
after every sign-in). Unknown paths redirect to the dashboard. No global component registration, no Pinia /
Vuex — state is local to views and modals, and shared state is held by the parent
view that's open; the only global state is `services/session.js`.

`App.vue` is the shell:

- A `<nav class="navbar">` with the app title, the search button (which
  opens `SpotlightSearch.vue`), and links to the main routes
  (`/customers`, `/invoices`, `/articles-services`).
- An "Einstellungen" `router-link` to `/settings`. The settings view is the single entry point for owner management, templates, and any future settings surface; the navbar has no dropdown.
- An `AccountMenu` at the end of the navbar, only in SaaS mode, with actions only: the tenant
  switcher (more than one company), open invitations to accept, sign-out. Who is signed in, the
  active company and the role are shown in Settings → Konto (`AccountSettings`, every role),
  which also switches to another company.
- A `main` containing `<router-view />`, or the `NoTenantNotice` for a signed-in user
  without any tenant.
- A `SpotlightSearch` modal listening for the global `Cmd+K` / `Ctrl+K`
  keybind (`App.vue:51-55`).

Navigation adapts to available viewport width: the full top bar includes the
brand, search button, and route links; below 1100px search is hidden, and
below 851px the brand is replaced by a Dashboard link. At 760px and narrower,
the top bar becomes a compact header with the brand centered; the fixed
five-destination bottom bar provides route navigation. The bottom bar accounts
for mobile safe areas and the page reserves space beneath its content so it does
not cover the last controls. Main content is capped at 1600px on
wide displays. Dense data tables remain scrollable within their labelled table
regions on narrow screens; list filters and modal forms wrap or stack for
touch-sized controls. Page titles that duplicate a primary navigation
destination are visually hidden at all screen sizes while remaining available
to screen readers; detail-page and dialog headings remain visible. The document
list and Catalog use matching segmented button groups on larger screens and
labeled native selectors on phones for their respective document and catalog
categories. Rows in the document, customer, owner, Catalog, search, dashboard,
and customer-detail lists are keyboard-focusable and open the row's primary
view or edit experience when selected. Row-specific secondary and destructive
actions are presented inside that experience rather than in a crowded table
action column; modal action groups wrap within the dialog width on constrained
screens. Standalone controls within a row (such as equipment usage) keep their
own click behavior.
The Catalog's equipment table shows the amortisation per piece of equipment
(`utils/equipmentAmortisation.js`): bar and percentage stop at 100 % and then
read "✓ amortisiert" (decided by the amounts, so 99.96 % never reads as done;
the API keeps the real `paybackPercent`), equipment without a purchase price
shows no bar, and a hint above the table (linked to the column header)
explains what counts: net revenue of linked services on fully paid invoices.
Clicking the value opens the usage dialog (`GET /api/equipment/{id}/usage`): a
wide (up to 1000 px) labelled dialog with a summary line, the lines and a sum
row; it closes with Escape and returns the focus to the cell.
The invoice / offer editor uses a viewport-bounded
dialog with equal-width form columns, line-item controls constrained to the
available width on phones and portrait-width tablets, a bounded scrolling
field area, and save/cancel actions fixed beneath it. On mobile, closing the
software keyboard restores the viewport scale captured before a text field
was focused, even if that field remains focused; leaving the field also
restores the captured scale. Moving between fields preserves that baseline.
Editable controls in the document and line-item editors use a 16px font at
widths up to 800px, avoiding iOS Safari's common automatic focus-zoom trigger;
the viewport reset handling remains as a fallback for browser-specific zoom.

### Mobile keyboard zoom diagnostics

In the Vite development build, append `?keyboardZoomDebug=1` (for example,
`/invoices?keyboardZoomDebug=1`) and open the document editor to show the
keyboard-zoom panel. It reports the live visual viewport, focus baseline, and
viewport metadata. **Start simulation**, **Keyboard open**, and **Keyboard
close** feed synthetic viewport heights through the same keyboard detection
and reset handler used by real viewport resize events. When the browser exposes
the VirtualKeyboard API, its `geometrychange` event directly confirms keyboard
open/close; the visual-viewport resize handler is the fallback and waits for
viewport expansion to settle before requesting a reset. This verifies that the
application detects the close and requests a reset. The temporary scale
constraint remains in place for 600ms before the original viewport metadata is
restored, allowing time for iOS Safari to apply the change. This does not emulate
the browser's native on-screen keyboard or prove that a particular mobile
browser honors the viewport metadata change.

The same opt-in panel buffers up to 250 diagnostic events in memory. It records
the keyboard/focus/resize/reset handler names and timings, browser and screen
dimensions, visual viewport scale and offsets, active control type and rendered
rect/font size, document/editor region rects and scroll sizes, VirtualKeyboard
API availability/geometry, and viewport metadata. It never reads form values,
invoice/customer data, or identifiers. Nothing is sent until **Send
diagnostics** is tapped. That button posts the bounded, sanitized log to the
same-origin Vite development server at `/__keyboard-zoom-logs`. The server
appends each complete session as one JSON line to
`frontend/logs/keyboard-zoom-diagnostics.jsonl` and prints only the file path
and event count to the terminal. This log directory is git-ignored. To test
from an iPhone on the same network, run `npm run dev -- --host 0.0.0.0`, open
the Vite server's LAN address with `?keyboardZoomDebug=1`, reproduce the
keyboard close, then tap **Send diagnostics**. Upload or share the logfile
instead of copying the large terminal output. The receiver rejects cross-origin
requests and payloads larger than 128 KiB. The panel and receiver are
development-only and are absent from production builds.

## Router

`router/index.js:11` defines nine routes, all using `createWebHistory`:

| Path | View |
|------|------|
| `/` | `DashboardView` |
| `/customers` | `CustomersView` |
| `/customers/:id` | `CustomerDetailView` |
| `/invoices` | `InvoicesView` |
| `/articles-services` | `ArticlesServicesView` |
| `/owners` | `OwnersView` |
| `/templates` | `TemplatesView` |
| `/search` | `SearchView` |
| `/settings` | `SettingsView` |

There are no route guards, no nested routes, no per-route meta. Adding a
new top-level page is a one-line change plus a new view file.

## Sign-in (SaaS mode)

A self-hosted install has no sign-in: `initAuth()` reads `GET /api/config`, sees
`{"mode": "selfhost"}` and the app mounts at once; no header is added to any call. With
the backend's `saas` profile the same call returns the OIDC settings of the identity
provider (`issuer`, `clientId`, `scope`, `audience`), and `services/authService.js`:

1. creates an `oidc-client-ts` `UserManager` for the **authorization-code flow with
   PKCE**. The signed-in user, and with it the **access token, is kept in memory only**
   (`InMemoryWebStorage`); `sessionStorage` holds just the transient sign-in state (the
   PKCE verifier) for the redirect. Nothing is written to `localStorage` except the id of
   the last tenant. A page reload therefore signs in again, which the provider answers
   from its own session without a prompt; the page the user was on is kept in the
   sign-in `state` and restored after `/auth/callback`. A callback whose sign-in state is
   gone (the callback URL reloaded or restored from history, "No matching state found")
   starts one fresh sign-in instead of failing on that URL; a second failure in a row is
   reported (`billery.signinRetry` in `sessionStorage`);
2. reads `GET /api/me` (user, tenants, roles) and chooses the active tenant: the one used
   last time (`billery.tenant` in `localStorage`) if the user still belongs to it, else the
   first. Without any tenant `session.status` is `no-tenant` and `App.vue` shows
   `NoTenantNotice`;
3. adds `Authorization: Bearer ...` and `X-Tenant-Id` to every call through an
   interceptor of the shared `apiClient`. The token is renewed silently
   (`automaticSilentRenew`; the provider should issue refresh tokens to public clients, which
   is why the default scope contains `offline_access`). Without a refresh token
   `oidc-client-ts` loads `/auth/callback` in a hidden iframe: there `initAuth` only hands
   the response to the parent (`signinSilentCallback`) and mounts nothing, and the backend
   lets the same origin frame that one page (`frame-ancestors 'self'`, `X-Frame-Options:
   SAMEORIGIN`; every other page keeps `'none'` / `DENY`). A **401** triggers one silent
   renewal and one repeat of the call; if the renewal fails the browser is sent to sign in
   again, and a second 401 after the renewal is reported instead of looping. A **403**
   re-reads the tenants once per page load and reloads if the user lost the active one;
4. lets a user **without a tenant** accept an invitation or create a company (`NoTenantNotice`, `teamService`, then `openTenant` stores the new tenant and reloads), lets a user **with** a tenant accept further invitations under "Einladungen" in `AccountMenu`, and lets owners and admins manage members, invitations and the closing of the tenant in Settings (`TeamSettings`, built from the shared classes `.settings-section` / `.section-title`, `.card`, `.data-table`, `.form-control`, `.btn`; the offered roles follow the acting role, the backend enforces the same rules); after an invitation it says whether the mail went out and, if not, how to tell the person;
5. switches tenants in `AccountMenu` or Settings → Konto (`setActiveTenant`): the choice is
   stored and the page reloads, so nothing of the previous tenant stays on screen;
6. shows a card with "Erneut versuchen" (`main.js`, `.start-error`) when the sign-in cannot be
   started at all.

All SaaS screens (`AccountMenu`, `AccountSettings`, `TeamSettings`, `NoTenantNotice`, the start
error) use the shared design-system classes (`.card`, `.form-control`, `.btn`, `.badge`,
`.settings-section` / `.section-title`).

The SPA is a public client: no secret is involved, and `/api/config` is open.

## Service layer

`src/services/` is the only place that talks to the API. The pattern is
identical for every resource:

- `baseService.js` exports a shared `apiClient` (an `axios.create` with
  `baseURL: ${VITE_API_BASE_URL || window.location.origin}/api`, see `apiBase.js`; in SaaS
  mode its interceptors add the token and tenant and handle 401/403). When
  `VITE_API_BASE_URL` is empty (e.g. when the backend hosts the SPA from
  the same origin), it falls back to `window.location.origin`, so requests
  go to the same host that served the page.
- `BaseService` is a thin wrapper that adds the four HTTP verbs and a
  consistent `handleError` that pulls the server-side error message out of
  the response body.
- Every resource service (`customerService.js`, `invoiceService.js`, …)
  imports `BaseService` and exposes named methods like `getAll()`,
  `getById(id)`, `create(dto)`, `update(id, dto)`, `delete(id)`. There's
  no shared interface — each service is free to add resource-specific
  methods (e.g. `invoiceService.generatePdf(id)`).

Binary downloads use `responseType: 'blob'` on the shared `apiClient`
(e.g. `invoiceService` PDFs, `templateService.previewPdf` for the
template editor's PDF preview). Logo uploads go through
`uploadService.uploadLogo`.

## State management

There is no global store. Each view keeps its own state in `data()` and
either fetches directly on mount or after a user action. Cross-component
state is passed via `props` / `$emit` or via the router (the
`/customers/:id` route is the canonical way to open a customer detail).

This is a deliberate choice — for the size of the application, a global
store would add ceremony without solving any real problem. If you find
yourself prop-drilling three levels deep, that's a signal to either:

1. Lift the state into the parent view, or
2. Introduce a small Pinia store scoped to that feature.

Do not introduce Vuex.

## The template editor subsystem

This is the most complex part of the frontend. It's worth a dedicated
section because it has its own internal state, its own component
hierarchy, and two parallel variable registries.

### Component tree

```
TemplatesView                 /templates — list (thumbnails), create, delete
  ├─ TemplateStartModal       new template: document type + preset
  └─ TemplateEditor           the editor (also opened from DocumentModal)
      ├─ DesignPanel          collapsible "Design" tools: simple mode
      ├─ BlockOutline         "Blöcke" tools: block order, selection
      ├─ BlockLibrary         "Blöcke" tools: add blocks (disabled at their limit)
      ├─ page preview         primary workspace: preview-row × N → preview-block-wrapper (v-html)
      └─ BlockConfigPanel     settings of the selected block
           └─ VariableTextInput → VariablePicker
```

`BlockConfigPanel.vue` is generated from the block type's `fields` /
`settings` and emits a changed copy of the block; it keeps no copy of
its own. UI behaviour is documented in
[`../reference/editor.md`](../reference/editor.md).

The editor prioritizes the page preview: the tool sidebar is collapsed by
default on wide screens, while selecting a block opens its settings beside
the page. At 900px and below, Preview, tools, and selected-block settings
become separate full-width workspaces so the A4 page is not squeezed between
panels. The editor's top bar remains available for save and cancel actions.

### Dirty state and the unsaved-changes dialog

The editor is wrapped in a parent modal in two places
(`TemplatesView.vue` and `DocumentModal.vue`), so a naive backdrop click
or cancel button would discard in-progress edits silently. To prevent
this, the editor tracks a dirty state via
`src/composables/useUnsavedChangesGuard.js`, which is created with a
getter for `this.form` and stores a JSON snapshot once the form is
fully populated. The unsaved-changes dialog itself is rendered inside
`TemplateEditor.vue` (so it inherits the editor's z-index context —
`z-index: 1100` puts it above the parent modal's `z-index: 1000`
backdrop). The dialog is purely presentational; the resolution logic
lives in the composable. Parents must forward their cancel and
backdrop-click handlers to the editor's exposed `requestClose()`
method (see [`../reference/editor.md`](../reference/editor.md#save--cancel)).

### Data model inside the editor

A template is a single `InvoiceTemplateDto` from the API. The fields
that drive the editor are `blocks` (JSON string), the margins and
`cssContent`; `htmlContent` is generated on save for the DOCX/ODT round
trip.

Each stored block is:

```js
{
  id: 'uuid',
  type: 'header' | 'address' | 'invoice-meta' | 'line-items' | 'totals' | 'payment' | 'notes' | 'footer',
  order: 0,                     // stored only; the editor uses the array position
  data: { ... block fields ... },
  config: { ..., format: { ... }, width: 'full' | 'half' }
}
```

`utils/blockModel.js` converts between the stored form and the editor
form (`normalizeBlocks` / `serializeBlocks`) and holds the pure block
operations (create, move, duplicate, layout rows). The editor state is
`form` (array order = block order) plus `selectedBlockId`; undo/redo
(`useHistory`) and the unsaved-changes guard both watch `form`.
Drag-and-drop uses native HTML5 events in `TemplateEditor.vue` and
`BlockOutline.vue`; the `vuedraggable` dependency is listed but unused.

### The two variable registries

This is the part most likely to confuse new contributors. **There are two
separate variable systems**, and they exist for historical reasons (the
project used to ship a free-form HTML template editor; the block-based
editor was added on top and now co-exists with it).

| File | Variable style | Used by |
|------|----------------|---------|
| `utils/variableRegistry.js` | lowercase: `{{firma}}`, `{{kunde}}`, `{{rechnungsnummer}}`, `{{netto}}`, `{{iban}}` | Block-based editor and its render path. |
| `utils/templateVariables.js` | uppercase, prefixed: `{{OWNER_COMPANY}}`, `{{CUSTOMER_FIRST_NAME}}`, `{{INVOICE_NUMBER}}` | Legacy HTML template path (DOCX/ODT import, `DocumentController`'s `/variables/list` and `/variables/validate` endpoints, `DocumentConversionService`). |

Both registries are static JS arrays of `{ name, label, type, category }`
records. The block system uses `getPickerGroups()`, `getChipLabel()`
and `isKnownVariable()` from `variableRegistry.js`; the legacy system
uses `VARIABLE_CATEGORIES` / `SPECIAL_VARIABLES` from
`templateVariables.js`.

The renderers in the two paths know nothing about each other:

- The block renderer is `BLOCK_TYPES[i].render(data, config, ctx)` in
  `blockRegistry.js`, called through `utils/renderBlockPreview.js`,
  returning an HTML string that the editor's `v-html` puts in the
  preview pane.
- The legacy renderer is the block of HTML stored in
  `invoice_template.html_content`, with `{{OWNER_*}}` placeholders
  substituted by the backend (`DocumentController`'s conversion path).

A new block on the block side can be added without touching the legacy
side, and vice versa. `TemplateBasedPdfGenerator` reads `blocks`
rather than `html_content`; only the import / export endpoints still
depend on the legacy path.

### Block configuration contract

A block type is described completely in `BLOCK_TYPES`
(`utils/blockRegistry.js`): defaults, `fields` and `settings` (the
config panel's form), `limits` (validation and library),
`formatDefaults` and the preview `render`. The config panel
(`components/editor/BlockConfigPanel.vue`) is generated from it, so a
new option is not forgotten in the UI; `blockRegistry.spec.js` fails
when a default key has no field / setting definition.

If you add a new config key:

1. Add it to `defaultConfig` **and** `settings` in `blockRegistry.js`.
2. Honour it in the block's `render(data, config, ctx)`.
3. Honour it in the matching `render*` method of
   `TemplateBasedPdfGenerator` (Java).
4. Add a case to `backend/src/test/resources/template-parity/cases.json`
   so both renderers are checked.

### The preview render path

The page preview, the template thumbnails and the parity test all
render a block with `renderBlockPreview(block, ctx)`: variables
resolved from the preview context (`utils/previewData.js`), data
escaped with `escapeBlockData`, then `render(data, config, ctx)`. The
result is **an HTML string**, bound with `v-html`. This means:

- The render function is the security boundary. Data fields arrive
  escaped; `ctx` values (line items, labels) are escaped inside
  `render`; colors and font names from `config` are validated
  (`safeColor`, `getFormat`). Template CSS reaches the preview only
  through `scopeCss` (confined to the page, no `@import` / remote
  `url()`).
- The "no hardcoded fallbacks" rule from `AGENTS.md` means the render
  function must not invent values for missing data; the only fallback
  is the invoice-meta block's number / dates, exactly like the PDF.

### Sanitisation

`utils/sanitize.js` wraps DOMPurify. `utils/templatePreview.js` calls
`sanitizeHtml(processedContent)` before injecting the result into the
preview iframe.

Block fields are **plain text**. The block renderers interpolate their
data into HTML strings, so `renderBlockPreview` passes the
variable-resolved data through `escapeBlockData()` (`utils/sanitize.js`)
before calling `render()`. That
keeps user input and real customer data (live preview) from injecting
markup, and matches the backend, which escapes the same fields for the PDF.

Semgrep (`pr-sast.yml`) flags every `v-html`. A reviewed use carries a
`<!-- nosemgrep: javascript.vue.security.audit.xss.templates.avoid-v-html.avoid-v-html -- reason -->`
comment on the line above (the rule id must be the full one, including the
doubled last segment). New `v-html` must render escaped or `sanitizeHtml()`
output.

Errors: the shared `apiClient` (`services/baseService.js`) has a response
interceptor that copies the backend's `{"message": ...}` into
`error.message`, including for `responseType: 'blob'` downloads, so
`'... fehlgeschlagen: ' + err.message` shows the real reason.

## Modal pattern

Dialogs follow a consistent pattern. Examples in `components/`:

- `CustomerCreateModal.vue`, `ArticleServiceCreateModal.vue`,
  `OwnerModal.vue`, `DocumentModal.vue`, `DocumentViewModal.vue`,
  `ConvertModal.vue`.

The pattern is: the parent view owns an `isOpen` ref and binds the modal
with `v-if="isOpen"`. The modal emits `close` (with no payload) and
`submit` / `save` (with the form payload). The parent closes the modal
on `close` and re-fetches its list on `save`. The modal never fetches
data on its own except for the resources it needs to populate dropdowns
(e.g. the customer list in the article/service creation modal).

**Form fields.** Every field has an `id` its `<label for>` points to, a `name` and an
`autocomplete` token (`given-name`, `family-name`, `organization`, `address-line1`,
`postal-code`, `address-level2`, `tel`, `email`, `url`; `off` for fields autofill has no token
for, such as tax id, IBAN or notes), plus `type` / `inputmode` where they fit (`tel`, `email`,
numeric keyboards), so browsers and phones can fill the owner and customer forms. Autofill knows
the street only together with the number (`address-line1`); `utils/addressFields.js`
(`splitStreetAndNumber`, on `change` of the street) moves a trailing number into an empty house
number field. On phones every text field is 16px (`styles/base.css`), or iOS zooms in on focus.

This is intentionally simple. If a modal grows beyond a single form
(e.g. multi-step wizards), prefer a sub-component per step rather than
a state machine inside the modal.

### Document editor — `DocumentModal.vue`

**Customer selection** is `components/CustomerPicker.vue`, a combobox (`role="combobox"` /
`listbox`, arrow keys, Enter, Escape) that searches `GET /api/customers/page?sort=name` on the
server (250 ms debounce, 20 results with a "… weitere" hint, focus shows the first 20
alphabetically). `v-model` is the customer id, `v-model:customer` the selected customer; for an
existing document it loads the customer by id to show the name. Enter never submits the form
while the list is open. Neither `InvoicesView` nor `DashboardView` loads the customer list for
the modal any more. `CustomersView` pages and searches on the server as well (50 per page,
300 ms debounce, superseded responses dropped).

The document editor modal owns a `form.items` array of trees: each
item has an optional `children: []` of sub-items. The recursive row
component `components/editor/InvoiceItemRow.vue` renders one item
plus its children, with depth-based indent. It receives the
parent array and its own index so it can splice itself out on
remove; the `+ Unterposition` button opens a small picker
(Artikel / Leistungen / Freitext) that pushes a new child into
`item.children`. The maximum visible depth is 2 (root + one level of
sub-items); the data model and the backend support deeper
nesting, but the v1 UI does not expose it.

The view modal `DocumentViewModal.vue` flattens the tree into a
position-numbered list at render time (`flatItems` computed) and
shows child rows with the same indented/grey description style as
the PDF.

### Action bar of the view modal

Everything a user can do with an open document sits in one bar at the
bottom of `DocumentViewModal.vue` (`.modal-footer`, sticky, so it stays
visible while a long document scrolls). The header only shows the title,
the status badge stays in the details. The bar has three groups in two
rows:

| Group | Content | Source |
|-------|---------|--------|
| top row, the lifecycle strip (`.action-group-lifecycle`) | a caption and the next lifecycle step; the first button is the **only** `.btn-primary`, further status changes are secondary | `lifecycleActions`, `lifecycleCaption` |
| row below, left (`.action-group-document`) | PDF, E-Rechnung (issued invoices, credit notes), Duplizieren, Bearbeiten, Wiederherstellen | template |
| row below, right (`.action-group-end`) | "Mehr" menu, Schließen | `menuActions` |

- **Two kinds of buttons, kept apart:** the strip changes the document's
  status, the row below works with the document as it is. Side by side
  in one row, "Angenommen" read like one more option next to "PDF". The
  strip therefore has a row, a tint (`--color-primary-soft`, border
  `--color-primary-border`) and a caption of its own; the caption also
  names the group for screen readers (`aria-labelledby`). The caption
  follows the `kind` of the actions: `'status'` buttons are named after
  the status they set and get "Status setzen auf:" (offers and cost
  calculations: "Gesendet", "Angenommen", "Abgelehnt"); `'step'` buttons
  are named after what they do and get "Nächster Schritt:" ("Ausstellen",
  "Zahlung erfassen", "In Rechnung", "In Angebot"). Without a next step
  there is no strip and the bar is one row. A new lifecycle action goes
  into `lifecycleActions` with its `kind`, never into the document row.

- **Next step:** invoice draft → "Ausstellen"; open invoice → "Zahlung
  erfassen"; offers and cost calculations → the statuses
  `OFFER_TRANSITIONS` allows, without `CANCELLED`, plus "In Rechnung" /
  "In Angebot" when the parent list allows converting
  (`listActions.convertToInvoice` / `convertToOffer`). When there is no
  next step (paid, cancelled, converted, credit note) the PDF download is
  the primary button, so the bar always has exactly one.
- **"Mehr" menu:** the rare and destructive actions, each with a
  confirmation: "Stornieren" (invoice: creates the credit note; offer /
  cost calculation: the final status `CANCELLED`), "Archivieren", and
  "Löschen" (emits `delete-request`; the list asks and deletes). The
  button is absent when the menu would be empty. It is a menu button
  (`aria-haspopup="menu"`, `aria-expanded`, `role="menu"` /
  `role="menuitem"`): it opens upward, focuses the first entry, the arrow
  keys, Home and End move, Escape closes it with the focus back on the
  button, a click outside closes it.
- **Forms replace the bar:** "Zahlung erfassen" and "Duplizieren" show
  their form in the footer instead of the bar (`showPaymentForm` /
  `showCloneForm`, both cleared by `resetFooter`) and focus
  its first field; "Abbrechen" brings the bar back. A refused action
  shows its message above the bar (`.action-error`, `role="alert"`).
- **Which actions the list offers** (edit, archive, restore, delete,
  convert) still comes from the parent through the `listActions` prop;
  the dashboard and the customer view pass fewer, so the bar is shorter
  there.
- **Phones (≤ 600 px):** the strip's caption stands above its buttons,
  which share the width; the other buttons share the rows below.

A new action goes into `lifecycleActions` (a step of the document's
life, with its `kind`), the template's document group (something done with the document)
or `menuActions` (rare or destructive, with a confirmation), never
back into the header. `DocumentViewModal.spec.js` asserts the bar per
document type and status; the smoke suite opens a draft and checks the
primary button, the captioned strip and the menu.

### `item_type` is a domain discriminator, not a UI preference

Root rows are always `ARTICLE` or `SERVICE` — there is no
"Freitext" tab at the document level. Pricing is only meaningful
when the row references a catalog entry, so the editor restricts
the top-level picker to the catalog tabs.

A `CUSTOM` row is a free-text note and may only appear as a
sub-item. `InvoiceItemRow.vue` detects this via the `isNote`
computed and hides the qty / unit / unit-price / total columns,
swaps the `Beschreibung` input for a multi-line `<textarea>`, and
tints the row with an amber left border. The same row shape
flows through the API; the backend normalises any pricing fields
on a `CUSTOM` row to neutral values (`quantity=1`, `unit_price=0`,
`total_price=0`, `unit=""`) at both the service and repository
layers so the invariant cannot be bypassed by a malformed DTO.

## Composables and static data

- `composables/useDocumentType.js` — encapsulates the
  document-type → label / strategy mapping. Use this from any view that
  needs to display document-type names in the UI.
- `data/documentTypes.js` — static lookup table for dropdowns. The
  values here must match the IDs in `V1__baseline.sql` (`document_type` and
  `document_status` rows). The template start gallery's presets live in
  `utils/templatePresets.js` (built from the block registry).

## Design system

Files in `frontend/src/styles/`, imported once in `main.js`:

| File | Content |
|------|---------|
| `tokens.css` | `:root` custom properties. Palette (`--blue-600`, `--gray-500` …) and **roles** (below). Spacing `--space-1…8` (4 px steps), radii `--radius-sm/md/lg/xl`, type `--font-size-xs…2xl` / `--font-weight-*`, shadows, stacking `--z-modal` (1000) and `--z-modal-nested` (1100, the template editor's unsaved-changes dialog over a modal). |
| `base.css` | Box-sizing and margin reset, page font, one `:focus-visible` ring. |
| `components.css` | The shared classes listed in the conventions. |

Roles: `--color-primary` (+ `-hover`, `-active`, `-soft`, `-border`) for the main action and
selection; `--color-danger`, `--color-success`, `--color-warning` (+ `-soft` backgrounds) for status
and destructive actions; `--color-text` / `-strong` / `-muted` / `-subtle`; `--color-border` /
`-strong`; `--color-surface` / `-subtle` / `-muted`; `--color-nav`. A theme (dark mode, a white-label
tenant) replaces the roles, not the components.

**The guard:** `styles/colorLiterals.spec.js` fails when a `.vue` file has a hex / `rgb()` / `hsl()` literal in
its style blocks. The migration is finished, so `colorLiterals.baseline.json` is empty (`{}`); the
mechanism stays as a per-file allowance (`UPDATE_COLOR_BASELINE=1 npm run test:run -- colorLiterals`
rewrites it, and the spec fails if a file is *below* its baseline until it is lowered). Use a role or add
one to `tokens.css`. The document preview of the template editor is exempt (`TemplateThumbnail.vue`,
`blockRegistry.js`, `previewData.js`): its colours come from the template and mirror the PDF (renderer
parity); the sheet of the editor preview uses `--color-paper`, which stays white in every theme.

**Migration status:** every view and dialog is migrated: no colour literals, no own `.btn-*`,
table or modal-shell copies in any component. The status pills `.status`, `.card`, `.btn-outline` and the
modal shell are shared. Vitest does not process CSS, so check the result with `npm run build` and a look at
the screen, and check a removed rule did not leave part of a grouped selector behind.

**Writing a view or dialog:** use roles (`var(--color-…)`) instead of literals, the shared classes instead
of your own `.btn-*` / table / badge copies, and put `btn` next to the variant class
(`class="btn btn-primary"`). A scoped rule in the component wins over the global class, so only add
what is specific to the view (layout). A full-screen modal on phones sets `padding: 0` on its backdrop.

## Conventions (frontend-specific)

- **No TypeScript.** Don't introduce `.ts` / `.tsx` files. The path
  alias `@` is the only concession to ergonomics beyond raw relative
  imports.
- **Options API is acceptable.** Most components use the Options API
  with `data()` and `methods`. New components may use `<script setup>`
  if they fit; don't mix styles in the same file.
- **Design system:** colours, spacing, radii, type, shadows and
  z-index layers are CSS custom properties in `styles/tokens.css`; a
  component writes `var(--color-primary)`, not `#2563eb`. Components
  use the roles (`--color-*`), never the palette (`--gray-500`)
  directly. Shared classes (`.btn` with `.btn-primary` /
  `.btn-secondary` / `.btn-danger` / `.btn-success`, `.form-control`,
  `.data-table`, `.badge-*`, `.card`, `.status`, `.modal-backdrop` / `.modal-panel` with `-sm` / `-lg`) live in
  `styles/components.css`; a view adds only its own layout. Inside a
  `.modal-panel` the `.form-group` and `.form-actions` are styled by the
  panel, so a modal only needs the markup. A full-screen modal on phones
  (`DocumentModal`, `DocumentViewModal`) sets `padding: 0` on its backdrop in
  its own 600 px rule, because the shared rule leaves 8 px around a panel. See
  [Design system](#design-system) below.
- **Other CSS is per-component `<style>` blocks** (some `<style scoped>`,
  others global); `App.vue` defines the navbar. There is no CSS
  preprocessor.
- **No ESLint / Prettier.** The code style is whatever is in the
  existing files. Watch the imports in neighbouring files before
  adding a new dependency.
- **The editor render path is the security boundary** for any
  user-supplied content. New `v-html` bindings must use
  `sanitizeHtml` unless the content is generated by
  `renderBlockPreview` (escaped block data) or is template CSS passed
  through `scopeCss`.

## Testing

- Vitest ^5.0.5 with the `jsdom` environment. See `vitest.config.js`.
- Spec files live next to the source: `Foo.vue` ⇒ `Foo.spec.js`,
  `foo.js` ⇒ `foo.spec.js`. The configured glob is
  `src/**/*.{test,spec}.{js,…}` but in practice only `.spec.js` is used.
- Test surfaces: `utils/*`, `composables/*`, `components/*`,
  `components/editor/*`, `components/template/*` (the plugin diagnostics
  spec is under `src/utils/` to match the Vitest include glob;
  `npm run test:run` prints the current numbers). New
  components should ship with at least one spec covering the render or
  interaction that was the reason for the change.
- `npm run test:run` for a single run, `npm run test` for watch mode,
  `npm run test:coverage` for V8 coverage.

## Build and dev

- `npm run dev` — Vite dev server on `:5173`, HMR enabled. The `/api`
  path proxies to `http://localhost:8080`; the API base URL is configured by
  `VITE_API_BASE_URL`. Use `npm run dev -- --host 0.0.0.0` to make the dev
  server reachable from a phone on the same network.
- `npm run build` — production build into `dist/`. The output is plain
  static files. Drop them into `backend/src/main/resources/static/`
  and the Spring Boot app will serve them (see `SpaWebConfig` in
  [`backend.md`](./backend.md)).
- `npm run preview` — local preview of the production build.

**Next:** [`data-model.md`](./data-model.md) — entities, schema,
relationships, migration policy.

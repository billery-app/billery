# Document Pipeline

> **Status:** chapter 6 of the `docs/architecture/` series.
> **Audience:** contributors and maintainers.
> **Scope:** HTML → PDF, XRechnung XML, DOCX / ODT import and export, and
> the mail subsystem. Replaces the deleted
> `backend/docs/LaTeX_PDF_Generation.md`, which described a
> superseded LaTeX-based path.
> For the higher-level system view see [`overview.md`](./overview.md).
> For the template data that flows through this pipeline see
> [`template-system.md`](./template-system.md).

## Pipeline overview

The "document pipeline" is the chain that turns an `Invoice` and a
template into a deliverable for the recipient.

```mermaid
flowchart LR
    subgraph SPA
        UI[Template editor / invoice list]
    end

    subgraph "Spring Boot :8080"
        IC[InvoiceController]
        DC[DocumentController]
        TBP[TemplateBasedPdfGenerator<br/>Flying Saucer]
        XRG[XRechnungGenerator]
        DCS[DocumentConversionService<br/>DOCX/ODT]
        SM[SmtpMailProvider<br/>(not yet wired)]
    end

    DB[(PostgreSQL)]
    SMTP[(SMTP server<br/>(not yet wired))]
    File[(./uploads)]

    UI -- "GET /api/invoices/{id}/pdf" --> IC
    IC --> TBP
    TBP -- "JDBC" --> DB
    TBP -- "PDF bytes" --> IC
    IC -- "application/pdf" --> UI

    UI -- "GET /api/invoices/{id}/xrechnung" --> IC
    IC --> XRG
    XRG -- "XML string" --> IC
    IC -- "application/xml" --> UI

    UI -- "POST /api/documents/import (file)" --> DC
    DC --> DCS
    DCS -- "sections" --> DC
    DC -- "JSON sections (nothing stored)" --> UI

    UI -- "POST /api/documents/export (sections)" --> DC
    DC --> DCS
    DCS -- "DOCX/ODT bytes" --> DC
    DC -- "download" --> UI

    UI -. "POST /api/invoices/{id}/mail<br/>(endpoint not yet wired)" .-> IC
    IC -. SM
    SM -. "SMTP" .-> SMTP

    UI -- "POST /api/upload" --> FU[FileUploadController]
    FU --> File
```

The "issue an invoice" sequence diagram in
[`overview.md`](./overview.md) is the canonical end-to-end trace.

## PDF generation

### `TemplateBasedPdfGenerator`

`pdf/TemplateBasedPdfGenerator.java:45` is the production entry point
for invoice PDF generation. The method:

1. Loads the `Invoice` (already in scope as the argument), the
   `Customer`, the `Owner` (via `getOwner()`), and the `InvoiceItem`s
   for the invoice.
2. Calls `loadTemplateForInvoice(invoice)` to pick the template. The
   helper first honours `invoice.templateId` (per-document override; see
   [`template-system.md > Per-document layout override`](./template-system.md#per-document-layout-override));
   if the override is null, missing, or has no usable block JSON, it
   falls back to `getTemplateForDocumentType(documentTypeId)`. The
   picker prefers `is_default = 1` templates that have valid blocks;
   falls back to any template with valid blocks; throws if no block
   template is found (the "no hardcoded fallbacks" rule from
   [`template-system.md`](./template-system.md)).
3. Calls `generateHtmlFromBlocks(template, invoice, customer, owner, items)`
   to produce an HTML string. The block-to-HTML conversion is a
   `switch` over block type; every branch resolves the `{{variable}}`
   placeholders in the block's `data` against one `VariableContext`
   (owner, customer, invoice — so any variable works in any block;
   unknown ones are removed), escapes the result, and renders the
   block. `layoutBlocks` then puts consecutive half-width blocks
   (`config.width = "half"`) side by side in table rows.
4. Calls `convertHtmlToPdf(htmlContent)` which uses Flying Saucer's
   `ITextRenderer` (with `RestrictedUserAgent`, see below) to turn the
   HTML into PDF bytes.

`POST /api/templates/preview` runs steps 3–4 for an unsaved template
draft (body `{ templateData, invoiceId }`; without `invoiceId` a
built-in sample document is used) and returns the PDF. Nothing is
stored; the template editor's "PDF-Vorschau" uses it. 400 without
template data, 409 when the draft has no blocks or no company data
exists.

Both the live PDF generation (`generatePdfInvoice`) and the save-time
snapshot (`DocumentService.generateAndStoreRenderedHtml`,
`backend/src/main/java/de/billery/service/DocumentService.java:259-269`)
go through `loadTemplateForInvoice`, so a per-document override is
honoured on download **and** the `invoice.rendered_html` snapshot is
regenerated consistently when the template changes.

The renderer is a **Java** implementation, not a re-use of the
frontend's `BLOCK_TYPES[i].render`. Adding a new block requires
implementing it in both places — see
[`../guides/extending.md`](../guides/extending.md) for the recipe.

### Sub-items in the PDF

`TemplateBasedPdfGenerator.renderItemRow` walks the `InvoiceItem`
tree recursively. Child rows are rendered immediately after their
parent, indented in the description cell (`padding-left: 32px`,
smaller font, grey colour). The XRechnung generator flattens the tree:
every priced sub-item becomes its own invoice line, and the text of a
`CUSTOM` note becomes the line note (BT-127) of the line above it.

### `item_type = CUSTOM` rows in the PDF

A `CUSTOM` ("Freitext") note row is rendered as a **single merged
cell** that starts at the "Menge" column and spans the rest of the
table. `renderItemRow` dispatches to `renderNoteItemRow`
(`TemplateBasedPdfGenerator.java`) which:

- keeps a one-cell `Bezeichnung` column for the note's title,
- emits one `<td colspan="N − 1">` for the description, so the
  numeric columns (Menge / Einheit / Preis / Gesamt) are absent
  from the row,
- styles the merged cell with a light amber background
  (`#fffbeb`) and `white-space: pre-wrap` so author-entered
  newlines survive in the PDF,
- applies `padding-left: 32px` to the merged cell for child rows
  so the tree indent is preserved,
- HTML-escapes the user-entered text (a note is the first place
  in the line-item table where free-form user input is rendered).

If the "Menge" column is hidden by the block config, the merge
falls back to starting at column index 1 (i.e. directly after
"Bezeichnung"). An empty name or description renders an empty
cell — no placeholder text is injected.

### `InvoicePdfGenerator`

`pdf/InvoicePdfGenerator.java` is the **legacy** PDF generator. The
class-level comment in the file says it produces "professional PDF
invoices without LaTeX dependency", which is a leftover from the
LaTeX era. The class is still wired up (the file exists) but the
current path goes through `TemplateBasedPdfGenerator` instead. Treat
`InvoicePdfGenerator` as deprecated; new work should call
`TemplateBasedPdfGenerator`.

### Flying Saucer

Flying Saucer (`org.xhtmlrenderer.pdf.ITextRenderer`) renders XHTML to
PDF via OpenPDF (Flying Saucer 10.x). Practical consequences:

- The HTML must be **well-formed XHTML** — every tag closed, every
  attribute quoted, no custom elements. The block-to-HTML output in
  `generateHtmlFromBlocks` is written to satisfy this.
- Inline `style="..."` is supported; `<style>` blocks in the
  document head are honoured.
- Margins come from `invoice_template.margin_*_mm` (default 10 mm
  each side) and are applied as padding of the `.invoice-box` page
  wrapper.
- **No flexbox / grid.** Layouts use tables (header, half-width rows).
- **Fonts:** every `font-family` carries its generic family
  (`genericFontFamily`), and the generic families print in Liberation
  Sans, Serif and Mono, embedded into the PDF (`pdf/EmbeddedFonts`, SIL
  Open Font License, `src/main/resources/fonts/liberation/`). They have
  the glyph widths of Helvetica / Arial, Times and Courier, so the layout
  is the one the standard PDF fonts gave. `EmbeddedFonts` replaces
  Flying Saucer's built-in (never embedded) families under their own
  names, so HTML and CSS are unchanged.
- **PDF/A-3b:** every PDF is PDF/A-3b (ISO 19005-3): embedded fonts,
  sRGB output intent and XMP metadata (`renderer.setPdfAConformance`;
  OpenPDF refuses to write a font that is not embedded). veraPDF checks
  the PDFs of every tax case in the `xrechnung` CI job.
- **Resources (`RestrictedUserAgent`):** template content is user
  input, so the renderer never touches the network or arbitrary files.
  It loads only uploaded files (any URI whose path is
  `/uploads/<name>`, read from `FILES_PATH` — host and port of the
  stored logo URL are ignored), embedded `data:` images, and classpath
  resources the class loader itself returns (Flying Saucer's default
  XHTML stylesheet; without it `<style>` content prints as text). Remote
  logos, `@import` and `url(http…)` in template CSS are not loaded.
- **Template CSS** is embedded with `<` and `&` CSS-escaped
  (`cssForStyleElement`), so it can neither break the XML parse nor
  close the `<style>` element.

### OpenPDF

OpenPDF is a dependency of the project (referenced in
[`overview.md`](./overview.md)) and is used by the DOCX / ODT
conversion path indirectly through document parsing. The main PDF
output goes through Flying Saucer, not OpenPDF. If you need to add
direct OpenPDF calls, look for existing usages in
`service/DocumentConversionService` first.

## The document PDF and ZUGFeRD

`service/DocumentPdfService` is the one place that decides which PDF a
document gets; the download (`GET /api/invoices/{id}/pdf`) and the GoBD
export use it. Issued invoices and credit notes render their issue-time
snapshot, drafts the current state, offers and cost calculations their
snapshot unless they have a per-document template.

An **issued invoice or credit note whose data suffices for an XRechnung
is a ZUGFeRD 2.x / Factur-X invoice**: the PDF/A-3b with the XRechnung
in CII syntax attached (`xmlgenerator/CiiGenerator`,
`pdf/FacturXEmbedder`). Profile `XRECHNUNG`, attachment
`xrechnung.xml` (the file name ZUGFeRD prescribes for that profile),
`/AFRelationship /Alternative`, listed in the catalog's `/AF` and in
the embedded-files name tree, XMP with the `fx` properties and their
PDF/A extension schema. The XML is an e-invoice in its own right; the
page is its readable copy. Without complete data (see **Required data**
below) the PDF has no attachment and the download still works; drafts,
offers and cost calculations never have one. The attachment's dates
are the invoice date, so every download gives the same file.

`ZugferdTest` downloads the PDF of every tax case, takes the XML out and
compares it field by field (36 business terms: number, dates, parties,
every total, the VAT breakdown, every line) with the UBL XRechnung, and
with the text on the page (number, buyer, net, gross, every VAT amount,
every line total). The `xrechnung` CI job also runs the attached XMLs
through the KoSIT validator (scenario EN16931 XRechnung (CII)) and the
PDFs through veraPDF.

## XRechnung

`xmlgenerator/XRechnungGenerator.java` produces the XRechnung XML
output required for German government e-invoicing; `CiiGenerator`
writes the same document in CII syntax for the ZUGFeRD PDF, with the
same data, rules and required fields.

- **Endpoints:** `GET /api/invoices/{id}/xrechnung` returns the UBL
  XML of an issued invoice or Stornorechnung (409 with the missing
  fields otherwise); `GET /api/invoices/{id}/xrechnung/check` lists
  what is missing (also for drafts, so the SPA can warn before
  issuing). `EInvoiceService` orchestrates; the generator itself is
  pure (no database access).
- **Syntax:** XRechnung 3.0 (CIUS of EN 16931), UBL 2.1. Invoices are
  `ubl:Invoice` (type 380), credit notes `ubl:CreditNote` (type 381)
  with `BillingReference` to the cancelled invoice. Customization ID
  `urn:cen.eu:en16931:2017#compliant#urn:xeinkauf.de:kosit:xrechnung_3.0`.
- **Frozen data:** issued documents use the seller and buyer frozen in
  `invoice.party_snapshot` at issue (`PartySnapshot`); the items are
  immutable anyway, so every download produces the same XML. Legacy
  documents without a snapshot use the current master data.
- **VAT:** one `TaxSubtotal` per rate group from `VatCalculator`
  (category `S`); Kleinunternehmer and tax-exempt documents use
  category `E`, reverse charge `AE`, both with the exemption reason.
- **Required data** (`XRechnungGenerator.missingFields`): seller name,
  address, contact name, phone, e-mail, VAT ID or tax number; buyer
  name, address and e-mail (BT-49 electronic address); for reverse
  charge the buyer's tax ID. The Käuferreferenz (BT-10) is the
  customer's Leitweg-ID when set, otherwise the document number.
- **Units:** `unitCode` maps German units (`Std`, `Stk`, `kg`, `m²`,
  `pauschal`, …) to UN/ECE Recommendation 20; unknown units become
  `C62` (one).
- **Limits:** seller and buyer are assumed to be in Germany (no
  country field).
- The generator does not consult the block template: XRechnung has
  its own schema.

Every change to either generator must keep
`VatAndEInvoiceTest.generatedXRechnungsPassTheKositValidator` and
`ZugferdTest` green (the `xrechnung` CI job runs both against the KoSIT
validator fetched by `scripts/fetch-kosit-validator.sh`); a new field goes into both
generators and into `ZugferdTest.FIELDS`.

## Document conversion (DOCX / ODT)

Templates can be exchanged with word processors: exported as DOCX or ODT, edited there, and read
back into a new template. The exchange format is a list of **sections**, each a heading with
plain-text paragraphs below it:

```
Kopfzeile
Firmenname: {{firma}}
Slogan: Gut & günstig
Hinweise
Text: Zahlbar innerhalb von 14 Tagen.
Bitte {{rechnungsnummer}} angeben.
Fußzeile
Text: Vielen Dank für Ihr Vertrauen!
```

One section per block with text (its German name, "Adresse – Absender", a second block of the
same kind "Hinweise (2)"); one line "Label: Wert" per text field, further lines of a multi-line
field on lines of their own; `{{variables}}` as they are. Line items and totals have no section:
their content comes from the document.

**Who does what.** The backend only converts files and sections; the mapping between sections and
blocks lives in the SPA, which knows the fields (`utils/documentExchange.js` with the German
`exchangeName` / `exchangeLabel` of `blockRegistry.js`). The words stay German in every interface
language (#15), so a file exported in the English interface imports in the German one and back:

- **Export** — Templates view, buttons "DOCX" / "ODT" on each template:
  `templateToSections(blocks)` → `POST /api/documents/export` `{name, format, sections}` →
  `DocumentController` → `DocumentConversionService.exportToDocx` (docx4j, headings in Word's
  style "Heading 1") / `exportToOdt` (odfdom, `text:h` of outline level 1) → download. The file
  name comes from the template name with path separators, quotes and control characters removed
  (`ContentDisposition`, UTF-8). Limits: 200 sections, 2 000 paragraphs, 10 000 characters per
  text (`checkExportLimits`, 400 with a German message).
- **Import** — "Neue Vorlage" → "Aus Datei übernehmen …" (`TemplateStartModal`):
  `POST /api/documents/import` (multipart `file`) → `importDocument` reads the sections and
  **stores nothing** → `applySections(presetBlocks, sections)` fills the blocks of the chosen
  style: a heading that names a block (case and a trailing colon ignored) maps its "Label: Wert"
  lines into that block's fields (a block the preset lacks is added before the footer); every
  other line, and every section whose heading is no block (an ordinary letter), becomes free text
  in the notes block, before its `{{notizen}}`. The result opens in the template editor like any
  new template and is saved through the normal template endpoints, so validation, the block
  model and the PDF apply to it unchanged.

What `DocumentConversionService` reads (POI for DOCX, odfdom for ODT):

- **Headings** start a section: DOCX paragraphs whose style id or style name is a heading or
  title (`Heading1`, Word's German id `berschrift1`, `Title`), ODT `text:h`. Text before the first
  heading is a section without heading. Lists, tables (cell by cell, in reading order) and
  sections are read paragraph by paragraph; empty paragraphs are dropped; a line break inside a
  paragraph splits it. ODT spaces (`text:s`) and tabs are resolved.
- **Left out:** ODT comments, footnotes and the change-tracking store (`text:tracked-changes`,
  which holds *deleted* paragraphs), so deleted text never reaches a template; the DOCX import
  drops deleted runs (`w:del` / `w:delText`) and keeps inserted ones (`w:ins`). Fonts, bold and
  italic, list bullets and table structure are not carried.
- **ODT is read paragraph by paragraph** on purpose: odfdom 0.13 returns an empty
  `getTextContent()` for the `office:text` root. **Elements are matched by namespace URI and
  local name, never by prefix:** prefixes are free in XML, and odfdom keeps the first prefix it
  saw for a namespace for the whole process.
- **Every XML part is checked before parsing** (`checkXmlParts`, StAX with DTDs and external
  entities off, every `.xml` / `.rels` part):
  - **No `DOCTYPE`.** OOXML and ODF never use a DTD, so a part that declares one is refused
    before POI or odfdom reads the file: external entities (files, URLs), external DTDs,
    parameter entities and entity expansion cannot reach a parser. POI (DOCTYPE disallowed, also
    for parts with other names) and odfdom (resolves nothing) hold on their own as well;
    `DocumentImportXmlAttackTest` proves both layers.
  - **Nesting depth.** odfdom and POI both recurse over the XML nesting, so a deeply nested
    upload overflowed the stack inside the library; more than `MAX_ODT_DEPTH` (100) levels are
    refused.
  The archive limits come from `FileSecurityService`; a realistic template with a logo and a
  table passes them (`DocumentConversionServiceTest`).
- **A file without any text** answers 400 with "Das Dokument enthält keinen Text; es wurde keine
  Vorlage angelegt." (`EmptyDocumentException`); any other unreadable file 400 with a German
  message naming the accepted formats.

The legacy column `html_content` is not part of the exchange any more (it is still written by the
editor and read by nothing). There is one variable set, `utils/variableRegistry.js`: the former
uppercase aliases (`OWNER_COMPANY`, …) and their endpoints `/api/documents/variables/*` are gone;
unknown variables in an imported file show up in the editor's validation.

## Mail subsystem

`mail/` sends plain-text mail with attachments through SMTP (Simple Java Mail on Angus Mail).
There are **two kinds of accounts, never mixed**:

| Kind | Configured by | Used for | Class |
|------|---------------|----------|-------|
| System account | the operator, `MAIL_*` environment ([`env-vars.md`](../reference/env-vars.md)) | system mails: today the SaaS invitations | `SmtpMailProvider` (`MailProvider`) |
| Company accounts | the company, Einstellungen → Mail-Einstellungen (`mail_account`) | documents to the company's customers, test mails | `CompanyMailSender` |

Without a company account nothing is sent to customers: there is no fallback to the system account
(the dialog says so and links to the settings).

- `SmtpTransport` delivers one `MailMessage` through one `SmtpEndpoint` (host, port, security,
  login, sender). A connection per message, 15 s timeout.
- `MailHostPolicy` decides which servers a company account may name. Self-host and desktop: any
  (a relay on the same host is normal). Profile `saas`: only public addresses (IPv4 without the
  private, shared, reserved and documentation ranges; IPv6 by allow-list: global unicast `2000::/3`
  without `2001::/23`, `2001:db8::/32`, `2002::/16`, `3fff::/20`), ports 25 / 465 /
  587 / 2525 and an encrypted connection, checked when the account is saved **and** on every
  connection by a guarded socket factory, at the moment the socket connects to the resolved
  address (so DNS rebinding cannot reach the operator's network). The factory is put into the
  JavaMail session as an object after the mailer is built: Simple Java Mail's `withProperty`
  turns every value into a string, and JavaMail then silently ignores it (`MailHostPolicyTest`
  proves the guard is used for STARTTLS, SSL and plain SMTP). `billery.mail.allow-private-hosts`
  switches the restriction off for the SaaS test suite only.
- `CredentialCipher` encrypts the company accounts' passwords (AES-256-GCM, random IV, the active
  tenant as authenticated data). The key is outside the database: `BILLERY_SECRETS_KEY`, or in the
  desktop app the file `mail-secrets.key` next to its database (in no backup). Without a key no
  password can be stored; changing the key means entering the passwords again. The API never
  returns a password (`hasPassword` only), and changing server, port, security or login of an
  account with a stored password requires the password again, so whoever may edit the settings
  cannot redirect the stored password to a server of their own.
- `MailMetrics` counts `billery_mails_total{kind=system|company, outcome=sent|failed, reason}`;
  `MailException.Reason` (`auth`, `connect`, `timeout`, `tls`, `rejected`, `refused_host`,
  `other`) is also the German explanation the user sees. Neither carries an address or content.
- `CustomerMailLimit`: SaaS counts the mails to customers per company and 24 hours
  (`saas.provisioning.CustomerMailQuota` in `daily_quota`, default
  `BILLERY_CUSTOMER_MAILS_PER_DAY`, a company's own limit in `tenant.mail_daily_limit` set by the
  operator); self-host has none.

### Sending a document

`DocumentMailService` (`GET` / `POST /api/invoices/{id}/mail`, `DocumentMailController`):

- The dialog starts from the company's template for the kind of document (`MailTemplateService`,
  stored in `user_settings` as `mail_<kind>_subject` / `mail_<kind>_text`, defaults in the code),
  with the variables resolved exactly like the PDF's (`TemplateBasedPdfGenerator.resolvePlainText`,
  amounts from `VatCalculator`). For an invoice draft the number variables stay and are filled
  after issuing; the subject and text the user sends are resolved once more. The settings
  (Einstellungen → Mail-Einstellungen) and the send dialog edit subject and text with the
  editor's `VariableTextInput`: placeholders are labelled chips, the stored text keeps `{{name}}`.
- The attachment is `DocumentPdfService.pdf` (the download's PDF, ZUGFeRD for issued invoices with
  complete data); optionally the XRechnung as XML (refused with the missing fields when
  incomplete).
- An invoice draft is issued only when the request confirms it (`issue: true`, the SPA asks with a
  confirmation): issuing is final (GoBD), so a mail that fails afterwards leaves an issued invoice
  that can be sent again, and the error message says so.
- At most 5 recipients (To and CC), plain addresses, no line breaks in the subject; "Kopie an
  mich" is a BCC to the account's own address.
- After the server accepted the mail, `document_history` gets `MAIL_SENT` (recipients, subject,
  account, attachment names, not the text) and a draft offer or cost calculation becomes `SENT`
  through the transition table. A failed delivery writes no history. Mail and database cannot be
  one transaction: a dispatch that went out but could not be recorded answers HTTP 500 with "nicht
  erneut senden", logs an ERROR and counts `billery_mail_dispatch_unrecorded_total` (alert
  `BilleryMailDispatchUnrecorded`); a stop in between shows as a "versendet" log line without
  its "vermerkt" line ([`ops-gotchas.md`](../operations/ops-gotchas.md#mail)). **Known residual
  risk:** when the server accepted the mail but its answer is lost (a timeout after `DATA`), the
  delivery counts as failed (reason `timeout`, a WARN line with the document id), nothing is
  recorded, and the user may send again although the customer has the mail. Accepted: it needs a
  lost answer at exactly that moment, and the log names the document.
- The daily limit is claimed before anything is issued or sent.

### Where mail is triggered

- The SaaS invitations: `TeamController` calls `InvitationMailer` (system account).
- The send dialog of a document (`SendMailModal` in the document view) and the test mail in
  Einstellungen → Mail-Einstellungen (company accounts).

The `MAIL_*` settings are read once at startup, so changing them requires a restart. An invalid
`MAIL_PORT` or `MAIL_SECURITY` stops the start; an empty `MAIL_PORT` means the usual port of
`MAIL_SECURITY`. Company accounts take effect at once.

## File upload

`api/controller/FileUploadController.java` accepts multipart uploads up
to 2 MB (configured in `application.properties`). Uploaded files land
in `FILES_PATH` (default `./uploads/`, mapped to `/uploads/**` by
`WebConfig`). The frontend uploads logos through
`services/uploadService.js` (template editor). The PDF renderer reads
uploaded logos directly from `FILES_PATH`.

The 2 MB cap is tight. If you need larger files, raise both
`spring.servlet.multipart.max-file-size` and
`spring.servlet.multipart.max-request-size`, and check the reverse
proxy in front (nginx, Caddy, etc.) for its own `client_max_body_size`
limit.

## Practical notes for contributors

- The PDF pipeline is the most performance-sensitive path. The
  current code is synchronous (blocks the request thread for the
  duration of the render). For high-volume use, consider moving
  PDF generation to an async job and returning a download URL.
- Block HTML is the only HTML the renderer is guaranteed to like.
  If you embed user-supplied HTML (e.g. the legacy template's
  `html_content`), wrap it in a well-formed XHTML skeleton
  (`<html><head></head><body>...</body></html>`) before passing
  it to Flying Saucer.
- XRechnung is a strict format. Validate the output against the
  KoSIT validator (`scripts/fetch-kosit-validator.sh`) before claiming a new field works.
- The mail subsystem has no retry logic. A failed send returns an
  exception; the SPA surfaces it. If you add retry, keep the
  `MailException` contract so the caller can distinguish
  transient from permanent failures.

**Next:** For extension recipes (adding blocks, variables,
entities, API endpoints, document types) see
[`../guides/extending.md`](../guides/extending.md). For deployment
(Docker, env vars, single-binary) see
[`../operations/deployment.md`](../operations/deployment.md). The
[docs index](../README.md) maps the full set of topics.

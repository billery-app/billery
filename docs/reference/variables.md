# Template Variables

> **Audience:** template authors and contributors.
> **Scope:** the `{{variable}}` registry used by the block-based
> editor and the per-block data fields the user can bind variables into.
> For the block data fields themselves see
> [`block-types.md`](./block-types.md). For the render pipeline see
> [`../architecture/template-system.md`](../architecture/template-system.md).

Source of truth for the block path:
`frontend/src/utils/variableRegistry.js`
(`VARIABLE_REGISTRY`, `CATEGORY_LABELS`, chip labels, aliases,
`getPickerGroups`, `isKnownVariable`) and, for what the PDF prints,
`TemplateBasedPdfGenerator.replaceVariables`.

> **One registry.** The editor, the PDF, the seed block JSON
> (`V1__baseline.sql`, `scheme-h2.sql`) and the DOCX/ODT exchange all use
> these lowercase German names. The former uppercase aliases
> (`OWNER_COMPANY`, …, `templateVariables.js` and
> `/api/documents/variables/*`) were removed; an imported file keeps its
> `{{variables}}` as written, and unknown ones show up in the editor's
> validation.

---

## Block-based variables (`variableRegistry.js`)

These are the variables the editor's picker offers (`getPickerGroups`).
**Every variable works in every block** — the PDF renderer resolves
all of them in every text field (`TemplateBasedPdfGenerator.VariableContext`).
In the editor they appear as chips with the chip label below; the
stored value is the `{{name}}` form.

### Ihre Firma (`Company`, from the company data)

| Variable | Chip label | PDF value |
|----------|------------|-----------|
| `firma` | Firmenname | company name |
| `adresse` | Firmenadresse | `Straße Nr., PLZ Ort` |
| `telefon` | Firmentelefon | phone |
| `email` | Firmen-E-Mail | e-mail |
| `website` | Website | URL |
| `ust_id` | USt-IdNr. | tax ID |

### Kunde (`Client`)

| Variable | Chip label | PDF value |
|----------|------------|-----------|
| `kunde` | Kundenname | `Vorname Nachname` |
| `kunde_strasse` | Kunde: Straße | `Straße Nr.` |
| `kunde_plz_ort` | Kunde: PLZ/Ort | `PLZ Ort` |
| `kunde_email` | Kunde: E-Mail | e-mail |
| `kunde_telefon` | Kunde: Telefon | phone |

### Dokument (`Invoice`)

| Variable | Chip label | PDF value |
|----------|------------|-----------|
| `rechnungsnummer` | Rechnungsnummer | document number (`ENTWURF` for drafts) |
| `rechnungsdatum` | Rechnungsdatum | `TT.MM.JJJJ` |
| `faelligkeitsdatum` | Fälligkeitsdatum | `TT.MM.JJJJ` |
| `leistungsdatum` | Leistungsdatum | start of the service period |
| `leistungszeitraum` | Leistungszeitraum | `TT.MM.JJJJ – TT.MM.JJJJ` |
| `notizen` | Notizen | the document's notes |
| `kleinunternehmer_hinweis` | Kleinunternehmer-Hinweis | § 19 UStG note for small businesses, else empty |

### Beträge (`Totals`)

| Variable | Chip label | PDF value |
|----------|------------|-----------|
| `netto` | Netto | `1.234,56 €` |
| `steuersatz` | MwSt.-Satz | the document's default rate (`19,0`); with mixed rates use the totals block |
| `steuer` | MwSt.-Betrag | sum of all rate groups |
| `betrag` | Gesamtbetrag | gross amount |
| `rabatt` | Rabatt | `0,00 €` |
| `waehrung` | Währung | `EUR` |

### Zahlung (`Payment`)

| Variable | Chip label | PDF value |
|----------|------------|-----------|
| `iban` | IBAN | the company's IBAN |
| `verwendungszweck` | Verwendungszweck | the document number |

`bank_name` and `bic` are still accepted (older templates) but not
offered: the company data has no bank name or BIC, so they always print
empty.

### Accepted aliases

The renderer also resolves English aliases (`company_name`,
`client_name`, `invoice_number`, `net_amount`, `notes`, …; full list in
`variableRegistry.js > ALIASES`). The editor treats them as known
variables and shows the German chip label.

---

## Preview values

The editor preview resolves variables from a preview context
(`utils/previewData.js`): the sample document (`SAMPLE_DATA` in
`utils/templatePreview.js`, e.g. `Musterfirma GmbH`, `RE-2025-001`),
overridden by the company data when it is configured (empty company
fields stay empty, like in the PDF), overridden by the real document in
the per-document editor. Values are formatted like the PDF (German
dates, `1.234,56 €`). The picker shows each variable's preview value.

---

## Substitution rules

- **Spaces inside the braces are ignored** — `{{ firma }}` is
  `{{firma}}` (both renderers).
- **Unknown variables** — the editor keeps them visible in the
  preview, shows them as red chips and lists a warning
  (`blockValidation.js`); the PDF renderer **removes** them, so a typo
  never reaches a customer.
- **Escaping** — the resolved text is HTML-escaped before it is put
  into the block markup (`escapeBlockData` in the editor,
  `substituteVariables` in Java).
- Editor: `utils/renderBlockPreview.js` resolves the variables of a
  block's `data` with `resolveVariableText` before calling the block's
  `render(data, config, ctx)`; the stored block is not changed.
- PDF: `TemplateBasedPdfGenerator.replaceVariables(text, VariableContext)`.

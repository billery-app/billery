# Block Types

> **Audience:** template authors and contributors extending the editor.
> **Scope:** the eight block types the visual editor supports, with
> their data fields, settings, defaults, limits and render behaviour.
> For the variable registry (what `{{firma}}` etc. resolve to) see
> [`variables.md`](./variables.md). For the editor UI itself see
> [`editor.md`](./editor.md).

Source of truth: `frontend/src/utils/blockRegistry.js` (`BLOCK_TYPES`:
defaults, `fields`, `settings`, `limits`, `formatDefaults`, preview
`render`) and the matching `render*` methods of
`backend/src/main/java/de/billery/pdf/TemplateBasedPdfGenerator.java`.
`TemplateParityTest` / `templateParity.spec.js` check that both render
the shared cases in `backend/src/test/resources/template-parity/cases.json`
alike.

## Summary

| Block | `type` key | Limit | Purpose |
|-------|-----------|-------|---------|
| Kopfzeile | `header` | max 1 | Company name, slogan, logo |
| Adresse | `address` | max 2 | Sender or receiver address |
| Dokumentdaten | `invoice-meta` | **required**, max 1 | Title, number, dates, order number |
| Positionen | `line-items` | **required**, max 1 | Table of line items |
| Summen | `totals` | **required**, max 1 | Net, VAT per rate, gross, legal note |
| Zahlungsdaten | `payment` | max 1 | Payment method and bank details |
| Hinweise | `notes` | any | Free text box (document notes by default) |
| Fußzeile | `footer` | max 1 | Footer text, website, registration / tax numbers |

The editor refuses to save a template that breaks a limit
(`blockValidation.js`); the library disables types that reached theirs.

### Common to every block

- **Text fields resolve variables** — any `{{variable}}` works in any
  block, in the preview and in the PDF (`VariableContext` in Java).
  Unknown placeholders are removed from the PDF and flagged in the
  editor.
- **`config.format`** — `{ fontFamily, fontSize, fontColor, fontWeight }`,
  edited in the config panel's "Darstellung" tab or for all blocks at
  once in the simple mode. Missing keys fall back to the block's
  `formatDefaults` (listed per block below, identical in Java). Colors
  must be hex colors and font names plain names; anything else falls
  back to the default. The PDF prints the font's generic family in the
  embedded Liberation Sans, Serif or Mono (the widths of Helvetica,
  Times and Courier).
- **`config.width`** — `full` (default) or `half` on every block except
  `line-items`. Two consecutive half-width blocks share a row; a single
  one takes the left half.
- **Labels and titles** from the config are plain text (escaped).

---

## `header` — Kopfzeile

| Data field | Label | Kind | Default |
|------------|-------|------|---------|
| `companyName` | Firmenname | text | `{{firma}}` |
| `companySlogan` | Slogan | text | `""` |
| `logoUrl` | Logo | image | `""` |

| Setting | Label | Kind | Options | Default |
|---------|-------|------|---------|---------|
| `showSlogan` | Slogan anzeigen | toggle | — | `true` |
| `logoAlignment` | Logo-Position | select | `left`, `right`, `top` | `left` |

Format defaults: 21 pt, `#2563eb`. The PDF scales a logo down to fit 200 × 80 px (never enlarges it) because Flying Saucer ignores `max-width` / `max-height` on images; unreadable files (e.g. SVGs uploaded before SVG uploads were rejected) get a height of 60 px. Uploaded logos are read from the
upload directory by the PDF renderer (host and port of the stored URL
are ignored); other URLs are not loaded.

---

## `address` — Adresse

| Data field | Label | Kind | Default (sender / receiver) |
|------------|-------|------|---------|
| `role` | Art | select | `sender` / `receiver` |
| `name` | Name | text | `{{firma}}` / `{{kunde}}` |
| `address` | Adresse | multiline | `{{adresse}}` / `{{kunde_strasse}}`↵`{{kunde_plz_ort}}` |
| `email` | E-Mail | text | `{{email}}` / `""` |
| `phone` | Telefon | text | `{{telefon}}` / `""` |
| `vatId` | USt-IdNr. | text | `{{ust_id}}` / `""` |

| Setting | Label | Kind | Default |
|---------|-------|------|---------|
| `showLabels` | Überschrift anzeigen | toggle | `true` |
| `label` | Überschrift | text (only while `showLabels`) | `Absender` / `Empfänger` |

The role defaults come from `rolePresets`. Switching the role in the
editor swaps every field the user has not changed. Format defaults:
10.5 pt, `#1f2937`.

---

## `invoice-meta` — Dokumentdaten

| Data field | Label | Kind | Default |
|------------|-------|------|---------|
| `invoiceTitle` | Titel | text | the document type's name (set by the editor) |
| `invoiceId` | Dokumentnummer | text | `{{rechnungsnummer}}` |
| `invoiceDate` | Datum | text | `{{rechnungsdatum}}` |
| `dueDate` | Fällig am | text | `{{faelligkeitsdatum}}` |
| `orderNumber` | Bestellnummer | text | `""` |
| `leistungsdatum` | Leistungsdatum | text | `""` |
| `leistungszeitraum` | Leistungszeitraum | text | `""` |

| Setting | Label | Kind | Options | Default |
|---------|-------|------|---------|---------|
| `showOrderNumber` | Bestellnummer anzeigen | toggle | — | `true` |
| `alignment` | Ausrichtung | select | `left`, `center`, `right` | `right` |

Empty number / date / service-date fields fall back to the document's
own values. The number label follows the document type
(Rechnungsnummer, Angebotsnummer, Kostenberechnungsnummer,
Stornorechnungsnummer); credit notes show "Storno zu Rechnung …"
instead of a due date. The Leistungsdatum row is only printed when
there is no Leistungszeitraum.

Format: the size is the **body text** (default 10.5 pt); the title is
12⁄7 of it (18 pt). Older editors stored the 18 pt title size there;
values of 16 pt and more are read as such a title size (Java
`metaBodyFontSizeOf`, JS `migrateBlock`).

---

## `line-items` — Positionen

| Data field | Label | Kind |
|------------|-------|------|
| `columns` | Spalten | columns (visible, label, order) |

Default columns: `description` Beschreibung · `quantity` Menge ·
`unit` Einheit · `rate` Preis · `amount` Gesamt. The rows come from the
document (never from the template): priced rows, indented sub-items,
and `CUSTOM` note rows whose text spans the columns from "Menge" on.
When a document has lines with different VAT rates and the template
shows no `taxRate` column, a "USt." column is added before "Gesamt"
(§14 Abs. 4 Nr. 8 UStG). Format defaults: 10.5 pt, `#1f2937` (header
and cells).

---

## `totals` — Summen

No data fields: the amounts come from `VatCalculator`.

| Setting | Label | Kind | Options | Default |
|---------|-------|------|---------|---------|
| `showTax` | Steuer anzeigen | toggle | — | `true` |
| `labelSubtotal` | Bezeichnung Netto | text | — | `Zwischensumme` |
| `labelTax` | Bezeichnung Steuer | text | — | `MwSt.` |
| `labelTotal` | Bezeichnung Gesamt | text | — | `Gesamtbetrag` |
| `alignment` | Ausrichtung | select | `left`, `center`, `right` | `right` |

One VAT row per rate ("MwSt. 7 % auf 50,00 €" when rates are mixed).
Documents without VAT (Kleinunternehmer, reverse charge, tax-exempt)
show no VAT row but the legal note of the treatment below the table,
even when `showTax` is off. Format defaults: 10.5 pt, `#1f2937`; the
total row is 3 pt larger and uses the font color.

---

## `payment` — Zahlungsdaten

| Data field | Label | Kind | Default |
|------------|-------|------|---------|
| `paymentMethod` | Zahlungsart | text | `Banküberweisung` |
| `bankName` | Bank | text | `""` |
| `iban` | IBAN | text | `{{iban}}` |
| `bic` | BIC | text | `""` |
| `reference` | Verwendungszweck | text | `{{verwendungszweck}}` |

| Setting | Label | Kind | Default |
|---------|-------|------|---------|
| `showBic` | BIC anzeigen | toggle | `true` |

Empty fields are not printed. Format defaults: 10.5 pt, `#1f2937`.

---

## `notes` — Hinweise

| Data field | Label | Kind | Default |
|------------|-------|------|---------|
| `content` | Text | multiline | `{{notizen}}` |

| Setting | Label | Kind | Default |
|---------|-------|------|---------|
| `showTitle` | Überschrift anzeigen | toggle | `true` |
| `title` | Überschrift | text (only while `showTitle`) | `Notizen` |
| `showBox` | Farbige Box | toggle | `true` |
| `backgroundColor` | Hintergrundfarbe | color (only while `showBox`) | `#fef3c7` |
| `borderColor` | Randfarbe | color (only while `showBox`) | `#f59e0b` |

The block is not printed when its text is empty (the editor shows a
hint in its place). `{{kleinunternehmer_hinweis}}` resolves to the
§ 19 UStG note for small businesses and to nothing otherwise. Format
defaults: 9.5 pt, `#78350f`.

---

## `footer` — Fußzeile

| Data field | Label | Kind | Default |
|------------|-------|------|---------|
| `text` | Text | multiline | `Vielen Dank für Ihr Vertrauen!` |
| `website` | Website | text | `{{website}}` |
| `registrationNumber` | Registernummer | text | `""` |
| `taxNumber` | Steuernummer | text | `""` |

| Setting | Label | Kind | Options | Default |
|---------|-------|------|---------|---------|
| `layout` | Ausrichtung | select | `left`, `centered` | `centered` |

Format defaults: 9 pt, `#9ca3af`.

---

## Legacy data

`blockModel.normalizeBlocks` brings stored blocks up to date when the
editor opens a template: missing config keys get their defaults,
`showUnit` / `showRate` / `decimalPlaces` are dropped, the header's old
`invoiceTitle` moves to `invoice-meta`, and the sample literals older
editors wrote (`INV-2026-001`, fixed dates, `Ihr Firmenname`) become
variables. The backend migration `migrateTemplateSampleLiterals` does
the latter for every stored template. Keys the current registry no
longer uses (`header.config.layout`, `footer.config.columns`,
`invoice-meta.config.dateFormat`, `totals.config.showDiscount`,
`line-items.config.currency`) are ignored by both renderers.

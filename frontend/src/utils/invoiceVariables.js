/**
 * Builds the per-document variable map that the template editor's preview
 * should merge on top of its existing SAMPLE_DATA + owner-derived defaults.
 *
 * Only the customer, invoice, and invoice-items dummies are replaced. Bank
 * fields, paymentMethod, rabatt, kleinunternehmer_hinweis, and the legacy
 * `positionen` / `footer_notiz` slots are intentionally left to the
 * existing global fallbacks.
 *
 * The key set mirrors the German and English aliases that the backend
 * `TemplateBasedPdfGenerator.substituteVariables` (Java) and the editor's
 * `resolveData` substitution both look up, so the preview is faithful to
 * the rendered output.
 */

import { totalsFromInvoice } from './previewData.js'

const DATE_FORMATTER = new Intl.DateTimeFormat('de-DE', {
  day: '2-digit',
  month: '2-digit',
  year: 'numeric'
})

/**
 * Best-effort conversion of an ISO date string ("2024-01-15") to the
 * German short form ("15.01.2024") the backend's PDF renderer uses.
 * Returns the input unchanged on parse failure so a malformed date does
 * not blank the preview.
 */
function formatDateForPreview(value) {
  if (value === null || value === undefined || value === '') {
    return ''
  }
  const s = String(value)
  const match = s.match(/^(\d{4})-(\d{2})-(\d{2})/)
  if (!match) {
    return s
  }
  const [, y, m, d] = match
  const date = new Date(Date.UTC(Number(y), Number(m) - 1, Number(d)))
  if (Number.isNaN(date.getTime())) {
    return s
  }
  return DATE_FORMATTER.format(date)
}

function safe(value) {
  return value === null || value === undefined ? '' : String(value)
}

function fullName(customer) {
  if (!customer) return ''
  const first = safe(customer.firstName)
  const last = safe(customer.lastName)
  // Mirrors TemplateBasedPdfGenerator.substituteVariables: first + ' ' + last.
  // We deliberately don't trim, so an empty first yields " last" (matches
  // the backend) and an empty last yields "first " (also matches). The
  // editor preview is the same as the rendered PDF.
  return `${first} ${last}`
}

function streetAndNumber(customer) {
  if (!customer) return ''
  const street = safe(customer.street)
  const house = customer.houseNumber === null || customer.houseNumber === undefined
    ? ''
    : String(customer.houseNumber)
  // Backend: street + ' ' + houseNumber. Match even when houseNumber is 0.
  return `${street} ${house}`
}

function postalCodeAndCity(customer) {
  if (!customer) return ''
  return `${safe(customer.postalCode)} ${safe(customer.city)}`
}

function formatPeriod(workStart, workEnd) {
  const start = formatDateForPreview(workStart)
  const end = formatDateForPreview(workEnd)
  if (!start && !end) return ''
  if (!end) return start
  if (!start) return end
  return `${start} – ${end}`
}

/**
 * Builds the live data the template editor's preview should merge on top of
 * its existing SAMPLE_DATA + owner-derived defaults.
 *
 * Only the customer, invoice, and invoice-items dummies are replaced. Bank
 * fields, paymentMethod, rabatt, kleinunternehmer_hinweis, and the legacy
 * `positionen` / `footer_notiz` slots are intentionally left to the
 * existing global fallbacks.
 *
 * The key set mirrors the German and English aliases that the backend
 * `TemplateBasedPdfGenerator.substituteVariables` (Java) and the editor's
 * `resolveData` substitution both look up, so the preview is faithful to
 * the rendered output.
 */

/**
 * @param {Object} params
 * @param {Object|null} params.invoice - Invoice DTO (`invoiceDate`, `dueDate`,
 *   `workStartDate`, `workEndDate`, `notes`, `netAmount`, `taxRate`,
 *   `taxAmount`, `grossAmount`, `invoiceNumber`).
 * @param {Object|null} params.customer - Customer DTO (`firstName`, `lastName`,
 *   `street`, `houseNumber`, `postalCode`, `city`, `email`, `phone`).
 * @returns {{ variables: Object, totals: Object }} live data to feed to the
 *   editor:
 *   - `variables`: the {{...}} substitution map (German + English aliases
 *     for customer, invoice header, totals, payment reference).
 *   - `totals`: the `data.subtotal/taxRate/taxAmount/total/currency` object
 *     the `totals` block reads directly (its renderer doesn't use {{...}}
 *     substitution). Merged on top of the block's data at render time.
 */
export function buildPreviewVariables({ invoice = null, customer = null } = {}) {
  const variables = {}
  const totals = {}

  // --- customer (receiver) ---
  variables.kunde = fullName(customer)
  variables.kunde_strasse = streetAndNumber(customer)
  variables.kunde_plz_ort = postalCodeAndCity(customer)
  variables.kunde_email = customer ? safe(customer.email) : ''
  variables.kunde_telefon = customer ? safe(customer.phone) : ''

  // --- invoice header (German + English aliases) ---
  if (invoice) {
    const number = safe(invoice.invoiceNumber)
    variables.rechnungsnummer = number
    variables.invoice_number = number

    variables.rechnungsdatum = formatDateForPreview(invoice.invoiceDate)
    variables.invoice_date = variables.rechnungsdatum

    variables.faelligkeitsdatum = formatDateForPreview(invoice.dueDate)
    variables.due_date = variables.faelligkeitsdatum

    variables.leistungsdatum = formatDateForPreview(invoice.workStartDate)
    variables.leistungszeitraum = formatPeriod(invoice.workStartDate, invoice.workEndDate)

    const notes = safe(invoice.notes)
    variables.notizen = notes
    variables.notes = notes

    // --- totals ---
    // The variable entries (German + English aliases) are useful for any
    // block that uses {{netto}} / {{steuersatz}} / etc. in a text field.
    // The `totals` object is the data the `totals` block's renderer reads
    // directly (data.subtotal/taxRate/taxAmount/total/currency); the
    // editor's render-time data swap feeds it.
    const net = invoice.netAmount === null || invoice.netAmount === undefined
      ? null
      : Number(invoice.netAmount)
    const tax = invoice.taxRate === null || invoice.taxRate === undefined
      ? null
      : Number(invoice.taxRate)
    const taxAmount = invoice.taxAmount === null || invoice.taxAmount === undefined
      ? null
      : Number(invoice.taxAmount)
    const gross = invoice.grossAmount === null || invoice.grossAmount === undefined
      ? null
      : Number(invoice.grossAmount)
    variables.netto = net === null ? '' : net
    variables.subtotal = variables.netto
    variables.steuersatz = tax === null ? '' : tax
    variables.tax_rate = variables.steuersatz
    variables.steuer = taxAmount === null ? '' : taxAmount
    variables.tax_amount = variables.steuer
    variables.betrag = gross === null ? '' : gross
    variables.gross_amount = variables.betrag
    variables.waehrung = 'EUR'
    variables.currency = 'EUR'

    totals.subtotal = net
    totals.taxRate = tax
    totals.taxAmount = taxAmount
    totals.total = gross
    totals.currency = 'EUR'
    // Shape the editor's totals block renders: VAT groups from the server's
    // VatCalculator, legal note from the tax treatment.
    Object.assign(totals, totalsFromInvoice(invoice))

    // --- payment (just the reference) ---
    // Bank fields (bank_name, bic) stay as SAMPLE_DATA fallbacks because
    // Owner has no source for them today.
    variables.verwendungszweck = number
  } else {
    // No invoice: leave German/English aliases empty so SAMPLE_DATA wins.
    variables.rechnungsnummer = ''
    variables.invoice_number = ''
    variables.rechnungsdatum = ''
    variables.invoice_date = ''
    variables.faelligkeitsdatum = ''
    variables.due_date = ''
    variables.leistungsdatum = ''
    variables.leistungszeitraum = ''
    variables.notizen = ''
    variables.notes = ''
    variables.netto = ''
    variables.subtotal = ''
    variables.steuersatz = ''
    variables.tax_rate = ''
    variables.steuer = ''
    variables.tax_amount = ''
    variables.betrag = ''
    variables.gross_amount = ''
    variables.waehrung = ''
    variables.currency = ''
    variables.verwendungszweck = ''
  }

  return { variables, totals }
}

/**
 * The InvoiceItemDto tree -> the line-items block's
 * `{ description, quantity, unit, rate, amount, parentItemId, children }`
 * shape.
 *
 * CUSTOM ("Freitext") items get qty=1, rate=0, amount=0, unit="" (the
 * renderer invariants in `DocumentService.buildItem` /
 * `InvoiceRepository.saveInvoiceItem`). The renderer treats them as
 * merged-cell note rows; the editor preview should match.
 *
 * Sub-items pass through as a tree; the block registry's `line-items`
 * renderer walks `data.items` flat, but the saved `blocks` JSON is
 * never mutated (TemplateEditor.renderBlock does the swap at preview
 * time), so the real items never leak into the stored template.
 */
export function buildLineItemsData(items) {
  if (!Array.isArray(items)) {
    return []
  }
  return items.map((item) => buildOneLineItem(item))
}

function buildOneLineItem(item) {
  const type = String(item.itemType || '').toUpperCase()
  const isNote = type === 'CUSTOM'
  const out = {
    description: item.itemName === null || item.itemName === undefined
      ? ''
      : String(item.itemName),
    quantity: isNote ? 1 : toNumberOr(item.quantity, 0),
    unit: isNote ? '' : (item.unit === null || item.unit === undefined ? '' : String(item.unit)),
    rate: isNote ? 0 : toNumberOr(item.unitPrice, 0),
    amount: isNote ? 0 : toNumberOr(item.totalPrice, 0),
    // InvoiceItemDto calls the per-line VAT rate `taxRate` (percent, e.g. 19).
    taxRate: isNote ? null : toNumberOr(item.taxRate ?? item.taxRatePercent, null),
    isNote,
    noteText: isNote ? (item.itemDescription == null ? '' : String(item.itemDescription)) : '',
    parentItemId: item.parentItemId && Number(item.parentItemId) > 0
      ? Number(item.parentItemId)
      : null
  }
  if (Array.isArray(item.children) && item.children.length > 0) {
    out.children = item.children.map(buildOneLineItem)
  }
  return out
}

function toNumberOr(value, fallback) {
  if (value === null || value === undefined || value === '') {
    return fallback
  }
  const n = Number(value)
  return Number.isFinite(n) ? n : fallback
}

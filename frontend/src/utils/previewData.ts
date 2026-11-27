import { SAMPLE_DATA } from './templatePreview.js'
import { formatCurrency, type PreviewItem, type PreviewTotals, type RenderContext } from './blockRegistry.ts'
import { legalNote } from './vat.ts'
import type { InvoiceDto, Owner } from '../api/types.js'

/**
 * Data the template editor preview renders with.
 *
 * The preview context has the same parts as the PDF renderer's input:
 * - `variables`: values for `{{placeholders}}`, already formatted like the
 *   PDF (German dates, `1.234,56 €`),
 * - `items`: line items for the `line-items` block,
 * - `totals`: net / gross / VAT groups / legal note for the `totals` block,
 * - `documentTypeId`: number label and credit-note rules.
 *
 * Three sources, later ones win: sample document → company data (owner) →
 * the real document (per-document editor, `liveData`).
 */

/** Real document data of the per-document editor (`DocumentModal.openCustomize`). */
export interface LiveData {
  variables?: Record<string, unknown>
  items?: PreviewItem[]
  totals?: PreviewTotals
  invoiceId?: number
}

/** What the preview renders with (see the file comment). */
export interface PreviewContext extends Required<Pick<RenderContext, 'variables' | 'items' | 'totals' | 'documentTypeId'>> {
  isSample: boolean
}

/** Sample line items: two VAT rates and a sub-item, so every case is visible. */
export const SAMPLE_ITEMS: PreviewItem[] = [
  { description: 'Beratung und Planung', quantity: 5, unit: 'Std.', rate: 100, amount: 500, taxRate: 19 },
  {
    description: 'Material',
    quantity: 1,
    unit: 'Pauschal',
    rate: 500,
    amount: 500,
    taxRate: 19,
    children: [{ description: 'Anfahrt', isNote: true, noteText: '50 km, pauschal' }]
  }
]

/** Totals matching {@link SAMPLE_ITEMS}. */
export const SAMPLE_TOTALS: PreviewTotals = {
  net: 1000,
  gross: 1190,
  groups: [{ ratePercent: 19, net: 1000, tax: 190 }],
  chargesVat: true,
  legalNote: ''
}

/**
 * VAT totals for the preview from an invoice DTO (`vatGroups`, `taxTreatment`
 * come from the server's `VatCalculator`; nothing is recomputed here). The legal note is the one
 * of `vat.ts` (mirrors `TaxTreatment.legalNote` in Java).
 * @returns totals in the shape of {@link SAMPLE_TOTALS}
 */
export function totalsFromInvoice(invoice: InvoiceDto): PreviewTotals {
  const treatment = String(invoice.taxTreatment || 'STANDARD').toUpperCase()
  return {
    net: Number(invoice.netAmount) || 0,
    gross: Number(invoice.grossAmount) || 0,
    groups: (invoice.vatGroups || []).map(g => ({
      ratePercent: Number(g.ratePercent),
      net: Number(g.net),
      tax: Number(g.tax)
    })),
    chargesVat: treatment === 'STANDARD',
    legalNote: legalNote(treatment, invoice.taxExemptionReason)
  }
}

/** Converts `2025-01-15` to `15.01.2025`; other values pass through. */
function germanDate(value: unknown): unknown {
  const match = typeof value === 'string' && value.match(/^(\d{4})-(\d{2})-(\d{2})$/)
  return match ? `${match[3]}.${match[2]}.${match[1]}` : value
}

/** Variables whose values are amounts and print as `1.234,56 €`. */
const MONEY_VARIABLES: readonly string[] = ['netto', 'steuer', 'betrag', 'rabatt']

/**
 * Formats raw variable values like the PDF renderer does.
 */
export function formatVariables(values: Record<string, unknown>): Record<string, unknown> {
  const out: Record<string, unknown> = { ...values }
  for (const key of ['rechnungsdatum', 'faelligkeitsdatum', 'leistungsdatum']) {
    if (key in out) out[key] = germanDate(out[key])
  }
  for (const key of MONEY_VARIABLES) {
    if (typeof out[key] === 'number') out[key] = formatCurrency(out[key])
  }
  if (typeof out.steuersatz === 'number') out.steuersatz = out.steuersatz.toFixed(1).replace('.', ',')
  return out
}

/**
 * Company variables from the owner record, formatted like the PDF
 * (`TemplateBasedPdfGenerator.replaceVariables`). Empty fields stay empty,
 * so the preview shows what the PDF will print.
 */
export function ownerVariables(owner: Owner | null | undefined): Record<string, string> {
  if (!owner) return {}
  const value = (v: unknown): string => (v == null ? '' : String(v))
  return {
    firma: value(owner.companyName),
    adresse: `${value(owner.street)} ${value(owner.houseNumber)}, ${value(owner.postalCode)} ${value(owner.city)}`,
    telefon: value(owner.phone),
    email: value(owner.email),
    website: value(owner.url),
    ust_id: value(owner.taxId),
    iban: value(owner.iban),
    bank_name: '',
    bic: ''
  }
}

/**
 * Builds the preview context.
 * @param params.owner - Company data; without it the sample company is shown
 * @param params.liveData - Real document data (`variables`, `items`, `totals`)
 */
export function buildPreviewContext({ owner = null, liveData = null, documentTypeId = 1 }: {
  owner?: Owner | null
  liveData?: LiveData | null
  documentTypeId?: number
} = {}): PreviewContext {
  const sample: Record<string, unknown> = { ...SAMPLE_DATA }
  delete sample.positionen
  const live = liveData && liveData.variables ? liveData.variables : {}
  // Live variables set to '' mean "this document has no value": keep them empty.
  const variables = formatVariables({ ...sample, ...ownerVariables(owner), ...live })
  return {
    variables,
    items: liveData && Array.isArray(liveData.items) ? liveData.items : SAMPLE_ITEMS,
    totals: liveData && liveData.totals && Array.isArray(liveData.totals.groups) ? liveData.totals : SAMPLE_TOTALS,
    documentTypeId,
    isSample: !liveData
  }
}

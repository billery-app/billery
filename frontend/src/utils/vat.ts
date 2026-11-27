/**
 * VAT helpers mirroring the backend's `VatCalculator` / `TaxTreatment`, so the
 * editor shows the same totals the server will store and print. The server
 * recomputes everything; these values are for display only.
 */

import { t } from '../i18n/index.js'

/** VAT rates a line may carry under regular taxation (§12 UStG). */
export const VAT_RATES: readonly number[] = [19, 7]

/** How VAT applies to a document; equals `TaxTreatment` in the backend. */
export type TaxTreatment = 'STANDARD' | 'SMALL_BUSINESS' | 'REVERSE_CHARGE' | 'TAX_EXEMPT'

/** One line as the calculation reads it (a document line or a line of the editor). */
export interface VatItem {
  itemType?: string | null
  quantity?: number | string | null
  unitPrice?: number | string | null
  taxRate?: number | string | null
  children?: VatItem[] | null
}

/** Net and VAT of one rate. */
export interface VatGroup {
  rate: number
  net: number
  tax: number
}

/** Result of {@link calculateVat}. */
export interface VatTotals {
  groups: VatGroup[]
  net: number
  tax: number
  gross: number
}

/**
 * How VAT applies to a whole document. Keys match `TaxTreatment` in the backend; the labels are in
 * the message files (`taxTreatments`). The legal notes below are printed on the document and stay
 * German (#15).
 */
export const TAX_TREATMENTS: readonly { value: TaxTreatment, readonly label: string }[] = (['STANDARD', 'REVERSE_CHARGE', 'TAX_EXEMPT'] as const).map(value => ({
  value,
  get label() {
    return t(`taxTreatments.${value}`)
  }
}))

/** Treatment of every document of a Kleinunternehmer; never chosen by hand. */
export const SMALL_BUSINESS: TaxTreatment = 'SMALL_BUSINESS'

const LEGAL_NOTES: Readonly<Record<Exclude<TaxTreatment, 'STANDARD'>, string>> = {
  SMALL_BUSINESS: 'Gemäß § 19 UStG wird keine Umsatzsteuer berechnet (Kleinunternehmer).',
  REVERSE_CHARGE: 'Steuerschuldnerschaft des Leistungsempfängers (§ 13b UStG).',
  TAX_EXEMPT: 'Steuerfreie Leistung'
}

/** True when the treatment charges VAT (only STANDARD does). */
export function chargesVat(treatment: string | null | undefined): boolean {
  return !treatment || treatment === 'STANDARD'
}

/**
 * The note an invoice must carry for its treatment (empty for STANDARD).
 * @param exemptionReason - legal basis for TAX_EXEMPT
 */
export function legalNote(treatment: string | null | undefined, exemptionReason?: string | null): string {
  if (treatment === 'TAX_EXEMPT') {
    const reason = (exemptionReason || '').trim()
    return reason ? `${LEGAL_NOTES.TAX_EXEMPT} ${reason}.` : `${LEGAL_NOTES.TAX_EXEMPT}.`
  }
  return (treatment && LEGAL_NOTES[treatment as keyof typeof LEGAL_NOTES]) || ''
}

function cents(value: number): number {
  return Math.round((value + Number.EPSILON) * 100) / 100
}

function isNote(item: VatItem): boolean {
  return (item.itemType || '').toLowerCase() === 'custom'
}

function collect(items: VatItem[] | null | undefined, treatment: string | null | undefined, netByRate: Map<number, number>): void {
  if (!Array.isArray(items)) return
  for (const item of items) {
    if (!isNote(item)) {
      const quantity = cents(Number(item.quantity) || 0)
      const unitPrice = cents(Number(item.unitPrice) || 0)
      const rate = chargesVat(treatment) ? Number(item.taxRate ?? 19) : 0
      netByRate.set(rate, (netByRate.get(rate) || 0) + cents(quantity * unitPrice))
    }
    collect(item.children, treatment, netByRate)
  }
}

/**
 * Net, VAT per rate and gross of an item tree. Tax is rounded once per rate
 * group, like the backend (EN 16931 BR-CO-17).
 * @param items - root items with optional `children`
 * @param treatment - STANDARD, SMALL_BUSINESS, REVERSE_CHARGE, TAX_EXEMPT
 */
export function calculateVat(items: VatItem[] | null | undefined, treatment: string | null | undefined): VatTotals {
  const netByRate = new Map<number, number>()
  collect(items, treatment, netByRate)
  const groups = [...netByRate.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([rate, net]) => ({ rate, net: cents(net), tax: cents(cents(net) * rate / 100) }))
  const net = cents(groups.reduce((sum, g) => sum + g.net, 0))
  const tax = cents(groups.reduce((sum, g) => sum + g.tax, 0))
  return { groups, net, tax, gross: cents(net + tax) }
}

/** "19 %", "7 %". */
export function formatRate(rate: number | string): string {
  return `${String(Number(rate)).replace('.', ',')} %`
}

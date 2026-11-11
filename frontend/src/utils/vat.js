/**
 * VAT helpers mirroring the backend's `VatCalculator` / `TaxTreatment`, so the
 * editor shows the same totals the server will store and print. The server
 * recomputes everything; these values are for display only.
 */

/** VAT rates a line may carry under regular taxation (§12 UStG). */
export const VAT_RATES = [19, 7]

/** How VAT applies to a whole document. Keys match `TaxTreatment` in the backend. */
export const TAX_TREATMENTS = [
  { value: 'STANDARD', label: 'Regelbesteuerung (19 % / 7 %)' },
  { value: 'REVERSE_CHARGE', label: 'Reverse Charge (§ 13b UStG)' },
  { value: 'TAX_EXEMPT', label: 'Steuerfrei (§ 4 UStG)' }
]

/** Treatment of every document of a Kleinunternehmer; never chosen by hand. */
export const SMALL_BUSINESS = 'SMALL_BUSINESS'

const LEGAL_NOTES = {
  SMALL_BUSINESS: 'Gemäß § 19 UStG wird keine Umsatzsteuer berechnet (Kleinunternehmer).',
  REVERSE_CHARGE: 'Steuerschuldnerschaft des Leistungsempfängers (§ 13b UStG).',
  TAX_EXEMPT: 'Steuerfreie Leistung'
}

/** True when the treatment charges VAT (only STANDARD does). */
export function chargesVat(treatment) {
  return !treatment || treatment === 'STANDARD'
}

/**
 * The note an invoice must carry for its treatment (empty for STANDARD).
 * @param {string} treatment
 * @param {string} [exemptionReason] - legal basis for TAX_EXEMPT
 */
export function legalNote(treatment, exemptionReason) {
  if (treatment === 'TAX_EXEMPT') {
    const reason = (exemptionReason || '').trim()
    return reason ? `${LEGAL_NOTES.TAX_EXEMPT} ${reason}.` : `${LEGAL_NOTES.TAX_EXEMPT}.`
  }
  return LEGAL_NOTES[treatment] || ''
}

function cents(value) {
  return Math.round((value + Number.EPSILON) * 100) / 100
}

function isNote(item) {
  return (item.itemType || '').toLowerCase() === 'custom'
}

function collect(items, treatment, netByRate) {
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
 * @param {Array} items - root items with optional `children`
 * @param {string} treatment - STANDARD, SMALL_BUSINESS, REVERSE_CHARGE, TAX_EXEMPT
 * @returns {{groups: Array<{rate: number, net: number, tax: number}>, net: number, tax: number, gross: number}}
 */
export function calculateVat(items, treatment) {
  const netByRate = new Map()
  collect(items, treatment, netByRate)
  const groups = [...netByRate.entries()]
    .sort((a, b) => a[0] - b[0])
    .map(([rate, net]) => ({ rate, net: cents(net), tax: cents(cents(net) * rate / 100) }))
  const net = cents(groups.reduce((sum, g) => sum + g.net, 0))
  const tax = cents(groups.reduce((sum, g) => sum + g.tax, 0))
  return { groups, net, tax, gross: cents(net + tax) }
}

/** "19 %", "7 %". */
export function formatRate(rate) {
  return `${String(Number(rate)).replace('.', ',')} %`
}

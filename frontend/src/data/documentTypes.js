export const INVOICE = 1
export const OFFER = 2
export const COST_CALCULATION = 3
export const PARTED_INVOICE = 4
export const FINAL_INVOICE = 5
/** Stornorechnung: created by cancelling an issued invoice, never by hand. */
export const CREDIT_NOTE = 6

export function isInvoice(typeId) {
  return typeId === INVOICE || typeId === PARTED_INVOICE || typeId === FINAL_INVOICE
}

export function isCreditNote(typeId) {
  return typeId === CREDIT_NOTE
}

/**
 * Invoice types and credit notes are bookkeeping records: once issued they can
 * no longer be edited or deleted (GoBD), only cancelled by a credit note.
 */
export function isBookkeepingRecord(typeId) {
  return isInvoice(typeId) || isCreditNote(typeId)
}

export function isOffer(typeId) {
  return typeId === OFFER
}

export function isCostCalc(typeId) {
  return typeId === COST_CALCULATION
}

export function isOfferOrCostCalc(typeId) {
  return typeId === OFFER || typeId === COST_CALCULATION
}

/**
 * The document types. `label` is the singular, `plural` the plural and `newLabel` the title for
 * creating one ("Neues Angebot"): the adjective depends on the noun's gender, so it is spelled out
 * per type instead of being put together from the label.
 */
export const DOCUMENT_TYPES = [
  { key: 'invoice', id: INVOICE, label: 'Rechnung', newLabel: 'Neue Rechnung', plural: 'Rechnungen', prefix: 'RE' },
  { key: 'offer', id: OFFER, label: 'Angebot', newLabel: 'Neues Angebot', plural: 'Angebote', prefix: 'AN' },
  { key: 'costCalculation', id: COST_CALCULATION, label: 'Kostenvoranschlag', newLabel: 'Neuer Kostenvoranschlag', plural: 'Kostenvoranschläge', prefix: 'KV' },
  { key: 'partedInvoice', id: PARTED_INVOICE, label: 'Teilrechnung', newLabel: 'Neue Teilrechnung', plural: 'Teilrechnungen', prefix: 'TR' },
  { key: 'finalInvoice', id: FINAL_INVOICE, label: 'Endrechnung', newLabel: 'Neue Endrechnung', plural: 'Endrechnungen', prefix: 'SR' },
  { key: 'creditNote', id: CREDIT_NOTE, label: 'Stornorechnung', newLabel: 'Neue Stornorechnung', plural: 'Stornorechnungen', prefix: 'ST' }
]

export function getDocumentType(key) {
  return DOCUMENT_TYPES.find(dt => dt.key === key)
}

export function getDocumentTypeById(id) {
  return DOCUMENT_TYPES.find(dt => dt.id === id)
}

export function getDocumentTypeKey(id) {
  const dt = getDocumentTypeById(id)
  return dt ? dt.key : 'invoice'
}

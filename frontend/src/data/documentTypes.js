import { t } from '../i18n/index.js'

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
 * One document type. `title` is the German name printed on the document (the default title of a
 * template, kept German whatever the interface language: documents stay German, #15). The names
 * shown in the interface come from the message files, `documentTypes.<key>.*`: `label` (singular),
 * `plural`, `newLabel` ("Neues Angebot": the adjective depends on the noun's gender, so it is a
 * message of its own), `editLabel`, `listTitle`, `empty` and `emptyArchived`.
 * @param {string} key
 * @param {number} id
 * @param {string} title - the German name on the document
 * @param {string} prefix - of the document number
 */
function documentType(key, id, title, prefix) {
  const text = name => t(`documentTypes.${key}.${name}`)
  return {
    key,
    id,
    title,
    prefix,
    get label() {
      return text('label')
    },
    get plural() {
      return text('plural')
    },
    get newLabel() {
      return text('new')
    },
    get editLabel() {
      return text('edit')
    },
    get listTitle() {
      return text('list')
    },
    get empty() {
      return text('empty')
    },
    get emptyArchived() {
      return text('emptyArchived')
    }
  }
}

/** The document types. */
export const DOCUMENT_TYPES = [
  documentType('invoice', INVOICE, 'Rechnung', 'RE'),
  documentType('offer', OFFER, 'Angebot', 'AN'),
  documentType('costCalculation', COST_CALCULATION, 'Kostenvoranschlag', 'KV'),
  documentType('partedInvoice', PARTED_INVOICE, 'Teilrechnung', 'TR'),
  documentType('finalInvoice', FINAL_INVOICE, 'Endrechnung', 'SR'),
  documentType('creditNote', CREDIT_NOTE, 'Stornorechnung', 'ST')
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

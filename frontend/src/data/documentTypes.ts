import { t } from '../i18n/index.js'

/**
 * The document types (ids of `document_type`) and their names (TypeScript since #39, step 3).
 */

export const INVOICE = 1
export const OFFER = 2
export const COST_CALCULATION = 3
export const PARTED_INVOICE = 4
export const FINAL_INVOICE = 5
/** Stornorechnung: created by cancelling an issued invoice, never by hand. */
export const CREDIT_NOTE = 6

export function isInvoice(typeId: number | null | undefined): boolean {
  return typeId === INVOICE || typeId === PARTED_INVOICE || typeId === FINAL_INVOICE
}

export function isCreditNote(typeId: number | null | undefined): boolean {
  return typeId === CREDIT_NOTE
}

/**
 * Invoice types and credit notes are bookkeeping records: once issued they can
 * no longer be edited or deleted (GoBD), only cancelled by a credit note.
 */
export function isBookkeepingRecord(typeId: number | null | undefined): boolean {
  return isInvoice(typeId) || isCreditNote(typeId)
}

export function isOffer(typeId: number | null | undefined): boolean {
  return typeId === OFFER
}

export function isCostCalc(typeId: number | null | undefined): boolean {
  return typeId === COST_CALCULATION
}

export function isOfferOrCostCalc(typeId: number | null | undefined): boolean {
  return typeId === OFFER || typeId === COST_CALCULATION
}

/** Key of a document type: also the key of its names in the message files. */
export type DocumentTypeKey = 'invoice' | 'offer' | 'costCalculation' | 'partedInvoice' | 'finalInvoice' | 'creditNote'

/** A document type with its names in the interface language (getters, they follow a switch). */
export interface DocumentType {
  readonly key: DocumentTypeKey
  readonly id: number
  /** The German name printed on the document. */
  readonly title: string
  /** Prefix of the document number. */
  readonly prefix: string
  readonly label: string
  readonly plural: string
  readonly newLabel: string
  readonly editLabel: string
  readonly listTitle: string
  readonly empty: string
  readonly emptyArchived: string
}

/**
 * One document type. `title` is the German name printed on the document (the default title of a
 * template, kept German whatever the interface language: documents stay German, #15). The names
 * shown in the interface come from the message files, `documentTypes.<key>.*`: `label` (singular),
 * `plural`, `newLabel` ("Neues Angebot": the adjective depends on the noun's gender, so it is a
 * message of its own), `editLabel`, `listTitle`, `empty` and `emptyArchived`.
 */
function documentType(key: DocumentTypeKey, id: number, title: string, prefix: string): DocumentType {
  const text = (name: string): string => t(`documentTypes.${key}.${name}`)
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
export const DOCUMENT_TYPES: readonly DocumentType[] = [
  documentType('invoice', INVOICE, 'Rechnung', 'RE'),
  documentType('offer', OFFER, 'Angebot', 'AN'),
  documentType('costCalculation', COST_CALCULATION, 'Kostenvoranschlag', 'KV'),
  documentType('partedInvoice', PARTED_INVOICE, 'Teilrechnung', 'TR'),
  documentType('finalInvoice', FINAL_INVOICE, 'Endrechnung', 'SR'),
  documentType('creditNote', CREDIT_NOTE, 'Stornorechnung', 'ST')
]

export function getDocumentType(key: string): DocumentType | undefined {
  return DOCUMENT_TYPES.find(dt => dt.key === key)
}

export function getDocumentTypeById(id: number | null | undefined): DocumentType | undefined {
  return DOCUMENT_TYPES.find(dt => dt.id === id)
}

export function getDocumentTypeKey(id: number | null | undefined): DocumentTypeKey {
  const dt = getDocumentTypeById(id)
  return dt ? dt.key : 'invoice'
}

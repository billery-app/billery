/**
 * Document statuses (ids of `document_status`) and the rules the SPA derives from them
 * (TypeScript since #39, step 3).
 */
export const DRAFT = 1
export const SENT = 2
export const ACCEPTED = 3
export const REJECTED = 4
export const CONVERTED = 5
export const PAID = 6
export const CANCELLED = 8
/** Derived from the recorded payments; never set by hand. Id 7 was the removed OVERDUE. */
export const PARTIALLY_PAID = 9

/** A status id as the API sends it (`documentStatusId`); may be missing on a new document. */
export type StatusId = number | null | undefined

/** What the overdue / expired checks read of a document. */
export interface StatusDates {
  documentStatusId?: number | null
  dueDate?: string | null
  paidDate?: string | null
  /** Not sent by the API yet (the column exists, no form sets it); the server filter reads it. */
  offerValidUntil?: string | null
}

/**
 * Status lifecycle of offers and cost calculations: status → the statuses it can move to. Must equal
 * `OfferStrategy.TRANSITIONS` in the backend; `useDocumentType.spec.js` checks it against the shared
 * `backend/src/test/resources/status-parity/transitions.json`. CONVERTED and CANCELLED are final.
 */
export const OFFER_TRANSITIONS: Readonly<Record<number, readonly number[]>> = {
  [DRAFT]: [SENT, ACCEPTED, CANCELLED],
  [SENT]: [ACCEPTED, REJECTED, CANCELLED],
  [REJECTED]: [ACCEPTED, CANCELLED],
  [ACCEPTED]: [CONVERTED, CANCELLED]
}

export function isDraft(statusId: StatusId): boolean { return statusId === DRAFT }
export function isSent(statusId: StatusId): boolean { return statusId === SENT }
export function isAccepted(statusId: StatusId): boolean { return statusId === ACCEPTED }
export function isRejected(statusId: StatusId): boolean { return statusId === REJECTED }
export function isConverted(statusId: StatusId): boolean { return statusId === CONVERTED }
export function isPaid(statusId: StatusId): boolean { return statusId === PAID }
export function isCancelled(statusId: StatusId): boolean { return statusId === CANCELLED }
export function isPartiallyPaid(statusId: StatusId): boolean { return statusId === PARTIALLY_PAID }

/** Issued and not yet fully paid: payments can be recorded. */
export function isOpenInvoice(statusId: StatusId): boolean { return statusId === SENT || statusId === PARTIALLY_PAID }

export function isDeletable(statusId: StatusId): boolean { return isDraft(statusId) }
export function isArchivable(statusId: StatusId): boolean { return !isDraft(statusId) }

const TERMINAL_STATUSES: ReadonlySet<number> = new Set([PAID, CANCELLED, CONVERTED, DRAFT])

/**
 * Returns true when an invoice is past its due date and has not been paid.
 *
 * <p>"Overdue" is a derived property: it is computed at render time from
 * {@code documentStatusId}, {@code dueDate} and {@code paidDate} instead of
 * being stored as a separate document_status. The backend used to keep a
 * stored OVERDUE status (id=7) and a daily job that flipped SENT invoices
 * with a past due_date into it; that stored state has been removed because
 * it duplicated this date check and could disagree with the UI between job
 * runs.</p>
 *
 * <p>An invoice is overdue when it has a {@code dueDate} in the past, has not
 * been paid (no {@code paidDate}), and is not in a terminal state (paid,
 * cancelled, converted, or still a draft).</p>
 */
export function isInvoiceOverdue(invoice: StatusDates | null | undefined): boolean {
  if (!invoice) return false
  if (invoice.paidDate) return false
  if (!invoice.dueDate) return false
  if (invoice.documentStatusId != null && TERMINAL_STATUSES.has(invoice.documentStatusId)) return false
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return new Date(invoice.dueDate) < today
}

/**
 * Returns true when an offer or cost calculation is past its validity date
 * and has not been accepted, rejected, converted, or cancelled. Mirrors
 * {@link isInvoiceOverdue} for the non-invoice document types where the
 * relevant date is {@code offerValidUntil}.
 */
export function isOfferExpired(doc: StatusDates | null | undefined): boolean {
  if (!doc) return false
  if (!doc.offerValidUntil) return false
  if (doc.documentStatusId === ACCEPTED
      || doc.documentStatusId === REJECTED
      || doc.documentStatusId === CONVERTED
      || doc.documentStatusId === CANCELLED) {
    return false
  }
  const today = new Date()
  today.setHours(0, 0, 0, 0)
  return new Date(doc.offerValidUntil) < today
}

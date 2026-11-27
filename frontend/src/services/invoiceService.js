import { apiClient } from './baseService.js'
import { INVOICE } from '../data/documentTypes.ts'

/**
 * Documents of every type, their payments, history and e-invoice (`/api/invoices`).
 * @typedef {import('@/api/types.js').InvoiceDto} InvoiceDto
 * @typedef {import('@/api/types.js').InvoicePageDto} InvoicePageDto
 * @typedef {import('@/api/types.js').InvoiceStatistics} InvoiceStatistics
 * @typedef {import('@/api/types.js').Payment} Payment
 * @typedef {import('@/api/types.js').PaymentRequest} PaymentRequest
 * @typedef {import('@/api/types.js').DocumentHistoryEntry} DocumentHistoryEntry
 */
/**
 * @template T
 * @typedef {import('@/api/types.js').Response<T>} Response
 */
export default {
  /**
   * One page of the document list, filtered and ordered by the backend.
   * @param {Object} query
   * @param {number} query.page - 0-based page
   * @param {number} query.size - Documents per page (max 200)
   * @param {number[]} query.types - Document type ids of the active tab
   * @param {boolean} query.archived - Archive instead of active documents
   * @param {boolean} [query.includeArchived] - Active and archived together (overrides archived)
   * @param {number|string} [query.year] - Year of the document date, '' for all
   * @param {string} [query.from] - First document date (YYYY-MM-DD)
   * @param {string} [query.to] - Last document date (YYYY-MM-DD)
   * @param {string} [query.q] - Text in the number or the customer's name
   * @param {string} [query.status] - Status filter key ('draft', 'overdue', 'open', …), '' for all
   * @returns {Promise<Response<InvoicePageDto>>}
   */
  getInvoicePage({ page, size, types, archived, includeArchived, year, from, to, q, status }) {
    /** @type {Record<string, string|number|boolean>} */
    const params = { page, size, types: types.join(','), archived }
    if (includeArchived) params.includeArchived = true
    if (from) params.from = from
    if (to) params.to = to
    if (year) params.year = year
    if (q) params.q = q
    if (status) params.status = status
    return apiClient.get('/invoices/page', { params })
  },

  /**
   * Gets next invoice number preview
   * @param {number} [documentTypeId] - the type whose next number is shown
   * @returns {Promise<Response<string>>} - Next invoice number
   */
  getNextInvoiceNumber(documentTypeId = INVOICE) {
    return apiClient.get('/invoices/next-number', { params: { documentTypeId } })
  },

  /**
   * Gets a specific invoice by ID
   * @param {number} id - Invoice ID
   * @returns {Promise<Response<InvoiceDto>>} - the document
   */
  getInvoice(id) {
    return apiClient.get(`/invoices/${id}`)
  },

  /**
   * Gets invoices by customer
   * @param {number} customerId - Customer ID
   * @returns {Promise<Response<InvoiceDto[]>>} - the customer's documents
   */
  getInvoicesByCustomer(customerId) {
    return apiClient.get(`/invoices/by-customer/${customerId}`)
  },

  /**
   * Creates a new invoice
   * @param {InvoiceDto} invoice - Invoice data
   * @returns {Promise<Response<InvoiceDto>>} - Created invoice
   */
  createInvoice(invoice) {
    return apiClient.post('/invoices', invoice)
  },

  /**
   * Updates an existing invoice
   * @param {number} id - Invoice ID
   * @param {InvoiceDto} invoice - Updated invoice data
   * @returns {Promise<Response<InvoiceDto>>} - Updated invoice
   */
  updateInvoice(id, invoice) {
    return apiClient.put(`/invoices/${id}`, invoice)
  },

  /**
   * Clones an existing invoice as a new draft
   * @param {number} id - Source invoice ID
   * @param {Object} dates - New dates { invoiceDate, dueDate, workStartDate, workEndDate }
   * @returns {Promise<Response<InvoiceDto>>} - Cloned invoice
   */
  cloneInvoice(id, dates) {
    return apiClient.post(`/invoices/${id}/clone`, dates)
  },

  /**
   * Gets invoice statistics for a date range
   * @param {string} from - Start date (YYYY-MM-DD)
   * @param {string} to - End date (YYYY-MM-DD)
   * @returns {Promise<Response<InvoiceStatistics>>} - Statistics object
   */
  getStatistics(from, to) {
    return apiClient.get('/invoices/statistics', { params: { from, to } })
  },

  /**
   * Deletes an invoice
   * @param {number} id - Invoice ID
   * @returns {Promise<void>}
   */
  deleteInvoice(id) {
    return apiClient.delete(`/invoices/${id}`)
  },

  /**
   * Requests a status change. For invoices the backend runs the bookkeeping
   * step behind it: SENT issues the draft (assigns its number), PAID records the
   * open amount as one payment on `paymentDate`, CANCELLED creates a
   * Stornorechnung.
   * @param {number} id - Document ID
   * @param {number} documentStatusId - New status ID
   * @param {Object} [extra] - `paymentDate` (YYYY-MM-DD, required for PAID) or
   *   `convertedToInvoiceId` (for CONVERTED)
   * @returns {Promise<Response<InvoiceDto>>} - The updated document
   */
  updateInvoiceStatus(id, documentStatusId, extra = {}) {
    return apiClient.patch(`/invoices/${id}/status`, { documentStatusId, ...extra })
  },

  /**
   * Cancels an issued invoice by creating a Stornorechnung.
   * @param {number} id - Invoice ID
   * @returns {Promise<Response<InvoiceDto>>} - The credit note
   */
  cancelInvoice(id) {
    return apiClient.post(`/invoices/${id}/cancel`)
  },

  /**
   * Lists the payments of an invoice, reversals included.
   * @param {number} id - Invoice ID
   * @returns {Promise<Response<Payment[]>>} - Payments, oldest first
   */
  getPayments(id) {
    return apiClient.get(`/invoices/${id}/payments`)
  },

  /**
   * Records a payment for an issued, open invoice.
   * @param {number} id - Invoice ID
   * @param {PaymentRequest} payment - { paymentDate, amount, paymentMethod, reference, notes }
   * @returns {Promise<Response<Payment>>} - The saved payment
   */
  recordPayment(id, payment) {
    return apiClient.post(`/invoices/${id}/payments`, payment)
  },

  /**
   * Reverses a mistaken payment with a negative counter-entry (payments are
   * never deleted).
   * @param {number} id - Invoice ID
   * @param {number} paymentId - Payment to reverse
   * @returns {Promise<Response<Payment>>} - The reversal row
   */
  reversePayment(id, paymentId) {
    return apiClient.post(`/invoices/${id}/payments/${paymentId}/reverse`)
  },

  /**
   * Downloads the XRechnung (e-invoice, UBL XML) of an issued invoice or credit
   * note. Fails with a message listing missing master data when incomplete.
   * @param {number} id - Document ID
   * @returns {Promise<Response<Blob>>} - The XML
   */
  downloadXRechnung(id) {
    return apiClient.get(`/invoices/${id}/xrechnung`, { responseType: 'blob' })
  },

  /**
   * Lists what an XRechnung of the document still lacks (works for drafts).
   * @param {number} id - Document ID
   * @returns {Promise<Response<{missing: string[]}>>}
   */
  checkXRechnung(id) {
    return apiClient.get(`/invoices/${id}/xrechnung/check`)
  },

  /**
   * Returns a document's audit trail.
   * @param {number} id - Document ID
   * @returns {Promise<Response<DocumentHistoryEntry[]>>} - History entries, oldest first
   */
  getHistory(id) {
    return apiClient.get(`/invoices/${id}/history`)
  },

  /**
   * Downloads invoice as PDF
   * @param {number} id - Invoice ID
   * @returns {Promise<Response<Blob>>} - PDF blob
   */
  downloadInvoicePdf(id) {
    return apiClient.get(`/invoices/${id}/pdf`, { responseType: 'blob' })
  },

  /**
   * Archives an invoice
   * @param {number} id - Invoice ID
   * @returns {Promise<void>}
   */
  archiveInvoice(id) {
    return apiClient.post(`/invoices/${id}/archive`)
  },

  /**
   * Unarchives an invoice
   * @param {number} id - Invoice ID
   * @returns {Promise<void>}
   */
  unarchiveInvoice(id) {
    return apiClient.post(`/invoices/${id}/unarchive`)
  }
}

import invoiceAPI from '../services/invoiceService.js'
import { CONVERTED, DRAFT } from './documentStatus.js'

/**
 * Converts an accepted offer into an invoice draft, or an accepted cost
 * calculation into an offer, and marks the source as converted (linked to the
 * new document).
 *
 * <p>An invoice created this way stays a draft: it gets its number only when
 * it is issued ("Ausstellen"), after the user has checked it and filled in the
 * Leistungsdatum. Issuing is an explicit, irreversible step (GoBD).</p>
 *
 * @param {Object} source - The offer / cost calculation as returned by the API
 * @param {number} targetType - Document type id of the new document
 * @param {{invoiceDate: string, dueDate: string}} dates - From the ConvertModal
 * @returns {Promise<Object>} - The new document
 */
export async function convertDocument(source, targetType, dates) {
  const response = await invoiceAPI.createInvoice({
    customerId: source.customerId,
    invoiceDate: dates.invoiceDate,
    dueDate: dates.dueDate,
    workStartDate: source.workStartDate || null,
    workEndDate: source.workEndDate || null,
    notes: source.notes || '',
    documentStatusId: DRAFT,
    documentTypeId: targetType,
    items: source.items || []
  })
  await invoiceAPI.updateInvoiceStatus(source.id, CONVERTED, {
    convertedToInvoiceId: response.data.id
  })
  return response.data
}

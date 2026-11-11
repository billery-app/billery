import { apiClient } from './baseService.js'

/**
 * Data exports for third parties.
 */
export default {
  /**
   * GoBD data export (§ 147 Abs. 6 AO) of the issued invoices and credit notes
   * of a period: ZIP with CSV tables, GDPdU index.xml and, optionally, the PDF
   * and XRechnung of every document.
   * @param {string} from - First document date (YYYY-MM-DD)
   * @param {string} to - Last document date (YYYY-MM-DD)
   * @param {boolean} documents - Include PDFs and XRechnungs
   * @returns {Promise<Blob>} - The ZIP file
   */
  gobdExport(from, to, documents) {
    return apiClient.get('/export/gobd', { params: { from, to, documents }, responseType: 'blob' })
  }
}

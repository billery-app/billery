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

/** Signature of the ZIP "end of central directory" record (PK\x05\x06), little-endian. */
const END_OF_CENTRAL_DIRECTORY = 0x06054b50
/** The record is 22 bytes plus a comment of at most 65 535 bytes. */
const LONGEST_TAIL = 22 + 0xffff

/**
 * Whether a downloaded ZIP is complete. The server streams the GoBD export; when it fails on the
 * way, the archive ends without its central directory, whose end record is the last thing a ZIP
 * holds. A file without it is cut off and must not be saved as the export.
 * @param {Blob} blob - The downloaded file
 * @returns {Promise<boolean>} - true if the archive ends with its end record
 */
export async function isCompleteZip(blob) {
  const tail = blob.slice(Math.max(0, blob.size - LONGEST_TAIL))
  const bytes = new DataView(await tail.arrayBuffer())
  for (let i = bytes.byteLength - 22; i >= 0; i--) {
    if (bytes.getUint32(i, true) === END_OF_CENTRAL_DIRECTORY && i + 22 + bytes.getUint16(i + 20, true) === bytes.byteLength) {
      return true
    }
  }
  return false
}

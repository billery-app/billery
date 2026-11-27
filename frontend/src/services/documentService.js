import { apiClient } from './baseService.js'

/**
 * Template exchange with word processors (`/api/documents`). The sections format is described in
 * `utils/documentExchange.js`.
 */
export default {
  /**
   * Reads a DOCX or ODT into sections; nothing is stored.
   * @param {File} file
   * @returns {Promise<{data: {name: string, sections: import('@/api/types.js').DocumentSection[]}}>}
   *   (the endpoint answers a map, so the spec does not describe it)
   */
  importDocument(file) {
    const formData = new FormData()
    formData.append('file', file)
    return apiClient.post('/documents/import', formData)
  },

  /**
   * Writes sections as a DOCX or ODT.
   * @param {import('@/api/types.js').ExportRequest} request - name, format (`docx` / `odt`), sections
   * @returns {Promise<{data: Blob}>}
   */
  exportDocument(request) {
    return apiClient.post('/documents/export', request, { responseType: 'blob' })
  }
}

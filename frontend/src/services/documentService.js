import { apiClient } from './baseService.js'

/**
 * Template exchange with word processors (`/api/documents`). The sections format is described in
 * `utils/documentExchange.js`.
 */
export default {
  /**
   * Reads a DOCX or ODT into sections; nothing is stored.
   * @param {File} file
   * @returns {Promise<{data: {name: string, sections: {heading: ?string, paragraphs: string[]}[]}}>}
   */
  importDocument(file) {
    const formData = new FormData()
    formData.append('file', file)
    return apiClient.post('/documents/import', formData)
  },

  /**
   * Writes sections as a DOCX or ODT.
   * @param {{name: string, format: 'docx'|'odt', sections: Object[]}} request
   * @returns {Promise<{data: Blob}>}
   */
  exportDocument(request) {
    return apiClient.post('/documents/export', request, { responseType: 'blob' })
  }
}

import { apiClient } from './baseService.js'

export default {
  getTemplates(options = {}) {
    const params = {}
    if (options.includeDocumentSpecific) {
      params.includeDocumentSpecific = true
    }
    return apiClient.get('/templates', { params })
  },

  getTemplate(id) {
    return apiClient.get(`/templates/${id}`)
  },

  getTemplatesByDocumentType(documentTypeId) {
    return apiClient.get(`/templates/by-type/${documentTypeId}`)
  },

  getDefaultTemplate(documentTypeId) {
    return apiClient.get(`/templates/default/${documentTypeId}`)
  },

  createTemplate(template) {
    return apiClient.post('/templates', template)
  },

  updateTemplate(id, template) {
    return apiClient.put(`/templates/${id}`, template)
  },

  deleteTemplate(id) {
    return apiClient.delete(`/templates/${id}`)
  },

  /**
   * Renders an unsaved template draft to PDF on the server, exactly like a real
   * document (the editor's "exact preview"). Nothing is stored.
   *
   * @param {object} templateData the editor's save payload
   * @param {number|null} invoiceId document to render with; null uses a sample document
   * @returns {Promise} axios response with a PDF `Blob` in `data`
   */
  previewPdf(templateData, invoiceId = null) {
    return apiClient.post('/templates/preview', { templateData, invoiceId }, { responseType: 'blob' })
  },

  setTemplateAsDefault(id) {
    return apiClient.post(`/templates/${id}/set-default`)
  },

  /**
   * Creates a per-document override for the given document. The new row is a copy of
   * {@code sourceTemplateId} (or the document's type default when null) marked
   * {@code is_document_specific = 1}. When {@code templateData} is provided it overrides the
   * source's content (name, blocks, CSS, margins, …) so the SPA can submit a freshly-edited
   * template in one round trip — the clone is created and persisted with the user's edits in a
   * single server call. {@code templateData.id} is ignored (the new row always gets a fresh id).
   *
   * Returns the new template's id and name; the SPA writes the id into the document's
   * {@code templateId} field.
   *
   * @param {number} documentId the document the override is for
   * @param {number|null} sourceTemplateId the template to clone; null means "use the document's
   *   type default" (resolved server-side)
   * @param {object|null} templateData optional editor form to apply on top of the source
   */
  cloneForDocument(documentId, sourceTemplateId, templateData = null) {
    const body = { sourceTemplateId }
    if (templateData) {
      body.templateData = templateData
    }
    return apiClient.post(`/templates/from-document/${documentId}`, body)
  }
}

import { apiClient } from './baseService.js'

/**
 * Templates of the documents (`/api/templates`).
 * @typedef {import('@/api/types.js').InvoiceTemplateDto} InvoiceTemplateDto
 */
/**
 * @template T
 * @typedef {import('@/api/types.js').Response<T>} Response
 */
export default {
  /**
   * @param {{includeDocumentSpecific?: boolean}} [options] - also the per-document clones
   * @returns {Promise<Response<InvoiceTemplateDto[]>>}
   */
  getTemplates(options = {}) {
    /** @type {Record<string, boolean>} */
    const params = {}
    if (options.includeDocumentSpecific) {
      params.includeDocumentSpecific = true
    }
    return apiClient.get('/templates', { params })
  },

  /**
   * @param {number} id
   * @returns {Promise<Response<InvoiceTemplateDto>>}
   */
  getTemplate(id) {
    return apiClient.get(`/templates/${id}`)
  },

  /**
   * The templates of a document type, never another document's clone.
   * @param {number} documentTypeId
   * @returns {Promise<Response<InvoiceTemplateDto[]>>}
   */
  getTemplatesByDocumentType(documentTypeId) {
    return apiClient.get(`/templates/by-type/${documentTypeId}`)
  },

  /**
   * @param {number} documentTypeId
   * @returns {Promise<Response<InvoiceTemplateDto>>}
   */
  getDefaultTemplate(documentTypeId) {
    return apiClient.get(`/templates/default/${documentTypeId}`)
  },

  /**
   * @param {InvoiceTemplateDto} template
   * @returns {Promise<Response<InvoiceTemplateDto>>} the stored template
   */
  createTemplate(template) {
    return apiClient.post('/templates', template)
  },

  /**
   * @param {number} id
   * @param {InvoiceTemplateDto} template
   * @returns {Promise<Response<InvoiceTemplateDto>>} the stored template
   */
  updateTemplate(id, template) {
    return apiClient.put(`/templates/${id}`, template)
  },

  /** @param {number} id */
  deleteTemplate(id) {
    return apiClient.delete(`/templates/${id}`)
  },

  /**
   * Renders an unsaved template draft to PDF on the server, exactly like a real
   * document (the editor's "exact preview"). Nothing is stored.
   *
   * @param {InvoiceTemplateDto} templateData the editor's save payload
   * @param {number|null} invoiceId document to render with; null uses a sample document
   * @returns {Promise<Response<Blob>>} the PDF
   */
  previewPdf(templateData, invoiceId = null) {
    return apiClient.post('/templates/preview', { templateData, invoiceId }, { responseType: 'blob' })
  },

  /** @param {number} id */
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
   * @param {InvoiceTemplateDto|null} templateData optional editor form to apply on top of the source
   * @returns {Promise<Response<{id: number, name: string}>>} the new template's id and name
   */
  cloneForDocument(documentId, sourceTemplateId, templateData = null) {
    /** @type {import('@/api/types.js').CloneForDocumentRequest} */
    const body = { sourceTemplateId }
    if (templateData) {
      body.templateData = templateData
    }
    return apiClient.post(`/templates/from-document/${documentId}`, body)
  }
}

import { apiClient } from './baseService.js'

/**
 * The company's own mail accounts and mail templates (Settings → Mail-Einstellungen) and sending a
 * document to the customer. Passwords go to the server only; it never sends them back (an account
 * carries `hasPassword` instead).
 */
export default {
  /**
   * What this installation allows, and the templates.
   * @returns {Promise<{passwordsStorable: boolean, restricted: boolean, templates: Object<string, {subject: string, text: string}>}>}
   */
  async settings() {
    return (await apiClient.get('/mail/settings')).data
  },

  /**
   * @param {Object<string, {subject: string, text: string}>} templates - keyed by kind
   *   (`invoice`, `credit_note`, `offer`, `cost_calculation`)
   */
  async saveTemplates(templates) {
    return (await apiClient.put('/mail/templates', templates)).data
  },

  /** @returns {Promise<Array<{id: number, name: string, host: string, port: number, security: string, username: string, hasPassword: boolean, fromAddress: string, fromName: string, isDefault: boolean}>>} */
  async accounts() {
    return (await apiClient.get('/mail/accounts')).data
  },

  /**
   * @param {Object} account - name, host, port, security, username, password (null keeps the stored
   *   one, '' removes it), fromAddress, fromName, isDefault
   */
  async createAccount(account) {
    return (await apiClient.post('/mail/accounts', account)).data
  },

  /** @param {number} id @param {Object} account - as for createAccount */
  async updateAccount(id, account) {
    return (await apiClient.put(`/mail/accounts/${id}`, account)).data
  },

  /** @param {number} id */
  deleteAccount(id) {
    return apiClient.delete(`/mail/accounts/${id}`)
  },

  /** @param {number} id */
  makeDefault(id) {
    return apiClient.post(`/mail/accounts/${id}/default`)
  },

  /** Sends a test mail through the account to its own sender address. @param {number} id */
  testAccount(id) {
    return apiClient.post(`/mail/accounts/${id}/test`)
  },

  /**
   * What the send dialog starts with.
   * @param {number} documentId
   * @returns {Promise<{to: string, subject: string, text: string, accounts: Array<{id: number, name: string, fromAddress: string, fromName: string, isDefault: boolean}>, needsIssue: boolean, attachmentName: string, xrechnungPossible: boolean, xrechnungMissing: string[]}>}
   */
  async draft(documentId) {
    return (await apiClient.get(`/invoices/${documentId}/mail`)).data
  },

  /**
   * Sends the document (an invoice draft is issued first when `issue` is true).
   * @param {number} documentId
   * @param {{accountId: number, to: string[], cc: string[], subject: string, text: string, attachXRechnung: boolean, copyToSelf: boolean, issue: boolean}} request
   * @returns {Promise<{issued: boolean, documentNumber: string, documentStatusId: number}>}
   */
  async send(documentId, request) {
    return (await apiClient.post(`/invoices/${documentId}/mail`, request)).data
  }
}

/**
 * Splits what the user typed into a recipient field into addresses: separated by comma, semicolon
 * or white space; empty parts are dropped.
 * @param {string} value
 * @returns {string[]}
 */
export function splitAddresses(value) {
  return (value || '')
    .split(/[\s,;]+/)
    .map(part => part.trim())
    .filter(Boolean)
}

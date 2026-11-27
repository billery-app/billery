import { apiClient } from './baseService.js'

/**
 * The company's own mail accounts and mail templates (Settings → Mail-Einstellungen) and sending a
 * document to the customer. Passwords go to the server only; it never sends them back (an account
 * carries `hasPassword` instead).
 * @typedef {import('@/api/types.js').AccountView} AccountView
 * @typedef {import('@/api/types.js').AccountInput} AccountInput
 * @typedef {import('@/api/types.js').MailTemplate} MailTemplate
 * @typedef {import('@/api/types.js').MailDraft} MailDraft
 * @typedef {import('@/api/types.js').MailRequest} MailRequest
 * @typedef {import('@/api/types.js').MailResult} MailResult
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
   * @param {MailTemplate} templates - keyed by kind
   *   (`invoice`, `credit_note`, `offer`, `cost_calculation`)
   * @returns {Promise<MailTemplate>} the stored templates
   */
  async saveTemplates(templates) {
    return (await apiClient.put('/mail/templates', templates)).data
  },

  /** @returns {Promise<AccountView[]>} the company's accounts (never a password) */
  async accounts() {
    return (await apiClient.get('/mail/accounts')).data
  },

  /**
   * @param {AccountInput} account - name, host, port, security, username, password (null keeps the
   *   stored one, '' removes it), fromAddress, fromName, isDefault
   * @returns {Promise<AccountView>} the stored account
   */
  async createAccount(account) {
    return (await apiClient.post('/mail/accounts', account)).data
  },

  /**
   * @param {number} id
   * @param {AccountInput} account - as for createAccount
   * @returns {Promise<AccountView>} the stored account
   */
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
   * @returns {Promise<MailDraft>}
   */
  async draft(documentId) {
    return (await apiClient.get(`/invoices/${documentId}/mail`)).data
  },

  /**
   * Sends the document (an invoice draft is issued first when `issue` is true).
   * @param {number} documentId
   * @param {MailRequest} request
   * @returns {Promise<MailResult>}
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

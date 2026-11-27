import { apiClient } from './baseService.js'

/**
 * Customers (`/api/customers`).
 * @typedef {import('@/api/types.js').CustomerDto} CustomerDto
 * @typedef {import('@/api/types.js').Customer} Customer
 * @typedef {import('@/api/types.js').CustomerPageDto} CustomerPageDto
 */
/**
 * @template T
 * @typedef {import('@/api/types.js').Response<T>} Response
 */
export default {
  /**
   * One page of customers, searched on the server.
   * @param {Object} query
   * @param {number} query.page - 0-based page
   * @param {number} query.size - Customers per page (max 200)
   * @param {string} [query.q] - Search text (name, address, phone, e-mail)
   * @param {'id'|'name'} [query.sort] - 'id' for the list, 'name' for the picker
   * @returns {Promise<Response<CustomerPageDto>>}
   */
  getCustomerPage({ page, size, q, sort = 'id' }) {
    /** @type {Record<string, string|number>} */
    const params = { page, size, sort }
    if (q) params.q = q
    return apiClient.get('/customers/page', { params })
  },

  /**
   * @param {number} id
   * @returns {Promise<Response<Customer>>}
   */
  getCustomer(id) {
    return apiClient.get(`/customers/${id}`)
  },

  /**
   * @param {CustomerDto} customer
   * @returns {Promise<Response<Customer>>} the stored customer
   */
  createCustomer(customer) {
    return apiClient.post('/customers', customer)
  },

  /**
   * @param {number} id
   * @param {CustomerDto} customer
   * @returns {Promise<Response<Customer>>} the stored customer
   */
  updateCustomer(id, customer) {
    return apiClient.put(`/customers/${id}`, customer)
  },

  /**
   * Refused with 409 while the customer has documents.
   * @param {number} id
   */
  deleteCustomer(id) {
    return apiClient.delete(`/customers/${id}`)
  }
}

import { apiClient } from './baseService.js'

export default {
  /**
   * One page of customers, searched on the server.
   * @param {Object} query
   * @param {number} query.page - 0-based page
   * @param {number} query.size - Customers per page (max 200)
   * @param {string} [query.q] - Search text (name, address, phone, e-mail)
   * @param {'id'|'name'} [query.sort] - 'id' for the list, 'name' for the picker
   * @returns {Promise<{data: {items: Object[], page: number, size: number, total: number}}>}
   */
  getCustomerPage({ page, size, q, sort = 'id' }) {
    const params = { page, size, sort }
    if (q) params.q = q
    return apiClient.get('/customers/page', { params })
  },

  getCustomer(id) {
    return apiClient.get(`/customers/${id}`)
  },

  createCustomer(customer) {
    return apiClient.post('/customers', customer)
  },

  updateCustomer(id, customer) {
    return apiClient.put(`/customers/${id}`, customer)
  },

  deleteCustomer(id) {
    return apiClient.delete(`/customers/${id}`)
  }
}

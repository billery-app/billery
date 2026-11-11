import { apiClient } from './baseService.js'

/**
 * Owner (company data) API. Uses the shared `apiClient`, so the backend URL
 * follows `VITE_API_BASE_URL` or the page origin like every other service.
 */
export default {
  /** @returns {Promise} all owners */
  getOwners() {
    return apiClient.get('/owners')
  },

  /** @returns {Promise} the owner used on documents */
  getPrimaryOwner() {
    return apiClient.get('/owners/primary')
  },

  /**
   * @param {Object} owner - Owner data
   * @returns {Promise} the created owner
   */
  createOwner(owner) {
    return apiClient.post('/owners', owner)
  },

  /**
   * @param {number} id - Owner ID
   * @param {Object} owner - Updated owner data
   * @returns {Promise} the updated owner
   */
  updateOwner(id, owner) {
    return apiClient.put(`/owners/${id}`, owner)
  },

  /**
   * @param {number} id - Owner ID
   * @returns {Promise<void>}
   */
  deleteOwner(id) {
    return apiClient.delete(`/owners/${id}`)
  }
}

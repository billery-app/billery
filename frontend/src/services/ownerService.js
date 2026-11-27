import { apiClient } from './baseService.js'

/**
 * Owner (company data) API. Uses the shared `apiClient`, so the backend URL
 * follows `VITE_API_BASE_URL` or the page origin like every other service.
 * @typedef {import('@/api/types.js').Owner} Owner
 * @typedef {import('@/api/types.js').OwnerDto} OwnerDto
 */
/**
 * @template T
 * @typedef {import('@/api/types.js').Response<T>} Response
 */
export default {
  /** @returns {Promise<Response<Owner[]>>} all owners */
  getOwners() {
    return apiClient.get('/owners')
  },

  /** @returns {Promise<Response<Owner>>} the owner used on documents (404 while there is none) */
  getPrimaryOwner() {
    return apiClient.get('/owners/primary')
  },

  /**
   * @param {OwnerDto} owner - Owner data
   * @returns {Promise<Response<Owner>>} the created owner
   */
  createOwner(owner) {
    return apiClient.post('/owners', owner)
  },

  /**
   * @param {number} id - Owner ID
   * @param {OwnerDto} owner - Updated owner data
   * @returns {Promise<Response<Owner>>} the updated owner
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

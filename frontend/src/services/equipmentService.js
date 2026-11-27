import { apiClient } from './baseService.js'

/**
 * Equipment and its amortisation (`/api/equipment`).
 * @typedef {import('@/api/types.js').EquipmentDto} EquipmentDto
 * @typedef {import('@/api/types.js').EquipmentSummaryDto} EquipmentSummaryDto
 * @typedef {import('@/api/types.js').EquipmentUsageDto} EquipmentUsageDto
 */
/**
 * @template T
 * @typedef {import('@/api/types.js').Response<T>} Response
 */
export default {
  /** @returns {Promise<Response<EquipmentDto[]>>} */
  getAll() {
    return apiClient.get('/equipment')
  },

  /**
   * @param {EquipmentDto} equipment
   * @returns {Promise<Response<EquipmentDto>>} the stored item
   */
  create(equipment) {
    return apiClient.post('/equipment', equipment)
  },

  /**
   * @param {number} id
   * @param {EquipmentDto} equipment
   * @returns {Promise<Response<EquipmentDto>>} the stored item
   */
  update(id, equipment) {
    return apiClient.put(`/equipment/${id}`, equipment)
  },

  /** @param {number} id */
  delete(id) {
    return apiClient.delete(`/equipment/${id}`)
  },

  /** @returns {Promise<Response<EquipmentSummaryDto[]>>} amortisation of every item */
  getSummary() {
    return apiClient.get('/equipment/summary')
  },

  /**
   * @param {number} id
   * @returns {Promise<Response<EquipmentUsageDto[]>>} the documents the item was used in
   */
  getUsage(id) {
    return apiClient.get(`/equipment/${id}/usage`)
  }
}

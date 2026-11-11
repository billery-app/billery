import { apiClient } from './baseService.js'

export default {
  getAll() {
    return apiClient.get('/equipment')
  },

  create(equipment) {
    return apiClient.post('/equipment', equipment)
  },

  update(id, equipment) {
    return apiClient.put(`/equipment/${id}`, equipment)
  },

  delete(id) {
    return apiClient.delete(`/equipment/${id}`)
  },

  getSummary() {
    return apiClient.get('/equipment/summary')
  },

  getUsage(id) {
    return apiClient.get(`/equipment/${id}/usage`)
  }
}

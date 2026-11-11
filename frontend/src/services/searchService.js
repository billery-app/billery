import { apiClient } from './baseService.js'

export default {
  search(query) {
    return apiClient.get('/search', { params: { q: query } })
  }
}

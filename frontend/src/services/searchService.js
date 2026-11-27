import { apiClient } from './baseService.js'

/**
 * The quick search over customers, catalog and documents (`/api/search`).
 * @template T
 * @typedef {import('@/api/types.js').Response<T>} Response
 */
export default {
  /**
   * @param {string} query
   * @returns {Promise<Response<import('@/api/types.js').SearchResult>>}
   */
  search(query) {
    return apiClient.get('/search', { params: { q: query } })
  }
}

import { apiClient } from './baseService.js'

/**
 * The catalog: articles and services (`/api/articles`, `/api/services`).
 * @typedef {import('@/api/types.js').ArticleDto} ArticleDto
 * @typedef {import('@/api/types.js').Article} Article
 * @typedef {import('@/api/types.js').ServiceDto} ServiceDto
 * @typedef {import('@/api/types.js').Service} Service
 */
/**
 * @template T
 * @typedef {import('@/api/types.js').Response<T>} Response
 */
export default {
  /** @returns {Promise<Response<ArticleDto[]>>} every article */
  getArticles() {
    return apiClient.get('/articles')
  },
  /**
   * @param {ArticleDto} article
   * @returns {Promise<Response<Article>>} the stored article
   */
  createArticle(article) {
    return apiClient.post('/articles', article)
  },
  /**
   * @param {number} id
   * @param {ArticleDto} article
   * @returns {Promise<Response<Article>>} the stored article
   */
  updateArticle(id, article) {
    return apiClient.put(`/articles/${id}`, article)
  },
  /** @param {number} id */
  deleteArticle(id) {
    return apiClient.delete(`/articles/${id}`)
  },

  /** @returns {Promise<Response<ServiceDto[]>>} every service */
  getServices() {
    return apiClient.get('/services')
  },
  /**
   * @param {ServiceDto} service
   * @returns {Promise<Response<Service>>} the stored service
   */
  createService(service) {
    return apiClient.post('/services', service)
  },
  /**
   * @param {number} id
   * @param {ServiceDto} service
   * @returns {Promise<Response<Service>>} the stored service
   */
  updateService(id, service) {
    return apiClient.put(`/services/${id}`, service)
  },
  /** @param {number} id */
  deleteService(id) {
    return apiClient.delete(`/services/${id}`)
  }
}

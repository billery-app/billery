import { apiClient } from './baseService.js'

export default {
  // Articles
  getArticles() {
    return apiClient.get('/articles')
  },
  createArticle(article) {
    return apiClient.post('/articles', article)
  },
  updateArticle(id, article) {
    return apiClient.put(`/articles/${id}`, article)
  },
  deleteArticle(id) {
    return apiClient.delete(`/articles/${id}`)
  },

  // Services
  getServices() {
    return apiClient.get('/services')
  },
  createService(service) {
    return apiClient.post('/services', service)
  },
  updateService(id, service) {
    return apiClient.put(`/services/${id}`, service)
  },
  deleteService(id) {
    return apiClient.delete(`/services/${id}`)
  }
}

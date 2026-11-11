import axios from 'axios'
import { apiBaseUrl } from './apiBase.js'
import { authHeaders, handleForbidden, renewAfterUnauthorized } from './authService.js'
import { isSaas } from './session.js'

/**
 * Shared axios instance for all API services
 */
export const apiClient = axios.create({
  baseURL: apiBaseUrl()
})

// In SaaS mode every call carries the bearer token and the active tenant (nothing in self-host).
apiClient.interceptors.request.use(async config => {
  const headers = await authHeaders()
  Object.entries(headers).forEach(([name, value]) => config.headers.set(name, value))
  return config
})

/**
 * Extracts the user-facing `message` the backend sends with error responses
 * (`{"message": "..."}`, see ApiExceptionHandler). For `responseType: 'blob'`
 * requests (PDF downloads) the error body arrives as a Blob and is parsed here.
 * @param {*} data - `error.response.data`
 * @returns {Promise<string|undefined>} - The message, if any
 */
export async function extractErrorMessage(data) {
  if (typeof Blob !== 'undefined' && data instanceof Blob) {
    if (!data.type.includes('json')) return undefined
    try {
      return JSON.parse(await data.text()).message
    } catch {
      return undefined
    }
  }
  return data && typeof data === 'object' ? data.message : undefined
}

apiClient.interceptors.response.use(
  response => response,
  async error => {
    if (isSaas() && error.config) {
      if (error.response?.status === 401 && !error.config._retried) {
        error.config._retried = true
        if (await renewAfterUnauthorized()) return apiClient(error.config)
      }
      if (error.response?.status === 403) {
        await handleForbidden()
      }
    }
    const message = await extractErrorMessage(error.response?.data)
    if (message) {
      error.message = message
    }
    return Promise.reject(error)
  }
)

/**
 * Base service class with common functionality
 */
export class BaseService {
  constructor() {
    this.client = apiClient
  }

  /**
   * Generic GET request
   * @param {string} url - API endpoint
   * @param {Object} config - Axios config
   * @returns {Promise} - Response data
   */
  get(url, config = {}) {
    return this.client.get(url, config)
  }

  /**
   * Generic POST request
   * @param {string} url - API endpoint
   * @param {Object} data - Request data
   * @param {Object} config - Axios config
   * @returns {Promise} - Response data
   */
  post(url, data = {}, config = {}) {
    return this.client.post(url, data, config)
  }

  /**
   * Generic PUT request
   * @param {string} url - API endpoint
   * @param {Object} data - Request data
   * @param {Object} config - Axios config
   * @returns {Promise} - Response data
   */
  put(url, data = {}, config = {}) {
    return this.client.put(url, data, config)
  }

  /**
   * Generic DELETE request
   * @param {string} url - API endpoint
   * @param {Object} config - Axios config
   * @returns {Promise} - Response data
   */
  delete(url, config = {}) {
    return this.client.delete(url, config)
  }

  /**
   * Handles API errors consistently
   * @param {Error} error - The error object
   * @param {string} context - Context for error message
   * @returns {Error} - Formatted error
   */
  handleError(error, context = 'Operation') {
    const message = error.response?.data?.message || error.message || context + ' fehlgeschlagen'
    console.error(context, 'error:', error)
    return new Error(message)
  }
}

export default apiClient

import { apiClient } from './baseService.js'

/**
 * Whether the introductory tour was seen, for self-hosted and desktop installations
 * (`/api/tour`, stored in `user_settings`). The SaaS build has no such endpoint: there the tour
 * state lives in the browser (see `utils/tour.js`).
 */
export default {
  /**
   * @returns {Promise<{seen: boolean, everySession: boolean, hasDocuments: boolean}>}
   *   `seen`: the tour was finished or skipped once; `everySession`: show it in every new browser
   *   session (Einstellungen → Hilfe); `hasDocuments`: the installation already holds documents
   */
  async state() {
    return (await apiClient.get('/tour')).data
  },

  /**
   * Stores the given flags and returns the whole state.
   * @param {{seen?: boolean, everySession?: boolean}} changes
   * @returns {Promise<{seen: boolean, everySession: boolean, hasDocuments: boolean}>}
   */
  async save(changes) {
    return (await apiClient.put('/tour', changes)).data
  }
}

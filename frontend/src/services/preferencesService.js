import { apiClient } from './baseService.js'
import { applyLanguage } from '../i18n/index.js'

/**
 * The user's own settings (`/api/preferences`, #15): the interface language. Self-host and desktop
 * keep them per installation, SaaS per user (no `X-Tenant-Id` needed).
 */
const preferencesApi = {
  /** @returns {Promise<{language: string|null}>} */
  async get() {
    return (await apiClient.get('/preferences')).data
  },

  /**
   * @param {string} language - 'de' or 'en'
   * @returns {Promise<{language: string|null}>}
   */
  async setLanguage(language) {
    return (await apiClient.put('/preferences', { language })).data
  }
}

export default preferencesApi

/**
 * Applies the stored language before the app is shown. A failed request leaves German: the app
 * must start even when the preferences cannot be read.
 * @param {{get: () => Promise<{language: string|null}>}} [api]
 * @returns {Promise<string>} the language now active
 */
export async function loadLanguage(api = preferencesApi) {
  try {
    return applyLanguage((await api.get()).language)
  } catch {
    return applyLanguage(null)
  }
}

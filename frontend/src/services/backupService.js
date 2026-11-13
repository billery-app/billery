import { apiClient } from './baseService.js'

/** Backups of the desktop app (`/api/desktop/backups`, Spring profile `desktop` only). */
export default {
  /** @returns {Promise<{data: Object}>} settings and backups (newest first) */
  getStatus() {
    return apiClient.get('/desktop/backups')
  },

  /**
   * @param {{directory: string, keep: number, onExit: boolean, password: ?string}} settings -
   *   `password`: null keeps the current one, '' switches encryption off
   * @returns {Promise<{data: Object}>} the new status
   */
  updateSettings(settings) {
    return apiClient.put('/desktop/backups/settings', settings)
  },

  /** @returns {Promise<{data: Object}>} the new backup */
  backupNow() {
    return apiClient.post('/desktop/backups')
  },

  /**
   * Chooses a backup to restore at the next start.
   * @param {string} name
   * @param {?string} password - for an encrypted backup
   */
  restore(name, password) {
    return apiClient.post('/desktop/backups/restore', { name, password: password || null })
  },

  /** Withdraws a chosen restore. */
  cancelRestore() {
    return apiClient.delete('/desktop/backups/restore')
  }
}

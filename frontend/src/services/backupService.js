import { apiClient } from './baseService.js'
import { desktopTokenOptions } from './desktopToken.js'

/** Backups of the desktop app (`/api/desktop/backups`, Spring profile `desktop` only). */
export default {
  /** @returns {Promise<{data: Object}>} settings and backups (newest first) */
  getStatus() {
    return apiClient.get('/desktop/backups', desktopTokenOptions())
  },

  /**
   * @param {{directory: string, keep: number, onExit: boolean, password: ?string}} settings -
   *   `password`: null keeps the current one, '' switches encryption off
   * @returns {Promise<{data: Object}>} the new status
   */
  updateSettings(settings) {
    return apiClient.put('/desktop/backups/settings', settings, desktopTokenOptions())
  },

  /** @returns {Promise<{data: Object}>} the new backup */
  backupNow() {
    return apiClient.post('/desktop/backups', null, desktopTokenOptions())
  },

  /**
   * Chooses a backup to restore at the next start.
   * @param {string} name
   * @param {?string} password - for an encrypted backup
   * @param {boolean} [confirmLoss] - the user confirmed what the restore takes out of the program
   */
  restore(name, password, confirmLoss = false) {
    return apiClient.post('/desktop/backups/restore', { name, password: password || null, confirmLoss }, desktopTokenOptions())
  },

  /** Withdraws a chosen restore. */
  cancelRestore() {
    return apiClient.delete('/desktop/backups/restore', desktopTokenOptions())
  }
}

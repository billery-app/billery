<template>
  <section class="settings-section" data-testid="backup-settings">

    <div class="card backup-card">
      <p v-if="error" class="backup-notice backup-notice--error" role="alert">{{ error }}</p>
      <p v-if="notice" class="backup-notice backup-notice--ok" role="status">{{ notice }}</p>

      <template v-if="status">
        <p v-if="status.restorePending" class="backup-notice backup-notice--warning" role="status">
          {{ $t('backup.pending') }}
          <button type="button" class="btn btn-small btn-secondary" @click="cancelRestore">{{ $t('backup.cancelRestore') }}</button>
        </p>

        <div class="backup-last" :class="{ 'backup-last--old': isOld }" data-testid="backup-last">
          <span v-if="latest">
            {{ $t('backup.last') }} <strong>{{ formatDate(latest.createdAt) }}</strong> ({{ reasonLabel(latest.reason) }})
          </span>
          <span v-else>{{ $t('backup.none') }}</span>
          <span v-if="isOld" class="backup-warning">
            {{ latest ? $t('backup.old') : $t('backup.createNow') }}
          </span>
          <button type="button" class="btn btn-primary btn-small" :disabled="busy || status.restorePending" @click="backupNow">
            {{ busy ? $t('backup.saving') : $t('backup.now') }}
          </button>
        </div>

        <form class="backup-form" @submit.prevent="saveSettings">
          <div class="form-group">
            <label for="backup-directory">{{ $t('backup.directory') }}</label>
            <input
              id="backup-directory"
              v-model="form.directory"
              class="form-control"
              name="backupDirectory"
              autocomplete="off"
              :placeholder="status.defaultDirectory"
            />
            <small>
              {{ $t('backup.directoryHint') }}
            </small>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label for="backup-keep">{{ $t('backup.keep') }}</label>
              <input id="backup-keep" v-model.number="form.keep" class="form-control" type="number" min="1" max="100" inputmode="numeric" />
            </div>
            <label class="checkbox-label">
              <input v-model="form.onExit" type="checkbox" />
              {{ $t('backup.onExit') }}
            </label>
          </div>
          <div class="form-group">
            <label class="checkbox-label">
              <input v-model="form.encrypt" type="checkbox" data-testid="backup-encrypt" />
              {{ $t('backup.encrypt') }}
            </label>
            <template v-if="form.encrypt">
              <label for="backup-password">{{ status.encrypted ? $t('backup.newPassword') : $t('backup.password') }}</label>
              <input id="backup-password" v-model="form.password" class="form-control" type="password" autocomplete="new-password" />
              <small class="backup-warning">
                {{ $t('backup.passwordHint') }}
              </small>
            </template>
          </div>
          <button type="submit" class="btn btn-primary" :disabled="busy || status.restorePending">{{ $t('backup.saveSettings') }}</button>
        </form>

        <h3 class="backup-heading">{{ $t('backup.list') }}</h3>
        <p class="backup-hint">
          {{ $t('backup.listHint') }}
        </p>
        <div v-if="loss" class="backup-notice backup-notice--warning backup-loss" role="alert" data-testid="backup-loss">
          <p>{{ loss.message }}</p>
          <div class="backup-loss-actions">
            <button type="button" class="btn btn-small btn-danger" :disabled="busy" @click="restore(loss.backup, true)">{{ $t('backup.restoreAnyway') }}</button>
            <button type="button" class="btn btn-small btn-secondary" @click="loss = null">{{ $t('common.cancel') }}</button>
          </div>
        </div>
        <div v-if="status.backups.length" class="table-scroll">
          <table class="data-table" :aria-label="$t('backup.list')">
            <thead>
              <tr><th>{{ $t('documents.columns.date') }}</th><th>{{ $t('backup.reason') }}</th><th>{{ $t('backup.size') }}</th><th><span class="visually-hidden">{{ $t('team.actions') }}</span></th></tr>
            </thead>
            <tbody>
              <tr v-for="backup in status.backups" :key="backup.name" :data-testid="`backup-${backup.name}`">
                <td>{{ formatDate(backup.createdAt) }}<span v-if="backup.encrypted" class="badge badge-neutral">{{ $t('backup.encrypted') }}</span></td>
                <td>{{ reasonLabel(backup.reason) }}</td>
                <td>{{ formatSize(backup.size) }}</td>
                <td class="actions">
                  <template v-if="restoring === backup.name">
                    <input
                      v-if="backup.encrypted"
                      v-model="restorePassword"
                      class="form-control"
                      type="password"
                      autocomplete="off"
                      :aria-label="$t('backup.passwordOf', { date: formatDate(backup.createdAt) })"
                      :placeholder="$t('backup.passwordPlaceholder')"
                    />
                    <button type="button" class="btn btn-small btn-danger" :disabled="busy" @click="restore(backup)">{{ $t('backup.restoreAtStart') }}</button>
                    <button type="button" class="btn btn-small btn-secondary" @click="restoring = null">{{ $t('common.cancel') }}</button>
                  </template>
                  <button v-else type="button" class="btn btn-small btn-outline" @click="startRestore(backup)">{{ $t('backup.restore') }}</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="backup-hint">{{ $t('backup.empty') }}</p>
      </template>
    </div>
  </section>
</template>

<script>
import backupAPI from '../services/backupService.js'
import { formatFileSize } from '../utils/fileValidation.js'

/** Days after which the last backup is shown as too old. */
const MAX_AGE_DAYS = 7

/** Reasons in the backup file names (`billery-<time>-<reason>.zip`); their names are in the message files. */
const REASONS = ['beenden', 'manuell', 'vor-wiederherstellung']

/**
 * The backup section of the desktop app's settings: last backup with a warning when it is old,
 * "Jetzt sichern", folder / number / backup on exit / encryption, and restoring a listed backup at
 * the next start (backend: DesktopBackupService).
 */
export default {
  name: 'BackupSettings',
  data() {
    return {
      status: null,
      form: { directory: '', keep: 10, onExit: true, encrypt: false, password: '' },
      busy: false,
      error: '',
      notice: '',
      restoring: null,
      restorePassword: '',
      // A restore the server refused because documents or uploaded files came after the backup: { backup, message }
      loss: null
    }
  },
  computed: {
    latest() {
      return this.status && this.status.backups.length ? this.status.backups[0] : null
    },
    isOld() {
      if (!this.latest) return true
      return Date.now() - new Date(this.latest.createdAt).getTime() > MAX_AGE_DAYS * 86_400_000
    }
  },
  mounted() {
    this.load()
  },
  methods: {
    async load() {
      try {
        const { data } = await backupAPI.getStatus()
        this.apply(data)
      } catch {
        this.error = this.$t('backup.readFailed')
      }
    },
    apply(status) {
      this.status = status
      this.form = {
        directory: status.directory === status.defaultDirectory ? '' : status.directory,
        keep: status.keep,
        onExit: status.onExit,
        encrypt: status.encrypted,
        password: ''
      }
    },
    async run(action, success) {
      this.busy = true
      this.error = ''
      this.notice = ''
      try {
        await action()
        this.notice = success
        await this.load()
      } catch (error) {
        this.error = error.response?.data?.message || this.$t('common.failed')
      } finally {
        this.busy = false
      }
    },
    backupNow() {
      return this.run(() => backupAPI.backupNow(), this.$t('backup.created'))
    },
    saveSettings() {
      if (this.form.encrypt && !this.status.encrypted && !this.form.password) {
        this.error = this.$t('backup.passwordMissing')
        return Promise.resolve()
      }
      // null keeps the stored password, '' removes it.
      const password = !this.form.encrypt ? '' : (this.form.password || null)
      return this.run(
        () => backupAPI.updateSettings({ directory: this.form.directory, keep: this.form.keep, onExit: this.form.onExit, password }),
        this.$t('backup.saved')
      )
    },
    startRestore(backup) {
      this.restoring = backup.name
      this.restorePassword = ''
    },
    /**
     * Chooses a backup for the next start. If documents were issued, payments recorded or changed
     * after the backup, the server answers 409 with what would be lost; the user confirms it
     * explicitly ("Trotzdem wiederherstellen").
     */
    async restore(backup, confirmLoss = false) {
      this.busy = true
      this.error = ''
      this.notice = ''
      try {
        await backupAPI.restore(backup.name, this.restorePassword, confirmLoss)
        this.restoring = null
        this.loss = null
        this.notice = this.$t('backup.restoreStaged')
        await this.load()
      } catch (error) {
        if (error.response?.status === 409) {
          this.loss = { backup, message: error.response.data.message }
        } else {
          this.error = error.response?.data?.message || this.$t('common.failed')
        }
      } finally {
        this.busy = false
      }
    },
    cancelRestore() {
      return this.run(() => backupAPI.cancelRestore(), this.$t('backup.restoreCancelled'))
    },
    formatDate(value) {
      return value ? new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' }) : ''
    },
    formatSize(bytes) {
      return formatFileSize(bytes)
    },
    reasonLabel(reason) {
      return REASONS.includes(reason) ? this.$t(`backup.reasons.${reason}`) : reason
    }
  }
}
</script>

<style scoped>
.backup-card { display: flex; flex-direction: column; gap: var(--space-4); }
.backup-last { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2) var(--space-3); }
.backup-last--old strong { color: var(--color-danger); }
.backup-warning { color: var(--color-danger); font-size: var(--font-size-sm); }
.backup-form { display: flex; flex-direction: column; gap: var(--space-3); }
.backup-form .form-row { display: flex; flex-wrap: wrap; gap: var(--space-4); align-items: flex-end; }
.backup-form small { display: block; margin-top: var(--space-1); color: var(--color-text-muted); }
.checkbox-label { display: flex; align-items: center; gap: var(--space-2); }
.backup-heading { margin: 0; font-size: var(--font-size-md); color: var(--color-text-strong); }
.backup-hint { margin: 0; color: var(--color-text-muted); font-size: var(--font-size-sm); }
.backup-notice { margin: 0; padding: var(--space-2) var(--space-3); border-radius: var(--radius-md); }
.backup-notice--error { background: var(--color-danger-soft); color: var(--color-danger); }
.backup-notice--ok { background: var(--color-success-soft); color: var(--color-success-hover); }
.backup-notice--warning { background: var(--color-warning-soft); color: var(--color-text-strong); }
.backup-loss p { margin: 0 0 var(--space-2); }
.backup-loss-actions { display: flex; flex-wrap: wrap; gap: var(--space-2); }
.actions { display: flex; flex-wrap: wrap; gap: var(--space-2); justify-content: flex-end; }
.badge { margin-left: var(--space-2); }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
</style>

<template>
  <section class="settings-section" data-testid="backup-settings">
    <h2 class="section-title">Datensicherung</h2>

    <div class="card backup-card">
      <p v-if="error" class="backup-notice backup-notice--error" role="alert">{{ error }}</p>
      <p v-if="notice" class="backup-notice backup-notice--ok" role="status">{{ notice }}</p>

      <template v-if="status">
        <p v-if="status.restorePending" class="backup-notice backup-notice--warning" role="status">
          Beim nächsten Start von Billery wird eine Sicherung wiederhergestellt. Der jetzige Stand
          wird vorher gesichert.
          <button type="button" class="btn btn-small btn-secondary" @click="cancelRestore">Wiederherstellung abbrechen</button>
        </p>

        <div class="backup-last" :class="{ 'backup-last--old': isOld }" data-testid="backup-last">
          <span v-if="latest">
            Letzte Sicherung: <strong>{{ formatDate(latest.createdAt) }}</strong> ({{ reasonLabel(latest.reason) }})
          </span>
          <span v-else>Noch keine Sicherung vorhanden.</span>
          <span v-if="isOld" class="backup-warning">
            {{ latest ? 'Die letzte Sicherung ist älter als 7 Tage.' : 'Bitte jetzt eine Sicherung anlegen.' }}
          </span>
          <button type="button" class="btn btn-primary btn-small" :disabled="busy" @click="backupNow">
            {{ busy ? 'Sichere …' : 'Jetzt sichern' }}
          </button>
        </div>

        <form class="backup-form" @submit.prevent="saveSettings">
          <div class="form-group">
            <label for="backup-directory">Sicherungsordner</label>
            <input
              id="backup-directory"
              v-model="form.directory"
              class="form-control"
              name="backupDirectory"
              autocomplete="off"
              :placeholder="status.defaultDirectory"
            />
            <small>
              Leer lassen für den Ordner der App. Besser ein externes Laufwerk oder ein
              Cloud-Ordner, damit die Sicherung den Verlust des Rechners übersteht.
            </small>
          </div>
          <div class="form-row">
            <div class="form-group">
              <label for="backup-keep">Aufbewahren (Anzahl)</label>
              <input id="backup-keep" v-model.number="form.keep" class="form-control" type="number" min="1" max="100" inputmode="numeric" />
            </div>
            <label class="checkbox-label">
              <input v-model="form.onExit" type="checkbox" />
              Beim Beenden sichern
            </label>
          </div>
          <div class="form-group">
            <label class="checkbox-label">
              <input v-model="form.encrypt" type="checkbox" data-testid="backup-encrypt" />
              Sicherungen mit Passwort verschlüsseln
            </label>
            <template v-if="form.encrypt">
              <label for="backup-password">{{ status.encrypted ? 'Neues Passwort (leer lassen: unverändert)' : 'Passwort (mindestens 8 Zeichen)' }}</label>
              <input id="backup-password" v-model="form.password" class="form-control" type="password" autocomplete="new-password" />
              <small class="backup-warning">
                Ohne dieses Passwort lässt sich eine verschlüsselte Sicherung nicht wiederherstellen.
                Bitte getrennt aufbewahren.
              </small>
            </template>
          </div>
          <button type="submit" class="btn btn-secondary" :disabled="busy">Einstellungen speichern</button>
        </form>

        <h3 class="backup-heading">Vorhandene Sicherungen</h3>
        <p class="backup-hint">
          Eine Sicherung von einem anderen Rechner: die Datei in den Sicherungsordner legen, dann
          erscheint sie hier.
        </p>
        <div v-if="status.backups.length" class="table-scroll">
          <table class="data-table" aria-label="Sicherungen">
            <thead>
              <tr><th>Datum</th><th>Anlass</th><th>Größe</th><th><span class="visually-hidden">Aktionen</span></th></tr>
            </thead>
            <tbody>
              <tr v-for="backup in status.backups" :key="backup.name" :data-testid="`backup-${backup.name}`">
                <td>{{ formatDate(backup.createdAt) }}<span v-if="backup.encrypted" class="badge badge-neutral">verschlüsselt</span></td>
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
                      :aria-label="`Passwort der Sicherung vom ${formatDate(backup.createdAt)}`"
                      placeholder="Passwort"
                    />
                    <button type="button" class="btn btn-small btn-danger" :disabled="busy" @click="restore(backup)">Beim nächsten Start wiederherstellen</button>
                    <button type="button" class="btn btn-small btn-secondary" @click="restoring = null">Abbrechen</button>
                  </template>
                  <button v-else type="button" class="btn btn-small btn-outline" @click="startRestore(backup)">Wiederherstellen …</button>
                </td>
              </tr>
            </tbody>
          </table>
        </div>
        <p v-else class="backup-hint">Im Sicherungsordner liegt noch keine Sicherung.</p>
      </template>
    </div>
  </section>
</template>

<script>
import backupAPI from '../services/backupService.js'
import { formatFileSize } from '../utils/fileValidation.js'

/** Days after which the last backup is shown as too old. */
const MAX_AGE_DAYS = 7

const REASONS = { beenden: 'beim Beenden', manuell: 'manuell', 'vor-wiederherstellung': 'vor einer Wiederherstellung' }

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
      restorePassword: ''
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
      } catch (error) {
        this.error = 'Die Sicherungen konnten nicht gelesen werden.'
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
        this.error = error.response?.data?.message || 'Das hat nicht geklappt.'
      } finally {
        this.busy = false
      }
    },
    backupNow() {
      return this.run(() => backupAPI.backupNow(), 'Die Sicherung wurde angelegt.')
    },
    saveSettings() {
      if (this.form.encrypt && !this.status.encrypted && !this.form.password) {
        this.error = 'Bitte ein Passwort für die Verschlüsselung angeben.'
        return Promise.resolve()
      }
      // null keeps the stored password, '' removes it.
      const password = !this.form.encrypt ? '' : (this.form.password || null)
      return this.run(
        () => backupAPI.updateSettings({ directory: this.form.directory, keep: this.form.keep, onExit: this.form.onExit, password }),
        'Die Einstellungen wurden gespeichert.'
      )
    },
    startRestore(backup) {
      this.restoring = backup.name
      this.restorePassword = ''
    },
    restore(backup) {
      return this.run(async () => {
        await backupAPI.restore(backup.name, this.restorePassword)
        this.restoring = null
      }, 'Die Sicherung wird beim nächsten Start wiederhergestellt. Bitte Billery jetzt beenden und neu starten.')
    },
    cancelRestore() {
      return this.run(() => backupAPI.cancelRestore(), 'Die Wiederherstellung wurde abgebrochen.')
    },
    formatDate(value) {
      return value ? new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' }) : ''
    },
    formatSize(bytes) {
      return formatFileSize(bytes)
    },
    reasonLabel(reason) {
      return REASONS[reason] || reason
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
.actions { display: flex; flex-wrap: wrap; gap: var(--space-2); justify-content: flex-end; }
.badge { margin-left: var(--space-2); }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
</style>

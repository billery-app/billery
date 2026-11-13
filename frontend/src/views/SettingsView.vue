<template>
  <div class="settings-view">
    <header class="view-header">
      <div>
        <h1 class="nav-destination-page-title">Einstellungen</h1>
      </div>
    </header>

    <AccountSettings v-if="showAccount" />
    <section class="settings-section">
      <h2 class="section-title">Allgemein</h2>
      <div class="settings-grid">
        <router-link
          v-for="entry in availableEntries"
          :key="entry.id"
          :to="entry.to"
          class="settings-card"
        >
          <div class="card-icon">{{ entry.icon }}</div>
          <div class="card-body">
            <div class="card-title">{{ entry.title }}</div>
            <div class="card-description">{{ entry.description }}</div>
          </div>
          <div class="card-chevron">&rsaquo;</div>
        </router-link>
      </div>
    </section>

    <section class="settings-section" data-testid="gobd-export">
      <h2 class="section-title">Daten &amp; Export</h2>
      <div class="export-panel">
        <p class="export-description">
          Alle ausgestellten Rechnungen und Stornorechnungen eines Zeitraums mit Positionen,
          Zahlungen und Änderungsprotokoll als ZIP – für die Betriebsprüfung (§ 147 Abs. 6 AO)
          oder die Steuerberatung. Enthält CSV-Dateien mit Beschreibung (index.xml).
        </p>
        <div class="export-row">
          <label>Von <input v-model="exportFrom" type="date" data-testid="export-from" /></label>
          <label>Bis <input v-model="exportTo" type="date" data-testid="export-to" /></label>
          <label class="export-check">
            <input v-model="exportDocuments" type="checkbox" />
            PDFs und E-Rechnungen beilegen
          </label>
          <button
            class="btn btn-primary"
            :disabled="exporting || !exportFrom || !exportTo"
            data-testid="export-button"
            @click="runExport"
          >{{ exporting ? 'Exportiere…' : 'Export herunterladen' }}</button>
        </div>
        <p v-if="exportError" class="export-error">{{ exportError }}</p>
      </div>
    </section>

    <TeamSettings v-if="showTeam" />

    <BackupSettings v-if="showBackup" />

    <section class="settings-section">
      <h2 class="section-title">Weitere Einstellungen</h2>
      <div class="settings-grid">
        <div
          v-for="entry in upcomingEntries"
          :key="entry.id"
          class="settings-card is-disabled"
          :aria-disabled="true"
        >
          <div class="card-icon">{{ entry.icon }}</div>
          <div class="card-body">
            <div class="card-title">
              {{ entry.title }}
              <span class="coming-soon-badge">Demnächst verfügbar</span>
            </div>
            <div class="card-description">{{ entry.description }}</div>
          </div>
        </div>
      </div>
    </section>
  </div>
</template>

<script>
import exportAPI from '../services/exportService.js'
import AccountSettings from '../components/AccountSettings.vue'
import TeamSettings from '../components/TeamSettings.vue'
import BackupSettings from '../components/BackupSettings.vue'
import { activeRole, isSaas, session } from '../services/session.js'

export default {
  name: 'SettingsView',
  components: { AccountSettings, TeamSettings, BackupSettings },
  data() {
    const today = new Date()
    return {
      exportFrom: `${today.getFullYear()}-01-01`,
      exportTo: today.toISOString().split('T')[0],
      exportDocuments: true,
      exporting: false,
      exportError: null,
      availableEntries: [
        {
          id: 'owners',
          to: '/owners',
          icon: '👤',
          title: 'Inhaber',
          description: 'Firmendaten und Stammdaten des Rechnungsstellers'
        },
        {
          id: 'templates',
          to: '/templates',
          icon: '📄',
          title: 'Dokumentvorlagen',
          description: 'Vorlagen für Rechnungen, Angebote und Kalkulationen'
        }
      ],
      upcomingEntries: [
        {
          id: 'mail',
          icon: '✉️',
          title: 'Mail-Einstellungen',
          description: 'SMTP-Zugangsdaten für den Rechnungsversand'
        },
        {
          id: 'profile',
          icon: '🪪',
          title: 'Benutzer & Profil',
          description: 'Persönliche Einstellungen und Profil'
        },
        {
          id: 'help',
          icon: '❓',
          title: 'Hilfe',
          description: 'Dokumentation, FAQ und Über diese Version'
        }
      ]
    }
  },
  computed: {
    /** Account and company (SaaS): who is signed in where; shown to every role. */
    showAccount() {
      return isSaas()
    },
    /** The team section is for the owners and admins of a SaaS tenant. */
    showTeam() {
      return isSaas() && ['OWNER', 'ADMIN'].includes(activeRole())
    },
    /** Backups of the H2 file database: the desktop app only (a server uses pg_dump). */
    showBackup() {
      return session.desktop
    }
  },
  methods: {
    /** Downloads the GoBD export ZIP for the chosen period. */
    async runExport() {
      if (this.exportFrom > this.exportTo) {
        this.exportError = 'Das Startdatum liegt nach dem Enddatum.'
        return
      }
      this.exporting = true
      this.exportError = null
      try {
        const response = await exportAPI.gobdExport(this.exportFrom, this.exportTo, this.exportDocuments)
        const url = window.URL.createObjectURL(new Blob([response.data], { type: 'application/zip' }))
        const a = document.createElement('a')
        a.href = url
        a.download = `GoBD-Export_${this.exportFrom}_${this.exportTo}.zip`
        document.body.appendChild(a)
        a.click()
        document.body.removeChild(a)
        window.URL.revokeObjectURL(url)
      } catch (err) {
        this.exportError = 'Export fehlgeschlagen: ' + err.message
      } finally {
        this.exporting = false
      }
    }
  }
}
</script>

<style scoped>
.export-panel {
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 10px;
  padding: 16px 20px;
}

.export-description {
  margin: 0 0 12px;
  color: var(--color-text-muted);
  font-size: 0.95em;
}

.export-row {
  display: flex;
  flex-wrap: wrap;
  gap: 12px 20px;
  align-items: center;
}

.export-row label {
  display: flex;
  gap: 6px;
  align-items: center;
}

.export-error {
  color: var(--color-danger);
  margin: 10px 0 0;
}

.settings-view {
  padding: 20px;
  max-width: 1400px;
  margin: 0 auto;
}

.view-header {
  margin-bottom: 24px;
}

.view-header h1 {
  margin: 0;
  font-size: 1.75rem;
  color: var(--color-text);
}

.settings-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr));
  gap: 16px;
}

.settings-card {
  background: var(--color-surface);
  border-radius: 8px;
  box-shadow: var(--shadow-sm);
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  text-decoration: none;
  color: inherit;
  transition: box-shadow 0.2s, transform 0.2s, border-color 0.2s;
  border: 2px solid transparent;
  min-height: 80px;
}

.settings-card:hover {
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
  border-color: var(--color-primary);
}

.settings-card.is-disabled {
  cursor: not-allowed;
  opacity: 0.65;
  background: var(--color-surface-subtle);
}

.settings-card.is-disabled:hover {
  box-shadow: var(--shadow-sm);
  transform: none;
  border-color: transparent;
}

.card-icon {
  font-size: 1.8em;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-surface-subtle);
  border-radius: 10px;
  flex-shrink: 0;
}

.settings-card.is-disabled .card-icon {
  background: var(--color-border);
}

.card-body {
  flex: 1;
  min-width: 0;
}

.card-title {
  font-weight: 600;
  color: var(--color-text-strong);
  font-size: 1rem;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.card-description {
  margin-top: 2px;
  color: var(--color-text-muted);
  font-size: 0.85rem;
  line-height: 1.35;
}

.card-chevron {
  font-size: 1.5em;
  color: var(--color-text-subtle);
  font-weight: 700;
  flex-shrink: 0;
}

.coming-soon-badge {
  display: inline-block;
  background: var(--color-border);
  color: var(--color-text-muted);
  font-size: 0.7rem;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  white-space: nowrap;
}

@media (max-width: 600px) {
  .settings-view { padding: 12px; }
  .settings-grid { grid-template-columns: minmax(0, 1fr); }
  .export-panel { padding: 12px; }
  .export-row { align-items: stretch; flex-direction: column; }
  .export-row label { justify-content: space-between; flex-wrap: wrap; }
  .export-row label input[type="date"] { min-width: 0; }
  .export-row .btn-primary { min-height: 42px; }
}
</style>

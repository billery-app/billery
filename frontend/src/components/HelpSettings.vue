<template>
  <section class="settings-section" data-testid="help-settings">
    <div class="card help-card">
      <h3 class="help-heading">Einführungstour</h3>
      <p class="muted">
        Fünf kurze Stationen: Katalog, eine Rechnung, vom Angebot zur Rechnung und die
        Amortisation Ihrer Ausstattung. Sie können die Tour jederzeit überspringen.
      </p>
      <div>
        <button type="button" class="btn btn-primary" data-testid="help-start-tour" @click="start">Tour starten</button>
      </div>
      <label v-if="canRepeat" class="checkbox-label">
        <input
          type="checkbox"
          data-testid="help-every-session"
          :checked="everySession"
          :disabled="!loaded || busy"
          @change="setEverySession($event.target.checked)"
        />
        Tour bei jedem Start zeigen
      </label>
      <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    </div>
    <div class="card help-card">
      <h3 class="help-heading">Vorlagen und Editor</h3>
      <p class="muted">
        Fünf Stationen zu Ihren Dokumentvorlagen: neue Vorlage, Standard pro Dokumenttyp, Design und
        Blöcke, Vorschau mit Platzhaltern, PDF-Vorschau. Die Tour öffnet dafür eine Vorlage im Editor.
      </p>
      <div>
        <button type="button" class="btn btn-secondary" data-testid="help-start-template-tour" @click="startTemplates">Vorlagen-Tour starten</button>
      </div>
    </div>
  </section>
</template>

<script>
import tourService from '../services/tourService.js'
import { isSaas, session } from '../services/session.js'
import { runTemplateTour, runTour } from '../utils/tour.js'

/**
 * Einstellungen → Hilfe: starts the introductory tour or the templates tour and, in a self-hosted or desktop
 * installation, switches on "show it in every new browser session" (`/api/tour`). The demo shows
 * the tour in every session anyway, and SaaS keeps no tour state on the server.
 */
export default {
  name: 'HelpSettings',
  data() {
    return {
      everySession: false,
      loaded: false,
      busy: false,
      error: null
    }
  },
  computed: {
    /** Only self-host and desktop store the setting (the installation's `user_settings`). */
    canRepeat() {
      return !isSaas() && !session.demo
    }
  },
  async mounted() {
    if (!this.canRepeat) return
    try {
      this.everySession = (await tourService.state()).everySession
      this.loaded = true
    } catch {
      this.error = 'Die Einstellung zur Tour konnte nicht geladen werden.'
    }
  },
  methods: {
    start() {
      runTour(this.$router)
    },
    startTemplates() {
      runTemplateTour(this.$router)
    },
    async setEverySession(value) {
      this.busy = true
      this.error = null
      try {
        this.everySession = (await tourService.save({ everySession: value })).everySession
      } catch {
        this.error = 'Die Einstellung konnte nicht gespeichert werden.'
      } finally {
        this.busy = false
      }
    }
  }
}
</script>

<style scoped>
.settings-section { display: flex; flex-direction: column; gap: var(--space-4); }
.help-card { display: flex; flex-direction: column; gap: var(--space-4); }
.help-heading { margin: 0; font-size: 1rem; color: var(--color-text); }
.muted { margin: 0; color: var(--color-text-muted); }
</style>

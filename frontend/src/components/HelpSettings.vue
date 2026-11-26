<template>
  <section class="settings-section" data-testid="help-settings">
    <div class="card help-card">
      <h3 class="help-heading">{{ $t('help.introHeading') }}</h3>
      <p class="muted">{{ $t('help.introText') }}</p>
      <div>
        <button type="button" class="btn btn-primary" data-testid="help-start-tour" @click="start">{{ $t('help.startIntro') }}</button>
      </div>
      <label v-if="canRepeat" class="checkbox-label">
        <input
          type="checkbox"
          data-testid="help-every-session"
          :checked="everySession"
          :disabled="!loaded || busy"
          @change="setEverySession(inputChecked($event))"
        />
        {{ $t('help.everySession') }}
      </label>
      <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    </div>
    <div class="card help-card">
      <h3 class="help-heading">{{ $t('help.templatesHeading') }}</h3>
      <p class="muted">{{ $t('help.templatesText') }}</p>
      <div>
        <button type="button" class="btn btn-secondary" data-testid="help-start-template-tour" @click="startTemplates">{{ $t('help.startTemplates') }}</button>
      </div>
    </div>
  </section>
</template>

<script>
import { inputChecked } from '../utils/dom.js'
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
      this.error = this.$t('help.loadFailed')
    }
  },
  methods: {
    inputChecked,
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
        this.error = this.$t('help.saveFailed')
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

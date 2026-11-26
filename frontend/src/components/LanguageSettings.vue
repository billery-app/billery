<template>
  <section class="settings-section" data-testid="language-settings">
    <div class="card language-card">
      <h3 class="language-heading">{{ $t('language.heading') }}</h3>
      <p class="muted">{{ saas ? $t('language.hintSaas') : $t('language.hint') }}</p>
      <div class="language-options" role="radiogroup" :aria-label="$t('language.heading')">
        <label v-for="option in languages" :key="option.code" class="checkbox-label">
          <input
            type="radio"
            name="language"
            :value="option.code"
            :checked="option.code === active"
            :disabled="busy"
            :data-testid="`language-${option.code}`"
            @change="choose(option.code)"
          />
          <!-- the language's own name, the same in every language -->
          <span lang="und">{{ option.label }}</span>
        </label>
      </div>
      <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
    </div>
  </section>
</template>

<script>
import preferencesService from '../services/preferencesService.js'
import { applyLanguage, currentLanguage, LANGUAGES } from '../i18n/index.js'
import { isSaas } from '../services/session.js'

/**
 * Einstellungen → Sprache (#15): the language of the user interface, stored on the server (per
 * installation, in SaaS per user) and applied at once. Documents and mails to customers are not
 * affected.
 */
export default {
  name: 'LanguageSettings',
  data() {
    return { languages: LANGUAGES, active: currentLanguage(), busy: false, error: null }
  },
  computed: {
    saas() {
      return isSaas()
    }
  },
  methods: {
    /** Stores the choice first; only a stored language is switched to. */
    async choose(code) {
      this.busy = true
      this.error = null
      try {
        const stored = await preferencesService.setLanguage(code)
        this.active = applyLanguage(stored.language)
      } catch {
        this.error = this.$t('language.saveFailed')
      } finally {
        this.busy = false
      }
    }
  }
}
</script>

<style scoped>
.language-card { display: flex; flex-direction: column; gap: var(--space-4); }
.language-heading { margin: 0; font-size: 1rem; color: var(--color-text); }
.muted { margin: 0; color: var(--color-text-muted); }
.language-options { display: flex; flex-direction: column; gap: var(--space-2); }
.language-options .checkbox-label { display: flex; align-items: center; gap: var(--space-2); cursor: pointer; }
</style>

import { createI18n } from 'vue-i18n'
import de from '../locales/de.json'
import en from '../locales/en.json'

/**
 * The languages of the user interface (#15). German is the default and the fallback: a key
 * missing in English shows the German text, never the key.
 *
 * Components use `$t('…')` (Options API) or `useI18n()` (`<script setup>`); code outside a
 * component (utils, the tour) uses {@link t}. Message files: `src/locales/<code>.json`; the key
 * sets of all languages are kept equal by `locales.spec.js`.
 */
export const LANGUAGES = [
  { code: 'de', label: 'Deutsch' },
  { code: 'en', label: 'English' }
]

const DEFAULT_LANGUAGE = 'de'

export const i18n = createI18n({
  legacy: false,
  globalInjection: true,
  locale: DEFAULT_LANGUAGE,
  fallbackLocale: DEFAULT_LANGUAGE,
  messages: { de, en }
})

/**
 * Translates outside a component.
 * @param {string} key - e.g. `tour.intro.welcome.title`
 * @param {Object} [values] - named placeholders of the message
 * @returns {string}
 */
export function t(key, values) {
  return values === undefined ? i18n.global.t(key) : i18n.global.t(key, values)
}

/**
 * Switches the language of the whole app at once (and the page's `lang`, for screen readers and
 * hyphenation). An unknown code falls back to German.
 * @param {string|null|undefined} code - 'de' or 'en'
 * @returns {string} the language now active
 */
export function applyLanguage(code) {
  const active = /** @type {'de'|'en'} */ (
    LANGUAGES.some(language => language.code === code) ? code : DEFAULT_LANGUAGE
  )
  i18n.global.locale.value = active
  document.documentElement.lang = active
  return active
}

/** @returns {string} the language now active */
export function currentLanguage() {
  return i18n.global.locale.value
}

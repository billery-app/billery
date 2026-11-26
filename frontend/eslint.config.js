import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import vueI18n from '@intlify/eslint-plugin-vue-i18n'

/**
 * ESLint (flat config): the recommended JavaScript rules and eslint-plugin-vue's
 * "flat/recommended" for the single-file components. `npm run lint`; CI runs it in the
 * frontend-test job (#39).
 *
 * Internationalisation (#15): every `$t('…')` / `t('…')` key must exist in the message files
 * (`no-missing-keys`), and the files already moved to the message files may not gain a hard-coded
 * text again (`no-raw-text`). A file joins I18N_DONE when its texts have moved.
 */
const I18N_DONE = [
  'src/App.vue',
  'src/views/SettingsView.vue',
  'src/views/SettingsAreaView.vue',
  'src/components/HelpSettings.vue',
  'src/components/LanguageSettings.vue'
]
export default [
  { ignores: ['dist/**', 'coverage/**', 'test-results/**', 'playwright-report/**'] },
  js.configs.recommended,
  ...pluginVue.configs['flat/recommended'],
  {
    languageOptions: {
      ecmaVersion: 'latest',
      sourceType: 'module',
      globals: { ...globals.browser }
    }
  },
  {
    rules: {
      // Layout only: the project has no formatter (no Prettier), and these rules would rewrite
      // the markup of every component without catching a single bug. A formatter is a decision
      // of its own; until then the markup keeps its hand-written layout.
      'vue/max-attributes-per-line': 'off',
      'vue/html-indent': 'off',
      'vue/singleline-html-element-content-newline': 'off',
      'vue/multiline-html-element-content-newline': 'off',
      'vue/html-self-closing': 'off',
      'vue/html-closing-bracket-spacing': 'off',
      'vue/html-closing-bracket-newline': 'off',
      // `_name` marks a parameter a signature needs but the body does not use
      'no-unused-vars': ['error', { argsIgnorePattern: '^_', caughtErrors: 'all', caughtErrorsIgnorePattern: '^_' }]
    }
  },
  {
    files: ['src/**/*.{js,vue}'],
    plugins: { '@intlify/vue-i18n': vueI18n },
    settings: {
      'vue-i18n': { localeDir: './src/locales/*.json', messageSyntaxVersion: '^11.0.0' }
    },
    rules: {
      '@intlify/vue-i18n/no-missing-keys': 'error'
    }
  },
  {
    files: I18N_DONE,
    plugins: { '@intlify/vue-i18n': vueI18n },
    settings: {
      'vue-i18n': { localeDir: './src/locales/*.json', messageSyntaxVersion: '^11.0.0' }
    },
    rules: {
      // brand, keyboard hints and arrows are no text to translate
      '@intlify/vue-i18n/no-raw-text': ['error', { ignorePattern: '^[-–—›‹→←·•…⌘K\\s]+$|^Billery$' }]
    }
  },
  {
    files: ['**/*.spec.js', 'e2e/**', 'playwright.config.js', 'vite.config.js', 'vitest.config.js', 'vite-plugins/**', 'eslint.config.js'],
    languageOptions: { globals: { ...globals.node } }
  }
]

import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'

/**
 * ESLint (flat config): the recommended JavaScript rules and eslint-plugin-vue's
 * "flat/recommended" for the single-file components. `npm run lint`; CI runs it in the
 * frontend-test job (#39).
 */
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
    files: ['**/*.spec.js', 'e2e/**', 'playwright.config.js', 'vite.config.js', 'vitest.config.js', 'vite-plugins/**', 'eslint.config.js'],
    languageOptions: { globals: { ...globals.node } }
  }
]

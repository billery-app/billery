import js from '@eslint/js'
import pluginVue from 'eslint-plugin-vue'
import globals from 'globals'
import vueI18n from '@intlify/eslint-plugin-vue-i18n'
import tseslint from 'typescript-eslint'

/**
 * ESLint (flat config): the recommended JavaScript rules and eslint-plugin-vue's
 * "flat/recommended" for the single-file components. `npm run lint`; CI runs it in the
 * frontend-test job (#39).
 *
 * Internationalisation (#15): every `$t('…')` / `t('…')` key must exist in the message files
 * (`no-missing-keys`), and no component template may hold a hard-coded text (`no-raw-text`;
 * attributes such as `title` / `aria-label` and texts in scripts are not seen by it, they follow
 * the same rule by review).
 *
 * TypeScript (#39, step 3): `.ts` files get typescript-eslint's recommended rules (parser and the
 * TypeScript versions of the core rules); the JavaScript and Vue files keep their rules unchanged.
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
  ...tseslint.configs.recommended.map(config => ({ ...config, files: ['**/*.ts'] })),
  {
    files: ['**/*.ts'],
    rules: {
      // the TypeScript version of the rule above, same options
      'no-unused-vars': 'off',
      '@typescript-eslint/no-unused-vars': ['error', { argsIgnorePattern: '^_', caughtErrors: 'all', caughtErrorsIgnorePattern: '^_' }]
    }
  },
  {
    files: ['src/**/*.{js,ts,vue}'],
    plugins: { '@intlify/vue-i18n': vueI18n },
    settings: {
      'vue-i18n': { localeDir: './src/locales/*.json', messageSyntaxVersion: '^11.0.0' }
    },
    rules: {
      '@intlify/vue-i18n/no-missing-keys': 'error'
    }
  },
  {
    files: ['src/**/*.vue'],
    plugins: { '@intlify/vue-i18n': vueI18n },
    settings: {
      'vue-i18n': { localeDir: './src/locales/*.json', messageSyntaxVersion: '^11.0.0' }
    },
    rules: {
      // no text to translate: punctuation, symbols, emoji (icons), currency and percent signs,
      // and names that are the same in every language (brand, file formats, keys, settings)
      '@intlify/vue-i18n/no-raw-text': [
        'error',
        {
          ignorePattern: '^[\\s\\p{P}\\p{S}\\p{Extended_Pictographic}\\u{FE0F}]*$',
          ignoreText: ['Billery', '⌘K', 'DOCX', 'ODT', 'Enter', 'Esc', 'BILLERY_SECRETS_KEY', 'PDF']
        }
      ]
    }
  },
  {
    files: ['**/*.spec.js', '**/*.spec.ts', 'e2e/**', 'playwright.config.js', 'vite.config.js', 'vitest.config.js', 'vite-plugins/**', 'eslint.config.js'],
    languageOptions: { globals: { ...globals.node } }
  }
]

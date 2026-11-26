import { config } from '@vue/test-utils'
import { beforeEach } from 'vitest'
import { applyLanguage, i18n } from '../i18n/index.js'

// Every mounted component gets the app's i18n (`$t`), German unless a test switches (#15).
config.global.plugins = [...(config.global.plugins || []), i18n]

beforeEach(() => {
  applyLanguage('de')
})

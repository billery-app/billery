import { describe, expect, it, vi } from 'vitest'
import { loadLanguage } from './preferencesService.js'
import { currentLanguage } from '../i18n/index.js'

describe('loadLanguage (#15)', () => {
  it('applies the stored language and sets the page language', async () => {
    expect(await loadLanguage({ get: vi.fn().mockResolvedValue({ language: 'en' }) })).toBe('en')
    expect(currentLanguage()).toBe('en')
    expect(document.documentElement.lang).toBe('en')
  })

  it('stays German without a choice, with an unknown one, or when the server does not answer', async () => {
    expect(await loadLanguage({ get: vi.fn().mockResolvedValue({ language: null }) })).toBe('de')
    expect(await loadLanguage({ get: vi.fn().mockResolvedValue({ language: 'xx' }) })).toBe('de')
    expect(await loadLanguage({ get: vi.fn().mockRejectedValue(new Error('500')) })).toBe('de')
    expect(currentLanguage()).toBe('de')
  })
})

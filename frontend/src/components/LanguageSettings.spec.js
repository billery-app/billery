import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import LanguageSettings from './LanguageSettings.vue'
import preferencesService from '../services/preferencesService.js'
import { currentLanguage } from '../i18n/index.js'

vi.mock('../services/preferencesService.js', () => ({ default: { setLanguage: vi.fn() } }))

beforeEach(() => vi.clearAllMocks())

describe('LanguageSettings (#15)', () => {
  it('stores the chosen language and switches the interface at once', async () => {
    preferencesService.setLanguage.mockResolvedValue({ language: 'en' })
    const wrapper = mount(LanguageSettings)
    expect(wrapper.text()).toContain('Sprache der Oberfläche')
    expect(wrapper.get('[data-testid="language-de"]').element.checked).toBe(true)

    await wrapper.get('[data-testid="language-en"]').trigger('change')
    await flushPromises()

    expect(preferencesService.setLanguage).toHaveBeenCalledWith('en')
    expect(currentLanguage()).toBe('en')
    expect(wrapper.text()).toContain('Language of the user interface')
  })

  it('keeps the language and says so when it cannot be stored', async () => {
    preferencesService.setLanguage.mockRejectedValue(new Error('500'))
    const wrapper = mount(LanguageSettings)
    await wrapper.get('[data-testid="language-en"]').trigger('change')
    await flushPromises()
    expect(currentLanguage()).toBe('de')
    expect(wrapper.get('[role="alert"]').text()).toContain('nicht gespeichert')
  })
})

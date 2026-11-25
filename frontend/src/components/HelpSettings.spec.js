import { flushPromises, mount } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import HelpSettings from './HelpSettings.vue'
import tourService from '../services/tourService.js'
import { runTemplateTour, runTour } from '../utils/tour.js'
import { session } from '../services/session.js'

vi.mock('../services/tourService.js', () => ({ default: { state: vi.fn(), save: vi.fn() } }))
vi.mock('../utils/tour.js', () => ({ runTour: vi.fn(), runTemplateTour: vi.fn() }))

const router = { push: vi.fn() }
const mountHelp = () => mount(HelpSettings, { global: { mocks: { $router: router } } })

beforeEach(() => {
  vi.clearAllMocks()
  tourService.state.mockResolvedValue({ seen: true, everySession: false, hasDocuments: true })
})

afterEach(() => {
  session.mode = 'selfhost'
  session.demo = false
})

describe('HelpSettings', () => {
  it('starts the tour', async () => {
    const wrapper = mountHelp()
    await wrapper.get('[data-testid="help-start-tour"]').trigger('click')
    expect(runTour).toHaveBeenCalledWith(router)
  })

  it('starts the templates tour', async () => {
    const wrapper = mountHelp()
    await wrapper.get('[data-testid="help-start-template-tour"]').trigger('click')
    expect(runTemplateTour).toHaveBeenCalledWith(router)
    expect(runTour).not.toHaveBeenCalled()
  })

  it('self-host: shows and stores "Tour bei jedem Start zeigen"', async () => {
    tourService.save.mockResolvedValue({ seen: true, everySession: true, hasDocuments: true })
    const wrapper = mountHelp()
    await flushPromises()
    const box = wrapper.get('[data-testid="help-every-session"]')
    expect(box.element.checked).toBe(false)
    expect(box.element.disabled).toBe(false)

    box.element.checked = true
    await box.trigger('change')
    await flushPromises()

    expect(tourService.save).toHaveBeenCalledWith({ everySession: true })
    expect(box.element.checked).toBe(true)
  })

  it('says so when the setting cannot be saved', async () => {
    tourService.save.mockRejectedValue(new Error('500'))
    const wrapper = mountHelp()
    await flushPromises()
    const box = wrapper.get('[data-testid="help-every-session"]')
    box.element.checked = true
    await box.trigger('change')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('nicht gespeichert')
  })

  it('demo and SaaS: only the button (the demo shows the tour every session, SaaS stores nothing)', async () => {
    session.demo = true
    let wrapper = mountHelp()
    await flushPromises()
    expect(wrapper.find('[data-testid="help-every-session"]').exists()).toBe(false)
    expect(tourService.state).not.toHaveBeenCalled()

    session.demo = false
    session.mode = 'saas'
    wrapper = mountHelp()
    await flushPromises()
    expect(wrapper.find('[data-testid="help-every-session"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="help-start-tour"]').exists()).toBe(true)
  })
})

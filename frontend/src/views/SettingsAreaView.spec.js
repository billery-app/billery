import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import SettingsAreaView from './SettingsAreaView.vue'

vi.mock('../services/backupService.js', () => ({
  default: { getStatus: vi.fn(() => new Promise(() => {})) }
}))

async function open(area) {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/settings', component: { template: '<div />' } },
      { path: `/settings/${area}`, component: SettingsAreaView, meta: { area } }
    ]
  })
  router.push(`/settings/${area}`)
  await router.isReady()
  const wrapper = mount(SettingsAreaView, { global: { plugins: [router] } })
  await flushPromises()
  return wrapper
}

describe('SettingsAreaView', () => {
  it('shows the GoBD export under its title, with a way back to the overview', async () => {
    const wrapper = await open('export')
    expect(wrapper.get('h1').text()).toBe('Daten & Export')
    expect(wrapper.find('[data-testid="gobd-export"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="settings-back"]').attributes('href')).toBe('/settings')
  })

  it('shows the backup settings on their sub-page', async () => {
    const wrapper = await open('backup')
    expect(wrapper.get('h1').text()).toBe('Datensicherung')
    expect(wrapper.find('[data-testid="backup-settings"]').exists()).toBe(true)
  })

  it('has exactly one heading per page: the area title', async () => {
    const wrapper = await open('backup')
    expect(wrapper.findAll('h1')).toHaveLength(1)
    expect(wrapper.findAll('h2').map(h => h.text())).not.toContain('Datensicherung')
  })
})

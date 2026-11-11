import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import SettingsView from './SettingsView.vue'

function mountWithRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/settings', component: SettingsView }
    ]
  })
  return mount(SettingsView, {
    global: {
      plugins: [router]
    }
  })
}

describe('SettingsView', () => {
  describe('Header', () => {
    it('renders the page title', () => {
      const wrapper = mountWithRouter()
      expect(wrapper.find('.view-header h1').text()).toBe('Einstellungen')
    })

    it('does not render the removed page subtitle', () => {
      const wrapper = mountWithRouter()
      expect(wrapper.find('.view-subtitle').exists()).toBe(false)
    })
  })

  describe('Section headings', () => {
    it('renders the active section title', () => {
      const wrapper = mountWithRouter()
      const titles = wrapper.findAll('.section-title').map(el => el.text())
      expect(titles).toContain('Allgemein')
    })

    it('groups the GoBD export under Daten & Export', () => {
      const wrapper = mountWithRouter()
      expect(wrapper.find('[data-testid="gobd-export"] .section-title').text()).toBe('Daten & Export')
    })

    it('renders the upcoming section title', () => {
      const wrapper = mountWithRouter()
      const titles = wrapper.findAll('.section-title').map(el => el.text())
      expect(titles).toContain('Weitere Einstellungen')
    })
  })

  describe('Active cards', () => {
    it('renders exactly two clickable cards', () => {
      const wrapper = mountWithRouter()
      const cards = wrapper.findAll('.settings-card')
      const activeCards = wrapper.findAll('.settings-card:not(.is-disabled)')
      expect(activeCards).toHaveLength(2)
    })

    it('links to /owners', () => {
      const wrapper = mountWithRouter()
      const ownersLink = wrapper.findAll('a').find(a => a.attributes('href') === '/owners')
      expect(ownersLink).toBeTruthy()
    })

    it('links to /templates', () => {
      const wrapper = mountWithRouter()
      const templatesLink = wrapper.findAll('a').find(a => a.attributes('href') === '/templates')
      expect(templatesLink).toBeTruthy()
    })

    it('shows the Inhaber title', () => {
      const wrapper = mountWithRouter()
      const ownersLink = wrapper.findAll('a').find(a => a.attributes('href') === '/owners')
      expect(ownersLink.text()).toContain('Inhaber')
    })

    it('shows the Dokumentvorlagen title', () => {
      const wrapper = mountWithRouter()
      const templatesLink = wrapper.findAll('a').find(a => a.attributes('href') === '/templates')
      expect(templatesLink.text()).toContain('Dokumentvorlagen')
    })

    it('shows a chevron on active cards', () => {
      const wrapper = mountWithRouter()
      const activeCards = wrapper.findAll('.settings-card:not(.is-disabled)')
      activeCards.forEach(card => {
        expect(card.find('.card-chevron').exists()).toBe(true)
      })
    })
  })

  describe('Upcoming cards', () => {
    it('renders exactly four placeholder cards', () => {
      const wrapper = mountWithRouter()
      const disabledCards = wrapper.findAll('.settings-card.is-disabled')
      expect(disabledCards).toHaveLength(4)
    })

    it('marks placeholder cards as aria-disabled', () => {
      const wrapper = mountWithRouter()
      const disabledCards = wrapper.findAll('.settings-card.is-disabled')
      disabledCards.forEach(card => {
        expect(card.attributes('aria-disabled')).toBe('true')
      })
    })

    it('does not make placeholder cards into router-links', () => {
      const wrapper = mountWithRouter()
      const disabledCards = wrapper.findAll('.settings-card.is-disabled')
      disabledCards.forEach(card => {
        expect(card.element.tagName).not.toBe('A')
      })
    })

    it('shows the Demnächst verfügbar badge on every placeholder', () => {
      const wrapper = mountWithRouter()
      const disabledCards = wrapper.findAll('.settings-card.is-disabled')
      disabledCards.forEach(card => {
        const badge = card.find('.coming-soon-badge')
        expect(badge.exists()).toBe(true)
        expect(badge.text()).toBe('Demnächst verfügbar')
      })
    })

    it('shows the Mail-Einstellungen entry', () => {
      const wrapper = mountWithRouter()
      const titles = wrapper.findAll('.is-disabled .card-title').map(el => el.text())
      expect(titles.some(t => t.includes('Mail-Einstellungen'))).toBe(true)
    })

    it('shows the Benutzer & Profil entry', () => {
      const wrapper = mountWithRouter()
      const titles = wrapper.findAll('.is-disabled .card-title').map(el => el.text())
      expect(titles.some(t => t.includes('Benutzer & Profil'))).toBe(true)
    })

    it('shows the Daten-Backup entry', () => {
      const wrapper = mountWithRouter()
      const titles = wrapper.findAll('.is-disabled .card-title').map(el => el.text())
      expect(titles.some(t => t.includes('Daten-Backup'))).toBe(true)
    })

    it('shows the Hilfe entry', () => {
      const wrapper = mountWithRouter()
      const titles = wrapper.findAll('.is-disabled .card-title').map(el => el.text())
      expect(titles.some(t => t.includes('Hilfe'))).toBe(true)
    })

    it('does not show a chevron on placeholder cards', () => {
      const wrapper = mountWithRouter()
      const disabledCards = wrapper.findAll('.settings-card.is-disabled')
      disabledCards.forEach(card => {
        expect(card.find('.card-chevron').exists()).toBe(false)
      })
    })
  })

  describe('Card structure', () => {
    it('renders six cards total', () => {
      const wrapper = mountWithRouter()
      const cards = wrapper.findAll('.settings-card')
      expect(cards).toHaveLength(6)
    })

    it('each card has an icon, title and description', () => {
      const wrapper = mountWithRouter()
      const cards = wrapper.findAll('.settings-card')
      cards.forEach(card => {
        expect(card.find('.card-icon').exists()).toBe(true)
        expect(card.find('.card-title').exists()).toBe(true)
        expect(card.find('.card-description').exists()).toBe(true)
      })
    })
  })
})

describe('SettingsView — GoBD export', () => {
  it('offers a period and downloads the export for it', async () => {
    const { default: exportAPI } = await import('../services/exportService.js')
    const spy = vi.spyOn(exportAPI, 'gobdExport').mockResolvedValue({ data: new Blob(['zip']) })
    window.URL.createObjectURL = vi.fn(() => 'blob:x')
    window.URL.revokeObjectURL = vi.fn()
    const wrapper = mountWithRouter()

    await wrapper.find('[data-testid="export-from"]').setValue('2026-01-01')
    await wrapper.find('[data-testid="export-to"]').setValue('2026-03-31')
    await wrapper.find('[data-testid="export-button"]').trigger('click')

    expect(spy).toHaveBeenCalledWith('2026-01-01', '2026-03-31', true)
    spy.mockRestore()
  })

  it('refuses an inverted period without calling the server', async () => {
    const { default: exportAPI } = await import('../services/exportService.js')
    const spy = vi.spyOn(exportAPI, 'gobdExport')
    const wrapper = mountWithRouter()

    await wrapper.find('[data-testid="export-from"]').setValue('2026-05-01')
    await wrapper.find('[data-testid="export-to"]').setValue('2026-01-01')
    await wrapper.find('[data-testid="export-button"]').trigger('click')

    expect(spy).not.toHaveBeenCalled()
    expect(wrapper.find('.export-error').text()).toContain('Startdatum')
    spy.mockRestore()
  })
})

describe('SettingsView in SaaS mode', () => {
  it('shows the account section to every role, the team section only to owners and admins', async () => {
    const { session } = await import('../services/session.js')
    session.mode = 'saas'
    session.user = { id: 'u', email: 'anna@example.com' }
    session.memberships = [{ tenantId: 1, tenantName: 'Eins GmbH', role: 'READER' }]
    session.activeTenantId = 1
    try {
      const reader = mountWithRouter()
      expect(reader.find('[data-testid="account-settings"]').exists()).toBe(true)
      expect(reader.get('[data-testid="account-company"]').text()).toBe('Eins GmbH')
      expect(reader.find('[data-testid="team-settings"]').exists()).toBe(false)
    } finally {
      session.mode = 'selfhost'
      session.memberships = []
      session.user = null
    }
  })

  it('shows no account section in a self-hosted install', () => {
    expect(mountWithRouter().find('[data-testid="account-settings"]').exists()).toBe(false)
  })
})

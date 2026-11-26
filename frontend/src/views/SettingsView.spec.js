import { describe, it, expect, afterEach } from 'vitest'
import { applyLanguage } from '../i18n/index.js'
import { mount } from '@vue/test-utils'
import { createRouter, createMemoryHistory } from 'vue-router'
import SettingsView from './SettingsView.vue'
import { session } from '../services/session.js'

function mountWithRouter() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [
      { path: '/', component: { template: '<div />' } },
      { path: '/settings', component: SettingsView }
    ]
  })
  return mount(SettingsView, { global: { plugins: [router] } })
}

const cardIds = wrapper =>
  wrapper.findAll('.settings-card').map(card => card.attributes('data-testid').replace('settings-card-', ''))

/** Signs in a SaaS user with the given role in company 1. */
function saasAs(role) {
  session.mode = 'saas'
  session.user = { id: 'u', email: 'anna@example.com' }
  session.memberships = [{ tenantId: 1, tenantName: 'Eins GmbH', role }]
  session.activeTenantId = 1
}

afterEach(() => {
  session.mode = 'selfhost'
  session.user = null
  session.memberships = []
  session.activeTenantId = null
  session.desktop = false
})

describe('SettingsView', () => {
  it('renders the page title and no subtitle', () => {
    const wrapper = mountWithRouter()
    expect(wrapper.find('h1').text()).toBe('Einstellungen')
    expect(wrapper.find('.view-header p').exists()).toBe(false)
  })

  it('shows every area as a card, nothing else: no settings panels on the overview', () => {
    const wrapper = mountWithRouter()
    expect(wrapper.find('[data-testid="gobd-export"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="team-settings"]').exists()).toBe(false)
    expect(wrapper.findAll('.settings-section').every(s => s.find('.settings-grid').exists())).toBe(true)
  })

  it('groups the self-hosted areas under Allgemein, Daten and Weitere Einstellungen', () => {
    const wrapper = mountWithRouter()
    expect(wrapper.findAll('.section-title').map(el => el.text())).toEqual([
      'Allgemein',
      'Daten',
      'Weitere Einstellungen'
    ])
    expect(cardIds(wrapper)).toEqual(['owners', 'templates', 'export', 'mail', 'language', 'profile', 'help'])
  })

  it('links the cards to their pages', () => {
    const wrapper = mountWithRouter()
    const href = id => wrapper.get(`[data-testid="settings-card-${id}"]`).attributes('href')
    expect(href('owners')).toBe('/owners')
    expect(href('templates')).toBe('/templates')
    expect(href('export')).toBe('/settings/export')
    expect(href('mail')).toBe('/settings/mail')
  })

  it('shows the cards in the interface language (#15)', async () => {
    applyLanguage('en')
    const wrapper = mountWithRouter()
    expect(wrapper.findAll('.section-title').map(el => el.text())).toEqual(['General', 'Data', 'More settings'])
    expect(wrapper.get('[data-testid="settings-card-export"] .card-title').text()).toBe('Data & export')
    applyLanguage('de')
    await wrapper.vm.$nextTick()
    expect(wrapper.get('[data-testid="settings-card-export"] .card-title').text()).toBe('Daten & Export')
  })

  it('keeps the names the documentation uses', () => {
    const wrapper = mountWithRouter()
    expect(wrapper.get('[data-testid="settings-card-export"] .card-title').text()).toBe('Daten & Export')
  })

  it('shows upcoming areas as disabled cards without link or chevron', () => {
    const wrapper = mountWithRouter()
    const upcoming = wrapper.findAll('.settings-card.is-disabled')
    expect(upcoming.map(card => card.find('.card-title').text())).toEqual([
      'Benutzer & Profil Demnächst verfügbar'
    ])
    upcoming.forEach(card => {
      expect(card.attributes('aria-disabled')).toBe('true')
      expect(card.element.tagName).toBe('DIV')
      expect(card.find('.card-chevron').exists()).toBe(false)
    })
  })

  it('gives every card an icon, a title and a description; active cards a chevron', () => {
    const wrapper = mountWithRouter()
    wrapper.findAll('.settings-card').forEach(card => {
      expect(card.find('.card-icon').exists()).toBe(true)
      expect(card.find('.card-title').exists()).toBe(true)
      expect(card.find('.card-description').exists()).toBe(true)
    })
    wrapper.findAll('a.settings-card').forEach(card => {
      expect(card.find('.card-chevron').exists()).toBe(true)
    })
  })

  it('shows the backup card only in the desktop app', () => {
    expect(cardIds(mountWithRouter())).not.toContain('backup')
    session.desktop = true
    const wrapper = mountWithRouter()
    expect(cardIds(wrapper)).toContain('backup')
    expect(wrapper.get('[data-testid="settings-card-backup"]').attributes('href')).toBe('/settings/backup')
  })
})

describe('SettingsView in SaaS mode', () => {
  it('shows the account card to every role, the team card only to owners and admins', () => {
    saasAs('READER')
    const reader = cardIds(mountWithRouter())
    expect(reader).toContain('account')
    expect(reader).not.toContain('team')

    saasAs('ADMIN')
    expect(cardIds(mountWithRouter())).toEqual(
      expect.arrayContaining(['account', 'team'])
    )
  })

  it('shows the mail settings only to owners and admins', () => {
    saasAs('ACCOUNTANT')
    expect(cardIds(mountWithRouter())).not.toContain('mail')
    saasAs('READER')
    expect(cardIds(mountWithRouter())).not.toContain('mail')
    saasAs('OWNER')
    expect(cardIds(mountWithRouter())).toContain('mail')
  })

  it('never shows the desktop backup in SaaS', () => {
    saasAs('OWNER')
    expect(cardIds(mountWithRouter())).not.toContain('backup')
  })
})

import { describe, it, expect, afterEach } from 'vitest'
import { createAppRouter } from './index.js'
import { session } from '../services/session.js'

describe('createAppRouter', () => {
  afterEach(() => window.history.replaceState(null, '', '/'))

  it('starts at the address of the moment it is created, not of the import (sign-in callback)', async () => {
    window.history.replaceState(null, '', '/auth/callback?code=abc&state=def')
    // initAuth completes the sign-in and replaces the callback URL before the router is created
    window.history.replaceState(null, '', '/customers')
    const router = createAppRouter()
    await router.push(router.options.history.location)
    expect(router.currentRoute.value.path).toBe('/customers')
  })

  it('sends an unknown address to the dashboard instead of an empty page', async () => {
    const router = createAppRouter()
    await router.push('/auth/callback?code=abc')
    expect(router.currentRoute.value.path).toBe('/')
  })

  describe('settings sub-pages', () => {
    afterEach(() => {
      session.mode = 'selfhost'
      session.memberships = []
      session.activeTenantId = null
      session.desktop = false
    })

    it('opens an area that applies and keeps the navigation on Einstellungen', async () => {
      const router = createAppRouter()
      await router.push('/settings/export')
      expect(router.currentRoute.value.path).toBe('/settings/export')
      expect(router.currentRoute.value.matched[0].path).toBe('/settings')
    })

    it('leads back to the overview from an area that does not apply', async () => {
      const router = createAppRouter()
      await router.push('/settings/team') // self-hosted: no team
      expect(router.currentRoute.value.path).toBe('/settings')
      await router.push('/settings/backup') // not the desktop app
      expect(router.currentRoute.value.path).toBe('/settings')
    })

    it('lets SaaS owners and admins open the team, not readers', async () => {
      session.mode = 'saas'
      session.memberships = [{ tenantId: 1, tenantName: 'Eins GmbH', role: 'READER' }]
      session.activeTenantId = 1
      const router = createAppRouter()
      await router.push('/settings/team')
      expect(router.currentRoute.value.path).toBe('/settings')
      await router.push('/settings/account')
      expect(router.currentRoute.value.path).toBe('/settings/account')

      session.memberships = [{ tenantId: 1, tenantName: 'Eins GmbH', role: 'ADMIN' }]
      await router.push('/settings/team')
      expect(router.currentRoute.value.path).toBe('/settings/team')
    })

    it('opens the mail settings self-hosted, in SaaS only for owners and admins', async () => {
      const router = createAppRouter()
      await router.push('/settings/mail')
      expect(router.currentRoute.value.path).toBe('/settings/mail')

      session.mode = 'saas'
      session.memberships = [{ tenantId: 1, tenantName: 'Eins GmbH', role: 'ACCOUNTANT' }]
      session.activeTenantId = 1
      await router.push('/settings')
      await router.push('/settings/mail')
      expect(router.currentRoute.value.path).toBe('/settings')
    })

    it('opens the backup in the desktop app', async () => {
      session.desktop = true
      const router = createAppRouter()
      await router.push('/settings/backup')
      expect(router.currentRoute.value.path).toBe('/settings/backup')
    })
  })
})

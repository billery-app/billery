import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  authHeaders,
  buildSettings,
  chooseTenant,
  configureForTests,
  handleForbidden,
  initAuth,
  openTenant,
  renewAfterUnauthorized,
  resetForTests,
  setActiveTenant,
  SIGNIN_RETRY_KEY,
  signOut,
  TENANT_KEY
} from './authService.js'
import { session } from './session.js'

const OIDC = {
  issuer: 'https://idp.test',
  clientId: 'billery-spa',
  scope: 'openid profile email offline_access',
  audience: 'billery'
}
const SAAS_CONFIG = { data: { mode: 'saas', oidc: OIDC } }
const USER = { access_token: 'secret-access-token', expired: false }

function fakeManager(overrides = {}) {
  return {
    getUser: vi.fn().mockResolvedValue(USER),
    signinRedirect: vi.fn().mockResolvedValue(undefined),
    signinRedirectCallback: vi.fn(),
    signinSilentCallback: vi.fn().mockResolvedValue(undefined),
    signinSilent: vi.fn().mockResolvedValue(USER),
    signoutRedirect: vi.fn().mockResolvedValue(undefined),
    ...overrides
  }
}

function fakeHttp({ config = SAAS_CONFIG, memberships = [], email = 'anna@example.com' } = {}) {
  return {
    get: vi.fn(async url => {
      if (url.endsWith('/config')) return config
      if (url.endsWith('/me')) return { data: { userId: 'u-1', email, memberships } }
      throw new Error(`unexpected ${url}`)
    })
  }
}

const TWO = [
  { tenantId: 1, tenantName: 'Eins GmbH', role: 'ADMIN' },
  { tenantId: 2, tenantName: 'Zwei AG', role: 'READER' }
]

let manager
let reload

beforeEach(() => {
  resetForTests()
  window.localStorage.clear()
  window.sessionStorage.clear()
  window.history.replaceState(null, '', '/')
  manager = fakeManager()
  reload = vi.fn()
  configureForTests({ createManager: () => manager, reload })
})

describe('self-host mode', () => {
  it('needs no sign-in and sends no auth headers', async () => {
    await initAuth({ http: fakeHttp({ config: { data: { mode: 'selfhost' } } }) })

    expect(session.mode).toBe('selfhost')
    expect(session.status).toBe('ready')
    expect(manager.getUser).not.toHaveBeenCalled()
    expect(await authHeaders()).toEqual({})
  })
})

describe('SaaS mode sign-in', () => {
  it('sends a visitor without session to the identity provider and remembers the page', async () => {
    manager = fakeManager({ getUser: vi.fn().mockResolvedValue(null) })
    window.history.replaceState(null, '', '/invoices?filter=open#top')

    let finished = false
    initAuth({ http: fakeHttp() }).then(() => (finished = true))
    await vi.waitFor(() => expect(manager.signinRedirect).toHaveBeenCalled())

    expect(manager.signinRedirect).toHaveBeenCalledWith({
      state: { returnTo: '/invoices?filter=open#top' }
    })
    await new Promise(resolve => setTimeout(resolve, 20))
    expect(finished).toBe(false) // the page is leaving; the app is not mounted
  })

  it('completes the redirect back from the provider and returns to the remembered page', async () => {
    window.history.replaceState(null, '', '/auth/callback?code=abc&state=xyz')
    manager = fakeManager({
      signinRedirectCallback: vi.fn().mockResolvedValue({ state: { returnTo: '/customers' } })
    })

    await initAuth({ http: fakeHttp({ memberships: TWO }) })

    expect(manager.signinRedirectCallback).toHaveBeenCalled()
    expect(window.location.pathname).toBe('/customers')
    expect(session.status).toBe('ready')
  })

  it('hands the silent renewal response to the parent window and does not start the app in the iframe', async () => {
    window.history.replaceState(null, '', '/auth/callback?code=abc&state=xyz')
    configureForTests({ inFrame: () => true })
    const http = fakeHttp({ memberships: TWO })

    let finished = false
    initAuth({ http }).then(() => (finished = true))
    await vi.waitFor(() => expect(manager.signinSilentCallback).toHaveBeenCalled())

    await new Promise(resolve => setTimeout(resolve, 20))
    expect(manager.signinRedirectCallback).not.toHaveBeenCalled()
    expect(http.get).not.toHaveBeenCalledWith(expect.stringMatching(/\/me$/), expect.anything())
    expect(finished).toBe(false) // nothing is mounted inside the hidden iframe
  })

  it('starts one fresh sign-in when the callback state is gone, instead of failing on that URL', async () => {
    window.history.replaceState(null, '', '/auth/callback?code=old&state=used')
    manager = fakeManager({
      signinRedirectCallback: vi.fn().mockRejectedValue(new Error('No matching state found in storage'))
    })

    let finished = false
    initAuth({ http: fakeHttp() }).then(() => (finished = true))
    await vi.waitFor(() => expect(manager.signinRedirect).toHaveBeenCalled())

    expect(manager.signinRedirect).toHaveBeenCalledWith({ state: { returnTo: '/' } })
    expect(window.sessionStorage.getItem(SIGNIN_RETRY_KEY)).not.toBeNull()
    await new Promise(resolve => setTimeout(resolve, 20))
    expect(finished).toBe(false)
  })

  it('reports a second failed callback in a row instead of redirecting in a loop', async () => {
    window.sessionStorage.setItem(SIGNIN_RETRY_KEY, '1')
    window.history.replaceState(null, '', '/auth/callback?code=old&state=used')
    manager = fakeManager({
      signinRedirectCallback: vi.fn().mockRejectedValue(new Error('No matching state found in storage'))
    })

    await expect(initAuth({ http: fakeHttp() })).rejects.toThrow('No matching state')
    expect(manager.signinRedirect).not.toHaveBeenCalled()
    expect(window.sessionStorage.getItem(SIGNIN_RETRY_KEY)).toBeNull()
  })

  it('forgets an earlier retry once a callback succeeds', async () => {
    window.sessionStorage.setItem(SIGNIN_RETRY_KEY, '1')
    window.history.replaceState(null, '', '/auth/callback?code=abc&state=xyz')
    manager = fakeManager({
      signinRedirectCallback: vi.fn().mockResolvedValue({ state: { returnTo: '/' } })
    })

    await initAuth({ http: fakeHttp({ memberships: TWO }) })

    expect(window.sessionStorage.getItem(SIGNIN_RETRY_KEY)).toBeNull()
    expect(session.status).toBe('ready')
  })

  it('loads the user and tenants with the bearer token and opens the first tenant', async () => {
    const http = fakeHttp({ memberships: TWO })

    await initAuth({ http })

    const meCall = http.get.mock.calls.find(([url]) => url.endsWith('/me'))
    expect(meCall[1].headers.Authorization).toBe('Bearer secret-access-token')
    expect(session.user).toEqual({ id: 'u-1', email: 'anna@example.com' })
    expect(session.memberships).toHaveLength(2)
    expect(session.activeTenantId).toBe(1)
    expect(session.status).toBe('ready')
  })

  it('reopens the tenant used last time', async () => {
    window.localStorage.setItem(TENANT_KEY, '2')

    await initAuth({ http: fakeHttp({ memberships: TWO }) })

    expect(session.activeTenantId).toBe(2)
  })

  it('shows the "no tenant" state for a user without memberships', async () => {
    await initAuth({ http: fakeHttp({ memberships: [] }) })

    expect(session.status).toBe('no-tenant')
    expect(session.activeTenantId).toBeNull()
  })

  it('keeps the access token out of every browser storage', async () => {
    await initAuth({ http: fakeHttp({ memberships: TWO }) })

    const dump = JSON.stringify({ ...window.localStorage }) + JSON.stringify({ ...window.sessionStorage })
    expect(dump).not.toContain('secret-access-token')
    expect(JSON.stringify(session)).not.toContain('secret-access-token')
  })

  it('configures an in-memory user store and only the sign-in state in sessionStorage', async () => {
    const settings = buildSettings(OIDC, 'https://app.test')

    await settings.userStore.set('user:key', 'the-user-with-the-token')
    await settings.stateStore.set('state:key', 'pkce-verifier')

    expect(window.localStorage.length).toBe(0)
    expect(JSON.stringify({ ...window.sessionStorage })).not.toContain('the-user-with-the-token')
    expect(await settings.userStore.get('user:key')).toBe('the-user-with-the-token')
    expect(settings.response_type).toBe('code')
    expect(settings.redirect_uri).toBe('https://app.test/auth/callback')
    expect(settings.extraQueryParams).toEqual({ audience: 'billery' })
  })
})

describe('API headers', () => {
  it('carry the token and the active tenant', async () => {
    await initAuth({ http: fakeHttp({ memberships: TWO }) })

    expect(await authHeaders()).toEqual({
      Authorization: 'Bearer secret-access-token',
      'X-Tenant-Id': '1'
    })
  })

  it('renews an expired token before the call', async () => {
    await initAuth({ http: fakeHttp({ memberships: TWO }) })
    manager.getUser.mockResolvedValue({ access_token: 'old', expired: true })
    manager.signinSilent.mockResolvedValue({ access_token: 'fresh', expired: false })

    expect((await authHeaders()).Authorization).toBe('Bearer fresh')
  })
})

describe('tenants', () => {
  it('chooses the stored tenant if still valid, otherwise the first', () => {
    expect(chooseTenant(TWO, 2)).toBe(2)
    expect(chooseTenant(TWO, 99)).toBe(1)
    expect(chooseTenant(TWO, null)).toBe(1)
    expect(chooseTenant([], 1)).toBeNull()
  })

  it('switches the tenant, remembers it and reloads the page', async () => {
    await initAuth({ http: fakeHttp({ memberships: TWO }) })

    setActiveTenant(2)

    expect(window.localStorage.getItem(TENANT_KEY)).toBe('2')
    expect(session.activeTenantId).toBe(2)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('opens a tenant that was just created or joined, though it is not in the loaded list', async () => {
    await initAuth({ http: fakeHttp({ memberships: [] }) })

    openTenant(42)

    expect(window.localStorage.getItem(TENANT_KEY)).toBe('42')
    expect(session.activeTenantId).toBe(42)
    expect(reload).toHaveBeenCalledTimes(1)
  })

  it('reads the open invitations of a user without a tenant', async () => {
    const invitations = [{ id: 'i', tenantId: 3, tenantName: 'Drei', role: 'READER' }]
    const http = {
      get: vi.fn(async url =>
        url.endsWith('/config')
          ? SAAS_CONFIG
          : { data: { userId: 'u', email: 'a@b.de', memberships: [], invitations } }
      )
    }

    await initAuth({ http })

    expect(session.status).toBe('no-tenant')
    expect(session.invitations).toEqual(invitations)
  })

  it('ignores a tenant the user does not belong to', async () => {
    await initAuth({ http: fakeHttp({ memberships: TWO }) })

    setActiveTenant(99)

    expect(session.activeTenantId).toBe(1)
    expect(reload).not.toHaveBeenCalled()
  })
})

describe('errors from the backend', () => {
  it('renews silently after a 401', async () => {
    await initAuth({ http: fakeHttp({ memberships: TWO }) })

    expect(await renewAfterUnauthorized()).toBe(true)
    expect(manager.signinRedirect).not.toHaveBeenCalled()
  })

  it('sends the user to sign in again when the silent renewal fails', async () => {
    await initAuth({ http: fakeHttp({ memberships: TWO }) })
    manager.signinSilent.mockRejectedValue(new Error('login_required'))

    renewAfterUnauthorized() // never resolves: the page leaves
    await vi.waitFor(() => expect(manager.signinRedirect).toHaveBeenCalled())
  })

  it('reloads once when a 403 shows that the tenant was lost', async () => {
    const http = fakeHttp({ memberships: TWO })
    await initAuth({ http })
    http.get.mockImplementation(async () => ({
      data: { userId: 'u-1', email: 'anna@example.com', memberships: [TWO[1]] }
    }))
    // handleForbidden uses the default http client; give it the same answers
    const axios = (await import('axios')).default
    const spy = vi.spyOn(axios, 'get').mockImplementation(http.get)

    await handleForbidden()
    await handleForbidden()

    expect(reload).toHaveBeenCalledTimes(1)
    spy.mockRestore()
  })
})

describe('sign out', () => {
  it('forgets the remembered tenant and ends the session at the provider', async () => {
    await initAuth({ http: fakeHttp({ memberships: TWO }) })
    window.localStorage.setItem(TENANT_KEY, '2')

    await signOut()

    expect(window.localStorage.getItem(TENANT_KEY)).toBeNull()
    expect(manager.signoutRedirect).toHaveBeenCalled()
  })
})

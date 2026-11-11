import { AxiosError } from 'axios'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { apiClient } from './baseService.js'
import { configureForTests, initAuth, resetForTests } from './authService.js'

const OIDC = { issuer: 'https://idp.test', clientId: 'spa', scope: 'openid' }
const TWO = [
  { tenantId: 7, tenantName: 'Sieben', role: 'ADMIN' },
  { tenantId: 8, tenantName: 'Acht', role: 'READER' }
]

let manager
let adapter

function http(mode) {
  return {
    get: async url =>
      url.endsWith('/config')
        ? { data: mode === 'saas' ? { mode, oidc: OIDC } : { mode } }
        : { data: { userId: 'u', email: 'a@b.de', memberships: TWO } }
  }
}

function ok(config) {
  return { data: { ok: true }, status: 200, statusText: 'OK', headers: {}, config }
}

function unauthorized(config) {
  const response = { data: { message: 'expired' }, status: 401, statusText: 'Unauthorized', headers: {}, config }
  return Promise.reject(new AxiosError('401', 'ERR_BAD_REQUEST', config, null, response))
}

beforeEach(() => {
  resetForTests()
  window.localStorage.clear()
  manager = {
    getUser: vi.fn().mockResolvedValue({ access_token: 'tok-1', expired: false }),
    signinRedirect: vi.fn().mockResolvedValue(undefined),
    signinSilent: vi.fn().mockResolvedValue({ access_token: 'tok-2', expired: false }),
    signoutRedirect: vi.fn()
  }
  configureForTests({ createManager: () => manager, reload: vi.fn() })
  adapter = vi.fn(async config => ok(config))
  apiClient.defaults.adapter = adapter
})

describe('apiClient in self-host mode', () => {
  it('adds no authorization and no tenant', async () => {
    await initAuth({ http: http('selfhost') })

    await apiClient.get('/customers')

    const headers = adapter.mock.calls[0][0].headers
    expect(headers.get('Authorization')).toBeFalsy()
    expect(headers.get('X-Tenant-Id')).toBeFalsy()
  })
})

describe('apiClient in SaaS mode', () => {
  beforeEach(async () => {
    await initAuth({ http: http('saas') })
  })

  it('sends the bearer token and the active tenant with every call', async () => {
    await apiClient.get('/customers')

    const headers = adapter.mock.calls[0][0].headers
    expect(headers.get('Authorization')).toBe('Bearer tok-1')
    expect(headers.get('X-Tenant-Id')).toBe('7')
  })

  it('renews after a 401 and repeats the call once with the new token', async () => {
    adapter.mockImplementationOnce(config => unauthorized(config))
    manager.getUser.mockResolvedValue({ access_token: 'tok-2', expired: false })

    const response = await apiClient.get('/customers')

    expect(response.status).toBe(200)
    expect(adapter).toHaveBeenCalledTimes(2)
    expect(adapter.mock.calls[1][0].headers.get('Authorization')).toBe('Bearer tok-2')
    expect(manager.signinSilent).toHaveBeenCalledTimes(1)
  })

  it('does not loop: a second 401 after the renewal is reported', async () => {
    adapter.mockImplementation(config => unauthorized(config))

    await expect(apiClient.get('/customers')).rejects.toMatchObject({ response: { status: 401 } })

    expect(adapter).toHaveBeenCalledTimes(2)
    expect(manager.signinSilent).toHaveBeenCalledTimes(1)
  })

  it('signs in again when the renewal is impossible', async () => {
    adapter.mockImplementation(config => unauthorized(config))
    manager.signinSilent.mockRejectedValue(new Error('login_required'))

    apiClient.get('/customers').catch(() => {}) // the page leaves; the call never settles
    await vi.waitFor(() => expect(manager.signinRedirect).toHaveBeenCalled())
  })
})

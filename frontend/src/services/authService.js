import axios from 'axios'
import { InMemoryWebStorage, UserManager, WebStorageStateStore } from 'oidc-client-ts'
import { apiBaseUrl } from './apiBase.js'
import { isSaas, session } from './session.js'

/** Where the identity provider sends the browser back to after the sign-in. */
export const CALLBACK_PATH = '/auth/callback'

/** localStorage key of the last tenant the user worked in (an id, not a secret). */
export const TENANT_KEY = 'billery.tenant'

/**
 * sessionStorage flag: a failed redirect callback has already started one fresh sign-in, so a
 * second failure is reported instead of redirecting in a loop.
 */
export const SIGNIN_RETRY_KEY = 'billery.signinRetry'

let manager = null
let forbiddenHandled = false

/** Replaced in tests. */
let createManager = settings => new UserManager(settings)
let reloadPage = () => window.location.reload()
let inFrame = isInFrame

/**
 * Whether the page runs inside a frame. The silent token renewal (`signinSilent`) loads the
 * callback URL in a hidden iframe; a cross-origin parent makes `window.top` unreadable, which
 * also means: framed.
 * @returns {boolean}
 */
function isInFrame() {
  try {
    return window.self !== window.top
  } catch {
    return true
  }
}

/**
 * Test hook: swap the OIDC client factory, the page reload and the frame detection.
 * @param {{createManager?: Function, reload?: Function, inFrame?: Function}} overrides
 */
export function configureForTests(overrides = {}) {
  if (overrides.createManager) createManager = overrides.createManager
  if (overrides.reload) reloadPage = overrides.reload
  if (overrides.inFrame) inFrame = overrides.inFrame
}

/** Test hook: forget the manager and the session. */
export function resetForTests() {
  manager = null
  forbiddenHandled = false
  session.mode = 'selfhost'
  session.status = 'loading'
  session.user = null
  session.memberships = []
  session.invitations = []
  session.activeTenantId = null
  createManager = settings => new UserManager(settings)
  reloadPage = () => window.location.reload()
  inFrame = isInFrame
}

/**
 * The settings of the OIDC client: authorization-code flow with PKCE. The user (and with it the
 * access token) is kept **in memory** only; the browser storage holds just the transient sign-in
 * state (the PKCE verifier), which lives for the duration of the redirect. After a page reload the
 * app signs in again, which the identity provider's own session answers without a prompt.
 * @param {{issuer: string, clientId: string, scope: string, audience?: string}} oidc
 * @param {string} origin - the page's origin
 * @returns {object} - `UserManager` settings
 */
export function buildSettings(oidc, origin = window.location.origin) {
  return {
    authority: oidc.issuer,
    client_id: oidc.clientId,
    redirect_uri: `${origin}${CALLBACK_PATH}`,
    post_logout_redirect_uri: `${origin}/`,
    response_type: 'code',
    scope: oidc.scope,
    extraQueryParams: oidc.audience ? { audience: oidc.audience } : undefined,
    userStore: new WebStorageStateStore({ store: new InMemoryWebStorage() }),
    stateStore: new WebStorageStateStore({ store: window.sessionStorage }),
    automaticSilentRenew: true
  }
}

/** Waits forever: used after a redirect to the identity provider, the page is going away. */
function neverResolves() {
  return new Promise(() => {})
}

/** Sends the browser to the identity provider, remembering where the user was. */
async function signIn() {
  const returnTo = `${window.location.pathname}${window.location.search}${window.location.hash}`
  await manager.signinRedirect({ state: { returnTo } })
}

/**
 * Starts the app: asks the backend how it is operated; in SaaS mode completes a sign-in redirect
 * or starts one, then loads the user's tenants and picks the active one.
 * Resolves when the app may be mounted. In SaaS mode without a session it navigates to the
 * identity provider and never resolves.
 * @param {{http?: {get: Function}}} deps
 * @returns {Promise<object>} - the {@link session}
 */
export async function initAuth({ http = axios } = {}) {
  const { data: config } = await http.get(`${apiBaseUrl()}/config`)
  session.mode = config.mode === 'saas' ? 'saas' : 'selfhost'
  if (!isSaas()) {
    session.status = 'ready'
    return session
  }

  manager = createManager(buildSettings(config.oidc))
  if (window.location.pathname === CALLBACK_PATH) {
    if (inFrame()) {
      // The hidden iframe of the silent renewal (the callback URL is also the silent redirect URI):
      // hand the response to the window that started the renewal and do not start the app here.
      // Treating it as a redirect sign-in consumed the renewal's state ("No matching state found").
      await manager.signinSilentCallback()
      return neverResolves()
    }
    const user = await completeRedirect()
    if (!user) return neverResolves()
    window.history.replaceState(null, '', user.state?.returnTo || '/')
  }
  const user = await manager.getUser()
  if (!user || user.expired) {
    await signIn()
    return neverResolves()
  }
  await loadMemberships(http)
  return session
}

/**
 * Completes the redirect back from the identity provider. A callback whose sign-in state is gone
 * (the callback URL reloaded or restored, the sign-in started in another tab) cannot be completed;
 * instead of showing an error forever on that URL, one fresh sign-in is started. A second failure
 * in a row is reported.
 * @returns {Promise<object|null>} - the user, or null when the browser is being redirected
 */
async function completeRedirect() {
  try {
    const user = await manager.signinRedirectCallback()
    forgetRetry()
    return user
  } catch (error) {
    if (readRetry()) {
      forgetRetry()
      throw error
    }
    rememberRetry()
    window.history.replaceState(null, '', '/')
    await signIn()
    return null
  }
}

function readRetry() {
  try {
    return window.sessionStorage.getItem(SIGNIN_RETRY_KEY) != null
  } catch {
    return true // without storage a retry could loop: report the error instead
  }
}

function rememberRetry() {
  try {
    window.sessionStorage.setItem(SIGNIN_RETRY_KEY, '1')
  } catch {
    // readRetry reports the next failure anyway
  }
}

function forgetRetry() {
  try {
    window.sessionStorage.removeItem(SIGNIN_RETRY_KEY)
  } catch {
    // nothing stored
  }
}

/** Reads `/api/me` and chooses the active tenant. */
async function loadMemberships(http = axios) {
  const { data: me } = await http.get(`${apiBaseUrl()}/me`, { headers: await authorizationOnly() })
  session.user = { id: me.userId, email: me.email }
  session.memberships = me.memberships
  session.invitations = me.invitations || []
  session.activeTenantId = chooseTenant(me.memberships, readStoredTenant())
  session.status = session.activeTenantId == null ? 'no-tenant' : 'ready'
}

/**
 * The tenant to work in: the one used last time if the user still belongs to it, otherwise the
 * first (the only one for most users).
 * @param {Array<{tenantId: number}>} memberships
 * @param {number|null} stored
 * @returns {number|null}
 */
export function chooseTenant(memberships, stored) {
  if (memberships.length === 0) return null
  if (stored != null && memberships.some(m => m.tenantId === stored)) return stored
  return memberships[0].tenantId
}

function readStoredTenant() {
  try {
    const value = window.localStorage.getItem(TENANT_KEY)
    return value == null ? null : Number(value)
  } catch {
    return null
  }
}

/**
 * Switches the tenant and reloads the page so that nothing from the previous tenant stays on
 * screen.
 * @param {number} tenantId
 */
export function setActiveTenant(tenantId) {
  if (!session.memberships.some(m => m.tenantId === tenantId)) return
  openTenant(tenantId)
}

/**
 * Makes a tenant the one to work in and reloads the page. Unlike {@link setActiveTenant} the
 * tenant need not be in the loaded memberships: it was just created or joined.
 * @param {number} tenantId
 */
export function openTenant(tenantId) {
  try {
    window.localStorage.setItem(TENANT_KEY, String(tenantId))
  } catch {
    // The choice is only a convenience; without storage the first tenant is used again.
  }
  session.activeTenantId = tenantId
  reloadPage()
}

async function authorizationOnly() {
  const token = await accessToken()
  return token ? { Authorization: `Bearer ${token}` } : {}
}

async function accessToken() {
  let user = await manager.getUser()
  if (!user || user.expired) {
    user = await manager.signinSilent()
  }
  return user?.access_token
}

/**
 * Headers every API call needs: the bearer token and the tenant. Empty for a self-hosted install.
 * @returns {Promise<object>}
 */
export async function authHeaders() {
  if (!isSaas() || !manager) return {}
  const headers = await authorizationOnly()
  if (session.activeTenantId != null) {
    headers['X-Tenant-Id'] = String(session.activeTenantId)
  }
  return headers
}

/**
 * The backend answered 401: the token is expired or was rejected. Renews it silently; when that
 * is not possible the user is sent to sign in again.
 * @returns {Promise<boolean>} - true if there is a fresh token and the request may be repeated;
 *   never resolves when the browser is being redirected
 */
export async function renewAfterUnauthorized() {
  try {
    const user = await manager.signinSilent()
    if (user && !user.expired) return true
  } catch {
    // fall through to a fresh sign-in
  }
  await signIn()
  return neverResolves()
}

/**
 * The backend answered 403 on a tenant request. If the user has meanwhile lost the tenant, forget
 * the stored choice and reload so that another tenant (or the "no access" notice) is shown. Once
 * per page load, so that a persistent 403 cannot cause a reload loop.
 */
export async function handleForbidden() {
  if (forbiddenHandled || !isSaas()) return
  forbiddenHandled = true
  const previous = session.activeTenantId
  await loadMemberships()
  // loadMemberships picks an active tenant again; what matters is whether the one that was
  // refused is still the user's.
  if (!session.memberships.some(m => m.tenantId === previous)) {
    reloadPage()
  }
}

/** Ends the session at the identity provider. */
export async function signOut() {
  try {
    window.localStorage.removeItem(TENANT_KEY)
  } catch {
    // nothing to forget
  }
  await manager.signoutRedirect()
}

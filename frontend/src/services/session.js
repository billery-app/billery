import { reactive } from 'vue'

/**
 * What the app knows about how it is operated and who is using it.
 *
 * - `mode`: `selfhost` (no sign-in, the default) or `saas` (OIDC sign-in, several tenants)
 * - `desktop`: the desktop app (Tauri, H2 file database): shows the backup settings
 * - `status`: `loading` while the sign-in runs, `ready` when the app may show its pages,
 *   `no-tenant` when the user is signed in but belongs to no tenant yet
 * - `user`: `{ id, email }` of the signed-in user (SaaS only)
 * - `memberships`: `[{ tenantId, tenantName, role }]` (SaaS only)
 * - `invitations`: open invitations addressed to the user, `[{ id, tenantId, tenantName, role }]`
 * - `activeTenantId`: the tenant every API call acts in (SaaS only)
 *
 * Only identifying data lives here, never a token: the access token stays inside the OIDC client,
 * in memory.
 */
export const session = reactive({
  mode: 'selfhost',
  desktop: false,
  status: 'loading',
  user: null,
  memberships: [],
  invitations: [],
  activeTenantId: null
})

/**
 * The signed-in user's role in the active tenant (SaaS), `null` otherwise.
 * @returns {string|null} - OWNER, ADMIN, ACCOUNTANT or READER
 */
export function activeRole() {
  const membership = session.memberships.find(m => m.tenantId === session.activeTenantId)
  return membership ? membership.role : null
}

/** Convenience for templates and guards. */
export function isSaas() {
  return session.mode === 'saas'
}

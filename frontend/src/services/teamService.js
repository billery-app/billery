import { apiClient } from './baseService.js'

/**
 * Sign-up, members, invitations and closing of a tenant (SaaS mode). The acting tenant is the one
 * the shared `apiClient` names in `X-Tenant-Id`; sign-up and accepting an invitation need none.
 */
export default {
  /**
   * Creates a tenant with the signed-in user as its owner.
   * @param {string} name - The company's name
   * @returns {Promise<{tenantId: number}>}
   */
  async createTenant(name) {
    return (await apiClient.post('/tenants', { name })).data
  },

  /**
   * Accepts an invitation addressed to the signed-in user's verified address.
   * @param {string} id - The invitation
   * @returns {Promise<{tenantId: number}>} - The tenant that was joined
   */
  async acceptInvitation(id) {
    return (await apiClient.post(`/invitations/${id}/accept`)).data
  },

  /** @returns {Promise<Array<{userId: string, email: string, displayName: string|null, role: string}>>} */
  async members() {
    return (await apiClient.get('/tenant/members')).data
  },

  /**
   * @param {string} userId
   * @param {string} role - OWNER, ADMIN, ACCOUNTANT or READER
   */
  changeRole(userId, role) {
    return apiClient.put(`/tenant/members/${userId}`, { role })
  },

  /** Removes a member; with the user's own id it leaves the tenant. */
  removeMember(userId) {
    return apiClient.delete(`/tenant/members/${userId}`)
  },

  /** @returns {Promise<Array<{id: string, email: string, role: string, expiresAt: string}>>} */
  /**
   * The administration log of the active tenant, newest first (owners and administrators).
   * @returns {Promise<Array<{id: number, occurredAt: string, action: string, details: ?string, actor: ?string}>>}
   */
  async auditLog() {
    return (await apiClient.get('/tenant/audit')).data
  },

  async invitations() {
    return (await apiClient.get('/tenant/invitations')).data
  },

  /**
   * @param {string} email
   * @param {string} role
   */
  async invite(email, role) {
    return (await apiClient.post('/tenant/invitations', { email, role })).data
  },

  /** @param {string} id - The invitation to withdraw */
  revokeInvitation(id) {
    return apiClient.delete(`/tenant/invitations/${id}`)
  },

  /** Closes the active tenant (owner only): it keeps its data but becomes read-only. */
  closeTenant() {
    return apiClient.post('/tenant/close')
  }
}

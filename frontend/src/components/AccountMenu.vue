<template>
  <div class="account-menu" role="group" aria-label="Konto und Mandant">
    <label v-if="session.memberships.length > 1" class="tenant-select">
      <span class="visually-hidden">Mandant</span>
      <select :value="session.activeTenantId" aria-label="Mandant wechseln" @change="switchTenant">
        <option v-for="m in session.memberships" :key="m.tenantId" :value="m.tenantId">
          {{ m.tenantName }}
        </option>
      </select>
    </label>
    <details v-if="session.invitations.length" class="invitations" data-testid="account-invitations">
      <summary>
        Einladungen <span class="count">{{ session.invitations.length }}</span>
      </summary>
      <ul>
        <li v-for="invitation in session.invitations" :key="invitation.id">
          <span><strong>{{ invitation.tenantName }}</strong> – {{ roleLabel(invitation.role) }}</span>
          <button type="button" class="btn btn-primary btn-small" :disabled="busy" @click="accept(invitation)">Annehmen</button>
        </li>
      </ul>
      <p v-if="error" class="error" role="alert">{{ error }}</p>
    </details>
    <button type="button" class="sign-out" @click="signOut">Abmelden</button>
  </div>
</template>

<script>
import { session } from '../services/session.js'
import { openTenant, setActiveTenant, signOut } from '../services/authService.js'
import teamService from '../services/teamService.js'
import { roleLabel } from '../utils/roles.js'

/**
 * The SaaS part of the navbar: only what does something (switch the company when there is more
 * than one, accept invitations, sign out). Who is signed in and in which company is shown in
 * Settings → Konto (AccountSettings).
 */
export default {
  name: 'AccountMenu',
  data() {
    return { session, busy: false, error: null }
  },
  methods: {
    roleLabel,
    /**
     * Accepts an invitation of a user who already works in a company and opens the joined one
     * (users without a company accept in NoTenantNotice).
     */
    async accept(invitation) {
      this.busy = true
      this.error = null
      try {
        openTenant((await teamService.acceptInvitation(invitation.id)).tenantId)
      } catch (error) {
        this.error = error.message || 'Das ging leider schief.'
        this.busy = false
      }
    },
    switchTenant(event) {
      setActiveTenant(Number(event.target.value))
    },
    signOut() {
      return signOut()
    }
  }
}
</script>

<style scoped>
.account-menu { display: flex; align-items: center; gap: 10px; min-width: 0; font-size: 14px; }
.tenant-select select {
  background: var(--color-nav-hover);
  color: var(--color-on-nav);
  border: 1px solid var(--color-nav-border);
  border-radius: 6px;
  padding: 6px 8px;
  font-size: 14px;
  max-width: 14em;
}
.tenant-select select option { color: var(--color-text-strong); }
.sign-out {
  background: transparent;
  color: var(--color-on-nav);
  border: 1px solid var(--color-nav-border-strong);
  border-radius: 6px;
  padding: 6px 10px;
  font-size: 14px;
  cursor: pointer;
}
.sign-out:hover { background: var(--color-nav-hover-strong); }
.invitations { position: relative; }
.invitations summary {
  list-style: none;
  cursor: pointer;
  color: var(--color-on-nav);
  border: 1px solid var(--color-nav-border-strong);
  border-radius: 6px;
  padding: 6px 10px;
  white-space: nowrap;
}
.invitations summary::-webkit-details-marker { display: none; }
.invitations .count {
  display: inline-block;
  min-width: 1.4em;
  padding: 0 5px;
  border-radius: 999px;
  background: var(--color-warning);
  color: var(--color-text-strong);
  font-weight: 600;
  text-align: center;
}
.invitations ul {
  position: absolute;
  right: 0;
  top: calc(100% + 6px);
  z-index: var(--z-sticky);
  min-width: 18em;
  margin: 0;
  padding: var(--space-2);
  list-style: none;
  background: var(--color-surface);
  color: var(--color-text);
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  box-shadow: var(--shadow-lg);
}
.invitations li { display: flex; align-items: center; justify-content: space-between; gap: var(--space-3); padding: var(--space-2); }
.invitations .error { color: var(--color-danger); margin: var(--space-1) 0 0; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
</style>

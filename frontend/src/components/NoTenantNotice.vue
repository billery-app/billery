<template>
  <section class="no-tenant" aria-labelledby="no-tenant-title">
    <h1 id="no-tenant-title">Willkommen bei Billery</h1>
    <p class="lead">
      Sie sind angemeldet<span v-if="session.user"> als <strong>{{ session.user.email }}</strong></span>,
      gehören aber noch zu keinem Unternehmen.
    </p>

    <div v-if="session.invitations.length" class="card panel" data-testid="invitations">
      <h2>Einladungen</h2>
      <ul>
        <li v-for="invitation in session.invitations" :key="invitation.id">
          <span><strong>{{ invitation.tenantName }}</strong> <span class="badge badge-neutral">{{ roleLabel(invitation.role) }}</span></span>
          <button type="button" class="btn btn-primary btn-small" :disabled="busy" @click="accept(invitation)">Annehmen</button>
        </li>
      </ul>
    </div>

    <form class="card panel" data-testid="create-tenant" @submit.prevent="create">
      <h2>Unternehmen anlegen</h2>
      <div class="form-group">
        <label for="tenant-name">Name des Unternehmens</label>
        <input id="tenant-name" v-model="name" class="form-control" type="text" maxlength="255" autocomplete="organization" />
      </div>
      <button type="submit" class="btn btn-primary" :disabled="busy || !name.trim()">Anlegen</button>
    </form>

    <p v-if="error" class="notice-error" role="alert">{{ error }}</p>
    <button type="button" class="btn btn-secondary link" @click="signOut">Abmelden</button>
  </section>
</template>

<script>
import { session } from '../services/session.js'
import { openTenant, signOut } from '../services/authService.js'
import teamService from '../services/teamService.js'
import { roleLabel } from '../utils/roles.js'

export default {
  name: 'NoTenantNotice',
  data() {
    return { session, name: '', busy: false, error: null }
  },
  methods: {
    roleLabel,
    async create() {
      await this.run(async () => openTenant((await teamService.createTenant(this.name.trim())).tenantId))
    },
    async accept(invitation) {
      await this.run(async () => openTenant((await teamService.acceptInvitation(invitation.id)).tenantId))
    },
    async run(action) {
      this.busy = true
      this.error = null
      try {
        await action()
      } catch (error) {
        this.error = error.message || 'Das ging leider schief.'
        this.busy = false
      }
    },
    signOut() {
      return signOut()
    }
  }
}
</script>

<style scoped>
.no-tenant { max-width: 40em; margin: 6vh auto; padding: 0 var(--space-5); line-height: 1.5; display: flex; flex-direction: column; gap: var(--space-4); }
.no-tenant h1 { margin: 0; font-size: var(--font-size-2xl); color: var(--color-text); }
.lead { margin: 0; color: var(--color-text-muted); }
.panel { display: flex; flex-direction: column; gap: var(--space-3); }
.panel h2 { margin: 0; font-size: var(--font-size-lg); color: var(--color-text); }
.panel ul { list-style: none; margin: 0; padding: 0; }
.panel li { display: flex; justify-content: space-between; align-items: center; gap: var(--space-3); padding: var(--space-2) 0; border-top: 1px solid var(--color-border); }
.panel li:first-child { border-top: none; }
.form-group { display: flex; flex-direction: column; gap: var(--space-1); }
.form-group label { font-weight: var(--font-weight-medium); color: var(--color-text); }
.panel > .btn { align-self: flex-start; }
.notice-error { margin: 0; padding: var(--space-2) var(--space-3); border-radius: var(--radius-md); background: var(--color-danger-soft); color: var(--color-danger-hover); }
.link { align-self: flex-start; }
</style>

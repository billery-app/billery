<template>
  <section class="settings-section team" data-testid="team-settings">
    <h2 class="section-title">Team &amp; Zugriff</h2>

    <div class="card team-card">
      <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="notice" :class="notice.ok ? 'notice-success' : 'notice-warning'" role="status">{{ notice.text }}</p>

      <h3 class="team-heading">Mitglieder</h3>
      <div class="table-scroll">
        <table class="data-table" aria-label="Mitglieder">
          <thead>
            <tr><th>E-Mail</th><th>Name</th><th>Rolle</th><th class="actions"><span class="visually-hidden">Aktionen</span></th></tr>
          </thead>
          <tbody>
            <tr v-for="member in members" :key="member.userId" :data-testid="`member-${member.email}`">
              <td>{{ member.email }}</td>
              <td>{{ member.displayName || '–' }}</td>
              <td class="role-cell">
                <select
                  v-if="canManage(member.role)"
                  class="form-control"
                  :value="member.role"
                  :aria-label="`Rolle von ${member.email}`"
                  @change="changeRole(member, $event.target.value)"
                >
                  <option v-for="role in roles" :key="role" :value="role">{{ roleLabel(role) }}</option>
                </select>
                <span v-else class="badge badge-neutral">{{ roleLabel(member.role) }}</span>
              </td>
              <td class="actions">
                <button v-if="isSelf(member)" type="button" class="btn btn-secondary btn-small" @click="remove(member)">Verlassen</button>
                <button v-else-if="canManage(member.role)" type="button" class="btn btn-outline btn-small" @click="remove(member)">Entfernen</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>

      <h3 class="team-heading">Einladungen</h3>
      <ul v-if="invitations.length" class="invitations">
        <li v-for="invitation in invitations" :key="invitation.id">
          <span class="invitation-text">
            <strong>{{ invitation.email }}</strong>
            <span class="badge badge-neutral">{{ roleLabel(invitation.role) }}</span>
            <span class="muted">gültig bis {{ formatDate(invitation.expiresAt) }}</span>
          </span>
          <button v-if="canManage(invitation.role)" type="button" class="btn btn-outline btn-small" @click="revoke(invitation)">Zurückziehen</button>
        </li>
      </ul>
      <p v-else class="muted">Keine offenen Einladungen.</p>

      <form class="invite" data-testid="invite-form" @submit.prevent="invite">
        <div class="form-group invite-email">
          <label for="invite-email">E-Mail-Adresse</label>
          <input id="invite-email" v-model="email" class="form-control" type="email" autocomplete="off" placeholder="name@firma.de" />
        </div>
        <div class="form-group invite-role">
          <label for="invite-role">Rolle</label>
          <select id="invite-role" v-model="role" class="form-control" aria-label="Rolle der Einladung">
            <option v-for="option in roles" :key="option" :value="option">{{ roleLabel(option) }}</option>
          </select>
        </div>
        <button type="submit" class="btn btn-primary" :disabled="!email.trim() || inviting">
          {{ inviting ? 'Lade ein…' : 'Einladen' }}
        </button>
      </form>
      <p class="muted">
        Die Person bekommt eine E-Mail, wenn der Mailversand eingerichtet ist. Sie meldet sich mit genau
        dieser Adresse an und nimmt die Einladung danach an.
      </p>

      <h3 class="team-heading">Protokoll</h3>
      <ol v-if="auditLog.length" class="audit-log" aria-label="Protokoll" data-testid="audit-log">
        <li v-for="entry in auditLog" :key="entry.id" class="audit-entry">
          <time class="muted" :datetime="entry.occurredAt">{{ formatDateTime(entry.occurredAt) }}</time>
          <span class="audit-action">{{ auditLabel(entry.action) }}</span>
          <span v-if="entry.details" class="audit-details">{{ auditDetails(entry.details) }}</span>
          <span v-if="entry.actor" class="muted audit-actor">{{ entry.actor }}</span>
        </li>
      </ol>
      <p v-else class="muted">Noch keine Einträge.</p>
    </div>

    <div v-if="activeRole === 'OWNER'" class="card danger-zone">
      <h3 class="team-heading">Unternehmen schließen</h3>
      <p class="muted">
        Alle Daten bleiben erhalten, das Unternehmen ist danach aber schreibgeschützt (Export bleibt
        möglich). Das Wiedereröffnen ist nur durch den Betreiber möglich.
      </p>
      <button type="button" class="btn btn-danger danger-button" @click="close">Unternehmen schließen</button>
    </div>
  </section>
</template>

<script>
import { activeRole, session } from '../services/session.js'
import teamService from '../services/teamService.js'
import { manageableRoles, roleLabel } from '../utils/roles.js'

/** German names of the actions of the administration log (`TenantAuditLog`). */
const AUDIT_LABELS = {
  TENANT_CREATED: 'Unternehmen angelegt',
  TENANT_CLOSED: 'Unternehmen geschlossen',
  MEMBER_INVITED: 'Eingeladen',
  INVITATION_REVOKED: 'Einladung zurückgezogen',
  INVITATION_ACCEPTED: 'Einladung angenommen',
  ROLE_CHANGED: 'Rolle geändert',
  MEMBER_REMOVED: 'Mitglied entfernt',
  MEMBER_LEFT: 'Unternehmen verlassen'
}

export default {
  name: 'TeamSettings',
  data() {
    return { session, members: [], invitations: [], auditLog: [], email: '', role: 'READER', error: null, notice: null, inviting: false }
  },
  computed: {
    activeRole() {
      return activeRole()
    },
    roles() {
      return manageableRoles(this.activeRole)
    }
  },
  async mounted() {
    this.role = this.roles.includes('READER') ? 'READER' : this.roles[0]
    await this.load()
  },
  methods: {
    roleLabel,
    /** "19.11.2026" for an ISO timestamp. */
    formatDate(value) {
      return value ? new Date(value).toLocaleDateString('de-DE') : ''
    },
    /** "19.11.2026, 14:05" for an ISO timestamp. */
    formatDateTime(value) {
      return value
        ? new Date(value).toLocaleString('de-DE', { dateStyle: 'medium', timeStyle: 'short' })
        : ''
    },
    /**
     * The German name of an action of the administration log; an unknown one never shows its key.
     * @param {string} action - `tenant_audit.action`
     * @returns {string} - the label
     */
    auditLabel(action) {
      return AUDIT_LABELS[action] || 'Sonstige Änderung'
    },
    /**
     * The details as stored, with role keys shown by their German names.
     * @param {string} details - e.g. `tom@example.com: READER → ACCOUNTANT`
     * @returns {string} - e.g. `tom@example.com: Nur lesen → Buchhaltung / Steuerberatung`
     */
    auditDetails(details) {
      return details.replace(/\b(OWNER|ADMIN|ACCOUNTANT|READER)\b/g, role => roleLabel(role))
    },
    isSelf(member) {
      return this.session.user && member.userId === this.session.user.id
    },
    canManage(role) {
      return this.roles.includes(role)
    },
    async load() {
      await this.attempt(async () => {
        this.members = await teamService.members()
        this.invitations = await teamService.invitations()
        this.auditLog = await teamService.auditLog()
      })
    },
    async changeRole(member, role) {
      this.error = null
      await this.attempt(() => teamService.changeRole(member.userId, role))
      await this.load()
    },
    async remove(member) {
      this.error = null
      const self = this.isSelf(member)
      if (!window.confirm(self ? 'Dieses Unternehmen wirklich verlassen?' : `${member.email} wirklich entfernen?`)) return
      const done = await this.attempt(() => teamService.removeMember(member.userId))
      if (done && self) {
        window.location.reload()
        return
      }
      await this.load()
    },
    /** Invites the address; the person is told outside the app (no mail is sent). */
    async invite() {
      this.error = null
      this.notice = null
      this.inviting = true
      const address = this.email.trim()
      let result = null
      const done = await this.attempt(async () => {
        result = await teamService.invite(address, this.role)
      })
      this.inviting = false
      if (done) {
        this.email = ''
        this.notice = this.inviteNotice(address, result)
      }
      await this.load()
    },
    async revoke(invitation) {
      this.error = null
      await this.attempt(() => teamService.revokeInvitation(invitation.id))
      await this.load()
    },
    async close() {
      this.error = null
      if (!window.confirm('Das Unternehmen wirklich schließen? Danach sind keine Änderungen mehr möglich.')) return
      const done = await this.attempt(() => teamService.closeTenant())
      if (done) window.location.reload()
    },
    /**
     * The confirmation after inviting: whether the mail went out, and if not, how to tell the
     * person instead (the invitation exists either way).
     * @param {string} address - the invited address
     * @param {{mailSent: boolean, mailProblem: ?string}} result - the backend's answer
     * @returns {{ok: boolean, text: string}} - the notice to show
     */
    inviteNotice(address, result) {
      if (result.mailSent) {
        return { ok: true, text: `Einladung an ${address} verschickt.` }
      }
      const reason = result.mailProblem ? ` (${result.mailProblem.replace(/\.$/, '')})` : ''
      return {
        ok: false,
        text: `Einladung für ${address} angelegt, aber keine E-Mail verschickt${reason}. `
          + `Bitte die Person selbst informieren: Anmeldung unter ${window.location.origin}`
          + ' mit genau dieser Adresse.'
      }
    },

    /**
     * Runs a call and shows the backend's message when it fails; true if it worked. The message
     * stays until the next action, also across the reload of the lists that follows.
     */
    async attempt(action) {
      try {
        await action()
        return true
      } catch (error) {
        this.error = error.message || 'Das ging leider schief.'
        return false
      }
    }
  }
}
</script>

<style scoped>
.team-card { display: flex; flex-direction: column; gap: var(--space-3); }
.team-heading { margin: var(--space-2) 0 0; font-size: 1rem; color: var(--color-text); }
.team-heading:first-of-type { margin-top: 0; }
.data-table td { vertical-align: middle; }
.role-cell .form-control { width: auto; min-width: 14em; }
.actions { text-align: right; white-space: nowrap; width: 1%; }
.invitations { list-style: none; margin: 0; padding: 0; }
.invitations li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--color-border);
}
.invitation-text { display: flex; flex-wrap: wrap; align-items: center; gap: var(--space-2); }
.invite { display: flex; flex-wrap: wrap; gap: var(--space-3); align-items: flex-end; margin-top: var(--space-2); }
.invite .form-group { display: flex; flex-direction: column; gap: var(--space-1); margin: 0; }
.invite label { font-weight: var(--font-weight-medium); color: var(--color-text); }
.invite-email { flex: 1 1 18em; }
.invite-role { flex: 0 1 16em; }
.muted { color: var(--color-text-muted); font-size: 0.92em; margin: 0; }
.notice { margin: 0; padding: var(--space-2) var(--space-3); border-radius: var(--radius-md); }
.notice-error { background: var(--color-danger-soft); color: var(--color-danger-hover); }
.notice-success { background: var(--color-success-soft); color: var(--color-success-hover); }
.notice-warning { background: var(--color-warning-soft); color: var(--color-warning-text); }
.audit-log { list-style: none; margin: 0; padding: 0; font-size: 0.92em; }
.audit-entry {
  display: grid;
  grid-template-columns: 9.5em 12em minmax(0, 1fr) auto;
  gap: var(--space-1) var(--space-3);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--color-border);
}
.audit-action { font-weight: var(--font-weight-medium); color: var(--color-text); }
.audit-details { overflow-wrap: anywhere; }
@media (max-width: 760px) {
  .audit-entry { grid-template-columns: minmax(0, 1fr); gap: 0; }
}
.danger-zone { margin-top: var(--space-4); display: flex; flex-direction: column; gap: var(--space-3); align-items: flex-start; }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 760px) {
  .role-cell .form-control { min-width: 0; width: 100%; }
  .invite > .btn { width: 100%; }
}
</style>

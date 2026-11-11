<template>
  <section class="settings-section" data-testid="account-settings">
    <h2 class="section-title">Konto</h2>
    <div class="card account-card">
      <dl class="account-facts">
        <dt>Angemeldet als</dt>
        <dd data-testid="account-email">{{ session.user ? session.user.email : '–' }}</dd>
        <dt>Unternehmen</dt>
        <dd data-testid="account-company">{{ active ? active.tenantName : '–' }}</dd>
        <dt>Ihre Rolle</dt>
        <dd><span v-if="active" class="badge badge-neutral">{{ roleLabel(active.role) }}</span></dd>
      </dl>
      <div v-if="others.length" class="other-companies">
        <h3 class="account-heading">Weitere Unternehmen</h3>
        <ul>
          <li v-for="membership in others" :key="membership.tenantId">
            <span>{{ membership.tenantName }} <span class="badge badge-neutral">{{ roleLabel(membership.role) }}</span></span>
            <button type="button" class="btn btn-secondary btn-small" @click="switchTo(membership.tenantId)">Wechseln</button>
          </li>
        </ul>
      </div>
    </div>
  </section>
</template>

<script>
import { session } from '../services/session.js'
import { setActiveTenant } from '../services/authService.js'
import { roleLabel } from '../utils/roles.js'

/**
 * Who is signed in, in which company and with which role (SaaS). This used to sit in the navbar,
 * where it took space without doing anything; the navbar keeps only actions (AccountMenu).
 */
export default {
  name: 'AccountSettings',
  data() {
    return { session }
  },
  computed: {
    active() {
      return this.session.memberships.find(m => m.tenantId === this.session.activeTenantId)
    },
    others() {
      return this.session.memberships.filter(m => m.tenantId !== this.session.activeTenantId)
    }
  },
  methods: {
    roleLabel,
    switchTo(tenantId) {
      setActiveTenant(tenantId)
    }
  }
}
</script>

<style scoped>
.account-card { display: flex; flex-direction: column; gap: var(--space-4); }
.account-facts {
  display: grid;
  grid-template-columns: max-content 1fr;
  gap: var(--space-2) var(--space-5);
  margin: 0;
}
.account-facts dt { color: var(--color-text-muted); }
.account-facts dd { margin: 0; color: var(--color-text); overflow-wrap: anywhere; }
.account-heading { margin: 0 0 var(--space-2); font-size: 1rem; color: var(--color-text); }
.other-companies ul { list-style: none; margin: 0; padding: 0; }
.other-companies li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-3);
  padding: var(--space-2) 0;
  border-top: 1px solid var(--color-border);
}
@media (max-width: 760px) {
  .account-facts { grid-template-columns: 1fr; gap: 0 0; }
  .account-facts dd { margin-bottom: var(--space-2); }
}
</style>

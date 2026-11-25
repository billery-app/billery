<template>
  <section class="settings-section mail" data-testid="mail-settings">
    <div class="card mail-card">
      <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>
      <p v-if="notice" class="notice notice-success" role="status">{{ notice }}</p>
      <p v-if="demo" class="notice notice-warning" data-testid="mail-demo">
        In der Demo ist der Mailversand abgeschaltet: Mailkonten können nicht angelegt werden. Die
        Vorlagen für Ihre Mails können Sie trotzdem ausprobieren.
      </p>
      <p v-if="loaded && !passwordsStorable" class="notice notice-warning" data-testid="no-secrets-key">
        Passwörter können hier nicht gespeichert werden: Der Betreiber hat keinen Schlüssel dafür
        eingerichtet (<code>BILLERY_SECRETS_KEY</code>). Konten ohne Anmeldung funktionieren trotzdem.
      </p>

      <h3 class="mail-heading">Mailkonten</h3>
      <p class="muted">
        Rechnungen und Angebote werden über Ihr eigenes Mailkonto an Kunden gesendet, mit Ihrer
        Adresse als Absender. Das Passwort wird verschlüsselt gespeichert und nie wieder angezeigt.
      </p>
      <div v-if="accounts.length" class="table-scroll">
        <table class="data-table" aria-label="Mailkonten">
          <thead>
            <tr><th>Name</th><th>Absender</th><th>Server</th><th class="actions"><span class="visually-hidden">Aktionen</span></th></tr>
          </thead>
          <tbody>
            <tr v-for="account in accounts" :key="account.id" :data-testid="`mail-account-${account.id}`">
              <td>
                {{ account.name }}
                <span v-if="account.isDefault" class="badge badge-success">Standard</span>
              </td>
              <td>{{ sender(account) }}</td>
              <td>{{ account.host }}:{{ account.port }} · {{ securityLabel(account.security) }}</td>
              <td class="actions">
                <button type="button" class="btn btn-secondary btn-small" :disabled="busy" @click="test(account)">Testmail</button>
                <button type="button" class="btn btn-secondary btn-small" :disabled="busy" @click="edit(account)">Bearbeiten</button>
                <button v-if="!account.isDefault" type="button" class="btn btn-secondary btn-small" :disabled="busy" @click="makeDefault(account)">Als Standard</button>
                <button type="button" class="btn btn-outline btn-small" :disabled="busy" @click="remove(account)">Löschen</button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else-if="loaded" class="muted" data-testid="no-mail-accounts">Noch kein Mailkonto eingerichtet.</p>

      <form v-if="!demo" class="account-form" data-testid="mail-account-form" @submit.prevent="save">
        <h4 class="mail-subheading">{{ form.id ? `Konto „${form.originalName}“ bearbeiten` : 'Neues Mailkonto' }}</h4>
        <div class="form-grid">
          <div class="form-group">
            <label for="mail-name">Name</label>
            <input id="mail-name" v-model="form.name" class="form-control" placeholder="z. B. Buchhaltung" maxlength="100" required />
          </div>
          <div class="form-group">
            <label for="mail-from-name">Absendername</label>
            <input id="mail-from-name" v-model="form.fromName" class="form-control" placeholder="Ihre Firma" maxlength="255" />
          </div>
          <div class="form-group">
            <label for="mail-from">Absenderadresse</label>
            <input id="mail-from" v-model="form.fromAddress" class="form-control" type="email" placeholder="rechnung@firma.de" required />
          </div>
          <div class="form-group">
            <label for="mail-host">Mailserver (SMTP)</label>
            <input id="mail-host" v-model="form.host" class="form-control" placeholder="smtp.example.com" autocomplete="off" required />
          </div>
          <div class="form-group">
            <label for="mail-security">Verschlüsselung</label>
            <select id="mail-security" v-model="form.security" class="form-control" @change="onSecurityChange">
              <option value="STARTTLS">STARTTLS (meist Port 587)</option>
              <option value="SSL">SSL/TLS (meist Port 465)</option>
              <option v-if="!restricted" value="NONE">Keine (nur lokales Relay)</option>
            </select>
          </div>
          <div class="form-group">
            <label for="mail-port">Port</label>
            <input id="mail-port" v-model.number="form.port" class="form-control" type="number" min="1" max="65535" />
          </div>
          <div class="form-group">
            <label for="mail-user">Benutzername</label>
            <input id="mail-user" v-model="form.username" class="form-control" autocomplete="off" />
          </div>
          <div class="form-group">
            <label for="mail-password">Passwort</label>
            <input
              id="mail-password"
              v-model="form.password"
              class="form-control"
              type="password"
              autocomplete="new-password"
              :placeholder="form.hasPassword ? 'gespeichert – leer lassen, um es zu behalten' : ''"
              :disabled="form.removePassword"
            />
            <label v-if="form.hasPassword" class="inline-check">
              <input v-model="form.removePassword" type="checkbox" /> Gespeichertes Passwort entfernen
            </label>
          </div>
        </div>
        <p class="muted">Ändern Sie Server, Port, Verschlüsselung oder Benutzernamen, geben Sie das Passwort bitte erneut ein.</p>
        <div class="form-actions">
          <button type="submit" class="btn btn-primary" :disabled="busy">{{ form.id ? 'Änderungen speichern' : 'Konto anlegen' }}</button>
          <button v-if="form.id" type="button" class="btn btn-secondary" :disabled="busy" @click="resetForm">Abbrechen</button>
        </div>
      </form>

      <h3 class="mail-heading">Vorlagen für Mails an Kunden</h3>
      <p class="muted">
        Betreff und Text, mit denen der Versanddialog beginnt; Sie können beides vor dem Senden
        ändern. Platzhalter wie Rechnungsnummer, Kundenname oder Betrag fügen Sie mit
        „+ Platzhalter“ oder @ ein; sie werden beim Senden durch die Werte des Dokuments ersetzt.
      </p>
      <div class="kinds" role="tablist" aria-label="Dokumentart">
        <button
          v-for="kind in kinds"
          :key="kind.key"
          type="button"
          role="tab"
          :aria-selected="kind.key === activeKind ? 'true' : 'false'"
          :class="['btn', 'btn-small', kind.key === activeKind ? 'btn-primary' : 'btn-secondary']"
          @click="activeKind = kind.key"
        >{{ kind.label }}</button>
      </div>
      <form v-if="templates[activeKind]" class="template-form" data-testid="mail-template-form" @submit.prevent="saveTemplates">
        <!-- chips instead of {{braces}}, like the template editor; the stored text keeps {{name}} -->
        <div class="form-group">
          <span class="field-label" aria-hidden="true">Betreff</span>
          <VariableTextInput
            id="mail-template-subject"
            :key="`subject-${activeKind}`"
            v-model="templates[activeKind].subject"
            label="Betreff"
          />
        </div>
        <div class="form-group">
          <span class="field-label" aria-hidden="true">Text</span>
          <VariableTextInput
            id="mail-template-text"
            :key="`text-${activeKind}`"
            v-model="templates[activeKind].text"
            label="Text"
            multiline
          />
        </div>
        <div class="form-actions">
          <button type="submit" class="btn btn-primary" :disabled="busy">Vorlagen speichern</button>
        </div>
      </form>
    </div>
  </section>
</template>

<script>
import mailService from '../services/mailService.js'
import VariableTextInput from './editor/VariableTextInput.vue'
import { session } from '../services/session.js'

/** The kinds of document with a mail template of their own, in display order. */
const KINDS = [
  { key: 'invoice', label: 'Rechnung' },
  { key: 'credit_note', label: 'Stornorechnung' },
  { key: 'offer', label: 'Angebot' },
  { key: 'cost_calculation', label: 'Kostenberechnung' }
]

const SECURITY_LABELS = { STARTTLS: 'STARTTLS', SSL: 'SSL/TLS', NONE: 'unverschlüsselt' }
const DEFAULT_PORTS = { STARTTLS: 587, SSL: 465, NONE: 25 }

function emptyForm() {
  return {
    id: null,
    originalName: '',
    name: '',
    host: '',
    port: 587,
    security: 'STARTTLS',
    username: '',
    password: '',
    hasPassword: false,
    removePassword: false,
    fromAddress: '',
    fromName: ''
  }
}

/**
 * Settings → Mail-Einstellungen: the company's own SMTP accounts (several, one default, a test mail
 * to the sender address) and the subject / text templates of the mails to customers. Only the
 * people who manage the company see it (SaaS: owners and administrators; `settingsAreas.js`).
 */
export default {
  name: 'MailSettings',
  components: { VariableTextInput },
  data() {
    return {
      accounts: [],
      templates: {},
      kinds: KINDS,
      activeKind: 'invoice',
      passwordsStorable: true,
      restricted: false,
      loaded: false,
      busy: false,
      error: null,
      notice: null,
      form: emptyForm()
    }
  },
  computed: {
    /** The public demo: its server refuses mail accounts and mail to customers. */
    demo() {
      return session.demo
    }
  },
  async mounted() {
    await this.load()
  },
  methods: {
    securityLabel(security) {
      return SECURITY_LABELS[security] || security
    },
    sender(account) {
      return account.fromName ? `${account.fromName} <${account.fromAddress}>` : account.fromAddress
    },
    async load() {
      await this.attempt(async () => {
        const settings = await mailService.settings()
        this.passwordsStorable = settings.passwordsStorable
        this.restricted = settings.restricted
        this.templates = settings.templates
        this.accounts = await mailService.accounts()
      })
      this.loaded = true
    },
    /** A changed security setting moves a port that was the usual one to the new usual one. */
    onSecurityChange() {
      if (Object.values(DEFAULT_PORTS).includes(Number(this.form.port))) {
        this.form.port = DEFAULT_PORTS[this.form.security]
      }
    },
    edit(account) {
      this.notice = null
      this.error = null
      this.form = {
        ...emptyForm(),
        id: account.id,
        originalName: account.name,
        name: account.name,
        host: account.host,
        port: account.port,
        security: account.security,
        username: account.username,
        hasPassword: account.hasPassword,
        fromAddress: account.fromAddress,
        fromName: account.fromName
      }
    },
    resetForm() {
      this.form = emptyForm()
    },
    /** The body for the API: an empty password field keeps the stored one. */
    payload() {
      const f = this.form
      let password = f.password ? f.password : null
      if (f.removePassword) password = ''
      if (!f.id && !f.password) password = null
      return {
        name: f.name,
        host: f.host,
        port: f.port || null,
        security: f.security,
        username: f.username,
        password,
        fromAddress: f.fromAddress,
        fromName: f.fromName
      }
    },
    async save() {
      this.notice = null
      const editing = this.form.id
      const done = await this.attempt(() =>
        editing ? mailService.updateAccount(editing, this.payload()) : mailService.createAccount(this.payload())
      )
      if (done) {
        this.notice = editing ? 'Mailkonto gespeichert.' : 'Mailkonto angelegt. Senden Sie eine Testmail, um es zu prüfen.'
        this.resetForm()
        await this.load()
      }
    },
    async test(account) {
      this.notice = null
      const done = await this.attempt(() => mailService.testAccount(account.id))
      if (done) this.notice = `Testmail an ${account.fromAddress} gesendet. Kommt sie an, ist das Konto bereit.`
    },
    async makeDefault(account) {
      this.notice = null
      await this.attempt(() => mailService.makeDefault(account.id))
      await this.load()
    },
    async remove(account) {
      this.notice = null
      if (!window.confirm(`Mailkonto „${account.name}“ wirklich löschen?`)) return
      await this.attempt(() => mailService.deleteAccount(account.id))
      if (this.form.id === account.id) this.resetForm()
      await this.load()
    },
    async saveTemplates() {
      this.notice = null
      const done = await this.attempt(async () => {
        this.templates = await mailService.saveTemplates(this.templates)
      })
      if (done) this.notice = 'Vorlagen gespeichert.'
    },
    /** Runs a call and shows the backend's message when it fails; true if it worked. */
    async attempt(action) {
      this.busy = true
      this.error = null
      try {
        await action()
        return true
      } catch (error) {
        this.error = error.message || 'Das ging leider schief.'
        return false
      } finally {
        this.busy = false
      }
    }
  }
}
</script>

<style scoped>
.mail-card { display: flex; flex-direction: column; gap: var(--space-3); }
.mail-heading { margin: var(--space-2) 0 0; font-size: 1rem; color: var(--color-text); }
.mail-heading:first-of-type { margin-top: 0; }
.mail-subheading { margin: 0 0 var(--space-2); font-size: 0.95rem; color: var(--color-text); }
.muted { color: var(--color-text-muted); font-size: 0.92em; margin: 0; }
.data-table td { vertical-align: middle; }
.actions { text-align: right; white-space: nowrap; width: 1%; }
.actions .btn { margin-left: var(--space-1); }
.account-form, .template-form {
  border-top: 1px solid var(--color-border);
  padding-top: var(--space-3);
  display: flex;
  flex-direction: column;
  gap: var(--space-2);
}
.form-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(16em, 1fr));
  gap: var(--space-2) var(--space-3);
}
.form-group { display: flex; flex-direction: column; gap: var(--space-1); margin: 0; }
.form-group label, .form-group .field-label { font-weight: var(--font-weight-medium); color: var(--color-text); }
.inline-check { display: flex; gap: var(--space-1); align-items: center; font-weight: normal !important; font-size: 0.92em; }
.form-actions { display: flex; gap: var(--space-2); flex-wrap: wrap; }
.kinds { display: flex; flex-wrap: wrap; gap: var(--space-1); }
.notice { margin: 0; padding: var(--space-2) var(--space-3); border-radius: var(--radius-md); }
.notice-error { background: var(--color-danger-soft); color: var(--color-danger-hover); }
.notice-success { background: var(--color-success-soft); color: var(--color-success-hover); }
.notice-warning { background: var(--color-warning-soft); color: var(--color-warning-text); }
.visually-hidden { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0, 0, 0, 0); white-space: nowrap; }
@media (max-width: 760px) {
  .actions { white-space: normal; }
  .actions .btn { margin: 0 0 var(--space-1) 0; width: 100%; }
  .form-actions .btn { width: 100%; }
}
</style>

<template>
  <div v-if="visible" class="modal-backdrop send-mail-backdrop" @click.self="close">
    <div class="modal-panel modal-panel-lg send-mail" role="dialog" aria-labelledby="send-mail-title" data-testid="send-mail">
      <h2 id="send-mail-title">{{ $t('sendMail.title') }}</h2>

      <p v-if="loading" class="muted">{{ $t('common.loading') }}</p>
      <p v-if="error" class="notice notice-error" role="alert">{{ error }}</p>

      <template v-if="draft && !loading">
        <div v-if="!draft.accounts.length" class="notice notice-warning" data-testid="no-account">
          {{ $t('sendMail.noAccount') }}
          <router-link v-if="canManage" to="/settings/mail" @click="close">{{ $t('sendMail.setUp') }}</router-link>
          <span v-else>{{ $t('sendMail.askOwner') }}</span>
        </div>

        <form v-else class="send-form" @submit.prevent="send">
          <p v-if="draft.needsIssue" class="notice notice-warning" data-testid="needs-issue">
            {{ $t('sendMail.needsIssue') }}
          </p>
          <div class="form-group">
            <label for="send-account">{{ $t('mail.sender') }}</label>
            <select id="send-account" v-model="form.accountId" class="form-control">
              <option v-for="account in draft.accounts" :key="account.id" :value="account.id">
                {{ account.name }} – {{ account.fromAddress }}
              </option>
            </select>
          </div>
          <div class="form-group">
            <label for="send-to">{{ $t('sendMail.to') }}</label>
            <input id="send-to" v-model="form.to" class="form-control" type="text" autocomplete="off" :placeholder="$t('sendMail.toPlaceholder')" data-testid="send-to" />
          </div>
          <div class="form-group">
            <label for="send-cc">{{ $t('sendMail.cc') }}</label>
            <input id="send-cc" v-model="form.cc" class="form-control" type="text" autocomplete="off" :placeholder="$t('sendMail.ccPlaceholder')" />
          </div>
          <!-- chips for what is still a placeholder (the number of a draft), like the mail templates -->
          <div class="form-group">
            <span class="field-label" aria-hidden="true">{{ $t('mail.subject') }}</span>
            <VariableTextInput id="send-subject" v-model="form.subject" :label="$t('mail.subject')" data-testid="send-subject" />
          </div>
          <div class="form-group">
            <span class="field-label" aria-hidden="true">{{ $t('mail.text') }}</span>
            <VariableTextInput id="send-text" v-model="form.text" :label="$t('mail.text')" multiline data-testid="send-text" />
          </div>
          <p class="muted">{{ $t('sendMail.attachment', { name: attachmentLabel }) }}</p>
          <label v-if="draft.xrechnungPossible" class="inline-check">
            <input v-model="form.attachXRechnung" type="checkbox" :disabled="draft.xrechnungMissing.length > 0" />
            {{ $t('sendMail.attachXRechnung') }}
          </label>
          <p v-if="draft.xrechnungPossible && draft.xrechnungMissing.length" class="muted">
            {{ $t('sendMail.xrechnungMissing', { fields: draft.xrechnungMissing.join(', ') }) }}
          </p>
          <label class="inline-check">
            <input v-model="form.copyToSelf" type="checkbox" />
            {{ $t('sendMail.copyToSelf') }}
          </label>
          <div class="form-actions">
            <button type="submit" class="btn btn-primary" :disabled="sending" data-testid="send-button">
              {{ sending ? $t('sendMail.sending') : draft.needsIssue ? $t('sendMail.issueAndSend') : $t('sendMail.send') }}
            </button>
            <button type="button" class="btn btn-secondary" :disabled="sending" @click="close">{{ $t('common.cancel') }}</button>
          </div>
        </form>
      </template>

      <div v-if="draft && !draft.accounts.length" class="form-actions">
        <button type="button" class="btn btn-secondary" @click="close">{{ $t('common.close') }}</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import { computed, ref, watch } from 'vue'
import mailService, { splitAddresses } from '../services/mailService.js'
import VariableTextInput from './editor/VariableTextInput.vue'
import { activeRole, isSaas } from '../services/session.js'

const { t } = useI18n()

/**
 * The send dialog of a document: sender account, recipients, subject and text (from the company's
 * template, editable), the PDF as attachment and optionally the XRechnung. An invoice draft is
 * issued when it is sent, after a confirmation, because issuing cannot be undone (GoBD).
 */
const props = defineProps({
  visible: Boolean,
  documentId: { type: Number, default: null }
})

const emit = defineEmits(['update:visible', 'sent'])

const draft = ref(null)
const loading = ref(false)
const sending = ref(false)
const error = ref(null)
const form = ref(emptyForm())

function emptyForm() {
  return { accountId: null, to: '', cc: '', subject: '', text: '', attachXRechnung: false, copyToSelf: false }
}

/** Whether the user may set up a mail account (self-host: always; SaaS: owners and admins). */
const canManage = computed(() => !isSaas() || ['OWNER', 'ADMIN'].includes(activeRole()))

const attachmentLabel = computed(() =>
  draft.value.needsIssue ? t('sendMail.draftAttachment') : draft.value.attachmentName
)

watch(
  () => props.visible,
  async visible => {
    if (!visible || !props.documentId) return
    draft.value = null
    error.value = null
    form.value = emptyForm()
    loading.value = true
    try {
      const loaded = await mailService.draft(props.documentId)
      draft.value = loaded
      const preferred = loaded.accounts.find(account => account.isDefault) || loaded.accounts[0]
      form.value = {
        ...emptyForm(),
        accountId: preferred ? preferred.id : null,
        to: loaded.to,
        subject: loaded.subject,
        text: loaded.text
      }
    } catch (err) {
      error.value = err.message || t('sendMail.prepareFailed')
    } finally {
      loading.value = false
    }
  },
  { immediate: true }
)

function close() {
  if (sending.value) return
  emit('update:visible', false)
}

/** Sends; an invoice draft only after the user confirmed that it is issued. */
async function send() {
  error.value = null
  const to = splitAddresses(form.value.to)
  if (!to.length) {
    error.value = t('sendMail.noRecipient')
    return
  }
  if (
    draft.value.needsIssue &&
    !window.confirm(t('sendMail.confirmIssue'))
  ) {
    return
  }
  sending.value = true
  try {
    const result = await mailService.send(props.documentId, {
      accountId: form.value.accountId,
      to,
      cc: splitAddresses(form.value.cc),
      subject: form.value.subject,
      text: form.value.text,
      attachXRechnung: form.value.attachXRechnung,
      copyToSelf: form.value.copyToSelf,
      issue: draft.value.needsIssue
    })
    emit('sent', { ...result, to })
    emit('update:visible', false)
  } catch (err) {
    error.value = err.message || t('sendMail.failed')
  } finally {
    sending.value = false
  }
}
</script>

<style scoped>
.send-mail-backdrop { z-index: var(--z-modal-nested); }
.send-form { display: flex; flex-direction: column; gap: var(--space-2); }
.form-group { display: flex; flex-direction: column; gap: var(--space-1); margin: 0; }
.form-group label, .form-group .field-label { font-weight: var(--font-weight-medium); color: var(--color-text); }
.inline-check { display: flex; gap: var(--space-2); align-items: center; }
.form-actions { display: flex; gap: var(--space-2); flex-wrap: wrap; margin-top: var(--space-2); }
.muted { color: var(--color-text-muted); font-size: 0.92em; margin: 0; }
.notice { margin: 0 0 var(--space-2); padding: var(--space-2) var(--space-3); border-radius: var(--radius-md); }
.notice-error { background: var(--color-danger-soft); color: var(--color-danger-hover); }
.notice-warning { background: var(--color-warning-soft); color: var(--color-warning-text); }
@media (max-width: 600px) {
  .form-actions .btn { width: 100%; }
}
</style>

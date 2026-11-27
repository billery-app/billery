<template>
  <div v-if="visible" class="modal-backdrop" @click.self="close">
    <div class="modal-panel">
      <div class="modal-header">
        <h2>{{ viewTitle }}</h2>
        <button class="close-btn" :aria-label="$t('common.close')" @click="close">&times;</button>
      </div>

      <div v-if="loading" class="loading">{{ $t('common.loading') }}</div>
      <div v-else-if="error" class="error">{{ error }}</div>
      <div v-else-if="doc" class="invoice-content">
        <div class="invoice-header">
          <div class="header-row">
            <span class="label">{{ $t('view.customer') }}</span>
            <span class="value">{{ doc.customerName }}</span>
          </div>
          <div class="header-row">
            <span class="label">{{ $t('view.date') }}</span>
            <span class="value">{{ formatDate(doc.invoiceDate) }}</span>
          </div>
          <div class="header-row">
            <span class="label">{{ $t('view.due') }}</span>
            <span class="value" :class="{ overdue: isOverdue }">{{ formatDate(doc.dueDate) }}</span>
          </div>
          <div v-if="doc.workStartDate && !doc.workEndDate" class="header-row">
            <span class="label">{{ $t('view.serviceDate') }}</span>
            <span class="value">{{ formatDate(doc.workStartDate) }}</span>
          </div>
          <div v-if="doc.workStartDate && doc.workEndDate" class="header-row">
            <span class="label">{{ $t('view.servicePeriod') }}</span>
            <span class="value">{{ formatDate(doc.workStartDate) }} – {{ formatDate(doc.workEndDate) }}</span>
          </div>
          <div class="header-row">
            <span class="label">{{ $t('view.status') }}</span>
            <span :class="['status', 'status-badge', statusClassComputed]">{{ statusTextComputed }}</span>
          </div>
          <div v-if="doc.cancelsInvoiceId" class="header-row">
            <span class="label">{{ $t('view.cancels') }}</span>
            <span class="value">{{ relatedNumber }}</span>
          </div>
          <div v-if="doc.cancelledByInvoiceId" class="header-row">
            <span class="label">{{ $t('view.cancelledBy') }}</span>
            <span class="value">{{ relatedNumber }}</span>
          </div>
          <div v-if="isIssuedInvoice" class="header-row">
            <span class="label">{{ $t('view.paidOpen') }}</span>
            <span class="value">{{ formatCurrency(doc.paidAmount || 0) }} / {{ formatCurrency(openAmount) }}</span>
          </div>
        </div>

        <div v-if="payments.length" class="payments-section">
          <h3>{{ $t('view.payments') }}</h3>
          <div class="table-scroll" role="region" :aria-label="$t('view.payments')" tabindex="0">
          <table class="items-table">
            <thead>
              <tr>
                <th>{{ $t('documents.columns.date') }}</th>
                <th class="numeric">{{ $t('dashboard.columns.amount') }}</th>
                <th>{{ $t('view.method') }}</th>
                <th>{{ $t('view.referenceNote') }}</th>
                <th></th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in payments" :key="p.id" :class="{ reversed: isReversed(p) || p.reversesPaymentId }">
                <td>{{ formatDate(p.paymentDate) }}</td>
                <td class="numeric">{{ formatCurrency(p.amount) }}</td>
                <td>{{ p.paymentMethod || '-' }}</td>
                <td>{{ [p.reference, p.notes].filter(Boolean).join(' – ') || '-' }}</td>
                <td>
                  <button
                    v-if="!p.reversesPaymentId && !isReversed(p)"
                    class="btn btn-small btn-secondary"
                    :disabled="statusUpdating"
                    @click="reversePayment(p)"
                  >{{ $t('view.reverse') }}</button>
                </td>
              </tr>
            </tbody>
          </table>
          </div>
        </div>

        <div class="items-section">
          <h3>{{ $t('view.items') }}</h3>
          <div v-if="flatItems.length" class="table-scroll" role="region" :aria-label="$t('view.items')" tabindex="0">
          <table class="items-table">
            <thead>
              <tr>
                <th>{{ $t('view.position') }}</th>
                <th>{{ $t('view.articleService') }}</th>
                <th class="numeric">{{ $t('catalog.columns.quantity') }}</th>
                <th class="numeric">{{ $t('catalog.columns.unit') }}</th>
                <th class="numeric">{{ $t('view.unitPrice') }}</th>
                <th v-if="mixedRates" class="numeric">{{ $t('catalog.columns.vat') }}</th>
                <th class="numeric">{{ $t('catalog.columns.total') }}</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="(entry, index) in flatItems"
                :key="entry.item.id || index"
                :class="{ 'item-row-child': entry.depth > 0 }"
              >
                <td>{{ index + 1 }}</td>
                <td :class="{ 'item-description-cell': entry.depth > 0 }">
                  <div class="item-name">{{ entry.item.itemName || $t('view.noName') }}</div>
                  <div v-if="entry.item.itemDescription" class="item-description">{{ entry.item.itemDescription }}</div>
                </td>
                <td class="numeric">{{ formatQuantity(entry.item.quantity) }}</td>
                <td class="numeric">{{ entry.item.unit || '-' }}</td>
                <td class="numeric">{{ formatCurrency(entry.item.unitPrice) }}</td>
                <td v-if="mixedRates" class="numeric">{{ isNoteItem(entry.item) ? '' : formatRate(entry.item.taxRate) }}</td>
                <td class="numeric">{{ formatCurrency(entry.item.totalPrice) }}</td>
              </tr>
            </tbody>
          </table>
          </div>
          <div v-else class="no-items">{{ $t('view.noItems') }}</div>
        </div>

        <div class="totals-section">
          <div class="total-row">
            <span>{{ $t('view.net') }}</span>
            <span>{{ formatCurrency(doc.netAmount) }}</span>
          </div>
          <div v-for="group in vatLines" :key="group.ratePercent" class="total-row">
            <span>{{ vatLines.length > 1 ? $t('view.vatOn', { rate: formatRate(group.ratePercent), net: formatCurrency(group.net) }) : $t('view.vat', { rate: formatRate(group.ratePercent) }) }}</span>
            <span>{{ formatCurrency(group.tax) }}</span>
          </div>
          <div class="total-row gross">
            <span>{{ $t('view.gross') }}</span>
            <span>{{ formatCurrency(doc.grossAmount) }}</span>
          </div>
          <p v-if="taxNote" class="tax-note" data-testid="tax-note">{{ taxNote }}</p>
        </div>

        <div v-if="doc.notes" class="notes-section">
          <h3>{{ $t('view.notes') }}</h3>
          <p>{{ doc.notes }}</p>
        </div>

        <details v-if="history.length" class="history-section">
          <summary>{{ $t('view.history', { count: history.length }) }}</summary>
          <ol class="history-list">
            <li v-for="h in history" :key="h.id" class="history-entry">
              <time class="history-date" :datetime="h.actionDate">{{ formatDateTime(h.actionDate) }}</time>
              <span class="history-action">{{ historyLabel(h.action) }}</span>
              <span class="history-details">{{ historyDetails(h) }}</span>
              <span v-if="h.performedByName" class="history-actor">{{ h.performedByName }}</span>
            </li>
          </ol>
        </details>
      </div>

      <!--
        One place for every action: the next step of the lifecycle on the left (the only
        primary button), the document actions in the middle, rare and destructive actions in the
        "Mehr" menu, "Schließen" on the right. The payment and the duplicate form open here and
        replace the bar while they are open.
      -->
      <div class="modal-footer">
        <div v-if="actionError" class="action-error" role="alert">{{ actionError }}</div>
        <div v-if="mailNotice" class="action-notice" role="status" data-testid="mail-notice">{{ mailNotice }}</div>

        <div v-if="doc && showPaymentForm" ref="footerForm" class="footer-form payment-form">
          <h3>{{ $t('view.recordPayment') }}</h3>
          <div class="footer-form-row footer-form-row-payment">
            <div class="form-group">
              <label for="payment-date">{{ $t('view.paymentDate') }}</label>
              <input id="payment-date" v-model="paymentForm.paymentDate" type="date" />
            </div>
            <div class="form-group">
              <label for="payment-amount">{{ $t('view.paymentAmount') }}</label>
              <input id="payment-amount" v-model.number="paymentForm.amount" type="number" step="0.01" min="0.01" :max="openAmount" />
            </div>
            <div class="form-group">
              <label for="payment-method">{{ $t('view.paymentMethod') }}</label>
              <input id="payment-method" v-model="paymentForm.paymentMethod" list="payment-methods" />
              <datalist id="payment-methods">
                <option value="Überweisung" />
                <option value="Lastschrift" />
                <option value="Bar" />
                <option value="PayPal" />
              </datalist>
            </div>
            <div class="form-group">
              <label for="payment-reference">{{ $t('view.reference') }}</label>
              <input id="payment-reference" v-model="paymentForm.reference" :placeholder="$t('view.referencePlaceholder')" />
            </div>
          </div>
          <div class="footer-form-buttons">
            <button type="button" class="btn btn-primary" :disabled="statusUpdating" @click="savePayment">{{ $t('common.save') }}</button>
            <button type="button" class="btn btn-secondary" @click="showPaymentForm = false">{{ $t('common.cancel') }}</button>
          </div>
        </div>

        <div v-else-if="doc && showCloneForm" ref="footerForm" class="footer-form clone-form">
          <h3>{{ $t('view.duplicateTitle', { type: documentTypeLabel }) }}</h3>
          <div class="footer-form-row">
            <div class="form-group">
              <label for="clone-date">{{ $t('view.newDate') }}</label>
              <input id="clone-date" v-model="cloneForm.invoiceDate" type="date" />
            </div>
            <div class="form-group">
              <label for="clone-work-date">{{ $t('view.newServiceDate') }}</label>
              <input id="clone-work-date" v-model="cloneForm.workStartDate" type="date" />
            </div>
          </div>
          <div class="footer-form-buttons">
            <button type="button" class="btn btn-primary" :disabled="cloneLoading" @click="cloneDocument">
              {{ cloneLoading ? $t('view.duplicating') : $t('view.confirm') }}
            </button>
            <button type="button" class="btn btn-secondary" @click="showCloneForm = false">{{ $t('common.cancel') }}</button>
          </div>
        </div>

        <div v-else class="action-bar">
          <div
            v-if="lifecycleActions.length"
            class="action-group action-group-lifecycle"
            role="group"
            aria-labelledby="lifecycle-caption"
          >
            <span id="lifecycle-caption" class="lifecycle-caption">{{ lifecycleCaption }}</span>
            <button
              v-for="(action, index) in lifecycleActions"
              :key="action.key"
              type="button"
              :class="['btn', index === 0 ? 'btn-primary' : 'btn-secondary']"
              :disabled="statusUpdating"
              @click="action.run()"
            >{{ action.label }}</button>
          </div>

          <div v-if="doc" class="action-group action-group-document" role="group" :aria-label="$t('view.document')">
            <button
              type="button"
              :class="['btn', lifecycleActions.length ? 'btn-secondary' : 'btn-primary']"
              :disabled="pdfLoading"
              :title="$t('view.pdfTitle')"
              data-testid="pdf-download"
              @click="downloadPdf"
            >{{ pdfLoading ? $t('common.loading') : 'PDF' }}</button>
            <button
              v-if="isIssuedInvoice || isCreditNoteDoc"
              type="button"
              class="btn btn-secondary"
              :title="$t('view.eInvoiceTitle')"
              data-testid="xrechnung-download"
              @click="downloadXRechnung"
            >{{ $t('view.eInvoice') }}</button>
            <button
              v-if="canSendMail"
              type="button"
              class="btn btn-secondary"
              :title="$t('view.mailTitle')"
              data-testid="send-mail-button"
              @click="showSendMail = true"
            >{{ $t('view.mail') }}</button>
            <button v-if="!isCreditNoteDoc" type="button" class="btn btn-secondary" @click="openCloneForm">{{ $t('view.duplicate') }}</button>
            <button v-if="listActions.edit" type="button" class="btn btn-secondary" @click="emit('edit-request', doc)">{{ $t('common.edit') }}</button>
            <button v-if="listActions.restore" type="button" class="btn btn-secondary" @click="emit('restore-request', doc.id)">{{ $t('view.restore') }}</button>
          </div>

          <div class="action-group action-group-end">
            <div v-if="menuActions.length" ref="menuRoot" class="more-menu">
              <button
                ref="menuButton"
                type="button"
                class="btn btn-secondary"
                aria-haspopup="menu"
                aria-controls="document-more-menu"
                :aria-expanded="menuOpen ? 'true' : 'false'"
                @click="toggleMenu"
                @keydown.down.prevent="openMenu"
                @keydown.up.prevent="openMenu"
              >{{ $t('view.more') }} <span class="more-menu-caret" aria-hidden="true">▾</span></button>
              <ul
                v-if="menuOpen"
                id="document-more-menu"
                ref="menuList"
                class="more-menu-list"
                role="menu"
                :aria-label="$t('view.moreActions')"
                @keydown="onMenuKeydown"
              >
                <li v-for="action in menuActions" :key="action.key" role="none">
                  <button
                    type="button"
                    role="menuitem"
                    :class="['more-menu-item', { 'more-menu-item-danger': action.danger }]"
                    :disabled="statusUpdating"
                    @click="chooseMenuAction(action)"
                  >{{ action.label }}</button>
                </li>
              </ul>
            </div>
            <button type="button" class="btn btn-secondary" @click="close">{{ $t('common.close') }}</button>
          </div>
        </div>
      </div>
    </div>
    <SendMailModal v-model:visible="showSendMail" :document-id="doc ? doc.id : null" @sent="onMailSent" />
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import { ref, watch, computed, nextTick, onBeforeUnmount } from 'vue'
import invoiceAPI from '../services/invoiceService.js'
import { useDocumentType } from '../composables/useDocumentType.js'
import { isInvoiceOverdue, isDraft, isOpenInvoice, isCancelled, DRAFT, SENT, CANCELLED } from '../utils/documentStatus.ts'
import { isInvoice, isCreditNote } from '../data/documentTypes.ts'
import { chargesVat, legalNote, formatRate } from '../utils/vat.ts'
import SendMailModal from './SendMailModal.vue'
import { activeRole, isSaas, session } from '../services/session.js'

const { t } = useI18n()

const { documentLabel, statusText, statusClass, statusOptions: getStatusOptions } = useDocumentType()

const props = defineProps({
  visible: Boolean,
  invoiceId: {
    type: Number,
    default: null
  },
  listActions: {
    type: Object,
    default: () => ({})
  }
})

const emit = defineEmits([
  'update:visible',
  'status-updated',
  'cloned',
  'edit-request',
  'archive-request',
  'restore-request',
  'delete-request',
  'convert-to-invoice',
  'convert-to-offer'
])

/** What the opening list allows for this document (edit, archive, restore, delete, convert). */
const listActions = computed(() => props.listActions || {})

const doc = ref(null)
const loading = ref(false)
const statusUpdating = ref(false)
const pdfLoading = ref(false)
const error = ref(null)

const actionError = ref(null)
/** What the last mail dispatch did ("Gesendet an …"), shown above the actions. */
const mailNotice = ref(null)
const showSendMail = ref(false)
const payments = ref([])
const history = ref([])
const relatedNumber = ref('')
const showPaymentForm = ref(false)
const paymentForm = ref(emptyPaymentForm(0))

const showCloneForm = ref(false)
const cloneLoading = ref(false)
const cloneForm = ref(emptyCloneForm())

/** The form that currently replaces the action bar (payment or duplicate), for the focus. */
const footerForm = ref(null)

const menuOpen = ref(false)
const menuRoot = ref(null)
const menuButton = ref(null)
const menuList = ref(null)

const documentTypeLabel = computed(() => {
  if (!doc.value) return ''
  return documentLabel(doc.value.documentTypeId)
})

const viewTitle = computed(() => {
  if (!doc.value) return ''
  return `${documentTypeLabel.value} ${doc.value.invoiceNumber}`
})

const statusTextComputed = computed(() => {
  if (!doc.value) return '-'
  return statusText(doc.value.documentTypeId, doc.value.documentStatusId)
})

const statusClassComputed = computed(() => {
  if (!doc.value) return ''
  return statusClass(doc.value.documentStatusId)
})

const vatLines = computed(() =>
  doc.value && chargesVat(doc.value.taxTreatment) ? doc.value.vatGroups || [] : []
)
const mixedRates = computed(() => vatLines.value.length > 1)
const taxNote = computed(() =>
  doc.value ? legalNote(doc.value.taxTreatment, doc.value.taxExemptionReason) : ''
)

function isNoteItem(item) {
  return (item.itemType || '').toLowerCase() === 'custom'
}

/** German quantity without forced decimals: "2", "1,5". */
function formatQuantity(quantity) {
  return new Intl.NumberFormat('de-DE', { maximumFractionDigits: 4 }).format(quantity || 0)
}

function flattenItems(items, depth = 0, acc = []) {
  if (!Array.isArray(items)) return acc
  for (const item of items) {
    acc.push({ item, depth })
    if (Array.isArray(item.children) && item.children.length) {
      flattenItems(item.children, depth + 1, acc)
    }
  }
  return acc
}

const flatItems = computed(() => flattenItems(doc.value?.items || []))

const isOverdue = computed(() => isInvoiceOverdue(doc.value))

const isInvoiceDoc = computed(() => !!doc.value && isInvoice(doc.value.documentTypeId))
const isCreditNoteDoc = computed(() => !!doc.value && isCreditNote(doc.value.documentTypeId))
const isDraftDoc = computed(() => !!doc.value && isDraft(doc.value.documentStatusId))
const isCancelledDoc = computed(() => !!doc.value && isCancelled(doc.value.documentStatusId))
const isIssuedInvoice = computed(() => isInvoiceDoc.value && !isDraftDoc.value)
const isOpen = computed(() => isInvoiceDoc.value && isOpenInvoice(doc.value.documentStatusId))

/** Gross amount still to be paid, never negative. */
const openAmount = computed(() => {
  if (!doc.value) return 0
  const open = (doc.value.grossAmount || 0) - (doc.value.paidAmount || 0)
  return Math.max(0, Math.round(open * 100) / 100)
})

/** The actions of the document history; their names are in the message files (`view.historyActions`). */
const HISTORY_ACTIONS = ['CREATED', 'UPDATED', 'ISSUED', 'STATUS_CHANGED', 'PAYMENT_RECORDED', 'PAYMENT_REVERSED', 'CANCELLED', 'MAIL_SENT']

/**
 * The German name of a history action; an action this version does not know never shows its key.
 * @param {string} action - `document_history.action`
 * @returns {string} - the label
 */
function historyLabel(action) {
  return HISTORY_ACTIONS.includes(action) ? t(`view.historyActions.${action}`) : t('team.audit.other')
}

/**
 * The details of a history entry. Entries are never rewritten (GoBD), so older status changes still
 * hold only the id of the new status (`Status 3`); those are shown with the status name.
 * @param {{notes: ?string}} entry - the history entry
 * @returns {?string} - the text to show, empty if there is none
 */
function historyDetails(entry) {
  const legacyStatus = /^Status (\d+)$/.exec(entry.notes || '')
  return legacyStatus ? statusText(doc.value.documentTypeId, Number(legacyStatus[1])) : entry.notes
}

function today() {
  return new Date().toISOString().split('T')[0]
}

function emptyPaymentForm(amount) {
  return { paymentDate: today(), amount, paymentMethod: 'Überweisung', reference: '', notes: '' }
}

function emptyCloneForm() {
  return { invoiceDate: today(), workStartDate: '' }
}

/**
 * The statuses an offer or cost calculation may get next, as `{ value, label }`. Invoices and
 * credit notes have none: their status follows from issuing, payments and cancelling. A draft is
 * not offered "Storniert" here, it is simply deleted.
 */
const nextStatuses = computed(() => {
  if (!doc.value || isInvoiceDoc.value || isCreditNoteDoc.value) return []
  const currentStatus = doc.value.documentStatusId
  return getStatusOptions(doc.value.documentTypeId, currentStatus)
    .filter(opt => opt.value !== currentStatus)
    .filter(opt => !(currentStatus === DRAFT && opt.value === CANCELLED))
    .map(opt => ({ value: opt.value, label: opt.label }))
})

/**
 * The steps that move the document on in its lifecycle, as `{ key, label, kind, run }`, most
 * likely first: the bar shows the first one as its only primary button and the others next to it.
 * `kind` is `'status'` for a button that is named after the status it sets ("Angenommen") and
 * `'step'` for one named after what it does ("Ausstellen"); {@link lifecycleCaption} reads it.
 * Cancelling is not a next step but a rare, final one; it is in {@link menuActions}.
 */
const lifecycleActions = computed(() => {
  if (!doc.value) return []
  if (isInvoiceDoc.value) {
    if (isDraftDoc.value) return [{ key: 'issue', label: t('view.issue'), kind: 'step', run: issue }]
    if (isOpen.value) return [{ key: 'payment', label: t('view.recordPayment'), kind: 'step', run: openPaymentForm }]
    return []
  }
  const actions = nextStatuses.value
    .filter(opt => opt.value !== CANCELLED)
    .map(opt => ({
      key: `status-${opt.value}`,
      label: opt.label,
      kind: 'status',
      run: () => updateStatus(opt.value)
    }))
  // Converting is the step after "Angenommen"; the list decides whether it is possible.
  if (listActions.value.convertToInvoice) {
    actions.push({ key: 'to-invoice', label: t('view.toInvoice'), kind: 'step', run: async () => emit('convert-to-invoice', doc.value) })
  }
  if (listActions.value.convertToOffer) {
    actions.push({ key: 'to-offer', label: t('view.toOffer'), kind: 'step', run: async () => emit('convert-to-offer', doc.value) })
  }
  return actions
})

/**
 * The caption in front of the lifecycle buttons. It says what kind of button follows, so that
 * "Angenommen" is read as "set the status to accepted" and not as one more option next to "PDF":
 * status buttons complete the sentence "Status setzen auf: …", the other steps are announced as
 * the next step.
 */
const lifecycleCaption = computed(() =>
  lifecycleActions.value.some(action => action.kind === 'status') ? t('view.setStatus') : t('view.nextStep')
)

/**
 * The rare and the destructive actions of the "Mehr" menu, as `{ key, label, danger, run }`.
 * Each of them asks before it acts: cancelling and archiving here, deleting in the list that
 * handles `delete-request`.
 */
const menuActions = computed(() => {
  if (!doc.value) return []
  const actions = []
  if (isInvoiceDoc.value) {
    if (isIssuedInvoice.value && !isCancelledDoc.value) {
      actions.push({ key: 'cancel', label: t('view.cancel'), danger: true, run: cancelInvoice })
    }
  } else if (nextStatuses.value.some(opt => opt.value === CANCELLED)) {
    actions.push({ key: 'cancel', label: t('view.cancel'), danger: true, run: cancelOffer })
  }
  if (listActions.value.archive) {
    actions.push({ key: 'archive', label: t('view.archive'), danger: false, run: requestArchive })
  }
  if (listActions.value.delete) {
    actions.push({ key: 'delete', label: t('common.delete'), danger: true, run: () => emit('delete-request', doc.value.id) })
  }
  return actions
})

/** Forms, menu and the last error belong to one document: closed when another one is shown. */
function resetFooter() {
  showCloneForm.value = false
  showPaymentForm.value = false
  menuOpen.value = false
  actionError.value = null
  mailNotice.value = null
}

/** A reader may look at documents but not send them (SaaS); everybody else may. */
// not for readers (SaaS) and not in the public demo, whose server refuses mail to customers
const canSendMail = computed(() => !!doc.value && !session.demo && !(isSaas() && activeRole() === 'READER'))

/**
 * After a dispatch: reload the document (an invoice draft is issued now, an offer "Gesendet") and
 * tell the list.
 * @param {{issued: boolean, documentNumber: string, to: string[]}} result
 */
async function onMailSent(result) {
  actionError.value = null
  await loadInvoice()
  emit('status-updated')
  mailNotice.value = result.issued
    ? t('view.mailIssuedSent', { number: result.documentNumber, to: result.to.join(', ') })
    : t('view.mailSent', { to: result.to.join(', ') })
}

watch(() => props.visible, async (newVal) => {
  resetFooter()
  if (newVal && props.invoiceId) {
    await loadInvoice()
  } else {
    doc.value = null
    error.value = null
  }
})

watch(() => props.invoiceId, async (newVal) => {
  if (props.visible && newVal) {
    resetFooter()
    await loadInvoice()
  }
})

/** While the menu is open, a click anywhere else closes it. */
watch(menuOpen, (open) => {
  if (open) {
    document.addEventListener('click', onDocumentClick, true)
  } else {
    document.removeEventListener('click', onDocumentClick, true)
  }
})

onBeforeUnmount(() => document.removeEventListener('click', onDocumentClick, true))

function onDocumentClick(event) {
  if (menuRoot.value && !menuRoot.value.contains(event.target)) {
    menuOpen.value = false
  }
}

/** The entries of the open menu that can take the focus. */
function menuItems() {
  return menuList.value ? [...menuList.value.querySelectorAll('[role="menuitem"]:not(:disabled)')] : []
}

/** Opens the "Mehr" menu and puts the focus on its first entry. */
async function openMenu() {
  menuOpen.value = true
  await nextTick()
  menuItems()[0]?.focus()
}

/**
 * Closes the "Mehr" menu.
 * @param {boolean} [returnFocus=false] - put the focus back on the "Mehr" button (keyboard use)
 */
function closeMenu(returnFocus = false) {
  menuOpen.value = false
  if (returnFocus) menuButton.value?.focus()
}

function toggleMenu() {
  if (menuOpen.value) closeMenu()
  else openMenu()
}

/**
 * Keyboard use of the open menu: the arrow keys, Home and End move through the entries, Escape
 * closes it (and only it, not the dialog), Tab closes it and moves on.
 * @param {KeyboardEvent} event - the key pressed inside the menu
 */
function onMenuKeydown(event) {
  const items = menuItems()
  const index = items.indexOf(document.activeElement)
  const focus = (i) => {
    event.preventDefault()
    items[(i + items.length) % items.length]?.focus()
  }
  if (event.key === 'ArrowDown') focus(index + 1)
  else if (event.key === 'ArrowUp') focus(index - 1)
  else if (event.key === 'Home') focus(0)
  else if (event.key === 'End') focus(items.length - 1)
  else if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    closeMenu(true)
  } else if (event.key === 'Tab') {
    closeMenu()
  }
}

/**
 * Runs an entry of the "Mehr" menu; the menu closes first so a confirmation is not covered.
 * @param {{run: Function}} action - an entry of {@link menuActions}
 */
function chooseMenuAction(action) {
  closeMenu()
  action.run()
}

/** Puts the focus into the form that has just replaced the action bar. */
async function focusFooterForm() {
  await nextTick()
  footerForm.value?.querySelector('input')?.focus()
}

async function loadInvoice() {
  loading.value = true
  error.value = null
  try {
    const response = await invoiceAPI.getInvoice(props.invoiceId)
    doc.value = response.data
    await loadBookkeepingDetails()
  } catch (err) {
    error.value = t('common.loadFailed', { reason: err.message })
  } finally {
    loading.value = false
  }
}

/** Payments, audit trail and the number of the related invoice / credit note. */
async function loadBookkeepingDetails() {
  const d = doc.value
  payments.value = []
  history.value = []
  relatedNumber.value = ''
  if (!d) return
  const requests = [invoiceAPI.getHistory(d.id)]
  if (isInvoice(d.documentTypeId) && !isDraft(d.documentStatusId)) {
    requests.push(invoiceAPI.getPayments(d.id))
  }
  const [historyResponse, paymentsResponse] = await Promise.all(requests)
  history.value = historyResponse.data || []
  payments.value = paymentsResponse ? paymentsResponse.data || [] : []
  const relatedId = d.cancelsInvoiceId || d.cancelledByInvoiceId
  if (relatedId) {
    const related = await invoiceAPI.getInvoice(relatedId)
    relatedNumber.value = related.data.invoiceNumber
  }
}

/** Runs a bookkeeping action, reloads the document and reports failures inline. */
async function runAction(action) {
  if (statusUpdating.value) return
  statusUpdating.value = true
  actionError.value = null
  try {
    await action()
    await loadInvoice()
    emit('status-updated')
  } catch (err) {
    actionError.value = err.message
  } finally {
    statusUpdating.value = false
  }
}

async function issue() {
  // Issued invoices are frozen, including the data the e-invoice is built from, so point out
  // missing master data now. A paper / PDF invoice can still be issued without it.
  let eInvoiceHint = ''
  try {
    const missing = (await invoiceAPI.checkXRechnung(doc.value.id)).data.missing || []
    if (missing.length) {
      eInvoiceHint = '\n\n' + t('view.eInvoiceMissing', { fields: missing.join(', ') })
    }
  } catch {
    // The check is advisory; issuing does not depend on it.
  }
  if (!confirm(t('view.confirmIssue') + eInvoiceHint)) {
    return
  }
  runAction(() => invoiceAPI.updateInvoiceStatus(doc.value.id, SENT))
}

/** Downloads the XRechnung; missing master data comes back as a readable message. */
async function downloadXRechnung() {
  actionError.value = null
  try {
    const response = await invoiceAPI.downloadXRechnung(doc.value.id)
    saveBlob(new Blob([response.data], { type: 'application/xml' }),
      `XRechnung_${doc.value.invoiceNumber}.xml`)
  } catch (err) {
    actionError.value = t('view.eInvoiceFailed', { reason: err.message })
  }
}

function saveBlob(blob, filename) {
  const url = window.URL.createObjectURL(blob)
  const a = document.createElement('a')
  a.href = url
  a.download = filename
  document.body.appendChild(a)
  a.click()
  document.body.removeChild(a)
  window.URL.revokeObjectURL(url)
}

/** Opens the payment form in place of the action bar, preset with the open amount. */
function openPaymentForm() {
  paymentForm.value = emptyPaymentForm(openAmount.value)
  actionError.value = null
  showCloneForm.value = false
  showPaymentForm.value = true
  focusFooterForm()
}

/** Opens the duplicate form in place of the action bar, preset with today's date. */
function openCloneForm() {
  cloneForm.value = emptyCloneForm()
  actionError.value = null
  showPaymentForm.value = false
  showCloneForm.value = true
  focusFooterForm()
}

function savePayment() {
  runAction(async () => {
    await invoiceAPI.recordPayment(doc.value.id, paymentForm.value)
    showPaymentForm.value = false
  })
}

function isReversed(payment) {
  return payments.value.some(p => p.reversesPaymentId === payment.id)
}

function reversePayment(payment) {
  if (!confirm(t('view.confirmReverse', { amount: formatCurrency(payment.amount), date: formatDate(payment.paymentDate) }))) {
    return
  }
  runAction(() => invoiceAPI.reversePayment(doc.value.id, payment.id))
}

function cancelInvoice() {
  if (!confirm(t('view.confirmCancelInvoice'))) {
    return
  }
  const original = doc.value
  runAction(async () => {
    const creditNote = (await invoiceAPI.cancelInvoice(original.id)).data
    if (confirm(t('view.confirmCopyAfterCancel', { number: creditNote.invoiceNumber }))) {
      await invoiceAPI.cloneInvoice(original.id, {
        invoiceDate: today(),
        workStartDate: original.workStartDate || null
      })
      emit('cloned')
    }
  })
}

/**
 * Cancels an offer or cost calculation. "Storniert" is final, so the user confirms it first
 * (an invoice is cancelled by {@link cancelInvoice}, with a credit note).
 */
function cancelOffer() {
  if (!confirm(t('view.confirmCancelOffer', { type: documentTypeLabel.value, number: doc.value.invoiceNumber }))) {
    return
  }
  updateStatus(CANCELLED)
}

/** Asks before the document leaves the list for the archive; the list then archives it. */
function requestArchive() {
  if (!confirm(t('view.confirmArchive', { type: documentTypeLabel.value, number: doc.value.invoiceNumber }))) {
    return
  }
  emit('archive-request', doc.value.id)
}

/**
 * Sets the status of an offer or cost calculation (invoices use the actions above).
 * @param {number} newStatus - id of the status to set
 */
async function updateStatus(newStatus) {
  if (!doc.value || statusUpdating.value) return
  statusUpdating.value = true
  actionError.value = null
  try {
    await invoiceAPI.updateInvoiceStatus(doc.value.id, newStatus)
    doc.value.documentStatusId = newStatus
    emit('status-updated')
  } catch (err) {
    actionError.value = t('documents.statusFailed', { reason: err.message })
  } finally {
    statusUpdating.value = false
  }
}

function close() {
  emit('update:visible', false)
}

async function downloadPdf() {
  if (!props.invoiceId) return
  pdfLoading.value = true
  actionError.value = null
  try {
    const response = await invoiceAPI.downloadInvoicePdf(props.invoiceId)
    saveBlob(new Blob([response.data], { type: 'application/pdf' }),
      `${documentTypeLabel.value}_${doc.value?.invoiceNumber || props.invoiceId}.pdf`)
  } catch (err) {
    actionError.value = 'PDF-Download fehlgeschlagen: ' + err.message
  } finally {
    pdfLoading.value = false
  }
}

async function cloneDocument() {
  if (!props.invoiceId || !doc.value) return
  cloneLoading.value = true
  actionError.value = null
  try {
    await invoiceAPI.cloneInvoice(props.invoiceId, {
      invoiceDate: cloneForm.value.invoiceDate || today(),
      workStartDate: cloneForm.value.workStartDate || null
    })
    showCloneForm.value = false
    emit('cloned')
    emit('update:visible', false)
  } catch (err) {
    // Not `error`: that one replaces the whole document view, and the form would be gone.
    actionError.value = t('view.duplicateFailed', { reason: err.message })
  } finally {
    cloneLoading.value = false
  }
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString('de-DE')
}

function formatDateTime(value) {
  if (!value) return '-'
  const date = new Date(String(value).replace(' ', 'T'))
  return isNaN(date.getTime()) ? value : date.toLocaleString('de-DE', { dateStyle: 'short', timeStyle: 'short' })
}

function formatCurrency(value) {
  if (!value && value !== 0) return '-'
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(value)
}

</script>

<style scoped>
.modal-panel {
  padding: 0;
  max-width: 800px;
  box-shadow: var(--shadow-lg);
}

.modal-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 20px 24px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
  border-radius: 12px 12px 0 0;
}

.modal-header h2 {
  margin: 0;
  color: var(--color-text-strong);
  font-size: 1.4em;
}

.close-btn {
  background: none;
  border: none;
  font-size: 1.8em;
  cursor: pointer;
  color: var(--color-text-subtle);
  line-height: 1;
  padding: 0;
  width: 32px;
  height: 32px;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
  transition: all 0.2s;
}

.close-btn:hover {
  background: var(--color-border);
  color: var(--color-danger);
}

.invoice-content {
  padding: 24px;
}

.loading, .error {
  padding: 40px;
  text-align: center;
}

.error {
  color: var(--color-danger);
}

.invoice-header {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 16px;
  margin-bottom: 24px;
  padding: 16px;
  background: var(--color-surface-subtle);
  border-radius: 8px;
}

.header-row {
  display: flex;
  flex-direction: column;
  gap: 4px;
}

.label {
  font-size: 0.85em;
  color: var(--color-text-muted);
  font-weight: 500;
}

.value {
  font-size: 1.1em;
  color: var(--color-text-strong);
  font-weight: 600;
}

.value.overdue {
  color: var(--color-danger);
}

.status-badge {
  display: inline-block;
  padding: 4px 12px;
  border-radius: 20px;
  font-size: 0.85em;
  font-weight: 600;
  width: fit-content;
}


.tax-note {
  margin: 8px 0 0;
  font-size: 0.85em;
  color: var(--color-text-muted);
}

.payments-section,
.history-section {
  margin-bottom: 24px;
}

.payments-section tr.reversed td {
  color: var(--color-text-subtle);
  text-decoration: line-through;
}

.history-section summary {
  cursor: pointer;
  font-weight: 600;
  color: var(--color-text-strong);
}

.history-list {
  list-style: none;
  margin: var(--space-2) 0 0;
  padding: 0;
  font-size: 0.9em;
}

.history-entry {
  position: relative;
  display: grid;
  grid-template-columns: 10em 10em minmax(0, 1fr) auto;
  gap: var(--space-1) var(--space-3);
  padding: var(--space-1) 0 var(--space-1) var(--space-4);
  border-left: 2px solid var(--color-border);
}

.history-entry::before {
  content: '';
  position: absolute;
  left: -5px;
  top: 0.7em;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--color-text-muted);
}

.history-date {
  color: var(--color-text-muted);
}

.history-action {
  font-weight: 600;
  color: var(--color-text-strong);
}

.history-actor {
  color: var(--color-text-muted);
}

.history-details {
  color: var(--color-text);
  overflow-wrap: anywhere;
}

@media (max-width: 760px) {
  .history-entry {
    grid-template-columns: minmax(0, 1fr);
    gap: 0;
  }
}

.items-section {
  margin-bottom: 24px;
}

.items-section h3 {
  margin: 0 0 12px 0;
  color: var(--color-text-strong);
  font-size: 1.1em;
}

.items-table {
  width: 100%;
  border-collapse: collapse;
}

.items-table th,
.items-table td {
  padding: 12px;
  text-align: left;
  border-bottom: 1px solid var(--color-border);
}

.items-table th {
  background: var(--color-surface-subtle);
  font-weight: 600;
  color: var(--color-text-strong);
  font-size: 0.85em;
}

.items-table td {
  color: var(--color-text-strong);
}

.items-table .numeric {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.item-name {
  font-weight: 500;
}

.item-description {
  font-size: 0.85em;
  color: var(--color-text-muted);
  margin-top: 2px;
}

.item-row-child td {
  background: var(--color-surface-subtle);
}

.item-description-cell {
  padding-left: 32px;
}

.item-description-cell .item-name {
  font-size: 12px;
  color: var(--color-text-muted);
  font-weight: 400;
}

.no-items {
  padding: 20px;
  text-align: center;
  color: var(--color-text-muted);
  font-style: italic;
  background: var(--color-surface-subtle);
  border-radius: 8px;
}

.totals-section {
  margin-left: auto;
  width: fit-content;
  min-width: 250px;
  padding: 16px;
  background: var(--color-surface-subtle);
  border-radius: 8px;
}

.total-row {
  display: flex;
  justify-content: space-between;
  gap: 20px;
  padding: 8px 0;
  color: var(--color-text-strong);
}

.total-row.gross {
  font-weight: 700;
  font-size: 1.1em;
  color: var(--color-text-strong);
  border-top: 2px solid var(--color-border);
  margin-top: 8px;
  padding-top: 12px;
}

.notes-section {
  margin-top: 24px;
  padding: 16px;
  background: var(--color-warning-soft);
  border-radius: 8px;
  border-left: 4px solid var(--color-warning);
}

.notes-section h3 {
  margin: 0 0 8px 0;
  color: var(--color-warning-text);
  font-size: 1em;
}

.notes-section p {
  margin: 0;
  color: var(--color-warning-text);
  white-space: pre-wrap;
}

/*
 * The footer holds every action of the document. It sticks to the bottom of the scrolling
 * panel, so the next step stays reachable however long the document is.
 */
.modal-footer {
  position: sticky;
  bottom: 0;
  z-index: 1;
  padding: var(--space-4) var(--space-6);
  border-top: 1px solid var(--color-border);
  border-radius: 0 0 var(--radius-xl) var(--radius-xl);
  background: var(--color-surface-subtle);
  box-shadow: var(--shadow-up);
}

.action-error {
  margin-bottom: var(--space-3);
  color: var(--color-danger);
  font-size: var(--font-size-md);
}

.action-notice {
  margin-bottom: var(--space-3);
  color: var(--color-success-hover);
  font-size: var(--font-size-md);
}

/*
 * Top row: the lifecycle strip. Row below: the document actions on the left, the menu and
 * "Schließen" on the right ("end").
 */
.action-bar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-3);
  min-width: 0;
}

.action-group {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.action-group-end {
  margin-left: auto;
}

/*
 * The lifecycle strip changes the document's status, the row below only works with the document
 * as it is. A row, a tint and a caption of its own keep the two apart: without them "Angenommen"
 * looked like one more option next to "PDF" and "Duplizieren".
 */
.action-group-lifecycle {
  flex: 0 0 100%;
  box-sizing: border-box;
  padding: var(--space-2) var(--space-3);
  border: 1px solid var(--color-primary-border);
  border-left-width: 4px;
  border-radius: var(--radius-lg);
  background: var(--color-primary-soft);
}

.lifecycle-caption {
  margin-right: var(--space-1);
  color: var(--color-text-strong);
  font-size: var(--font-size-md);
  font-weight: var(--font-weight-semibold);
}

/* Tighter than a form button: the row of an archived invoice holds seven of them. */
.action-bar .btn,
.footer-form-buttons .btn {
  padding: 8px var(--space-3);
  white-space: nowrap;
}

.more-menu {
  position: relative;
}

.more-menu-caret {
  font-size: 0.8em;
}

/* Opens upwards: the bar is the last thing in the dialog. */
.more-menu-list {
  position: absolute;
  right: 0;
  bottom: calc(100% + var(--space-1));
  z-index: 2;
  min-width: 200px;
  margin: 0;
  padding: var(--space-1) 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-lg);
  background: var(--color-surface);
  box-shadow: var(--shadow-lg);
  list-style: none;
}

.more-menu-item {
  display: block;
  width: 100%;
  padding: 10px var(--space-4);
  border: 0;
  background: none;
  color: var(--color-text);
  font: inherit;
  text-align: left;
  cursor: pointer;
}

.more-menu-item:hover:not(:disabled),
.more-menu-item:focus-visible {
  background: var(--color-surface-muted);
  outline: none;
}

.more-menu-item:focus-visible {
  box-shadow: inset 0 0 0 2px var(--color-focus-ring);
}

.more-menu-item:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}

.more-menu-item-danger {
  color: var(--color-danger);
}

.more-menu-item-danger:hover:not(:disabled),
.more-menu-item-danger:focus-visible {
  background: var(--color-danger-soft);
}

/* The payment and the duplicate form take the place of the bar while they are open. */
.footer-form h3 {
  margin: 0 0 var(--space-3);
  color: var(--color-text-strong);
  font-size: var(--font-size-md);
}

.footer-form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-3) var(--space-4);
  margin-bottom: var(--space-4);
}

.footer-form-row-payment {
  grid-template-columns: repeat(4, minmax(0, 1fr));
}

.footer-form-row .form-group {
  min-width: 0;
  margin-bottom: 0;
}

.footer-form-row .form-group label {
  margin-bottom: var(--space-1);
  color: var(--color-text-muted);
  font-size: 13px;
  font-weight: var(--font-weight-normal);
}

.footer-form-row .form-group input {
  padding: 8px;
  background: var(--color-surface);
  color: var(--color-text);
}

.footer-form-buttons {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2);
}

/* A phone held sideways: a sticky bar would cover most of the document. */
@media (max-height: 480px) {
  .modal-footer { position: static; }
}

@media (max-width: 600px) {
  .modal-backdrop { align-items: stretch; padding: 0; }
  .modal-panel { width: 100%; max-width: none; max-height: 100dvh; height: 100dvh; border-radius: 0; }
  .modal-header { padding: 14px; }
  .modal-header h2 { font-size: 1.1em; }
  .invoice-content { padding: 12px; }
  .invoice-header { grid-template-columns: repeat(auto-fit, minmax(min(100%, 160px), 1fr)); padding: 12px; }
  .totals-section { min-width: 0; width: 100%; }
  /* The panel fills the screen: the footer stays at its bottom even under a short document. */
  .modal-panel { display: flex; flex-direction: column; }
  .modal-footer { margin-top: auto; padding: var(--space-3); border-radius: 0; }
  /* The caption above its buttons; the other groups share the rows below the strip. */
  .action-group { flex: 1 1 auto; }
  .action-group-lifecycle { flex-basis: 100%; }
  .lifecycle-caption { flex-basis: 100%; }
  .action-group-end { margin-left: 0; }
  .action-bar .btn, .footer-form-buttons .btn { flex: 1 1 auto; min-height: 40px; }
  .more-menu { display: flex; flex: 1 1 auto; }
  .more-menu-list { right: auto; left: 0; }
  .more-menu-item { min-height: 44px; }
  .footer-form-row-payment { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
</style>

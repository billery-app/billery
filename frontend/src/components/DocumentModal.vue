<template>
  <div v-if="visible" class="modal-backdrop document-modal" @click.self="cancel">
    <div class="modal-panel wide">
      <h2>{{ modalTitle }}</h2>
      <form
        @submit.prevent="submit"
        @focusin="onDocumentFieldFocus"
        @focusout="onDocumentFieldBlur"
        class="modal-form"
      >
        <div class="scroll-container">
        <div class="form-row">
          <div class="form-group customer-group">
            <label>Kunde:</label>
            <div class="customer-select-row">
              <CustomerPicker v-model="form.customerId" v-model:customer="selectedCustomer" />
              <button type="button" @click="showCreateCustomer = true" class="btn btn-small btn-success" title="Neuen Kunden anlegen">+</button>
            </div>
          </div>
          <div class="form-group">
            <label>Dokumentnummer:</label>
            <input v-model="form.invoiceNumber" readonly :placeholder="numberPlaceholder" class="invoice-number-preview" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label>Datum:</label>
            <input v-model="form.invoiceDate" type="date" required />
          </div>
          <div class="form-group">
            <label>Fälligkeitsdatum:</label>
            <input v-model="form.dueDate" type="date" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label>Leistungsdatum:<span v-if="isInvoiceDocument" class="required-hint"> (Pflicht zum Ausstellen)</span></label>
            <input v-model="form.workStartDate" type="date" />
          </div>
          <div class="form-group" v-if="showWorkEndDate">
            <label>Leistungsende:</label>
            <div class="date-with-toggle">
              <input v-model="form.workEndDate" type="date" />
              <button type="button" @click="showWorkEndDate = false" class="btn btn-small btn-danger" title="Zeitraum entfernen">&times;</button>
            </div>
          </div>
          <div class="form-group" v-else>
            <label>&nbsp;</label>
            <button type="button" @click="showWorkEndDate = true" class="btn btn-small btn-secondary">Zeitraum angeben</button>
          </div>
        </div>
        <div class="form-group">
          <label>Notizen:</label>
          <textarea
            v-model="form.notes"
            class="document-notes-input"
          ></textarea>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label>Besteuerung:</label>
            <span v-if="isKleinunternehmer" class="tax-info" data-testid="small-business-info">
              Kleinunternehmer (§ 19 UStG) – keine Umsatzsteuer
            </span>
            <select v-else v-model="form.taxTreatment" data-testid="tax-treatment">
              <option v-for="t in TAX_TREATMENTS" :key="t.value" :value="t.value">{{ t.label }}</option>
            </select>
          </div>
          <div v-if="!isKleinunternehmer && form.taxTreatment === 'TAX_EXEMPT'" class="form-group">
            <label>Grund der Steuerbefreiung:</label>
            <input v-model="form.taxExemptionReason" placeholder="z. B. gemäß § 4 Nr. 14 UStG" />
          </div>
        </div>

        <div class="layout-section">
          <details class="layout-details">
            <summary>Layout</summary>
            <div class="layout-body">
              <div class="layout-row">
                <label>
                  <input
                    type="radio"
                    name="layout-mode"
                    value="default"
                    :checked="form.templateId === null"
                    @change="onLayoutModeDefault"
                  />
                  Standardvorlage für diesen Dokumenttyp
                </label>
              </div>
              <div class="layout-row">
                <label>
                  <input
                    type="radio"
                    name="layout-mode"
                    value="pick"
                    :checked="form.templateId !== null"
                    @change="onLayoutModePick"
                  />
                  Gespeicherte Vorlage verwenden
                </label>
                <select
                  v-model="form.templateId"
                  :disabled="layoutMode !== 'pick'"
                  @change="onTemplatePicked"
                >
                  <option :value="null" disabled>Vorlage auswählen…</option>
                  <option v-for="t in availableTemplates" :key="t.id" :value="t.id">
                    {{ t.name }}{{ t.isDefault ? ' (Standard)' : '' }}
                  </option>
                </select>
              </div>
              <div class="layout-row">
                <button
                  type="button"
                  class="btn btn-secondary"
                  :disabled="!canCustomize"
                  :title="customizeTooltip"
                  @click="openCustomize"
                >
                  Layout für dieses Dokument anpassen…
                </button>
                <small v-if="!canCustomize" class="layout-hint">{{ customizeHint }}</small>
                <small v-else-if="form.templateId" class="layout-hint">
                  Aktuell: eigene Vorlage (id #{{ form.templateId }})
                </small>
              </div>
            </div>
          </details>
        </div>

        <div class="items-section">
          <div class="items-header">
            <h3>Positionen</h3>
            <div class="item-type-tabs">
              <button type="button" :class="['tab', itemTypeTab === 'articles' ? 'active' : '']" @click="itemTypeTab = 'articles'">Artikel</button>
              <button type="button" :class="['tab', itemTypeTab === 'services' ? 'active' : '']" @click="itemTypeTab = 'services'">Leistungen</button>
            </div>
          </div>

          <div v-if="itemTypeTab === 'articles'" class="item-selector">
            <div class="selector-row">
              <select v-model="selectedArticleId" @change="onArticleSelect">
                <option value="">Artikel auswählen...</option>
                <option v-for="a in articles" :key="a.id" :value="a.id">{{ a.name }} ({{ formatCurrency(a.salesPrice) }})</option>
              </select>
              <button type="button" @click="showCreateArticle = true" class="btn btn-small btn-success" title="Neuen Artikel anlegen">+</button>
            </div>
            <div v-if="selectedArticle" class="selected-item-preview">
              <span>{{ selectedArticle.name }} - {{ selectedArticle.description }}</span>
              <button type="button" @click="addSelectedArticle" class="btn btn-small btn-primary">Hinzufügen</button>
            </div>
          </div>

          <div v-if="itemTypeTab === 'services'" class="item-selector">
            <div class="selector-row">
              <select v-model="selectedServiceId" @change="onServiceSelect">
                <option value="">Leistung auswählen...</option>
                <option v-for="s in services" :key="s.id" :value="s.id">{{ s.name }} ({{ formatCurrency(s.hourlyRate) }}/h)</option>
              </select>
              <button type="button" @click="showCreateService = true" class="btn btn-small btn-success" title="Neue Leistung anlegen">+</button>
            </div>
            <div v-if="selectedService" class="selected-item-preview">
              <span>{{ selectedService.name }} - {{ selectedService.description }}</span>
              <button type="button" @click="addSelectedService" class="btn btn-small btn-primary">Hinzufügen</button>
            </div>
          </div>
        </div>

        <div v-for="(item, index) in form.items" :key="item._key || index" class="items-tree">
          <InvoiceItemRow
            :item="item"
            :parent-array="form.items"
            :index="index"
            :articles="articles"
            :services="services"
            :depth="0"
            :max-depth="2"
            :show-tax-rate="showTaxRates"
          />
        </div>

        <div v-if="form.items.length" class="totals-summary" data-testid="totals-summary">
          <div class="total-line">
            <span>Netto</span><span>{{ formatAmount(vat.net) }}</span>
          </div>
          <div v-for="group in vatLines" :key="group.rate" class="total-line">
            <span>MwSt. {{ formatRate(group.rate) }}<template v-if="vatLines.length > 1"> auf {{ formatAmount(group.net) }}</template></span>
            <span>{{ formatAmount(group.tax) }}</span>
          </div>
          <div class="total-line gross">
            <span>Gesamt</span><span>{{ formatAmount(vat.gross) }}</span>
          </div>
          <p v-if="taxNote" class="tax-note">{{ taxNote }}</p>
        </div>

        </div>
        <div class="form-actions">
          <button type="submit" class="btn btn-primary">Speichern</button>
          <button type="button" @click="cancel" class="btn btn-secondary">Abbrechen</button>
        </div>
      </form>
    </div>

    <CustomerCreateModal
      v-model:visible="showCreateCustomer"
      @created="onCustomerCreated"
    />

    <div v-if="showCreateArticle" class="modal-backdrop" @click.self="showCreateArticle = false">
      <div class="modal-panel">
        <h2>Neuer Artikel</h2>
        <form @submit.prevent="createArticleInline">
          <div class="form-group">
            <label>Name:</label>
            <input v-model="articleForm.name" required />
          </div>
          <div class="form-group">
            <label>Beschreibung:</label>
            <textarea v-model="articleForm.description"></textarea>
          </div>
          <div class="form-group">
            <label>Einheit:</label>
            <input v-model="articleForm.unit" placeholder="z.B. Stk, kg, m" />
          </div>
          <div class="form-group">
            <label>Einkaufspreis:</label>
            <input v-model="articleForm.purchasePrice" type="number" step="0.01" />
          </div>
          <div class="form-group">
            <label>Verkaufspreis:</label>
            <input v-model="articleForm.salesPrice" type="number" step="0.01" required />
          </div>
          <div class="form-actions">
            <button type="submit" class="btn btn-primary">Artikel anlegen</button>
            <button type="button" @click="showCreateArticle = false" class="btn btn-secondary">Abbrechen</button>
          </div>
        </form>
      </div>
    </div>

    <div v-if="showCreateService" class="modal-backdrop" @click.self="showCreateService = false">
      <div class="modal-panel">
        <h2>Neue Leistung</h2>
        <form @submit.prevent="createServiceInline">
          <div class="form-group">
            <label>Name:</label>
            <input v-model="serviceForm.name" required />
          </div>
          <div class="form-group">
            <label>Beschreibung:</label>
            <textarea v-model="serviceForm.description"></textarea>
          </div>
          <div class="form-group">
            <label>Stundensatz:</label>
            <input v-model="serviceForm.hourlyRate" type="number" step="0.01" required />
          </div>
          <div class="form-group" v-if="equipmentList.length">
            <label>Verknüpfte Ausstattung:</label>
            <select v-model="serviceForm.equipmentId">
              <option :value="null">Keine</option>
              <option v-for="eq in equipmentList" :key="eq.id" :value="eq.id">{{ eq.name }}</option>
            </select>
          </div>
          <div class="form-actions">
            <button type="submit" class="btn btn-primary">Leistung anlegen</button>
            <button type="button" @click="showCreateService = false" class="btn btn-secondary">Abbrechen</button>
          </div>
        </form>
      </div>
    </div>

    <div v-if="showTemplateEditor" class="modal-backdrop template-editor-modal" @click.self="onBackdropClick">
      <div class="template-editor-container">
        <TemplateEditor
          ref="editorRef"
          :template="editingCustomTemplate"
          :live-data="editingLiveData"
          @save="onCustomTemplateSaved"
          @cancel="closeTemplateEditor"
        />
      </div>
    </div>

    <aside v-if="keyboardZoomDebugEnabled" class="keyboard-zoom-debug" data-testid="keyboard-zoom-debug">
      <strong>Keyboard zoom debug · dev only</strong>
      <dl>
        <dt>Browser viewport</dt>
        <dd>{{ keyboardZoomDebug.liveHeight }}px · scale {{ formatDebugScale(keyboardZoomDebug.liveScale) }}</dd>
        <dt>Focus baseline</dt>
        <dd>{{ keyboardZoomDebug.baselineHeight ?? '—' }}px · scale {{ formatDebugScale(keyboardZoomDebug.baselineScale) }}</dd>
        <dt>Simulated keyboard</dt>
        <dd>{{ keyboardZoomDebug.simulatedHeight ?? '—' }}px · scale {{ formatDebugScale(keyboardZoomDebug.simulatedScale) }}</dd>
        <dt>Focused element</dt>
        <dd>{{ keyboardZoomDebug.focusedElement }}</dd>
        <dt>Viewport meta</dt>
        <dd class="debug-meta">{{ keyboardZoomDebug.viewportMeta }}</dd>
      </dl>
      <p class="debug-status" data-testid="keyboard-zoom-debug-status" role="status">{{ keyboardZoomDebug.status }}</p>
      <p class="debug-upload-status" data-testid="keyboard-zoom-telemetry-status" role="status">{{ telemetryUploadStatus }}</p>
      <div class="debug-actions">
        <button type="button" @click="startKeyboardZoomSimulation">Start simulation</button>
        <button type="button" :disabled="keyboardZoomDebug.baselineScale === null" @click="simulateKeyboardOpen">Keyboard open</button>
        <button type="button" :disabled="keyboardZoomDebug.baselineScale === null" @click="simulateKeyboardClose">Keyboard close</button>
        <button type="button" @click="resetKeyboardZoomSimulation">Reset</button>
        <button type="button" :disabled="keyboardZoomDebug.telemetryEventCount === 0 || telemetrySending" @click="sendKeyboardZoomDiagnostics">Send diagnostics ({{ keyboardZoomDebug.telemetryEventCount }})</button>
      </div>
    </aside>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import invoiceAPI from '../services/invoiceService.js'
import articleAPI from '../services/articleService.js'
import customerAPI from '../services/customerService.js'
import equipmentAPI from '../services/equipmentService.js'
import ownerAPI from '../services/ownerService.js'
import templateAPI from '../services/templateService.js'
import InvoiceItemRow from './editor/InvoiceItemRow.vue'
import CustomerCreateModal from './CustomerCreateModal.vue'
import CustomerPicker from './CustomerPicker.vue'
import TemplateEditor from './TemplateEditor.vue'
import { useDocumentType } from '../composables/useDocumentType.js'
import { DRAFT, CANCELLED, CONVERTED, isCancelled, isDraft } from '../utils/documentStatus.js'
import { INVOICE, OFFER, isInvoice, isBookkeepingRecord } from '../data/documentTypes.js'
import { buildPreviewVariables, buildLineItemsData } from '../utils/invoiceVariables.js'
import { TAX_TREATMENTS, SMALL_BUSINESS, calculateVat, chargesVat, legalNote, formatRate } from '../utils/vat.js'
import { captureKeyboardZoomSnapshot, createKeyboardZoomTelemetry } from '../utils/keyboardZoomTelemetry.js'

const { documentFormTitle } = useDocumentType()

const props = defineProps({
  visible: Boolean,
  articles: {
    type: Array,
    default: () => []
  },
  services: {
    type: Array,
    default: () => []
  },
  invoice: {
    type: Object,
    default: null
  },
  convertFromOffer: {
    type: Boolean,
    default: false
  },
  convertFromCostCalc: {
    type: Boolean,
    default: false
  },
  documentType: {
    type: Number,
    default: 1
  },
  initialCustomerId: {
    type: Number,
    default: null
  }
})

const emit = defineEmits(['update:visible', 'created', 'updated', 'reload-articles', 'reload-services'])

// The customer chosen in the picker; also the cache of the live preview (no customer list loaded).
const selectedCustomer = ref(null)

const keyboardZoomDebugEnabled = import.meta.env.DEV
  && new URLSearchParams(window.location.search).get('keyboardZoomDebug') === '1'
const keyboardZoomTelemetry = keyboardZoomDebugEnabled
  ? createKeyboardZoomTelemetry({ enabled: true })
  : null
const keyboardZoomDebug = ref({
  liveHeight: 0,
  liveScale: 1,
  baselineHeight: null,
  baselineScale: null,
  simulatedHeight: null,
  simulatedScale: null,
  telemetryEventCount: 0,
  focusedElement: 'none',
  viewportMeta: 'not found',
  status: 'Idle — start a simulation or focus a document field.'
})
const telemetryUploadStatus = ref('Logs stay on this device until you tap Send diagnostics.')
const telemetrySending = ref(false)

function recordKeyboardZoomTelemetry(event, details = {}) {
  if (!keyboardZoomDebugEnabled) return
  keyboardZoomDebug.value.telemetryEventCount = keyboardZoomTelemetry.record(
    event,
    details,
    captureKeyboardZoomSnapshot(virtualKeyboardApi)
  )
}

async function sendKeyboardZoomDiagnostics() {
  if (!keyboardZoomDebugEnabled || telemetrySending.value) return
  telemetrySending.value = true
  telemetryUploadStatus.value = 'Sending diagnostics to the Vite dev server…'
  recordKeyboardZoomTelemetry('diagnostics.send.started', {
    source: 'debug-panel',
    result: 'user-requested'
  })
  try {
    const result = await keyboardZoomTelemetry.send()
    telemetryUploadStatus.value = `Saved ${result.eventCount} events · ${result.logFile || 'check the Vite terminal'}`
  } catch {
    telemetryUploadStatus.value = 'Send failed · check that the Vite dev server is reachable'
  } finally {
    telemetrySending.value = false
  }
}

const isEditMode = computed(() => !!props.invoice)

const currentTypeId = computed(() => props.invoice?.documentTypeId || props.documentType)

// The type the saved document will have (an offer being converted becomes an invoice).
const targetTypeId = computed(() =>
  props.convertFromOffer ? INVOICE : props.convertFromCostCalc ? OFFER : currentTypeId.value
)

const isInvoiceDocument = computed(() => isInvoice(targetTypeId.value))

// Invoice drafts get their number when they are issued (no gaps from deleted drafts).
const numberPlaceholder = computed(() => {
  if (isInvoiceDocument.value && (!isEditMode.value || isDraft(props.invoice?.documentStatusId)
      || props.convertFromOffer)) {
    return 'Wird beim Ausstellen vergeben'
  }
  return isEditMode.value ? '' : 'Wird automatisch generiert'
})

/** "Neues Angebot" when creating, "Angebot bearbeiten" when editing. */
const modalTitle = computed(() => documentFormTitle(currentTypeId.value, isEditMode.value))

const form = ref({
  customerId: '',
  invoiceNumber: '',
  invoiceDate: new Date().toISOString().split('T')[0],
  dueDate: '',
  workStartDate: '',
  workEndDate: '',
  notes: '',
  documentStatusId: DRAFT,
  documentTypeId: INVOICE,
  items: [],
  templateId: null,
  taxTreatment: 'STANDARD',
  taxExemptionReason: ''
})

const itemTypeTab = ref('articles')
const selectedArticleId = ref('')
const selectedArticle = ref(null)
const selectedServiceId = ref('')
const selectedService = ref(null)
const showWorkEndDate = ref(false)

const equipmentList = ref([])

const showCreateCustomer = ref(false)
const showCreateArticle = ref(false)
const showCreateService = ref(false)

const isKleinunternehmer = ref(false)

// A Kleinunternehmer always invoices under § 19 UStG; the server enforces the same rule.
const effectiveTreatment = computed(() =>
  isKleinunternehmer.value ? SMALL_BUSINESS : (form.value.taxTreatment || 'STANDARD')
)
const showTaxRates = computed(() => chargesVat(effectiveTreatment.value))
// Display only: the server recomputes the amounts with the same rules.
const vat = computed(() => calculateVat(form.value.items, effectiveTreatment.value))
const vatLines = computed(() => (showTaxRates.value ? vat.value.groups : []))
const taxNote = computed(() => legalNote(effectiveTreatment.value, form.value.taxExemptionReason))

function formatAmount(amount) {
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(amount || 0)
}

// === Per-document layout override state ===
const availableTemplates = ref([])
const showTemplateEditor = ref(false)
const editingCustomTemplate = ref(null)
const editingLiveData = ref(null)
// True when the user clicked "Customize…" on a document that has no override yet. The editor's
// save handler will create the per-document clone + persist template_id only on save. False when
// the document already has an override; the editor saves back to the existing row.
const editingIsNewOverride = ref(false)
const editorRef = ref(null)

const layoutMode = computed(() => (form.value.templateId === null ? 'default' : 'pick'))
// "Customize" is only allowed for documents that have been saved at least once. Without a server
// id, there's no row in the database to attach a clone to.
const canCustomize = computed(() => isEditMode.value && !!props.invoice?.id)
const customizeTooltip = computed(() =>
  canCustomize.value
    ? 'Erstellt eine eigene Vorlage nur für dieses Dokument'
    : 'Bitte zuerst das Dokument speichern'
)
const customizeHint = computed(() =>
  canCustomize.value
    ? ''
    : 'Bitte zuerst das Dokument speichern, dann ein eigenes Layout anlegen.'
)

async function loadOwnerSettings() {
  try {
    const response = await ownerAPI.getPrimaryOwner()
    isKleinunternehmer.value = response.data?.isSmallBusiness || false
  } catch (e) {
    isKleinunternehmer.value = false
  }
}

const articleForm = ref({ name: '', description: '', unit: 'Stk', purchasePrice: 0, salesPrice: 0 })
const serviceForm = ref({ name: '', description: '', hourlyRate: 0, equipmentId: null })

// Some mobile browsers leave the page magnified after a text field loses
// focus or dismissing the software keyboard. Temporarily constrain the
// viewport to the scale captured on focus, then restore the app's viewport
// settings so pinch zoom remains available.
const KEYBOARD_VIEWPORT_DELTA = 100
const KEYBOARD_CLOSE_SETTLE_MS = 160
const VIEWPORT_SCALE_CONSTRAINT_MS = 600
let documentFieldFocusScale = null
let viewportMetaToRestore = null
let originalViewportMetaContent = null
let viewportRestoreTimer = null
let keyboardCloseCheckTimer = null
let documentFieldFocusViewportHeight = null
let documentFieldKeyboardIsOpen = false
let documentFieldKeyboardOpenViewportHeight = null
let virtualKeyboardApi = null

function restoreViewportMeta() {
  recordKeyboardZoomTelemetry('viewport-meta.restore.called', {
    hadStoredMeta: Boolean(viewportMetaToRestore && originalViewportMetaContent !== null),
    timerPending: viewportRestoreTimer !== null,
    metaBefore: document.querySelector('meta[name="viewport"]')?.content || 'not-found'
  })
  if (viewportRestoreTimer !== null) {
    window.clearTimeout(viewportRestoreTimer)
    viewportRestoreTimer = null
  }
  if (viewportMetaToRestore && originalViewportMetaContent !== null) {
    viewportMetaToRestore.content = originalViewportMetaContent
  }
  viewportMetaToRestore = null
  originalViewportMetaContent = null
  recordKeyboardZoomTelemetry('viewport-meta.restore.completed', {
    metaAfter: document.querySelector('meta[name="viewport"]')?.content || 'not-found'
  })
  refreshKeyboardZoomDebug()
}

function isDocumentTextField(element) {
  return element instanceof Element && element.matches(
    'input:not([type="button"]):not([type="submit"]):not([type="reset"]):not([type="checkbox"]):not([type="radio"]):not([type="file"]):not([type="hidden"]), textarea'
  )
}

function currentVisualViewportHeight() {
  const viewport = window.visualViewport
  return viewport?.height || window.innerHeight
}

function formatDebugScale(scale) {
  return scale === null || scale === undefined ? '—' : `${Number(scale).toFixed(2)}×`
}

function refreshKeyboardZoomDebug(status = null) {
  if (!keyboardZoomDebugEnabled) return

  const viewport = window.visualViewport
  const activeElement = document.activeElement
  const tag = activeElement?.tagName?.toLowerCase() || 'none'
  const className = typeof activeElement?.className === 'string' ? activeElement.className.trim() : ''
  Object.assign(keyboardZoomDebug.value, {
    liveHeight: Math.round(viewport?.height || window.innerHeight),
    liveScale: viewport?.scale || 1,
    focusedElement: className ? `${tag}.${className.split(/\s+/)[0]}` : tag,
    viewportMeta: document.querySelector('meta[name="viewport"]')?.content || 'not found',
    ...(status ? { status } : {})
  })
}

function restoreViewportScale(scale, reason = 'Focus left the field') {
  const viewportMeta = document.querySelector('meta[name="viewport"]')
  if (!viewportMeta) {
    recordKeyboardZoomTelemetry('viewport-scale.reset.skipped', { reason, result: 'viewport-meta-missing' })
    return
  }

  recordKeyboardZoomTelemetry('viewport-scale.reset.started', {
    reason,
    targetScale: scale,
    currentScale: window.visualViewport?.scale || 1,
    metaBefore: viewportMeta.content
  })
  restoreViewportMeta()
  const originalContent = viewportMeta.content
  const viewportBase = originalContent
    .split(',')
    .map(directive => directive.trim())
    .filter(directive => directive && !/^(initial-scale|minimum-scale|maximum-scale)\s*=/i.test(directive))
  const targetScale = Number(scale.toFixed(3))

  viewportMetaToRestore = viewportMeta
  originalViewportMetaContent = originalContent
  viewportMeta.content = [...viewportBase, `initial-scale=${targetScale}`, `minimum-scale=${targetScale}`, `maximum-scale=${targetScale}`].join(', ')
  // iOS Safari can apply viewport changes after the keyboard animation; keep
  // the temporary scale constraint in place long enough for that update.
  viewportRestoreTimer = window.setTimeout(() => {
    recordKeyboardZoomTelemetry('viewport-scale.constraint-expired', {
      targetScale,
      timerMs: VIEWPORT_SCALE_CONSTRAINT_MS,
      currentScale: window.visualViewport?.scale || 1,
      metaBefore: viewportMeta.content
    })
    restoreViewportMeta()
  }, VIEWPORT_SCALE_CONSTRAINT_MS)
  recordKeyboardZoomTelemetry('viewport-scale.reset.requested', {
    reason,
    targetScale,
    timerMs: VIEWPORT_SCALE_CONSTRAINT_MS,
    metaAfter: viewportMeta.content
  })
  refreshKeyboardZoomDebug(`Reset requested: ${formatDebugScale(targetScale)} (${reason})`)
}

function clearKeyboardCloseCheck() {
  recordKeyboardZoomTelemetry('keyboard.close-check.clear', { timerPending: keyboardCloseCheckTimer !== null })
  if (keyboardCloseCheckTimer !== null) {
    window.clearTimeout(keyboardCloseCheckTimer)
    keyboardCloseCheckTimer = null
  }
}

function scheduleKeyboardCloseCheck(source) {
  clearKeyboardCloseCheck()
  recordKeyboardZoomTelemetry('keyboard.close-check.scheduled', {
    source,
    timerMs: KEYBOARD_CLOSE_SETTLE_MS,
    keyboardOpenHeight: documentFieldKeyboardOpenViewportHeight,
    viewportHeight: currentVisualViewportHeight()
  })
  refreshKeyboardZoomDebug(`${source}: viewport expanded; waiting for keyboard close to settle`)
  keyboardCloseCheckTimer = window.setTimeout(() => {
    keyboardCloseCheckTimer = null
    recordKeyboardZoomTelemetry('keyboard.close-check.fired', {
      source,
      keyboardOpen: documentFieldKeyboardIsOpen,
      viewportHeight: currentVisualViewportHeight(),
      keyboardOpenHeight: documentFieldKeyboardOpenViewportHeight,
      focusScale: documentFieldFocusScale
    })
    if (!props.visible || !documentFieldKeyboardIsOpen || documentFieldFocusScale === null) return

    const viewportHeight = currentVisualViewportHeight()
    const keyboardOpenHeight = documentFieldKeyboardOpenViewportHeight
    if (keyboardOpenHeight === null || viewportHeight - keyboardOpenHeight < KEYBOARD_VIEWPORT_DELTA) {
      recordKeyboardZoomTelemetry('keyboard.close-check.rejected', {
        source,
        result: 'viewport-expansion-below-threshold',
        viewportHeight,
        keyboardOpenHeight,
        delta: keyboardOpenHeight === null ? null : viewportHeight - keyboardOpenHeight,
        threshold: KEYBOARD_VIEWPORT_DELTA
      })
      refreshKeyboardZoomDebug(`${source}: keyboard still open`)
      return
    }

    recordKeyboardZoomTelemetry('keyboard.close-check.confirmed', {
      source,
      viewportHeight,
      keyboardOpenHeight,
      delta: viewportHeight - keyboardOpenHeight
    })
    completeKeyboardClose(source)
  }, KEYBOARD_CLOSE_SETTLE_MS)
}

function completeKeyboardClose(source) {
  if (!props.visible || !documentFieldKeyboardIsOpen || documentFieldFocusScale === null) {
    recordKeyboardZoomTelemetry('keyboard.close.completed.skipped', {
      source,
      visible: props.visible,
      keyboardOpen: documentFieldKeyboardIsOpen,
      focusScale: documentFieldFocusScale
    })
    return
  }

  const originalScale = documentFieldFocusScale
  documentFieldKeyboardIsOpen = false
  documentFieldKeyboardOpenViewportHeight = null
  recordKeyboardZoomTelemetry('keyboard.close.completed', { source, originalScale })
  restoreViewportScale(originalScale, `${source}: keyboard close detected`)
}

/** Uses the VirtualKeyboard API close event when available, independent of focus and viewport resizing. */
function onVirtualKeyboardGeometryChange() {
  if (!props.visible || documentFieldFocusScale === null || !virtualKeyboardApi) return

  const keyboardHeight = Number(virtualKeyboardApi.boundingRect?.height) || 0
  recordKeyboardZoomTelemetry('virtual-keyboard.geometrychange', {
    keyboardHeight,
    keyboardOpen: documentFieldKeyboardIsOpen,
    viewportHeight: currentVisualViewportHeight(),
    focusScale: documentFieldFocusScale
  })
  if (keyboardHeight > 0) {
    clearKeyboardCloseCheck()
    if (!documentFieldKeyboardIsOpen) {
      documentFieldKeyboardIsOpen = true
      documentFieldKeyboardOpenViewportHeight = currentVisualViewportHeight()
      refreshKeyboardZoomDebug(`Native keyboard open detected (${Math.round(keyboardHeight)}px)`)
    }
    return
  }

  if (!documentFieldKeyboardIsOpen) return

  clearKeyboardCloseCheck()
  refreshKeyboardZoomDebug('Native keyboard closed; waiting for close to settle')
  keyboardCloseCheckTimer = window.setTimeout(() => {
    keyboardCloseCheckTimer = null
    if (!props.visible || !documentFieldKeyboardIsOpen || documentFieldFocusScale === null) return
    if ((Number(virtualKeyboardApi?.boundingRect?.height) || 0) > 0) {
      refreshKeyboardZoomDebug('Native keyboard still open')
      return
    }

    completeKeyboardClose('Native keyboard')
  }, KEYBOARD_CLOSE_SETTLE_MS)
}

function handleKeyboardViewportHeight(viewportHeight, source = 'Browser') {
  if (!props.visible || documentFieldFocusScale === null || documentFieldFocusViewportHeight === null) return

  recordKeyboardZoomTelemetry('keyboard.viewport-height.handled', {
    source,
    viewportHeight,
    baselineHeight: documentFieldFocusViewportHeight,
    keyboardOpenHeight: documentFieldKeyboardOpenViewportHeight,
    keyboardOpen: documentFieldKeyboardIsOpen,
    threshold: KEYBOARD_VIEWPORT_DELTA,
    focusScale: documentFieldFocusScale
  })
  if (!documentFieldKeyboardIsOpen) {
    if (documentFieldFocusViewportHeight - viewportHeight >= KEYBOARD_VIEWPORT_DELTA) {
      documentFieldKeyboardIsOpen = true
      documentFieldKeyboardOpenViewportHeight = viewportHeight
      refreshKeyboardZoomDebug(`${source}: keyboard open detected`)
    } else if (source === 'Browser') {
      const shrink = Math.max(0, Math.round(documentFieldFocusViewportHeight - viewportHeight))
      refreshKeyboardZoomDebug(`Browser resize: viewport changed by ${shrink}px; keyboard-open threshold is ${KEYBOARD_VIEWPORT_DELTA}px`)
    }
    return
  }

  documentFieldKeyboardOpenViewportHeight = Math.min(documentFieldKeyboardOpenViewportHeight, viewportHeight)
  if (viewportHeight - documentFieldKeyboardOpenViewportHeight >= KEYBOARD_VIEWPORT_DELTA) {
    scheduleKeyboardCloseCheck(source)
  } else if (keyboardCloseCheckTimer !== null) {
    clearKeyboardCloseCheck()
  } else if (source === 'Browser') {
    const expansion = Math.max(0, Math.round(viewportHeight - documentFieldKeyboardOpenViewportHeight))
    refreshKeyboardZoomDebug(`Browser resize: viewport expanded by ${expansion}px; waiting for ${KEYBOARD_VIEWPORT_DELTA}px`)
  }
}

function onKeyboardViewportResize(event) {
  const eventSource = window.visualViewport && event?.currentTarget === window.visualViewport
    ? 'visualViewport.resize'
    : event?.currentTarget === window ? 'window.resize' : 'resize'
  recordKeyboardZoomTelemetry('viewport.resize', {
    eventSource,
    viewportHeight: currentVisualViewportHeight()
  })
  handleKeyboardViewportHeight(currentVisualViewportHeight())
  refreshKeyboardZoomDebug()
}

function onDocumentFieldFocus(event) {
  if (!isDocumentTextField(event.target)) return
  recordKeyboardZoomTelemetry('document-field.focusin', {
    targetTag: event.target.tagName?.toLowerCase(),
    targetType: event.target.getAttribute('type') || null,
    targetClass: typeof event.target.className === 'string' ? event.target.className.split(/\s+/)[0] : null,
    currentScale: window.visualViewport?.scale || 1
  })

  // Preserve the pre-zoom value while the user moves between fields.
  if (documentFieldFocusScale === null) {
    restoreViewportMeta()
    documentFieldFocusScale = window.visualViewport?.scale || 1
    documentFieldFocusViewportHeight = currentVisualViewportHeight()
    documentFieldKeyboardIsOpen = false
    documentFieldKeyboardOpenViewportHeight = null
    keyboardZoomDebug.value.baselineHeight = Math.round(documentFieldFocusViewportHeight)
    keyboardZoomDebug.value.baselineScale = documentFieldFocusScale
    keyboardZoomDebug.value.simulatedHeight = null
    keyboardZoomDebug.value.simulatedScale = null
    recordKeyboardZoomTelemetry('document-field.focus-baseline-captured', {
      focusScale: documentFieldFocusScale,
      baselineHeight: documentFieldFocusViewportHeight
    })
    refreshKeyboardZoomDebug('Field focused — baseline captured')
  }
}

function onDocumentFieldBlur(event) {
  if (!isDocumentTextField(event.target)) return
  const focusRemainsInForm = isDocumentTextField(event.relatedTarget) && event.currentTarget.contains(event.relatedTarget)
  recordKeyboardZoomTelemetry('document-field.focusout', {
    targetTag: event.target.tagName?.toLowerCase(),
    targetType: event.target.getAttribute('type') || null,
    targetClass: typeof event.target.className === 'string' ? event.target.className.split(/\s+/)[0] : null,
    focusRemainsInForm,
    currentScale: window.visualViewport?.scale || 1,
    focusScale: documentFieldFocusScale
  })
  if (focusRemainsInForm) return

  const originalScale = documentFieldFocusScale
  clearKeyboardCloseCheck()
  documentFieldFocusScale = null
  documentFieldFocusViewportHeight = null
  documentFieldKeyboardIsOpen = false
  documentFieldKeyboardOpenViewportHeight = null
  const currentScale = window.visualViewport?.scale || 1
  keyboardZoomDebug.value.baselineHeight = null
  keyboardZoomDebug.value.baselineScale = null
  if (originalScale === null || currentScale <= originalScale + 0.01) {
    refreshKeyboardZoomDebug('Field blurred')
    return
  }

  restoreViewportScale(originalScale)
}

function startKeyboardZoomSimulation() {
  recordKeyboardZoomTelemetry('simulation.started')
  restoreViewportMeta()
  documentFieldFocusScale = window.visualViewport?.scale || 1
  documentFieldFocusViewportHeight = currentVisualViewportHeight()
  documentFieldKeyboardIsOpen = false
  documentFieldKeyboardOpenViewportHeight = null
  keyboardZoomDebug.value.baselineHeight = Math.round(documentFieldFocusViewportHeight)
  keyboardZoomDebug.value.baselineScale = documentFieldFocusScale
  keyboardZoomDebug.value.simulatedHeight = Math.round(documentFieldFocusViewportHeight)
  keyboardZoomDebug.value.simulatedScale = documentFieldFocusScale
  refreshKeyboardZoomDebug('Simulation ready — baseline captured')
}

function simulateKeyboardOpen() {
  if (documentFieldFocusScale === null) startKeyboardZoomSimulation()
  const effectiveHeight = Math.max(200, documentFieldFocusViewportHeight - 240)
  recordKeyboardZoomTelemetry('simulation.keyboard-open', { viewportHeight: effectiveHeight })
  keyboardZoomDebug.value.simulatedHeight = Math.round(effectiveHeight)
  keyboardZoomDebug.value.simulatedScale = documentFieldFocusScale * 1.25
  handleKeyboardViewportHeight(effectiveHeight, 'Simulation')
  refreshKeyboardZoomDebug(keyboardZoomDebug.value.status)
}

function simulateKeyboardClose() {
  if (documentFieldFocusScale === null) startKeyboardZoomSimulation()
  const effectiveHeight = documentFieldFocusViewportHeight
  recordKeyboardZoomTelemetry('simulation.keyboard-close', { viewportHeight: effectiveHeight })
  keyboardZoomDebug.value.simulatedHeight = Math.round(effectiveHeight)
  keyboardZoomDebug.value.simulatedScale = documentFieldFocusScale * 1.25
  handleKeyboardViewportHeight(effectiveHeight, 'Simulation')
  refreshKeyboardZoomDebug(keyboardZoomDebug.value.status)
}

function resetKeyboardZoomSimulation() {
  recordKeyboardZoomTelemetry('simulation.reset')
  resetDocumentFieldZoom()
  keyboardZoomDebug.value.baselineHeight = null
  keyboardZoomDebug.value.baselineScale = null
  keyboardZoomDebug.value.simulatedHeight = null
  keyboardZoomDebug.value.simulatedScale = null
  refreshKeyboardZoomDebug('Simulation reset')
}

function resetDocumentFieldZoom() {
  recordKeyboardZoomTelemetry('document-field-zoom.reset', {
    keyboardOpen: documentFieldKeyboardIsOpen,
    focusScale: documentFieldFocusScale,
    viewportHeight: currentVisualViewportHeight()
  })
  clearKeyboardCloseCheck()
  documentFieldFocusScale = null
  documentFieldFocusViewportHeight = null
  documentFieldKeyboardIsOpen = false
  documentFieldKeyboardOpenViewportHeight = null
  restoreViewportMeta()
}

onMounted(() => {
  virtualKeyboardApi = navigator.virtualKeyboard || null
  recordKeyboardZoomTelemetry('listeners.installed', {
    visible: props.visible,
    visualViewportSupported: Boolean(window.visualViewport),
    virtualKeyboardSupported: Boolean(virtualKeyboardApi),
    viewportHeight: currentVisualViewportHeight()
  })
  window.addEventListener('resize', onKeyboardViewportResize)
  window.visualViewport?.addEventListener?.('resize', onKeyboardViewportResize)
  virtualKeyboardApi?.addEventListener?.('geometrychange', onVirtualKeyboardGeometryChange)
  refreshKeyboardZoomDebug()
})

onBeforeUnmount(() => {
  recordKeyboardZoomTelemetry('listeners.removed', { visible: props.visible })
  window.removeEventListener('resize', onKeyboardViewportResize)
  window.visualViewport?.removeEventListener?.('resize', onKeyboardViewportResize)
  virtualKeyboardApi?.removeEventListener?.('geometrychange', onVirtualKeyboardGeometryChange)
  virtualKeyboardApi = null
  resetDocumentFieldZoom()
})

watch(() => props.visible, async (newVal) => {
  if (newVal) {
    await loadOwnerSettings()
    try {
      const eqResponse = await equipmentAPI.getAll()
      equipmentList.value = eqResponse.data
    } catch (e) {
      equipmentList.value = []
    }

    itemTypeTab.value = 'articles'
    selectedArticleId.value = ''
    selectedArticle.value = null
    selectedServiceId.value = ''
    selectedService.value = null

    if (props.invoice) {
      const isConverting = props.convertFromOffer || props.convertFromCostCalc
      form.value = {
        customerId: props.invoice.customerId,
        invoiceNumber: isConverting || (isBookkeepingRecord(props.invoice.documentTypeId)
          && isDraft(props.invoice.documentStatusId)) ? '' : (props.invoice.invoiceNumber || ''),
        invoiceDate: isConverting ? new Date().toISOString().split('T')[0] : props.invoice.invoiceDate,
        dueDate: props.invoice.dueDate || '',
        workStartDate: props.invoice.workStartDate || '',
        workEndDate: props.invoice.workEndDate || '',
        notes: props.invoice.notes || '',
        documentStatusId: isConverting ? DRAFT : (props.invoice.documentStatusId || DRAFT),
        documentTypeId: props.invoice.documentTypeId,
        items: props.invoice.items ? props.invoice.items.map(mapApiItemToLocal) : [],
        parentOfferId: props.convertFromOffer ? props.invoice.id : null,
        parentCostCalcId: props.convertFromCostCalc ? props.invoice.id : null,
        templateId: props.invoice.templateId ?? null,
        taxTreatment: props.invoice.taxTreatment === SMALL_BUSINESS
          ? 'STANDARD'
          : (props.invoice.taxTreatment || 'STANDARD'),
        taxExemptionReason: props.invoice.taxExemptionReason || ''
      }
      showWorkEndDate.value = !!props.invoice.workEndDate
    } else {
      form.value = {
        customerId: props.initialCustomerId || '',
        invoiceNumber: '',
        invoiceDate: new Date().toISOString().split('T')[0],
        dueDate: '',
        workStartDate: '',
        workEndDate: '',
        notes: '',
        documentStatusId: DRAFT,
        documentTypeId: props.documentType,
        items: [],
        templateId: null,
        taxTreatment: 'STANDARD',
        taxExemptionReason: ''
      }
      showWorkEndDate.value = false
      if (!isInvoice(props.documentType)) {
        fetchNextInvoiceNumber()
      }
    }

    await loadAvailableTemplates()
  } else {
    resetDocumentFieldZoom()
  }
})

async function fetchNextInvoiceNumber() {
  try {
    const dt = form.value.documentTypeId || props.documentType
    const response = await invoiceAPI.getNextInvoiceNumber(dt)
    form.value.invoiceNumber = response.data
  } catch (err) {
    console.warn('Failed to fetch next invoice number:', err)
  }
}

async function loadAvailableTemplates() {
  const dt = currentTypeId.value
  if (!dt) {
    availableTemplates.value = []
    return
  }
  try {
    const response = await templateAPI.getTemplatesByDocumentType(dt)
    availableTemplates.value = response.data || []
  } catch (e) {
    console.warn('Failed to load templates for picker:', e)
    availableTemplates.value = []
  }
}

function onLayoutModeDefault() {
  // Guard A: if the document already has a per-document override, the radio
  // click is about to silently discard the user's customisation. Confirm
  // first. If the user cancels, revert to the override so the radio state
  // reflects the actual server state.
  if (props.invoice?.templateId != null) {
    const confirmed = confirm(
      'Diese Aktion entfernt die individuelle Vorlage für dieses Dokument. '
      + 'Die PDF wird dann mit der globalen Standardvorlage erstellt. Fortfahren?'
    )
    if (!confirmed) {
      form.value.templateId = props.invoice.templateId
      return
    }
  }
  form.value.templateId = null
}

function onLayoutModePick() {
  // If switching into "pick" without a selection, prefill with the first available template so the
  // select isn't stuck on the disabled "Vorlage auswählen…" placeholder.
  if (form.value.templateId === null && availableTemplates.value.length > 0) {
    form.value.templateId = availableTemplates.value[0].id
  }
}

function onTemplatePicked() {
  // No-op for now; v-model handles the assignment. Kept for symmetry with onLayoutModeDefault.
}

async function openCustomize() {
  if (!canCustomize.value) {
    return
  }
  // Step 1: pick the template the editor should open against. Two cases:
  //   (a) the document already has a per-document override — re-open the
  //       editor on the existing clone (no new row is created);
  //   (b) the document has no override — open the editor on the document's
  //       type default as a draft. The clone + template_id association is
  //       created on save (see onCustomTemplateSaved); cancelling the
  //       editor leaves the document untouched.
  let sourceTemplate
  let isNewOverride
  if (form.value.templateId) {
    try {
      const response = await templateAPI.getTemplate(form.value.templateId)
      sourceTemplate = response.data
    } catch (e) {
      console.error('Failed to load override template:', e)
      alert('Eigene Vorlage konnte nicht geladen werden: ' + e.message)
      return
    }
    isNewOverride = false
  } else {
    try {
      const response = await templateAPI.getDefaultTemplate(currentTypeId.value)
      sourceTemplate = response.data
    } catch (e) {
      console.error('Failed to load type default:', e)
      alert('Standardvorlage konnte nicht geladen werden: ' + e.message)
      return
    }
    isNewOverride = true
  }
  editingCustomTemplate.value = sourceTemplate
  editingIsNewOverride.value = isNewOverride
  // Build the live preview data from the actual document: customer (cached
  // or fetched), invoice, and line items. The editor merges these on top
  // of SAMPLE_DATA so the preview shows the real values for the variables
  // we care about. Other fields (bank, kleinunternehmer_hinweis, etc.)
  // stay on the SAMPLE_DATA fallback.
  const liveData = await buildLivePreviewData(props.invoice, selectedCustomer.value ? [selectedCustomer.value] : [])
  editingLiveData.value = liveData
  showTemplateEditor.value = true
}

async function buildLivePreviewData(invoice, cachedCustomers) {
  const customerId = invoice.customerId
  let customer = null
  if (cachedCustomers && Array.isArray(cachedCustomers)) {
    customer = cachedCustomers.find(c => Number(c.id) === Number(customerId)) || null
  }
  if (!customer && customerId) {
    try {
      const response = await customerAPI.getCustomer(customerId)
      customer = response.data
    } catch (e) {
      console.warn('Failed to load customer for live preview:', e)
      customer = null
    }
  }
  const { variables, totals } = buildPreviewVariables({ invoice, customer })
  return {
    variables,
    totals,
    items: buildLineItemsData(invoice.items || []),
    // Lets the editor's "PDF-Vorschau" render this document on the server.
    invoiceId: invoice.id || null
  }
}

function closeTemplateEditor() {
  showTemplateEditor.value = false
  editingCustomTemplate.value = null
  editingLiveData.value = null
  editingIsNewOverride.value = false
}

function onBackdropClick() {
  const editor = editorRef.value
  if (editor && typeof editor.requestClose === 'function') {
    editor.requestClose()
  } else {
    closeTemplateEditor()
  }
}

async function onCustomTemplateSaved(savedTemplate) {
  if (!savedTemplate) {
    closeTemplateEditor()
    return
  }
  try {
    if (editingIsNewOverride.value) {
      // The user has not yet committed to a per-document override; the
      // clone row doesn't exist yet. Create it in a single call with the
      // editor's draft applied to the source's content. The endpoint
      // persists template_id and regenerates the snapshot server-side,
      // so the new id is immediately effective at PDF download time.
      const response = await templateAPI.cloneForDocument(
        props.invoice.id,
        editingCustomTemplate.value.id,
        savedTemplate
      )
      form.value.templateId = response.data.id
    } else if (form.value.templateId != null) {
      // Editing an existing per-document override. The clone's id is
      // already owned by form.value.templateId; just forward the
      // editor's edits to updateTemplate. The editor's save event
      // carries a form with no id of its own — the URL path parameter
      // is the source of truth.
      // (Bug found 2026-06-20: the previous handler only re-fetched
      // the template and never called updateTemplate, so the editor's
      // edits were silently dropped.)
      await templateAPI.updateTemplate(form.value.templateId, savedTemplate)
    }
  } catch (e) {
    console.error('Failed to save custom template:', e)
    alert('Eigene Vorlage konnte nicht gespeichert werden: ' + e.message)
  }
  closeTemplateEditor()
}

function onArticleSelect() {
  selectedArticle.value = props.articles.find(a => a.id === selectedArticleId.value)
}

function onServiceSelect() {
  selectedService.value = props.services.find(s => s.id === selectedServiceId.value)
}

function addSelectedArticle() {
  if (selectedArticle.value) {
    form.value.items.push({
      _key: generateItemKey(),
      itemType: 'article',
      itemId: selectedArticle.value.id,
      itemName: selectedArticle.value.name,
      itemDescription: selectedArticle.value.description,
      quantity: 1,
      unitPrice: selectedArticle.value.salesPrice,
      unit: selectedArticle.value.unit || 'Stk',
      taxRate: Number(selectedArticle.value.taxRate ?? 19),
      totalPrice: selectedArticle.value.salesPrice,
      children: []
    })
    selectedArticleId.value = ''
    selectedArticle.value = null
  }
}

function addSelectedService() {
  if (selectedService.value) {
    form.value.items.push({
      _key: generateItemKey(),
      itemType: 'service',
      itemId: selectedService.value.id,
      itemName: selectedService.value.name,
      itemDescription: selectedService.value.description,
      quantity: 1,
      unitPrice: selectedService.value.hourlyRate,
      unit: selectedService.value.unit || 'Std',
      taxRate: Number(selectedService.value.taxRate ?? 19),
      totalPrice: selectedService.value.hourlyRate,
      children: []
    })
    selectedServiceId.value = ''
    selectedService.value = null
  }
}

function mapApiItemToLocal(item) {
  const unitPrice = item.unitPrice ? Number(item.unitPrice) : 0
  const quantity = item.quantity || 0
  return {
    _key: generateItemKey(),
    id: item.id,
    itemType: item.itemType,
    itemId: item.itemId,
    itemName: item.itemName,
    itemDescription: item.itemDescription,
    quantity,
    unitPrice,
    unit: item.unit,
    taxRate: item.taxRate !== null && item.taxRate !== undefined ? Number(item.taxRate) : 19,
    totalPrice: item.totalPrice ? Number(item.totalPrice) : quantity * unitPrice,
    parentItemId: item.parentItemId || null,
    children: Array.isArray(item.children) ? item.children.map(mapApiItemToLocal) : []
  }
}

let _itemKeyCounter = 0
function generateItemKey() {
  _itemKeyCounter += 1
  return `item-${Date.now()}-${_itemKeyCounter}`
}

function serializeItemForApi(item) {
  const quantity = item.quantity || 0
  const unitPrice = item.unitPrice || 0
  return {
    id: item.id,
    itemType: item.itemType,
    itemId: item.itemId,
    itemName: item.itemName,
    itemDescription: item.itemDescription,
    quantity,
    unitPrice,
    unit: item.unit,
    taxRate: item.taxRate ?? 19,
    totalPrice: quantity * unitPrice,
    parentItemId: item.parentItemId || null,
    children: Array.isArray(item.children) ? item.children.map(serializeItemForApi) : []
  }
}

function sumItemsDeep(items) {
  if (!Array.isArray(items) || items.length === 0) return 0
  let total = 0
  for (const item of items) {
    total += (item.totalPrice || 0)
    if (Array.isArray(item.children) && item.children.length) {
      total += sumItemsDeep(item.children)
    }
  }
  return total
}

function countItemsDeep(items) {
  if (!Array.isArray(items) || items.length === 0) return 0
  let count = 0
  for (const item of items) {
    count += 1
    if (Array.isArray(item.children) && item.children.length) {
      count += countItemsDeep(item.children)
    }
  }
  return count
}

function onCustomerCreated(newCustomer) {
  selectedCustomer.value = newCustomer
  form.value.customerId = newCustomer.id
}

async function createArticleInline() {
  try {
    const response = await articleAPI.createArticle(articleForm.value)
    const newArticle = response.data
    emit('reload-articles')
    showCreateArticle.value = false
    articleForm.value = { name: '', description: '', unit: 'Stk', purchasePrice: 0, salesPrice: 0, equipmentId: null }
    itemTypeTab.value = 'articles'
    selectedArticleId.value = newArticle.id
    onArticleSelect()
    addSelectedArticle()
  } catch (err) {
    alert('Fehler beim Anlegen des Artikels: ' + err.message)
  }
}

async function createServiceInline() {
  try {
    const response = await articleAPI.createService(serviceForm.value)
    const newService = response.data
    emit('reload-services')
    showCreateService.value = false
    serviceForm.value = { name: '', description: '', hourlyRate: 0, equipmentId: null }
    itemTypeTab.value = 'services'
    selectedServiceId.value = newService.id
    onServiceSelect()
    addSelectedService()
  } catch (err) {
    alert('Fehler beim Anlegen der Leistung: ' + err.message)
  }
}

function cancel() {
  emit('update:visible', false)
}

async function submit() {
  try {
    if (!form.value.customerId) {
      alert('Bitte wählen Sie einen Kunden aus')
      return
    }

    // Guard D: safety net for every other path that could silently clear
    // the per-document override (a stale modal reset, a dropdown
    // deselect, the "Standardvorlage" radio if Guard A was somehow
    // bypassed, etc.). If the form has no override but the document on
    // the server does, the next save would wipe the customisation.
    if (
      form.value.templateId == null
      && props.invoice?.templateId != null
      && isEditMode.value
      && !props.convertFromOffer
      && !props.convertFromCostCalc
    ) {
      const confirmed = confirm(
        'Sie sind dabei, die individuelle Vorlage für dieses Dokument zu '
        + 'entfernen. Sind Sie sicher?'
      )
      if (!confirmed) {
        return
      }
    }

    if (!isCancelled(form.value.documentStatusId) && !isCancelled(props.invoice?.documentStatusId)
        && isCancelled(form.value.documentStatusId)) {
      if (!confirm('Möchten Sie dieses Dokument wirklich stornieren?')) {
        form.value.documentStatusId = props.invoice?.documentStatusId || DRAFT
        return
      }
    }

    const isNonDraftEdit = isEditMode.value && props.invoice && !isDraft(props.invoice.documentStatusId)

    const originalItemCount = countItemsDeep(props.invoice?.items)
    const currentItemCount = countItemsDeep(form.value.items)
    const contentChanged = currentItemCount !== originalItemCount ||
        form.value.customerId !== props.invoice?.customerId

    const itemsWithTotals = form.value.items.map(serializeItemForApi)

    const grossAmount = sumItemsDeep(itemsWithTotals)

    // Issued invoices are frozen; the list only offers "Bearbeiten" for drafts.
    if (isNonDraftEdit && isBookkeepingRecord(props.invoice.documentTypeId)) {
      alert('Ausgestellte Rechnungen können nicht mehr geändert werden. '
        + 'Bitte stornieren und eine korrigierte Rechnung erstellen.')
      return
    }

    const submitData = {
      customerId: parseInt(form.value.customerId) || null,
      invoiceNumber: props.invoice?.invoiceNumber || '',
      invoiceDate: form.value.invoiceDate || null,
      dueDate: form.value.dueDate || null,
      workStartDate: form.value.workStartDate || null,
      workEndDate: form.value.workEndDate || null,
      notes: form.value.notes || '',
      documentStatusId: parseInt(form.value.documentStatusId) || DRAFT,
      items: itemsWithTotals,
      grossAmount: grossAmount,
      taxRate: isKleinunternehmer.value ? 0.0 : 19.0,
      taxTreatment: effectiveTreatment.value,
      taxExemptionReason: form.value.taxTreatment === 'TAX_EXEMPT' ? form.value.taxExemptionReason : '',
      // Paid dates follow from recorded payments (DocumentViewModal), never from the editor.
      paidDate: null,
      documentTypeId: targetTypeId.value || form.value.documentTypeId || props.documentType,
      templateId: form.value.templateId
    }

    if (isEditMode.value && props.invoice && !props.convertFromOffer && !props.convertFromCostCalc) {
      if (isNonDraftEdit && contentChanged) {
        const originalNumber = props.invoice?.invoiceNumber || ''
        const baseNumber = originalNumber.includes('/')
            ? originalNumber.substring(0, originalNumber.lastIndexOf('/'))
            : originalNumber

        submitData.invoiceNumber = baseNumber + '/'

        submitData.documentStatusId = DRAFT

        await invoiceAPI.updateInvoice(props.invoice.id, { documentStatusId: CANCELLED })
        const response = await invoiceAPI.createInvoice(submitData)
        emit('created', response.data)
      } else {
        const response = await invoiceAPI.updateInvoice(props.invoice.id, submitData)
        emit('updated', response.data)
      }
    } else {
      const response = await invoiceAPI.createInvoice(submitData)
      if ((props.convertFromOffer || props.convertFromCostCalc) && props.invoice) {
        try {
          await invoiceAPI.updateInvoiceStatus(props.invoice.id, CONVERTED, {
            convertedToInvoiceId: response.data.id
          })
        } catch (e) {
          console.warn('Failed to update source document status:', e)
        }
      }
      emit('created', response.data)
    }
    cancel()
  } catch (error) {
    console.error('Failed to save document:', error)
    alert('Fehler beim Speichern: ' + error.message)
  }
}

function formatCurrency(amount) {
  return amount ? new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(amount) : '-'
}
</script>

<style scoped>
.modal-panel.wide {
  width: min(800px, calc(100vw - 40px));
  max-width: 800px;
  min-width: 0;
  height: min(90vh, 900px);
  height: min(90dvh, 900px);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.modal-form {
  display: flex;
  flex-direction: column;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  min-height: 0;
  flex: 1;
  overflow: hidden;
}

.scroll-container {
  width: 100%;
  max-width: 100%;
  min-width: 0;
  box-sizing: border-box;
  overflow-y: auto;
  overflow-x: hidden;
  overscroll-behavior-y: contain;
  scrollbar-gutter: stable;
  flex: 1;
  min-height: 0;
  display: flex;
  flex-direction: column;
  gap: 15px;
}

.modal-panel h2 {
  margin-top: 0;
  color: var(--color-text-strong);
  flex-shrink: 0;
}

.form-row {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  width: 100%;
  min-width: 0;
  align-items: start;
  gap: 12px;
}

.form-row .form-group {
  min-width: 0;
}

.form-row > .form-group:only-child {
  grid-column: 1 / -1;
}

.scroll-container > .form-group,
.scroll-container .form-row .form-group {
  margin-bottom: 0;
}

.form-group {
  min-width: 0;
  margin-bottom: 15px;
}

.form-group label {
  display: block;
  margin-bottom: 5px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.form-group input,
.form-group select,
.form-group textarea {
  width: 100%;
  padding: 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  font-size: 14px;
  box-sizing: border-box;
}

.tax-info {
  display: inline-block;
  padding: 8px 0;
  color: var(--color-text-muted);
}

.totals-summary {
  margin: 16px 0 8px auto;
  max-width: 340px;
  font-size: 0.95em;
}

.total-line {
  display: flex;
  justify-content: space-between;
  padding: 4px 0;
}

.total-line.gross {
  border-top: 2px solid var(--color-text-strong);
  margin-top: 4px;
  padding-top: 8px;
  font-weight: 700;
}

.tax-note {
  margin: 8px 0 0;
  font-size: 0.85em;
  color: var(--color-text-muted);
}

.required-hint {
  font-weight: normal;
  font-size: 0.85em;
  color: var(--color-text-muted);
}

.form-group input[readonly] {
  background-color: var(--color-surface-muted);
  color: var(--color-text-muted);
  cursor: default;
}

.invoice-number-preview {
  font-weight: 600;
  color: var(--color-text-strong);
}

.form-group textarea {
  min-height: 60px;
  resize: vertical;
}

.form-actions {
  margin-top: 20px;
  display: flex;
  gap: 10px;
}

.modal-panel.wide > .modal-form > .form-actions {
  flex: 0 0 auto;
  margin-top: 0;
  padding-top: 14px;
  border-top: 1px solid var(--color-border);
}

.modal-panel.wide > .modal-form > .form-actions button {
  flex: 1;
  min-height: 44px;
}

.customer-group {
  min-width: 0;
}

.customer-select-row {
  display: flex;
  gap: 8px;
}

.customer-select-row select,
.customer-select-row .customer-picker {
  flex: 1;
}

.customer-select-row button {
  flex-shrink: 0;
}

.date-with-toggle {
  display: flex;
  align-items: center;
  gap: 8px;
}

.date-with-toggle input {
  flex: 1;
  min-width: 0;
}

.date-with-toggle button {
  flex-shrink: 0;
}

.items-section {
  background: var(--color-surface-subtle);
  padding: 15px;
  border-radius: 8px;
  margin: 20px 0;
}

.items-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 15px;
  flex-wrap: wrap;
  gap: 10px;
}

.items-header h3 {
  margin: 0;
}

.items-hint {
  flex-basis: 100%;
  margin: 0;
  font-size: 0.85em;
  color: var(--color-text-muted);
}

.item-type-tabs {
  display: flex;
  gap: 5px;
}

.tab {
  padding: 8px 16px;
  border: none;
  background: var(--color-border);
  cursor: pointer;
  border-radius: 4px;
  font-size: 0.9em;
}

.tab.active {
  background: var(--color-primary);
  color: var(--color-on-primary);
}

.item-selector {
  margin-bottom: 15px;
}

.selector-row {
  display: flex;
  gap: 8px;
  margin-bottom: 10px;
}

.selector-row select {
  flex: 1;
  padding: 8px;
  border: 1px solid var(--color-border-strong);
  border-radius: 4px;
}

.selector-row button {
  flex-shrink: 0;
}

.selected-item-preview {
  display: flex;
  justify-content: space-between;
  align-items: center;
  background: var(--color-surface);
  padding: 10px;
  border-radius: 4px;
  border: 1px solid var(--color-border-strong);
}

.selected-item-preview span {
  color: var(--color-text-muted);
  font-size: 0.9em;
}

.item-row {
  display: flex;
  gap: 8px;
  margin-bottom: 8px;
  align-items: center;
  background: var(--color-surface);
  padding: 8px;
  border-radius: 4px;
  border: 1px solid var(--color-border-strong);
  flex-wrap: nowrap;
}

.item-row input {
  flex: 1;
  min-width: 0;
  padding: 6px;
  border: 1px solid var(--color-border-strong);
  border-radius: 4px;
  font-size: 13px;
}

.layout-section {
  background: var(--color-surface-subtle);
  border: 1px solid var(--color-border);
  border-radius: 8px;
  padding: 0;
  margin: 0;
}

.layout-details summary {
  cursor: pointer;
  padding: 12px 16px;
  font-weight: 600;
  color: var(--color-text-strong);
  user-select: none;
  list-style: none;
}

.layout-details summary::-webkit-details-marker {
  display: none;
}

.layout-details summary::before {
  content: '▶';
  display: inline-block;
  margin-right: 8px;
  font-size: 0.7em;
  transition: transform 0.15s;
}

.layout-details[open] summary::before {
  transform: rotate(90deg);
}

.layout-body {
  padding: 4px 16px 16px;
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.layout-row {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.layout-row label {
  display: flex;
  align-items: center;
  gap: 8px;
  margin: 0;
  font-weight: 400;
  color: var(--color-text-strong);
  cursor: pointer;
}

.layout-row select {
  flex: 1;
  min-width: 200px;
  padding: 6px 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: 4px;
  font-size: 13px;
}

.layout-row select:disabled {
  background: var(--color-surface-muted);
  color: var(--color-text-subtle);
  cursor: not-allowed;
}

.layout-hint {
  color: var(--color-text-muted);
  font-size: 12px;
}

.template-editor-modal {
  align-items: stretch;
  padding: 0;
}

.template-editor-container {
  width: 100%;
  max-width: 1600px;
  height: 94vh;
  margin: 3vh auto;
  background: var(--color-surface);
  border-radius: 8px;
  overflow: hidden;
}

.keyboard-zoom-debug {
  position: fixed;
  top: 8px;
  left: 8px;
  z-index: 3000;
  box-sizing: border-box;
  width: min(360px, calc(100vw - 16px));
  max-height: min(46dvh, 360px);
  overflow: auto;
  padding: 12px;
  border: 1px solid var(--color-overlay-border);
  border-radius: 8px;
  background: var(--color-overlay);
  color: var(--color-on-overlay);
  box-shadow: var(--shadow-overlay);
  font: 12px/1.4 ui-monospace, SFMono-Regular, Menlo, monospace;
}

.keyboard-zoom-debug strong {
  display: block;
  margin-bottom: 8px;
  color: var(--color-overlay-ok);
}

.keyboard-zoom-debug dl {
  display: grid;
  grid-template-columns: 112px minmax(0, 1fr);
  gap: 3px 8px;
  margin: 0;
}

.keyboard-zoom-debug dt {
  color: var(--color-overlay-muted);
}

.keyboard-zoom-debug dd {
  min-width: 0;
  margin: 0;
  overflow-wrap: anywhere;
}

.keyboard-zoom-debug .debug-meta {
  max-height: 38px;
  overflow: auto;
  color: var(--color-overlay-muted);
}

.keyboard-zoom-debug .debug-status {
  margin: 8px 0;
  color: var(--color-overlay-warn);
}

.keyboard-zoom-debug .debug-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 5px;
}

.keyboard-zoom-debug button {
  min-height: 30px;
  padding: 4px 7px;
  border: 1px solid var(--color-overlay-button-border);
  border-radius: 4px;
  background: var(--color-overlay-button);
  color: var(--color-on-overlay);
  font: inherit;
  cursor: pointer;
}

.keyboard-zoom-debug button:disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

@media (max-width: 800px) {
  .form-group input,
  .form-group select,
  .form-group textarea { font-size: 16px; }
}

@media (max-width: 600px) {
  .modal-backdrop { align-items: stretch; padding: 0; }
  .modal-panel { width: 100%; max-width: none; height: 100vh; height: 100dvh; max-height: 100dvh; padding: 16px; border-radius: 0; }
  .document-modal { align-items: center; padding: 8px; box-sizing: border-box; }
  .document-modal > .modal-panel.wide {
    width: min(560px, calc(100vw - 24px));
    max-width: calc(100vw - 24px);
    height: min(92vh, 900px);
    height: min(92dvh, 900px);
    max-height: calc(100dvh - 16px);
    padding: 16px;
    border-radius: 14px;
  }
  .modal-panel.wide { padding: 16px; }
  .form-row { grid-template-columns: minmax(0, 1fr); gap: 12px; }
  .form-row > .form-group:only-child { grid-column: auto; }
  .modal-panel.wide,
  .modal-form,
  .scroll-container,
  .form-row,
  .form-group,
  .items-section,
  .items-tree { min-width: 0; max-width: 100%; }
  .form-group input,
  .form-group select,
  .form-group textarea { min-width: 0; max-width: 100%; }
  .customer-select-row { flex-wrap: nowrap; align-items: stretch; }
  .customer-select-row select { min-width: 0; }
  .customer-select-row button { width: 44px; min-height: 44px; }
  .items-section { padding: 10px; margin: 12px 0; }
  .item-type-tabs { flex-wrap: wrap; }
  .items-tree { min-width: 0; }
  .selector-row { flex-wrap: nowrap; align-items: stretch; }
  .selector-row select { width: auto; min-width: 0; }
  .selector-row button { width: 44px; min-height: 44px; }
  .selected-item-preview { align-items: stretch; flex-direction: column; gap: 8px; }
  .selected-item-preview span { min-width: 0; overflow-wrap: anywhere; }
  .form-actions { flex-wrap: nowrap; gap: 8px; }
  .form-actions button { flex: 1 1 0; min-height: 44px; padding-inline: 8px; }
  .layout-body { padding: 4px 12px 12px; }
  .layout-row { align-items: stretch; flex-direction: column; }
  .layout-row select { width: 100%; min-width: 0; }
  .layout-row > button { width: 100%; min-height: 44px; }
  .template-editor-container { height: 100dvh; margin: 0; border-radius: 0; }
}
</style>

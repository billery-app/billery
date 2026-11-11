<template>
  <div class="dashboard">
    <h1 class="dashboard-title nav-destination-page-title">Dashboard</h1>
    
    <!-- Quick Stats Cards -->
    <div class="stats-grid">
      <div class="stat-card" :class="{ active: selectedFilter === null }" @click="selectedFilter = null">
        <div class="stat-icon">📄</div>
        <div class="stat-content">
          <div class="stat-value">{{ periodStats.totalInvoices }}</div>
          <div class="stat-label">Gesamt Rechnungen</div>
        </div>
      </div>
      
      <div class="stat-card paid" :class="{ active: selectedFilter === 'paid' }" @click="selectedFilter = 'paid'">
        <div class="stat-icon">✅</div>
        <div class="stat-content">
          <div class="stat-value">{{ periodStats.paidInvoices }}</div>
          <div class="stat-label">Bezahlte Rechnungen</div>
        </div>
      </div>
      
      <div class="stat-card unpaid" :class="{ active: selectedFilter === 'unpaid' }" @click="selectedFilter = 'unpaid'">
        <div class="stat-icon">⚠️</div>
        <div class="stat-content">
          <div class="stat-value">{{ periodStats.unpaidInvoices }}</div>
          <div class="stat-label">Unbezahlte Rechnungen</div>
        </div>
      </div>
      
    </div>
    
    <!-- Global Date Period Selector -->
    <div class="period-selector-bar">
      <div class="period-buttons">
        <button 
          v-for="period in periods" 
          :key="period.value"
          :class="{ active: selectedPeriod === period.value }"
          @click="selectPeriod(period.value)"
        >
          {{ period.label }}
        </button>
        <button 
          :class="{ active: selectedPeriod === 'custom' }"
          @click="selectPeriod('custom')"
        >
          Benutzerdefiniert
        </button>
      </div>
      <div v-if="selectedPeriod === 'custom'" class="custom-date-inputs">
        <label>Von:</label>
        <input v-model="customStartDate" type="date" @change="updateCustomPeriod" />
        <label>Bis:</label>
        <input v-model="customEndDate" type="date" @change="updateCustomPeriod" />
      </div>
    </div>
    
    <!-- Main Content Grid -->
    <div class="dashboard-grid">
      <!-- Invoices Widget -->
      <div class="widget">
        <div class="widget-header">
          <h2>{{ tableTitle }}</h2>
          <span class="badge" data-testid="list-total" :class="{ 'badge-paid': selectedFilter === 'paid', 'badge-unpaid': selectedFilter === 'unpaid' }">{{ listTotal }}</span>
        </div>
        <div class="widget-content">
          <div v-if="loadError" class="load-error" data-testid="load-error">{{ loadError }}</div>
          <div v-if="filteredInvoices.length === 0" class="empty-state">
            Keine Rechnungen vorhanden.
          </div>
          <div v-else class="table-scroll table-scroll--compact" role="region" aria-label="Aktuelle Rechnungstabelle" tabindex="0">
          <table class="data-table">
            <thead>
              <tr>
                <th>Nr.</th>
                <th>Kunde</th>
                <th>Fällig</th>
                <th class="numeric">Betrag</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="invoice in filteredInvoices"
                :key="invoice.id"
                :class="[{ 'overdue-row': isInvoiceOverdue(invoice) }, 'clickable-row']"
                tabindex="0"
                :aria-label="`${invoice.invoiceNumber || 'Rechnung'} ansehen`"
                @click="openInvoice(invoice.id)"
                @keydown.enter.prevent="openInvoice(invoice.id)"
                @keydown.space.prevent="openInvoice(invoice.id)"
              >
                <td>{{ invoice.invoiceNumber }}</td>
                <td>{{ invoice.customerName }}</td>
                <td :class="{ overdue: isInvoiceOverdue(invoice) }">{{ formatDate(invoice.dueDate) }}</td>
                <td class="numeric">{{ formatCurrency(invoice.grossAmount) }}</td>
              </tr>
            </tbody>
          </table>
          </div>
          <div v-if="listTotal > filteredInvoices.length" class="widget-footer">
            <router-link :to="invoicesLink">Alle anzeigen →</router-link>
          </div>
        </div>
        <div class="widget-summary">
          <span></span> <!-- Placeholder -->
          <span class="summary-amount">Summe: {{ formatCurrency(filteredTotal) }}</span>
        </div>
      </div>
      
      <!-- Quick Actions Widget -->
      <div class="widget">
        <div class="widget-header">
          <h2>Schnellzugriff</h2>
        </div>
        <div class="widget-content">
          <div class="quick-actions">
            <div class="action-btn" @click="createInvoice">
              <span class="action-icon">📝</span>
              <span>Neue Rechnung</span>
            </div>
            <div class="action-btn" @click="createOffer">
              <span class="action-icon">💼</span>
              <span>Neues Angebot</span>
            </div>
            <div class="action-btn" @click="createCostCalculation">
              <span class="action-icon">📊</span>
              <span>Neuer Kostenvoranschlag</span>
            </div>
            <div class="action-btn" @click="showCreateCustomer = true">
              <span class="action-icon">👤</span>
              <span>Neuer Kunde</span>
            </div>
            <div class="action-btn" @click="showCreateArticleService = true">
              <span class="action-icon">📦</span>
              <span>Artikel & Leistungen</span>
            </div>
          </div>
        </div>
      </div>
    </div>
    
    <!-- Period Statistics -->
    <div class="widget full-width">
      <div class="widget-header">
        <h2>Statistiken für ausgewählten Zeitraum</h2>
      </div>
      <div class="widget-content">
        <div class="statistics-grid">
          <div class="stat-section">
            <h3>Übersicht</h3>
            <div class="stat-row">
              <span>Gesamt Rechnungen:</span>
              <strong>{{ periodStats.totalInvoices }}</strong>
            </div>
            <div class="stat-row">
              <span>Bezahlte Rechnungen:</span>
              <strong>{{ periodStats.paidInvoices }}</strong>
            </div>
            <div class="stat-row">
              <span>Unbezahlte Rechnungen:</span>
              <strong>{{ periodStats.unpaidInvoices }}</strong>
            </div>
          </div>
          
          <div class="stat-section">
            <h3>Umsatz</h3>
            <div class="stat-row">
              <span>Gesamt Umsatz:</span>
              <strong>{{ formatCurrency(periodStats.totalRevenue) }}</strong>
            </div>
            <div class="stat-row">
              <span>Bezahlter Umsatz:</span>
              <strong>{{ formatCurrency(periodStats.paidRevenue) }}</strong>
            </div>
            <div class="stat-row">
              <span>Unbezahlter Umsatz:</span>
              <strong class="warning">{{ formatCurrency(periodStats.unpaidRevenue) }}</strong>
            </div>
          </div>
          
          <div class="stat-section">
            <h3>Steuern</h3>
            <div class="stat-row">
              <span>Gesamt Steuer:</span>
              <strong>{{ formatCurrency(periodStats.totalTax) }}</strong>
            </div>
            <div class="stat-row">
              <span>Bezahlte Steuer:</span>
              <strong>{{ formatCurrency(periodStats.paidTax) }}</strong>
            </div>
            <div class="stat-row">
              <span>Unbezahlte Steuer:</span>
              <strong class="warning">{{ formatCurrency(periodStats.unpaidTax) }}</strong>
            </div>
          </div>
        </div>
      </div>
    </div>
    
    <!-- Create Modals -->
    <DocumentModal
      v-model:visible="showCreateInvoice"
      :articles="articles"
      :services="services"
      :document-type="createDocumentType"
      @created="onInvoiceCreated"
      @reload-articles="loadArticles"
      @reload-services="loadServices"
    />
    <CustomerCreateModal
      v-model:visible="showCreateCustomer"
      @created="onCustomerCreated"
    />
    <ArticleServiceCreateModal
      v-model:visible="showCreateArticleService"
      @created="onArticleServiceCreated"
    />
    <DocumentViewModal
      v-model:visible="showInvoiceModal"
      :invoice-id="selectedInvoiceId"
      @status-updated="loadData"
      @cloned="loadData"
    />
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import articleAPI from '../services/articleService.js'
import invoiceAPI from '../services/invoiceService.js'
import CustomerCreateModal from '../components/CustomerCreateModal.vue'
import DocumentModal from '../components/DocumentModal.vue'
import DocumentViewModal from '../components/DocumentViewModal.vue'
import ArticleServiceCreateModal from '../components/ArticleServiceCreateModal.vue'
import { isInvoiceOverdue } from '../utils/documentStatus.js'
import { INVOICE, PARTED_INVOICE, FINAL_INVOICE, OFFER, COST_CALCULATION } from '../data/documentTypes.js'

// The dashboard never loads all documents: the figures come from GET /api/invoices/statistics,
// the table shows the first invoices of the selected card from GET /api/invoices/page.
const stats = ref(null)
const listPage = ref({ items: [], total: 0 })
const loadError = ref(null)
let statisticsRequest = 0
let listRequest = 0

/** Invoice types the dashboard covers (credit notes are not listed). */
const INVOICE_TYPES = [INVOICE, PARTED_INVOICE, FINAL_INVOICE]
/** Rows the dashboard table shows. */
const LIST_SIZE = 5
/** Card filter → status key of the list endpoint ("issued" = sent, partly paid or paid). */
const LIST_STATUS = { paid: 'paid', unpaid: 'open' }
const selectedPeriod = ref('currentYear')
const selectedFilter = ref(null) // null = all, 'paid', 'unpaid'

const customStartDate = ref('')
const customEndDate = ref('')

// Modal states
const showCreateInvoice = ref(false)
const showCreateCustomer = ref(false)
const showCreateArticleService = ref(false)
const showInvoiceModal = ref(false)
const selectedInvoiceId = ref(null)
const createDocumentType = ref(INVOICE)

const articles = ref([])
const services = ref([])

// Component event handlers
function onInvoiceCreated() {
  loadData()
}

function onCustomerCreated() {
  // Nothing to reload: the dashboard holds no customer list (the document form searches).
}

function onArticleServiceCreated() {
  // Article/service created, no need to reload dashboard
}

const periods = [
  { value: 'currentMonth', label: 'Aktueller Monat' },
  { value: 'lastMonth', label: 'Letzter Monat' },
  { value: 'currentYear', label: 'Aktuelles Jahr' },
  { value: 'lastYear', label: 'Letztes Jahr' },
  { value: 'last12Months', label: 'Letzte 12 Monate' }
]

const dateRange = computed(() => {
  const now = new Date()
  let startDate, endDate
  
  switch (selectedPeriod.value) {
    case 'currentMonth':
      startDate = new Date(now.getFullYear(), now.getMonth(), 1)
      endDate = now
      break
    case 'lastMonth':
      startDate = new Date(now.getFullYear(), now.getMonth() - 1, 1)
      endDate = new Date(now.getFullYear(), now.getMonth(), 0)
      break
    case 'currentYear':
      startDate = new Date(now.getFullYear(), 0, 1)
      endDate = now
      break
    case 'lastYear':
      startDate = new Date(now.getFullYear() - 1, 0, 1)
      endDate = new Date(now.getFullYear() - 1, 11, 31)
      break
    case 'last12Months':
      startDate = new Date(now.getFullYear(), now.getMonth() - 12, 1)
      endDate = now
      break
    case 'custom':
      startDate = customStartDate.value ? new Date(customStartDate.value) : new Date(now.getFullYear(), 0, 1)
      endDate = customEndDate.value ? new Date(customEndDate.value) : now
      break
    default:
      startDate = new Date(now.getFullYear(), 0, 1)
      endDate = now
  }
  
  return { startDate, endDate }
})

/** The period as ISO dates (local calendar days, not UTC). */
const isoRange = computed(() => {
  const { startDate, endDate } = dateRange.value
  return { from: isoDate(startDate), to: isoDate(endDate) }
})

function isoDate(date) {
  const pad = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

const filteredInvoices = computed(() => listPage.value.items)
const listTotal = computed(() => listPage.value.total)

const filteredTotal = computed(() => {
  if (selectedFilter.value === 'paid') return periodStats.value.paidRevenue
  if (selectedFilter.value === 'unpaid') return periodStats.value.unpaidRevenue
  return periodStats.value.totalRevenue
})

const tableTitle = computed(() => {
  return 'Gesendete Rechnungen'
})

const invoicesLink = computed(() => {
  const query = {}
  if (selectedFilter.value) {
    // The document list knows "open" (sent or partly paid), not the card's "unpaid".
    query.filter = LIST_STATUS[selectedFilter.value]
  }
  // Add year for year-based periods
  if (selectedPeriod.value === 'currentYear') {
    query.year = new Date().getFullYear()
  } else if (selectedPeriod.value === 'lastYear') {
    query.year = new Date().getFullYear() - 1
  } else if (selectedPeriod.value === 'custom' && customStartDate.value) {
    const year = new Date(customStartDate.value).getFullYear()
    if (customEndDate.value) {
      const endYear = new Date(customEndDate.value).getFullYear()
      if (year === endYear) {
        query.year = year
      }
    } else {
      query.year = year
    }
  }
  return { path: '/invoices', query }
})

/**
 * The figures of the period from the backend: issued invoices (sent, partly paid, paid), gross and
 * VAT computed per rate group by VatCalculator. Every amount, including the totals, is summed on
 * the server in BigDecimal; the browser only displays them. Credit notes are not netted here (see
 * the statistics endpoint's totalGross for that view).
 */
const periodStats = computed(() => {
  const s = stats.value
  if (!s) {
    return { totalInvoices: 0, paidInvoices: 0, unpaidInvoices: 0, totalRevenue: 0, paidRevenue: 0, unpaidRevenue: 0, totalTax: 0, paidTax: 0, unpaidTax: 0 }
  }
  return {
    totalInvoices: s.paidInvoiceCount + s.unpaidInvoiceCount,
    paidInvoices: s.paidInvoiceCount,
    unpaidInvoices: s.unpaidInvoiceCount,
    totalRevenue: s.issuedGross,
    paidRevenue: s.paidGross,
    unpaidRevenue: s.unpaidGross,
    totalTax: s.issuedTaxAmount,
    paidTax: s.paidTaxAmount,
    unpaidTax: s.unpaidTaxAmount
  }
})

function selectPeriod(period) {
  selectedPeriod.value = period
}

function updateCustomPeriod() {
  // Triggered when custom date inputs change
  // The computed properties will automatically recalculate
}

function formatCurrency(value) {
  if (!value) return '0,00 €'
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(value)
}

function formatDate(dateStr) {
  if (!dateStr) return '-'
  return new Date(dateStr).toLocaleDateString('de-DE')
}

function openInvoice(id) {
  selectedInvoiceId.value = id
  showInvoiceModal.value = true
}

function createInvoice() {
  createDocumentType.value = INVOICE
  showCreateInvoice.value = true
}

function createOffer() {
  createDocumentType.value = OFFER
  showCreateInvoice.value = true
}

function createCostCalculation() {
  createDocumentType.value = COST_CALCULATION
  showCreateInvoice.value = true
}

const isLoading = ref(false)

async function loadArticles() {
  try {
    const response = await articleAPI.getArticles()
    articles.value = response.data
  } catch (error) {
    console.error('Failed to load articles:', error)
  }
}

async function loadServices() {
  try {
    const response = await articleAPI.getServices()
    services.value = response.data
  } catch (error) {
    console.error('Failed to load services:', error)
  }
}

/** Figures of the selected period; a superseded response is dropped. */
async function loadStatistics() {
  const request = ++statisticsRequest
  const { from, to } = isoRange.value
  try {
    const response = await invoiceAPI.getStatistics(from, to)
    if (request === statisticsRequest) stats.value = response.data
  } catch (error) {
    if (request === statisticsRequest) loadError.value = 'Statistik konnte nicht geladen werden: ' + error.message
  }
}

/** First invoices of the selected card and period; a superseded response is dropped. */
async function loadList() {
  const request = ++listRequest
  const { from, to } = isoRange.value
  try {
    const response = await invoiceAPI.getInvoicePage({
      page: 0,
      size: LIST_SIZE,
      types: INVOICE_TYPES,
      archived: false,
      includeArchived: true,
      from,
      to,
      status: LIST_STATUS[selectedFilter.value] || 'issued'
    })
    if (request === listRequest) listPage.value = response.data
  } catch (error) {
    if (request === listRequest) loadError.value = 'Rechnungen konnten nicht geladen werden: ' + error.message
  }
}

/**
 * Loads everything the dashboard shows. The four requests are independent, so they run in
 * parallel; each loader handles its own error, so one failing request doesn't blank the rest.
 */
async function loadData() {
  isLoading.value = true
  try {
    loadError.value = null
    await Promise.all([loadStatistics(), loadList(), loadArticles(), loadServices()])
  } finally {
    isLoading.value = false
  }
}

onMounted(() => {
  loadData()
})

watch(isoRange, (now, before) => {
  if (now.from === before.from && now.to === before.to) return
  loadError.value = null
  loadStatistics()
  loadList()
})

watch(selectedFilter, () => {
  loadError.value = null
  loadList()
})
</script>

<style scoped>
.dashboard {
  max-width: 1400px;
  margin: 0 auto;
  padding: clamp(12px, 1.5vw, 24px);
}

.dashboard-title {
  margin-bottom: 24px;
  color: var(--color-text-strong);
}

/* Stats Grid */
.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(240px, 1fr));
  gap: 20px;
  margin-bottom: 30px;
}

.stat-card {
  background: var(--color-surface);
  border-radius: 12px;
  padding: 20px;
  display: flex;
  align-items: center;
  gap: 16px;
  box-shadow: var(--shadow-md);
  transition: transform 0.2s, box-shadow 0.2s;
}

.stat-card:hover {
  transform: translateY(-2px);
  box-shadow: var(--shadow-lg);
}

.stat-card.paid {
  border-left: 4px solid var(--color-success);
}

.stat-card.paid.active {
  background: var(--color-success-soft);
  border: 2px solid var(--color-success);
}

.stat-card.unpaid {
  border-left: 4px solid var(--color-danger);
}

.stat-card.unpaid.active {
  background: var(--color-danger-soft);
  border: 2px solid var(--color-danger);
}

.stat-card.active {
  background: var(--color-primary-soft);
  border: 2px solid var(--color-primary);
  cursor: pointer;
}

.stat-card {
  cursor: pointer;
}

.period-selector-bar {
  background: var(--color-surface);
  border-radius: 12px;
  padding: 16px 20px;
  margin-bottom: 24px;
  box-shadow: var(--shadow-md);
}

.custom-date-inputs {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--color-border);
}

.custom-date-inputs label {
  font-weight: 600;
  color: var(--color-text-strong);
}

.custom-date-inputs input {
  padding: 6px 12px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  font-size: 0.9em;
}

.stat-icon {
  font-size: 2em;
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-surface-subtle);
  border-radius: 12px;
}

.stat-value {
  font-size: 1.8em;
  font-weight: 700;
  color: var(--color-text-strong);
}

.stat-label {
  color: var(--color-text-muted);
  font-size: 0.9em;
}

/* Dashboard Grid */
.dashboard-grid {
  display: grid;
  grid-template-columns: 2fr 1fr;
  gap: 24px;
  margin-bottom: 24px;
}

@media (max-width: 1024px) {
  .dashboard-grid {
    grid-template-columns: 1fr;
  }
}

/* Widget Styles */
.widget {
  background: var(--color-surface);
  border-radius: 12px;
  box-shadow: var(--shadow-md);
  overflow: hidden;
}

.widget.full-width {
  grid-column: 1 / -1;
}

.widget-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 16px 20px;
  background: var(--color-surface-subtle);
  border-bottom: 1px solid var(--color-border);
}

.widget-header h2 {
  margin: 0;
  font-size: 1.1em;
  color: var(--color-text-strong);
  display: flex;
  align-items: center;
  gap: 8px;
}

.badge {
  background: var(--color-primary);
  color: var(--color-on-primary);
  padding: 2px 10px;
  border-radius: 12px;
  font-size: 0.85em;
  font-weight: 600;
}

.badge-paid {
  background: var(--color-success);
}

.badge-unpaid {
  background: var(--color-danger);
}

.widget-content {
  padding: 0;
}

.widget-footer {
  padding: 12px 20px;
  border-top: 1px solid var(--color-border);
  text-align: right;
}

.widget-footer a {
  color: var(--color-primary);
  text-decoration: none;
  font-size: 0.9em;
}

.widget-footer a:hover {
  text-decoration: underline;
}

.widget-summary {
  display: flex;
  justify-content: space-between;
  padding: 12px 20px;
  background: var(--color-surface-subtle);
  border-top: 1px solid var(--color-border);
  font-size: 0.9em;
}

.summary-amount {
  color: var(--color-text-strong);
  font-weight: 600;
}

/* Data Table */
.data-table {
  width: 100%;
  border-collapse: collapse;
}

.data-table th,
.data-table td {
  padding: 12px 16px;
  text-align: left;
  border-bottom: 1px solid var(--color-border);
}

.data-table th {
  background: var(--color-surface-subtle);
  font-weight: 600;
  color: var(--color-text-strong);
  font-size: 0.85em;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.data-table tbody tr {
  cursor: pointer;
  transition: background 0.15s;
}

.data-table tbody tr:hover {
  background: var(--color-surface-subtle);
}

.data-table td {
  color: var(--color-text-strong);
}

.data-table .numeric {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.data-table .overdue {
  color: var(--color-danger);
  font-weight: 600;
}
.data-table tr.overdue-row td {
  color: var(--color-danger);
}

.load-error {
  color: var(--color-danger-hover);
  padding: 8px 0;
}

.empty-state {
  padding: 40px;
  text-align: center;
  color: var(--color-text-muted);
}

/* Quick Actions */
.quick-actions {
  display: flex;
  flex-direction: column;
  gap: 12px;
  padding: 20px;
}

.action-btn {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px;
  background: var(--color-surface-subtle);
  border-radius: 8px;
  text-decoration: none;
  color: var(--color-text-strong);
  transition: all 0.2s;
  border: 1px solid transparent;
}

.action-btn:hover {
  background: var(--color-primary-soft);
  border-color: var(--color-primary);
  transform: translateX(4px);
}

.action-icon {
  font-size: 1.4em;
}

/* Period Buttons */
.period-buttons {
  display: flex;
  gap: 8px;
  flex-wrap: wrap;
}

.period-buttons button {
  padding: 6px 14px;
  border: 1px solid var(--color-border);
  background: var(--color-surface);
  border-radius: 6px;
  cursor: pointer;
  font-size: 0.85em;
  transition: all 0.2s;
}

.period-buttons button:hover {
  background: var(--color-surface-subtle);
  border-color: var(--color-text-subtle);
}

.period-buttons button.active {
  background: var(--color-primary);
  color: var(--color-on-primary);
  border-color: var(--color-primary);
}

/* Statistics Grid */
.statistics-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 280px), 1fr));
  gap: 24px;
  padding: 24px;
}

.stat-section {
  background: var(--color-surface-subtle);
  border-radius: 8px;
  padding: 20px;
}

.stat-section h3 {
  margin: 0 0 16px 0;
  color: var(--color-text-strong);
  font-size: 1em;
  text-transform: uppercase;
  letter-spacing: 0.5px;
}

.stat-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 10px 0;
  border-bottom: 1px solid var(--color-border);
}

.stat-row:last-child {
  border-bottom: none;
}

.stat-row span {
  color: var(--color-text-muted);
  font-size: 0.95em;
}

.stat-row strong {
  color: var(--color-text-strong);
  font-weight: 600;
}

.stat-row strong.warning {
  color: var(--color-danger);
}

@media (max-width: 600px) {
  .stats-grid { grid-template-columns: minmax(0, 1fr); gap: 12px; }
  .stat-card { padding: 14px; }
  .stat-value { font-size: 1.5em; }
  .period-selector-bar { padding: 12px; }
  .custom-date-inputs { align-items: stretch; flex-wrap: wrap; gap: 8px; }
  .custom-date-inputs label { flex: 1 1 35px; }
  .custom-date-inputs input { flex: 1 1 120px; min-width: 0; }
  .widget-header { padding: 12px; }
  .widget-summary { padding: 10px 12px; }
  .statistics-grid { grid-template-columns: minmax(0, 1fr); gap: 14px; padding: 14px; }
  .stat-section { padding: 14px; }
}
</style>

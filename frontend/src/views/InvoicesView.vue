<template>
  <div class="invoices">
    <div class="doc-type-tabs" role="group" aria-label="Dokumenttyp">
      <button
        v-for="tab in documentTypeTabs"
        :key="tab.key"
        :class="['tab-btn', { active: documentTypeTab === tab.key }]"
        :aria-pressed="documentTypeTab === tab.key"
        @click="switchDocumentTypeTab(tab.key)"
      >{{ tab.label }}</button>
    </div>
    <label class="mobile-doc-type-picker" for="mobile-document-type">
      <span>Dokumenttyp</span>
      <select
        id="mobile-document-type"
        data-testid="mobile-document-type"
        :value="documentTypeTab"
        @change="switchDocumentTypeTab($event.target.value)"
      >
        <option v-for="tab in documentTypeTabs" :key="tab.key" :value="tab.key">{{ tab.label }}</option>
      </select>
    </label>
    <h1 class="page-heading nav-destination-page-title">
      <span class="page-heading-desktop">{{ pageTitle }}</span>
      <span class="page-heading-mobile">Dokumente</span>
    </h1>
    <div class="tabs">
      <button :class="['tab-btn', { active: !showArchived }]" @click="showArchived = false">Aktiv</button>
      <button :class="['tab-btn', { active: showArchived }]" @click="showArchived = true">Archiv</button>
    </div>
    <div class="filters">
      <input v-model="searchQuery" type="text" placeholder="Suchen..." class="search-input" />
      <select v-model="selectedYear" class="year-select">
        <option value="">Alle Jahre</option>
        <option v-for="year in availableYears" :key="year" :value="year">{{ year }}</option>
      </select>
      <select v-model="selectedFilter" class="filter-select">
        <option v-for="opt in filterOptions" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
      </select>
      <button v-if="!showArchived" @click="openCreateModal" class="btn btn-primary">{{ newButtonLabel }}</button>
    </div>
    <div v-if="loading" class="loading">Lade Rechnungen...</div>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="invoices.length" class="table-scroll" role="region" aria-label="Dokumententabelle" tabindex="0">
    <table class="data-table">
      <thead>
        <tr>
          <th>{{ isInvoiceTab ? 'Rechnungs-Nr' : 'Nummer' }}</th>
          <th>Kunde</th>
          <th>Datum</th>
          <th>{{ isInvoiceTab ? 'Fällig' : 'Gültig bis' }}</th>
          <th>Netto</th>
          <th>Brutto</th>
          <th>Status</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="invoice in invoices"
          :key="invoice.id"
          :class="[{ archived: invoice.archived, 'overdue-row': invoice.documentTypeId === INVOICE && isInvoiceOverdue(invoice) }, 'clickable-row']"
          tabindex="0"
          :aria-label="`${invoice.invoiceNumber || 'Entwurf'} ansehen und Aktionen öffnen`"
          @click="viewInvoice(invoice)"
          @keydown.enter.prevent="viewInvoice(invoice)"
          @keydown.space.prevent="viewInvoice(invoice)"
        >
          <td>{{ invoice.invoiceNumber }}</td>
          <td>{{ invoice.customerName }}</td>
          <td>{{ formatDate(invoice.invoiceDate) }}</td>
          <td>{{ formatDate(invoice.dueDate) }}</td>
          <td>{{ formatCurrency(invoice.netAmount) }}</td>
          <td>{{ formatCurrency(invoice.grossAmount) }}</td>
          <td>
            <span :class="['status', dt.statusClass(invoice.documentStatusId)]">
              {{ dt.statusText(invoice.documentTypeId, invoice.documentStatusId) }}
            </span>
          </td>
        </tr>
      </tbody>
    </table>
    </div>
    <nav v-if="total > pageSize" class="pager" aria-label="Seiten" data-testid="pager">
      <button class="btn btn-small btn-secondary" :disabled="page === 0 || loading" @click="goToPage(page - 1)">Zurück</button>
      <span class="pager-info" data-testid="pager-info">Seite {{ page + 1 }} von {{ pageCount }} · {{ total }} Dokumente</span>
      <button class="btn btn-small btn-secondary" :disabled="page + 1 >= pageCount || loading" @click="goToPage(page + 1)">Weiter</button>
    </nav>
    <div v-if="!invoices.length && !loading" class="no-data">
      {{ emptyStateMessage }}
    </div>

    <DocumentModal
      v-model:visible="showModal"
      :articles="articles"
      :services="services"
      :invoice="editingInvoice"
      :document-type="newDocumentType"
      :initial-customer-id="selectedCustomerId"
      @created="onInvoiceCreated"
      @updated="onInvoiceUpdated"
      @reload-articles="loadArticles"
      @reload-services="loadServices"
    />
    <DocumentViewModal
      v-model:visible="showInvoiceModal"
      :invoice-id="selectedInvoiceId"
      :list-actions="selectedInvoiceActions"
      @status-updated="onDocumentStatusUpdated"
      @cloned="loadInvoices"
      @edit-request="editFromDocumentModal"
      @archive-request="archiveFromDocumentModal"
      @restore-request="restoreFromDocumentModal"
      @delete-request="deleteFromDocumentModal"
      @convert-to-invoice="convertFromDocumentModal"
      @convert-to-offer="convertFromDocumentModal"
    />
    <ConvertModal
      v-model:visible="showConvertModal"
      :source="documentToConvert"
      :target-type="convertTargetType"
      @convert="onConvert"
    />
  </div>
</template>

<script>
import invoiceAPI from '../services/invoiceService.js'
import articleAPI from '../services/articleService.js'
import DocumentModal from '../components/DocumentModal.vue'
import DocumentViewModal from '../components/DocumentViewModal.vue'
import ConvertModal from '../components/ConvertModal.vue'
import { useDocumentType } from '../composables/useDocumentType.js'
import { isSent as _isSent, isAccepted as _isAccepted, isDraft as _isDraft, isConverted as _isConverted, isInvoiceOverdue as _isInvoiceOverdue, SENT, PAID, CANCELLED, CONVERTED, ACCEPTED } from '../utils/documentStatus.js'
import { INVOICE as _INVOICE, OFFER as _OFFER, COST_CALCULATION as _COST_CALCULATION, isBookkeepingRecord } from '../data/documentTypes.js'
import { convertDocument } from '../utils/documentConversion.js'

export default {
  name: 'InvoicesView',
  components: { DocumentModal, DocumentViewModal, ConvertModal },
  data() {
    return {
      // One page of the list, filtered by the backend (GET /api/invoices/page).
      invoices: [],
      page: 0,
      pageSize: 50,
      total: 0,
      years: [],
      articles: [],
      services: [],
      searchQuery: '',
      selectedYear: new Date().getFullYear(),
      selectedFilter: '',
      listReady: false,
      loading: false,
      isLoadingData: false,
      error: null,
      showModal: false,
      editingInvoice: null,
      showArchived: false,
      newDocumentType: 1,
      documentTypeTab: 'invoice',
      selectedCustomerId: null,
      showInvoiceModal: false,
      selectedInvoiceId: null,
      selectedInvoice: null,
      showConvertModal: false,
      documentToConvert: null,
      convertTargetType: 1,
      INVOICE: _INVOICE,
      OFFER: _OFFER,
      COST_CALCULATION: _COST_CALCULATION,
      SENT,
      PAID,
      CANCELLED,
      CONVERTED,
      ACCEPTED
    }
  },
  async mounted() {
    this.setupRouteWatch()
    // Apply tab / filter / year from the URL first, so the first page is loaded with them.
    this.handleRouteQuery()
    await this.loadData()
    this.listReady = true
  },
  beforeUnmount() {
    if (this._routeWatcher) {
      this._routeWatcher()
    }
    clearTimeout(this._searchTimer)
  },
  watch: {
    listFilters() {
      if (!this.listReady) return
      this.page = 0
      this.loadInvoices()
    },
    searchQuery() {
      if (!this.listReady) return
      clearTimeout(this._searchTimer)
      this._searchTimer = setTimeout(() => {
        this.page = 0
        this.loadInvoices()
      }, 300)
    }
  },
  computed: {
    dt() {
      return useDocumentType()
    },
    documentTypeTabs() {
      return this.dt.documentTypeTabs.value
    },
    activeDocTypes() {
      return this.dt.activeDocTypes(this.documentTypeTab)
    },
    isInvoiceTab() {
      return this.dt.isInvoiceType(this.tabToDocType(this.documentTypeTab))
    },
    pageTitle() {
      return this.dt.pageTitle(this.documentTypeTab)
    },
    newButtonLabel() {
      return this.dt.newButtonLabel(this.documentTypeTab)
    },
    filterOptions() {
      return this.dt.statusFilterOptions(this.tabToDocType(this.documentTypeTab))
    },
    emptyStateMessage() {
      return this.dt.emptyMessage(this.documentTypeTab, this.showArchived)
    },
    /** Years with documents (from the backend); the selected one stays selectable. */
    availableYears() {
      const years = new Set(this.years)
      if (this.selectedYear) years.add(this.selectedYear)
      return Array.from(years).sort((a, b) => b - a)
    },
    pageCount() {
      return Math.max(1, Math.ceil(this.total / this.pageSize))
    },
    /** Everything the backend filters by; a change starts again at page 1. */
    listFilters() {
      return [this.documentTypeTab, this.showArchived, this.selectedYear, this.selectedFilter].join('|')
    },
    selectedInvoiceActions() {
      const invoice = this.selectedInvoice
      if (!invoice) return {}
      const draft = this.isDraft(invoice.documentStatusId)
      return {
        edit: !this.showArchived
          && invoice.documentStatusId !== this.CANCELLED
          && invoice.documentStatusId !== this.CONVERTED
          && this.isEditable(invoice),
        archive: !this.showArchived && !draft,
        restore: this.showArchived,
        delete: draft,
        convertToInvoice: !this.showArchived && invoice.documentTypeId === this.OFFER && this.isAccepted(invoice.documentStatusId),
        convertToOffer: !this.showArchived && invoice.documentTypeId === this.COST_CALCULATION && this.isAccepted(invoice.documentStatusId)
      }
    }
  },
  methods: {
    setupRouteWatch() {
      this._routeWatcher = this.$watch(
        () => this.$route.query,
        () => this.handleRouteQuery(),
        { deep: true }
      )
    },
    handleRouteQuery() {
      let type = this.$route.query.type
      if (type === 'cost-calculation') type = 'costCalculation'
      if (type) this.documentTypeTab = type
      this.newDocumentType = this.tabToDocType(this.documentTypeTab)

      if (this.$route.query.create === 'true') {
        this.selectedCustomerId = this.$route.query.customerId ? parseInt(this.$route.query.customerId) : null
        this.openCreateModal()
      }
      // Only keys the current tab offers: an unknown key (an old link) would be refused by the
      // backend, so it falls back to "all statuses".
      const filter = this.$route.query.filter
      if (filter) {
        this.selectedFilter = this.filterOptions.some(opt => opt.value === filter) ? filter : ''
      }
      if (this.$route.query.year) {
        this.selectedYear = parseInt(this.$route.query.year)
      }
      const editId = this.$route.query.edit
      if (editId) {
        this.loadInvoiceForEdit(parseInt(editId))
      }
      const viewId = this.$route.query.view
      if (viewId) {
        this.loadInvoiceForView(parseInt(viewId))
      }
    },
    tabToDocType(key) {
      const dt = this.dt.getDocumentType(key)
      return dt ? dt.id : 1
    },
    switchDocumentTypeTab(key) {
      this.documentTypeTab = key
      this.newDocumentType = this.tabToDocType(key)
      this.selectedFilter = ''
    },
    async loadData() {
      if (this.isLoadingData) return
      this.isLoadingData = true
      this.loading = true
      try {
        await Promise.all([
          this.loadInvoices(),
          this.loadArticles(),
          this.loadServices()
        ])
      } catch (err) {
        console.error('Error loading data:', err)
      } finally {
        this.loading = false
        this.isLoadingData = false
      }
    },
    /**
     * Loads the current page with the current filters. A response that arrives after a newer
     * request was started is dropped, so fast typing or clicking cannot show a stale page.
     */
    async loadInvoices() {
      const request = (this._listRequest || 0) + 1
      this._listRequest = request
      try {
        const response = await invoiceAPI.getInvoicePage({
          page: this.page,
          size: this.pageSize,
          types: this.activeDocTypes,
          archived: this.showArchived,
          year: this.selectedYear,
          q: this.searchQuery.trim(),
          status: this.selectedFilter
        })
        if (request !== this._listRequest) return
        const { items, total, years } = response.data
        this.invoices = items
        this.total = total
        this.years = years
        if (!items.length && this.page > 0 && total > 0) {
          // The page became empty (e.g. its last document was archived): show the last page.
          this.page = Math.ceil(total / this.pageSize) - 1
          return this.loadInvoices()
        }
        await this.refreshSelectedInvoice(items)
      } catch (err) {
        if (request === this._listRequest) {
          this.error = 'Fehler beim Laden der Dokumente: ' + err.message
        }
      }
    },
    goToPage(page) {
      this.page = page
      this.loadInvoices()
    },
    async loadArticles() {
      const response = await articleAPI.getArticles()
      this.articles = response.data
    },
    async loadServices() {
      const response = await articleAPI.getServices()
      this.services = response.data
    },
    openCreateModal() {
      this.editingInvoice = null
      this.showModal = true
    },
    editInvoice(invoice) {
      this.loadInvoiceForEdit(invoice.id)
    },
    async loadInvoiceForEdit(id) {
      try {
        const response = await invoiceAPI.getInvoice(id)
        this.editingInvoice = response.data
        this.showModal = true
      } catch (err) {
        this.error = 'Fehler beim Laden: ' + err.message
      }
    },
    async loadInvoiceForView(id) {
      try {
        const response = await invoiceAPI.getInvoice(id)
        this.viewInvoice(response.data)
      } catch (err) {
        this.error = 'Fehler beim Laden: ' + err.message
      }
    },
    onInvoiceCreated() {
      this.showModal = false
      this.loadInvoices()
    },
    onInvoiceUpdated() {
      this.showModal = false
      this.editingInvoice = null
      this.loadInvoices()
    },
    async deleteInvoice(id) {
      if (!confirm('Wirklich löschen?')) return
      try {
        await invoiceAPI.deleteInvoice(id)
        if (this.selectedInvoiceId === id) this.showInvoiceModal = false
        this.loadInvoices()
      } catch (err) {
        this.error = 'Fehler beim Löschen: ' + err.message
      }
    },
    async archiveInvoice(id) {
      try {
        await invoiceAPI.archiveInvoice(id)
        if (this.selectedInvoiceId === id) this.showInvoiceModal = false
        this.loadInvoices()
      } catch (err) {
        this.error = 'Fehler beim Archivieren: ' + err.message
      }
    },
    async unarchiveInvoice(id) {
      try {
        await invoiceAPI.unarchiveInvoice(id)
        if (this.selectedInvoiceId === id) this.showInvoiceModal = false
        this.loadInvoices()
      } catch (err) {
        this.error = 'Fehler beim Wiederherstellen: ' + err.message
      }
    },
    viewInvoice(invoice) {
      this.selectedInvoice = invoice
      this.selectedInvoiceId = invoice.id
      this.showInvoiceModal = true
    },
    /**
     * Keeps the open document (its list actions depend on it) current after a reload. It may be on
     * another page or filtered out, so it is fetched on its own then.
     */
    async refreshSelectedInvoice(invoices) {
      if (!this.selectedInvoiceId || !this.showInvoiceModal) return
      const refreshed = invoices.find(invoice => invoice.id === this.selectedInvoiceId)
      if (refreshed) {
        this.selectedInvoice = refreshed
        return
      }
      try {
        this.selectedInvoice = (await invoiceAPI.getInvoice(this.selectedInvoiceId)).data
      } catch {
        // Deleted meanwhile: keep the last known state; the modal reports its own errors.
      }
    },
    async onDocumentStatusUpdated() {
      await this.loadInvoices()
    },
    editFromDocumentModal(invoice) {
      this.showInvoiceModal = false
      this.editInvoice(invoice)
    },
    archiveFromDocumentModal(id) {
      this.archiveInvoice(id)
    },
    restoreFromDocumentModal(id) {
      this.unarchiveInvoice(id)
    },
    deleteFromDocumentModal(id) {
      this.deleteInvoice(id)
    },
    convertFromDocumentModal(invoice) {
      this.showInvoiceModal = false
      if (invoice.documentTypeId === this.OFFER) this.convertToInvoice(invoice)
      else this.convertToOffer(invoice)
    },
    convertToInvoice(offer) {
      this.documentToConvert = offer
      this.convertTargetType = 1
      this.showConvertModal = true
    },
    convertToOffer(calc) {
      this.documentToConvert = calc
      this.convertTargetType = 2
      this.showConvertModal = true
    },
    async onConvert(dates) {
      try {
        await convertDocument(this.documentToConvert, this.convertTargetType, dates)
        this.showConvertModal = false
        this.documentToConvert = null
        this.loadInvoices()
      } catch (err) {
        this.error = 'Fehler beim Umwandeln: ' + err.message
      }
    },
    async downloadPdf(id) {
      try {
        const response = await invoiceAPI.downloadInvoicePdf(id)
        const blob = new Blob([response.data], { type: 'application/pdf' })
        const url = window.URL.createObjectURL(blob)
        window.open(url)
      } catch (err) {
        this.error = 'PDF-Download fehlgeschlagen: ' + err.message
      }
    },
    formatDate(date) {
      return date ? new Date(date).toLocaleDateString('de-DE') : '-'
    },
    formatCurrency(amount) {
      return amount ? new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(amount) : '-'
    },
    async updateStatus(id, statusId) {
      try {
        await invoiceAPI.updateInvoiceStatus(id, statusId)
        this.loadInvoices()
      } catch (err) {
        this.error = 'Fehler beim Status-Update: ' + err.message
      }
    },
    isDraft(statusId) { return _isDraft(statusId) },
    /**
     * Issued invoices and credit notes are frozen (GoBD): only drafts open in
     * the editor. Offers and cost calculations stay editable as before.
     */
    isEditable(doc) {
      return !isBookkeepingRecord(doc.documentTypeId) || _isDraft(doc.documentStatusId)
    },
    isSent(statusId) { return _isSent(statusId) },
    isAccepted(statusId) { return _isAccepted(statusId) },
    isConverted(statusId) { return _isConverted(statusId) },
    isInvoiceOverdue(invoice) { return _isInvoiceOverdue(invoice) }
  }
}
</script>

<style scoped>
.invoices { box-sizing: border-box; width: 100%; max-width: 100%; min-width: 0; padding: 20px; }
.page-heading { min-width: 0; }
.page-heading-mobile { display: none; }
.mobile-doc-type-picker { display: none; }
.tabs { display: flex; gap: 10px; margin-bottom: 20px; }
.pager { display: flex; align-items: center; justify-content: center; gap: 12px; margin: 16px 0; flex-wrap: wrap; }
.pager-info { color: var(--color-text-muted); font-size: 14px; }
.doc-type-tabs { display: flex; justify-content: center; gap: 0; margin-bottom: 24px; background: var(--color-surface-muted); border-radius: 12px; padding: 4px; max-width: 500px; margin-left: auto; margin-right: auto; }
.doc-type-tabs .tab-btn { padding: 12px 28px; border: none; background: transparent; cursor: pointer; border-radius: 10px; font-size: 15px; font-weight: 600; color: var(--color-text-muted); transition: all 0.2s; }
.doc-type-tabs .tab-btn:hover { color: var(--color-text-strong); background: color-mix(in srgb, var(--color-surface) 50%, transparent); }
.doc-type-tabs .tab-btn.active { background: var(--color-surface); color: var(--color-primary); box-shadow: var(--shadow-md); }
.tab-btn { padding: 10px 20px; border: 1px solid var(--color-border-strong); background: var(--color-surface); cursor: pointer; border-radius: 4px 4px 0 0; }
.tab-btn.active { background: var(--color-primary); color: var(--color-on-primary); border-color: var(--color-primary); }
.filters { display: flex; gap: 15px; margin-bottom: 20px; align-items: center; }
.search-input { flex: 1; max-width: 250px; padding: 8px 12px; border: 1px solid var(--color-border-strong); border-radius: 4px; font-size: 14px; }
.year-select { padding: 8px 12px; border: 1px solid var(--color-border-strong); border-radius: 4px; font-size: 14px; background: var(--color-surface); }
.filter-select { padding: 8px 12px; border: 1px solid var(--color-border-strong); border-radius: 4px; font-size: 14px; background: var(--color-surface); }
.data-table tr.archived { background: var(--color-surface-muted); opacity: 0.8; }
.data-table tr.overdue-row td { color: var(--color-danger); }
.loading, .error, .no-data { padding: 20px; text-align: center; }
.error { color: var(--color-danger); }

@media (max-width: 600px) {
  .invoices { padding: 12px; }
  .doc-type-tabs { display: none; }
  .mobile-doc-type-picker { display: flex; flex-direction: column; gap: 6px; width: 100%; min-width: 0; margin: 0 0 14px; color: var(--color-text-strong); font-size: 14px; font-weight: 600; }
  .mobile-doc-type-picker select { box-sizing: border-box; width: 100%; max-width: 100%; min-width: 0; min-height: 44px; padding: 9px 12px; border: 1px solid var(--color-border-strong); border-radius: 6px; background: var(--color-surface); color: var(--color-text); font: inherit; font-size: 16px; }
  .page-heading { margin-bottom: 14px; font-size: 1.5rem; line-height: 1.2; overflow-wrap: anywhere; }
  .page-heading-desktop { display: none; }
  .page-heading-mobile { display: inline; }
  .filters { flex-wrap: wrap; gap: 10px; }
  .search-input { flex: 1 1 100%; max-width: none; }
  .year-select, .filter-select { flex: 1 1 calc(50% - 5px); min-width: 0; }
  .filters .btn-primary { flex: 1 1 100%; min-height: 42px; }
  .tabs { flex-wrap: wrap; }
}
</style>

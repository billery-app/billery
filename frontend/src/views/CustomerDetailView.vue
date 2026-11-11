<template>
  <div class="customer-detail">
    <div class="header">
      <button @click="goBack" class="btn btn-secondary">← Zurück</button>
      <h1>Kundendetails</h1>
    </div>

    <div v-if="loading" class="loading">Lade Kundendaten...</div>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="customer && !loading" class="content">
      <!-- Customer Info Card -->
      <div class="card info-card">
        <h2>{{ customer.firstName }} {{ customer.lastName }}</h2>
        <div class="info-grid">
          <div class="info-item">
            <label>Adresse:</label>
            <span>{{ customer.street }} {{ customer.houseNumber }}, {{ customer.postalCode }} {{ customer.city }}</span>
          </div>
          <div class="info-item">
            <label>Telefon:</label>
            <span>{{ customer.phone || '-' }}</span>
          </div>
          <div class="info-item">
            <label>Email:</label>
            <span>{{ customer.email || '-' }}</span>
          </div>
          <div class="info-item">
            <label>USt-IdNr. / Steuernummer:</label>
            <span>{{ customer.taxId || '-' }}</span>
          </div>
          <div v-if="customer.buyerReference" class="info-item">
            <label>Leitweg-ID / Käuferreferenz:</label>
            <span>{{ customer.buyerReference }}</span>
          </div>
          <div v-if="customer.note" class="info-item full-width">
            <label>Notiz:</label>
            <span>{{ customer.note }}</span>
          </div>
        </div>
        <div class="actions">
          <button @click="editCustomer" class="btn btn-primary">Bearbeiten</button>
          <button @click="createInvoice" class="btn btn-success">Neue Rechnung</button>
          <button @click="createOffer" class="btn btn-secondary">Neues Angebot</button>
          <button @click="createCostCalculation" class="btn btn-secondary">Neuer Kostenvoranschlag</button>
        </div>
      </div>

      <!-- Statistics -->
      <div class="stats-grid">
        <div class="card stat-card">
          <h3>Gesamtumsatz</h3>
          <p class="stat-value">{{ formatCurrency(totalRevenue) }}</p>
        </div>
        <div class="card stat-card">
          <h3>Offener Betrag</h3>
          <p class="stat-value" :class="{ 'negative': openAmount > 0 }">{{ formatCurrency(openAmount) }}</p>
        </div>
        <div class="card stat-card">
          <h3>Anzahl Rechnungen</h3>
          <p class="stat-value">{{ invoiceCount }}</p>
        </div>
        <div class="card stat-card">
          <h3>Anzahl Angebote</h3>
          <p class="stat-value">{{ offerCount }}</p>
        </div>
        <div class="card stat-card">
          <h3>Anzahl Kostenvoranschläge</h3>
          <p class="stat-value">{{ costCalcCount }}</p>
        </div>
      </div>

      <!-- Documents Section -->
        <div class="card documents-section">
        <h2>Dokumente</h2>
        <div class="tabs">
          <button 
            :class="['tab-btn', { active: activeTab === 'invoices' }]" 
            @click="activeTab = 'invoices'"
          >
            Rechnungen ({{ trueInvoices.length }})
          </button>
          <button 
            :class="['tab-btn', { active: activeTab === 'offers' }]" 
            @click="activeTab = 'offers'"
          >
            Angebote ({{ offers.length }})
          </button>
          <button 
            :class="['tab-btn', { active: activeTab === 'costCalculations' }]" 
            @click="activeTab = 'costCalculations'"
          >
            Kostenvoranschläge ({{ costCalculations.length }})
          </button>
        </div>

        <!-- Invoices Tab -->
        <div v-if="activeTab === 'invoices'" class="tab-content">
          <div v-if="trueInvoices.length" class="table-scroll" role="region" aria-label="Rechnungstabelle" tabindex="0">
          <table class="data-table">
            <thead>
              <tr>
                <th>Rechnungs-Nr</th>
                <th>Datum</th>
                <th>Fällig</th>
                <th>Netto</th>
                <th>Brutto</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="invoice in trueInvoices"
                :key="invoice.id"
                class="clickable-row"
                tabindex="0"
                :aria-label="`${invoice.invoiceNumber || 'Rechnung'} ansehen und Aktionen öffnen`"
                @click="viewInvoice(invoice)"
                @keydown.enter.prevent="viewInvoice(invoice)"
                @keydown.space.prevent="viewInvoice(invoice)"
              >
                <td>{{ invoice.invoiceNumber }}</td>
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
          <div v-else class="empty-state">Keine Rechnungen vorhanden</div>
        </div>

        <!-- Offers Tab -->
        <div v-if="activeTab === 'offers'" class="tab-content">
          <div v-if="offers.length" class="table-scroll" role="region" aria-label="Angebotstabelle" tabindex="0">
          <table class="data-table">
            <thead>
              <tr>
                <th>Angebots-Nr</th>
                <th>Datum</th>
                <th>Gültig bis</th>
                <th>Netto</th>
                <th>Brutto</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="offer in offers"
                :key="offer.id"
                class="clickable-row"
                tabindex="0"
                :aria-label="`${offer.invoiceNumber || 'Angebot'} ansehen und Aktionen öffnen`"
                @click="viewInvoice(offer)"
                @keydown.enter.prevent="viewInvoice(offer)"
                @keydown.space.prevent="viewInvoice(offer)"
              >
                <td>{{ offer.invoiceNumber }}</td>
                <td>{{ formatDate(offer.invoiceDate) }}</td>
                <td>{{ formatDate(offer.dueDate) }}</td>
                <td>{{ formatCurrency(offer.netAmount) }}</td>
                <td>{{ formatCurrency(offer.grossAmount) }}</td>
                <td>
                  <span :class="['status', dt.statusClass(offer.documentStatusId)]">
                    {{ dt.statusText(offer.documentTypeId, offer.documentStatusId) }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
          </div>
          <div v-else class="empty-state">Keine Angebote vorhanden</div>
        </div>

        <!-- Cost Calculations Tab -->
        <div v-if="activeTab === 'costCalculations'" class="tab-content">
          <div v-if="costCalculations.length" class="table-scroll" role="region" aria-label="Kostenvoranschlagstabelle" tabindex="0">
          <table class="data-table">
            <thead>
              <tr>
                <th>KV-Nummer</th>
                <th>Datum</th>
                <th>Gültig bis</th>
                <th>Netto</th>
                <th>Brutto</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              <tr
                v-for="calc in costCalculations"
                :key="calc.id"
                class="clickable-row"
                tabindex="0"
                :aria-label="`${calc.invoiceNumber || 'Kostenvoranschlag'} ansehen und Aktionen öffnen`"
                @click="viewInvoice(calc)"
                @keydown.enter.prevent="viewInvoice(calc)"
                @keydown.space.prevent="viewInvoice(calc)"
              >
                <td>{{ calc.invoiceNumber }}</td>
                <td>{{ formatDate(calc.invoiceDate) }}</td>
                <td>{{ formatDate(calc.dueDate) }}</td>
                <td>{{ formatCurrency(calc.netAmount) }}</td>
                <td>{{ formatCurrency(calc.grossAmount) }}</td>
                <td>
                  <span :class="['status', dt.statusClass(calc.documentStatusId)]">
                    {{ dt.statusText(calc.documentTypeId, calc.documentStatusId) }}
                  </span>
                </td>
              </tr>
            </tbody>
          </table>
          </div>
          <div v-else class="empty-state">Keine Kostenvoranschläge vorhanden</div>
        </div>
      </div>
    </div>

    <!-- Customer Edit Modal -->
    <CustomerCreateModal
      v-model:visible="showEditModal"
      :customer="customer"
      @updated="onCustomerUpdated"
    />
    
    <!-- Document View Modal -->
    <DocumentViewModal
      v-model:visible="showInvoiceModal"
      :invoice-id="selectedInvoiceId"
      :list-actions="selectedDocumentActions"
      @status-updated="loadData"
      @cloned="loadData"
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
import customerAPI from '../services/customerService.js'
import articleAPI from '../services/articleService.js'
import invoiceAPI from '../services/invoiceService.js'
import CustomerCreateModal from '../components/CustomerCreateModal.vue'
import DocumentViewModal from '../components/DocumentViewModal.vue'
import ConvertModal from '../components/ConvertModal.vue'
import { useDocumentType } from '../composables/useDocumentType.js'
import { isAccepted as _isAccepted, isPaid, isOpenInvoice } from '../utils/documentStatus.js'
import { isInvoice, isOffer, isCostCalc, INVOICE, OFFER, COST_CALCULATION } from '../data/documentTypes.js'
import { convertDocument } from '../utils/documentConversion.js'

export default {
  name: 'CustomerDetailView',
  components: { CustomerCreateModal, DocumentViewModal, ConvertModal },
  data() {
    return {
      customer: null,
      invoices: [],
      articles: [],
      services: [],
      loading: false,
      error: null,
      activeTab: 'invoices',
      showEditModal: false,
      showInvoiceModal: false,
      selectedInvoiceId: null,
      selectedDocument: null,
      showConvertModal: false,
      documentToConvert: null,
      convertTargetType: 1,

    }
  },
  computed: {
    dt() {
      return useDocumentType()
    },
    customerId() {
      return parseInt(this.$route.params.id)
    },
    // Issued, not cancelled invoices: INVOICE (1), PARTED_INVOICE (4), FINAL_INVOICE (5)
    trueInvoices() {
      return this.invoices.filter(inv =>
        (isInvoice(inv.documentTypeId) || !inv.documentTypeId)
        && (isOpenInvoice(inv.documentStatusId) || isPaid(inv.documentStatusId))
      )
    },
    // Offers only: OFFER (2) — count only accepted
    offers() {
      return this.invoices.filter(inv =>
        isOffer(inv.documentTypeId) && _isAccepted(inv.documentStatusId)
      )
    },
    // Cost calculations only: COST_CALCULATION (3) — count only accepted
    costCalculations() {
      return this.invoices.filter(inv =>
        isCostCalc(inv.documentTypeId) && _isAccepted(inv.documentStatusId)
      )
    },
    invoiceCount() {
      return this.trueInvoices.length
    },
    offerCount() {
      return this.offers.length
    },
    costCalcCount() {
      return this.costCalculations.length
    },
    // Money actually received (sum of recorded payments, partial ones included)
    totalRevenue() {
      return this.trueInvoices
        .reduce((sum, inv) => sum + (inv.paidAmount || 0), 0)
    },
    openAmount() {
      return this.trueInvoices
        .filter(inv => isOpenInvoice(inv.documentStatusId))
        .reduce((sum, inv) => sum + (inv.grossAmount || 0) - (inv.paidAmount || 0), 0)
    },
    selectedDocumentActions() {
      const document = this.selectedDocument
      return {
        convertToInvoice: !!document && isOffer(document.documentTypeId) && _isAccepted(document.documentStatusId),
        convertToOffer: !!document && isCostCalc(document.documentTypeId) && _isAccepted(document.documentStatusId)
      }
    }
  },
  mounted() {
    this.loadData()
  },
  methods: {
    async loadData() {
      this.loading = true
      try {
        const [customerResponse, invoicesResponse, articlesResponse, servicesResponse] = await Promise.all([
          customerAPI.getCustomer(this.customerId),
          invoiceAPI.getInvoicesByCustomer(this.customerId),
          articleAPI.getArticles(),
          articleAPI.getServices()
        ])
        this.customer = customerResponse.data
        this.invoices = invoicesResponse.data
        if (this.selectedInvoiceId) {
          this.selectedDocument = this.invoices.find(document => document.id === this.selectedInvoiceId) || this.selectedDocument
        }
        this.articles = articlesResponse.data
        this.services = servicesResponse.data
      } catch (err) {
        this.error = 'Fehler beim Laden der Daten: ' + err.message
      } finally {
        this.loading = false
      }
    },
    goBack() {
      this.$router.push('/customers')
    },
    editCustomer() {
      this.showEditModal = true
    },
    onCustomerUpdated() {
      this.showEditModal = false
      this.loadData()
    },
    createInvoice() {
      this.$router.push({
        path: '/invoices',
        query: { create: 'true', customerId: this.customerId }
      })
    },
    createOffer() {
      this.$router.push({
        path: '/invoices',
        query: { create: 'true', customerId: this.customerId, type: 'offer' }
      })
    },
    createCostCalculation() {
      this.$router.push({
        path: '/invoices',
        query: { create: 'true', customerId: this.customerId, type: 'cost-calculation' }
      })
    },
    viewInvoice(document) {
      this.selectedDocument = document
      this.selectedInvoiceId = document.id
      this.showInvoiceModal = true
    },
    isAccepted(statusId) {
      return _isAccepted(statusId)
    },
    convertFromDocumentModal(document) {
      this.showInvoiceModal = false
      if (isOffer(document.documentTypeId)) this.convertToInvoice(document)
      else this.convertToOffer(document)
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
        this.loadData()
      } catch (err) {
        this.error = 'Fehler beim Umwandeln: ' + err.message
      }
    },
    formatDate(date) {
      return date ? new Date(date).toLocaleDateString('de-DE') : '-'
    },
    formatCurrency(amount) {
      return amount ? new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(amount) : '-'
    }
  }
}
</script>

<style scoped>
.customer-detail {
  padding: 20px;
  max-width: 1200px;
  margin: 0 auto;
}

.header {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-bottom: 30px;
}

h1 {
  margin: 0;
}

.content {
  display: flex;
  flex-direction: column;
  gap: 20px;
}

.info-card h2 {
  margin: 0 0 20px 0;
  color: var(--color-text);
}

.info-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(250px, 1fr));
  gap: 15px;
  margin-bottom: 20px;
}

.info-item {
  display: flex;
  flex-direction: column;
  gap: 5px;
}

.info-item.full-width {
  grid-column: 1 / -1;
}

.info-item label {
  font-weight: bold;
  color: var(--color-text-muted);
  font-size: 0.9em;
}

.info-item span {
  color: var(--color-text);
}

.actions {
  display: flex;
  gap: 10px;
  flex-wrap: wrap;
}

.stats-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: 15px;
}

.stat-card {
  padding: 15px;
  text-align: center;
}

.stat-card h3 {
  margin: 0 0 10px 0;
  font-size: 0.9em;
  color: var(--color-text-muted);
}

.stat-value {
  margin: 0;
  font-size: 1.5em;
  font-weight: bold;
  color: var(--color-text);
}

.stat-value.negative {
  color: var(--color-danger);
}

.documents-section h2 {
  margin: 0 0 20px 0;
}

.tabs {
  display: flex;
  gap: 10px;
  margin-bottom: 20px;
  border-bottom: 1px solid var(--color-border);
}

.tab-btn {
  padding: 10px 20px;
  border: none;
  background: none;
  cursor: pointer;
  border-bottom: 2px solid transparent;
  font-size: 14px;
}

.tab-btn.active {
  border-bottom-color: var(--color-primary);
  color: var(--color-primary);
  font-weight: bold;
}

.tab-btn:hover {
  background: var(--color-surface-muted);
}

.loading, .error, .empty-state {
  padding: 40px;
  text-align: center;
}

.error {
  color: var(--color-danger);
}

.empty-state {
  color: var(--color-text-muted);
  font-style: italic;
}

@media (max-width: 600px) {
  .customer-detail { padding: 12px; }
  .header { align-items: flex-start; flex-direction: column; gap: 10px; margin-bottom: 20px; }
  .info-card, .documents-section { padding: 14px; }
  .info-grid { grid-template-columns: minmax(0, 1fr); }
  .actions { display: grid; grid-template-columns: minmax(0, 1fr); }
  .actions button { min-height: 42px; }
  .tabs { overflow-x: auto; white-space: nowrap; }
  .tab-btn { flex: 0 0 auto; padding: 10px 12px; }
}
</style>

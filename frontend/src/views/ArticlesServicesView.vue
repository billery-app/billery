<template>
  <div class="articles-services">
    <h1 class="nav-destination-page-title">Artikel & Leistungen</h1>
    <label class="mobile-catalog-picker" for="mobile-catalog-section">
      <span>Katalogbereich</span>
      <select
        id="mobile-catalog-section"
        data-testid="mobile-catalog-section"
        :value="activeTab"
        @change="activeTab = $event.target.value"
      >
        <option value="articles">Artikel</option>
        <option value="services">Leistungen</option>
        <option value="equipment">Ausstattung</option>
      </select>
    </label>
    <div class="catalog-section-tabs" role="group" aria-label="Katalogbereich">
      <button type="button" :class="['tab-btn', { active: activeTab === 'articles' }]" :aria-pressed="activeTab === 'articles'" @click="activeTab = 'articles'">Artikel</button>
      <button type="button" :class="['tab-btn', { active: activeTab === 'services' }]" :aria-pressed="activeTab === 'services'" @click="activeTab = 'services'">Leistungen</button>
      <button type="button" :class="['tab-btn', { active: activeTab === 'equipment' }]" :aria-pressed="activeTab === 'equipment'" @click="activeTab = 'equipment'">Ausstattung</button>
    </div>
    <div class="filters">
      <input v-model="searchQuery" type="text" placeholder="Suchen..." class="search-input" />
      <button @click="openCreateModal" class="btn btn-primary">{{ activeTab === 'articles' ? 'Neuer Artikel' : activeTab === 'services' ? 'Neue Leistung' : 'Neue Ausstattung' }}</button>
    </div>
    <div v-if="loading" class="loading">Lade...</div>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="activeTab === 'articles' && filteredArticles.length" class="table-scroll" role="region" aria-label="Artikeltabelle" tabindex="0">
    <table class="data-table">
      <thead>
        <tr>
          <th>ID</th>
          <th>Name</th>
          <th>Beschreibung</th>
          <th>Einheit</th>
          <th>Einkaufspreis</th>
          <th>Verkaufspreis</th>
          <th>USt.</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="article in filteredArticles"
          :key="article.id"
          class="clickable-row"
          tabindex="0"
          :aria-label="`Artikel ${article.name} bearbeiten`"
          @click="editArticle(article)"
          @keydown.enter.prevent="editArticle(article)"
          @keydown.space.prevent="editArticle(article)"
        >
          <td>{{ article.id }}</td>
          <td>{{ article.name }}</td>
          <td>{{ article.description }}</td>
          <td>{{ article.unit }}</td>
          <td>{{ formatCurrency(article.purchasePrice) }}</td>
          <td>{{ formatCurrency(article.salesPrice) }}</td>
          <td>{{ article.taxRate ?? 19 }} %</td>
        </tr>
      </tbody>
    </table>
    </div>
    <div v-if="activeTab === 'services' && filteredServices.length" class="table-scroll" role="region" aria-label="Leistungstabelle" tabindex="0">
    <table class="data-table">
      <thead>
        <tr>
          <th>ID</th>
          <th>Name</th>
          <th>Beschreibung</th>
          <th>Stundensatz</th>
          <th>USt.</th>
          <th>Verknüpfter Artikel</th>
          <th>Verknüpfte Ausstattung</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="service in filteredServices"
          :key="service.id"
          class="clickable-row"
          tabindex="0"
          :aria-label="`Leistung ${service.name} bearbeiten`"
          @click="editService(service)"
          @keydown.enter.prevent="editService(service)"
          @keydown.space.prevent="editService(service)"
        >
          <td>{{ service.id }}</td>
          <td>{{ service.name }}</td>
          <td>{{ service.description }}</td>
          <td>{{ formatCurrency(service.hourlyRate) }}</td>
          <td>{{ service.taxRate ?? 19 }} %</td>
          <td>{{ getArticleName(service.articleId) }}</td>
          <td>{{ getEquipmentName(service.equipmentId) }}</td>
        </tr>
      </tbody>
    </table>
    </div>
    <p v-if="activeTab === 'equipment' && equipmentList.length" id="amortisation-hint" class="amort-hint">
      Amortisation: Netto-Umsatz der verknüpften Leistungen aus vollständig bezahlten Rechnungen im Verhältnis zum Kaufpreis.
      Offene und teilbezahlte Rechnungen zählen nicht, monatliche Kosten werden nicht abgezogen.
      Ein Klick auf den Wert zeigt die Einsätze.
    </p>
    <div v-if="activeTab === 'equipment' && equipmentList.length" class="table-scroll" role="region" aria-label="Ausstattungstabelle" tabindex="0">
    <table class="data-table">
      <thead>
        <tr>
          <th>ID</th>
          <th>Name</th>
          <th>Kaufpreis</th>
          <th>Kaufdatum</th>
          <th>Monatliche Kosten</th>
          <th aria-describedby="amortisation-hint">Amortisation</th>
          <th>Status</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="{ eq, amortisation } in equipmentRows"
          :key="eq.id"
          class="clickable-row"
          tabindex="0"
          :aria-label="`Ausstattung ${eq.name} bearbeiten`"
          @click="editEquipment(eq)"
          @keydown.enter.prevent="editEquipment(eq)"
          @keydown.space.prevent="editEquipment(eq)"
        >
          <td>{{ eq.id }}</td>
          <td>{{ eq.name }}</td>
          <td>{{ formatCurrency(eq.purchaseValue) }}</td>
          <td>{{ eq.acquisitionDate ? formatDate(eq.acquisitionDate) : '-' }}</td>
          <td>{{ formatCurrency(eq.monthlyOperatingCost) }}</td>
          <td>
            <button type="button" class="amort-cell" :aria-label="usageButtonLabel(eq, amortisation)" @click.stop="showUsage(eq, $event)" @keydown.stop>
            <template v-if="amortisation">
              <div v-if="amortisation.hasPurchaseValue" class="amort-row">
                <div class="amort-bar">
                  <div class="amort-fill" :style="{ width: amortisation.barWidth + '%' }" :class="{ paid: amortisation.done }"></div>
                </div>
                <span v-if="amortisation.done" class="amort-text amort-done"><span aria-hidden="true">✓</span> amortisiert</span>
                <span v-else class="amort-text">{{ amortisation.percentText }}</span>
              </div>
              <div v-else class="amort-text text-muted">ohne Kaufpreis</div>
              <div class="amort-sub">{{ formatAmount(amortisation.revenue) }} · {{ usageCountLabel(amortisation.usageCount) }}</div>
            </template>
            <span v-else class="text-muted">-</span>
            </button>
          </td>
          <td>{{ eq.status === 'active' ? 'Aktiv' : eq.status }}</td>
        </tr>
      </tbody>
    </table>
    </div>
    <div v-if="activeTab === 'equipment' && !equipmentList.length && !loading" class="empty-state">
      Keine Ausstattung vorhanden.
    </div>
    <ArticleServiceCreateModal
      v-model:visible="showModal"
      :articles="articles"
      :item="editingItem"
      :item-type="activeTab === 'articles' ? 'article' : 'service'"
      :show-delete-action="true"
      @created="onItemCreated"
      @updated="onItemUpdated"
      @delete="deleteCatalogItem"
    />

    <div v-if="showEquipmentModal" class="modal" @click.self="resetEquipmentForm">
      <div class="modal-content">
        <h2>{{ equipmentForm.id ? 'Ausstattung bearbeiten' : 'Neue Ausstattung' }}</h2>
        <form @submit.prevent="saveEquipment">
          <div class="form-group">
            <label>Name:</label>
            <input v-model="equipmentForm.name" required />
          </div>
          <div class="form-group">
            <label>Kaufpreis (€):</label>
            <input v-model="equipmentForm.purchaseValue" type="number" step="0.01" />
          </div>
          <div class="form-group">
            <label>Kaufdatum:</label>
            <input v-model="equipmentForm.acquisitionDate" type="date" />
          </div>
          <div class="form-group">
            <label>Nutzungsdauer (Monate):</label>
            <input v-model="equipmentForm.expectedLifetimeMonths" type="number" />
          </div>
          <div class="form-group">
            <label>Monatliche Betriebskosten (€):</label>
            <input v-model="equipmentForm.monthlyOperatingCost" type="number" step="0.01" />
          </div>
          <div class="form-group">
            <label>Restwert (€):</label>
            <input v-model="equipmentForm.residualValue" type="number" step="0.01" />
          </div>
          <div class="form-group">
            <label>Status:</label>
            <select v-model="equipmentForm.status">
              <option value="active">Aktiv</option>
              <option value="sold">Verkauft</option>
              <option value="retired">Ausgemustert</option>
            </select>
          </div>
          <div class="form-group">
            <label>Notizen:</label>
            <textarea v-model="equipmentForm.notes"></textarea>
          </div>
          <div class="form-actions">
            <button type="submit" class="btn btn-primary">Speichern</button>
            <button v-if="equipmentForm.id" type="button" class="btn btn-danger" @click="deleteEquipment(equipmentForm.id)">Löschen</button>
            <button type="button" @click="resetEquipmentForm" class="btn btn-secondary">Abbrechen</button>
          </div>
        </form>
      </div>
    </div>

    <div v-if="showUsageModal" class="modal" @click.self="closeUsage" @keydown.esc="closeUsage">
      <div ref="usageDialog" class="modal-content usage-modal" role="dialog" aria-modal="true" aria-labelledby="usage-title" tabindex="-1">
        <h2 id="usage-title">Einsätze: {{ usageEquipment?.name }}</h2>
        <div v-if="usageLoading" class="loading">Lade...</div>
        <div v-else-if="usageError" class="error" role="alert">{{ usageError }}</div>
        <template v-else-if="usageItems.length">
        <p class="usage-summary" data-testid="usage-summary">
          <strong>{{ usageCountLabel(usageItems.length) }}</strong> · <strong>{{ formatAmount(usageTotal) }}</strong>
          Netto-Umsatz aus vollständig bezahlten Rechnungen
        </p>
        <div class="table-scroll" role="region" aria-label="Einsatztabelle" tabindex="0">
        <table class="data-table">
          <thead>
            <tr>
              <th>Rechnung</th>
              <th>Datum</th>
              <th>Leistung</th>
              <th>Menge</th>
              <th>Preis</th>
              <th>Gesamt</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(u, i) in usageItems" :key="i">
              <td>{{ u.invoiceNumber }}</td>
              <td>{{ formatDate(u.invoiceDate) }}</td>
              <td>{{ u.serviceName }}</td>
              <td>{{ formatQuantity(u.quantity, u.unit) }}</td>
              <td>{{ formatCurrency(u.unitPrice) }}</td>
              <td>{{ formatCurrency(u.totalPrice) }}</td>
            </tr>
          </tbody>
          <tfoot>
            <tr class="usage-total">
              <th scope="row" colspan="5">Summe · {{ usageCountLabel(usageItems.length) }}</th>
              <td>{{ formatAmount(usageTotal) }}</td>
            </tr>
          </tfoot>
        </table>
        </div>
        </template>
        <div v-else class="empty-state">
          Keine Einsätze gefunden. Gezählt werden nur Leistungen auf vollständig bezahlten Rechnungen.
        </div>
        <div class="form-actions">
          <button type="button" @click="closeUsage" class="btn btn-secondary">Schließen</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import articleAPI from '../services/articleService.js'
import equipmentAPI from '../services/equipmentService.js'
import ArticleServiceCreateModal from '../components/ArticleServiceCreateModal.vue'
import { describeAmortisation } from '../utils/equipmentAmortisation.js'

const CURRENCY_FORMAT = new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' })
const QUANTITY_FORMAT = new Intl.NumberFormat('de-DE', { maximumFractionDigits: 3 })

export default {
  name: 'ArticlesServicesView',
  components: { ArticleServiceCreateModal },
  data() {
    return {
      activeTab: 'articles',
      articles: [],
      services: [],
      equipmentList: [],
      equipmentSummary: [],
      searchQuery: '',
      loading: false,
      error: null,
      showModal: false,
      editingItem: null,
      showEquipmentModal: false,
      equipmentForm: { id: null, name: '', purchaseValue: null, acquisitionDate: '', expectedLifetimeMonths: null, monthlyOperatingCost: null, residualValue: null, status: 'active', notes: '' },
      showUsageModal: false,
      usageEquipment: null,
      usageItems: [],
      usageLoading: false,
      usageError: null,
      usageTrigger: null
    }
  },
  mounted() {
    this.loadItems()
    if (this.$route.query.create === 'true') {
      this.openCreateModal()
    }
    const articleId = this.$route.query.article
    if (articleId) {
      this.loadArticleForEdit(parseInt(articleId))
    }
    const serviceId = this.$route.query.service
    if (serviceId) {
      this.loadServiceForEdit(parseInt(serviceId))
    }
  },
  watch: {
    activeTab() {
      this.loadItems()
    }
  },
  computed: {
    filteredArticles() {
      if (!this.searchQuery) return this.articles
      const query = this.searchQuery.toLowerCase()
      return this.articles.filter(a =>
        (a.name && a.name.toLowerCase().includes(query)) ||
        (a.description && a.description.toLowerCase().includes(query)) ||
        (a.unit && a.unit.toLowerCase().includes(query))
      )
    },
    filteredServices() {
      if (!this.searchQuery) return this.services
      const query = this.searchQuery.toLowerCase()
      return this.services.filter(s =>
        (s.name && s.name.toLowerCase().includes(query)) ||
        (s.description && s.description.toLowerCase().includes(query))
      )
    },
    /**
     * The equipment rows with what the amortisation cell shows, so the template does not look the
     * summary up several times per row.
     *
     * @returns {Array<{eq: object, amortisation: object|null}>}
     */
    equipmentRows() {
      const summaries = new Map(this.equipmentSummary.map(summary => [summary.id, summary]))
      return this.equipmentList.map(eq => ({ eq, amortisation: describeAmortisation(summaries.get(eq.id)) }))
    },
    /**
     * Net revenue of the lines in the usage dialog. The line totals are cent amounts from the
     * server; the sum is only displayed, rounded to cents by {@link formatAmount}.
     *
     * @returns {number} the sum in euros
     */
    usageTotal() {
      return this.usageItems.reduce((sum, usage) => sum + (Number(usage.totalPrice) || 0), 0)
    }
  },
  methods: {
    async loadItems() {
      this.loading = true
      try {
        if (this.activeTab === 'articles') {
          const response = await articleAPI.getArticles()
          this.articles = response.data
        } else if (this.activeTab === 'services') {
          const [servicesRes, articlesRes, equipRes] = await Promise.all([
            articleAPI.getServices(),
            articleAPI.getArticles(),
            equipmentAPI.getAll()
          ])
          this.services = servicesRes.data
          this.articles = articlesRes.data
          this.equipmentList = equipRes.data
        } else if (this.activeTab === 'equipment') {
          const [equipRes, summaryRes] = await Promise.all([
            equipmentAPI.getAll(),
            equipmentAPI.getSummary()
          ])
          this.equipmentList = equipRes.data
          this.equipmentSummary = summaryRes.data
        }
      } catch (err) {
        this.error = 'Fehler beim Laden: ' + err.message
      } finally {
        this.loading = false
      }
    },
    openCreateModal() {
      if (this.activeTab === 'equipment') {
        this.equipmentForm = { id: null, name: '', purchaseValue: null, acquisitionDate: '', expectedLifetimeMonths: null, monthlyOperatingCost: null, residualValue: null, status: 'active', notes: '' }
        this.showEquipmentModal = true
      } else {
        this.editingItem = null
        this.showModal = true
      }
    },
    editArticle(article) {
      this.editingItem = article
      this.activeTab = 'articles'
      this.showModal = true
    },
    async loadArticleForEdit(id) {
      try {
        const response = await articleAPI.getArticles()
        const article = response.data.find(a => a.id === id)
        if (article) {
          this.editingItem = article
          this.activeTab = 'articles'
          this.showModal = true
        }
      } catch (err) {
        this.error = 'Fehler beim Laden des Artikels: ' + err.message
      }
    },
    editService(service) {
      this.editingItem = service
      this.activeTab = 'services'
      this.showModal = true
    },
    async loadServiceForEdit(id) {
      try {
        const response = await articleAPI.getServices()
        const service = response.data.find(s => s.id === id)
        if (service) {
          this.editingItem = service
          this.activeTab = 'services'
          // Load articles for the dropdown
          const articlesResponse = await articleAPI.getArticles()
          this.articles = articlesResponse.data
          this.showModal = true
        }
      } catch (err) {
        this.error = 'Fehler beim Laden der Leistung: ' + err.message
      }
    },
    getArticleName(articleId) {
      if (!articleId) return '-'
      const article = this.articles.find(a => a.id === articleId)
      return article ? article.name : '-'
    },
    getEquipmentName(equipmentId) {
      if (!equipmentId) return '-'
      const eq = this.equipmentList.find(e => e.id === equipmentId)
      return eq ? eq.name : '-'
    },
    onItemCreated() {
      this.showModal = false
      this.loadItems()
    },
    onItemUpdated() {
      this.showModal = false
      this.editingItem = null
      this.loadItems()
    },
    async deleteArticle(id) {
      if (!confirm('Wirklich löschen?')) return
      try {
        await articleAPI.deleteArticle(id)
        this.showModal = false
        this.editingItem = null
        this.loadItems()
      } catch (err) {
        this.error = 'Fehler beim Löschen: ' + err.message
      }
    },
    async deleteService(id) {
      if (!confirm('Wirklich löschen?')) return
      try {
        await articleAPI.deleteService(id)
        this.showModal = false
        this.editingItem = null
        this.loadItems()
      } catch (err) {
        this.error = 'Fehler beim Löschen: ' + err.message
      }
    },
    deleteCatalogItem({ id, itemType }) {
      if (itemType === 'article') {
        this.deleteArticle(id)
      } else {
        this.deleteService(id)
      }
    },
    editEquipment(eq) {
      this.equipmentForm = { ...eq }
      this.showEquipmentModal = true
    },
    resetEquipmentForm() {
      this.showEquipmentModal = false
      this.equipmentForm = { id: null, name: '', purchaseValue: null, acquisitionDate: '', expectedLifetimeMonths: null, monthlyOperatingCost: null, residualValue: null, status: 'active', notes: '' }
    },
    async saveEquipment() {
      try {
        const data = { ...this.equipmentForm }
        if (this.equipmentForm.id) {
          await equipmentAPI.update(this.equipmentForm.id, data)
        } else {
          await equipmentAPI.create(data)
        }
        this.resetEquipmentForm()
        this.loadItems()
      } catch (err) {
        this.error = 'Fehler beim Speichern: ' + err.message
      }
    },
    async deleteEquipment(id) {
      if (!confirm('Wirklich löschen?')) return
      try {
        await equipmentAPI.delete(id)
        this.resetEquipmentForm()
        this.loadItems()
      } catch (err) {
        this.error = 'Fehler beim Löschen: ' + err.message
      }
    },
    /**
     * Opens the usage dialog of one equipment and loads its lines.
     *
     * @param {object} eq the equipment
     * @param {Event} [event] the click that opened the dialog; its button gets the focus back
     */
    async showUsage(eq, event) {
      this.usageTrigger = event?.currentTarget || null
      this.usageEquipment = eq
      this.showUsageModal = true
      this.usageLoading = true
      this.usageError = null
      this.usageItems = []
      this.$nextTick(() => this.$refs.usageDialog?.focus())
      try {
        const response = await equipmentAPI.getUsage(eq.id)
        this.usageItems = response.data
      } catch (err) {
        this.usageError = 'Fehler beim Laden der Einsätze: ' + err.message
      } finally {
        this.usageLoading = false
      }
    },
    /** Closes the usage dialog and puts the focus back on the cell that opened it. */
    closeUsage() {
      this.showUsageModal = false
      this.usageTrigger?.focus?.()
      this.usageTrigger = null
    },
    /**
     * Accessible name of the amortisation cell. The button's own text is replaced by the label, so
     * the label repeats what the cell shows.
     *
     * @param {object} eq the equipment
     * @param {object|null} amortisation what `describeAmortisation` returned for it
     * @returns {string}
     */
    usageButtonLabel(eq, amortisation) {
      const base = `Einsätze für ${eq.name} anzeigen`
      if (!amortisation) return base
      const state = !amortisation.hasPurchaseValue
        ? 'ohne Kaufpreis'
        : amortisation.done
          ? 'amortisiert'
          : `${amortisation.percentText} amortisiert`
      return `${base}: ${state}, ${this.formatAmount(amortisation.revenue)}, ${this.usageCountLabel(amortisation.usageCount)}`
    },
    /**
     * @param {number} count number of usages
     * @returns {string} "1 Einsatz" / "n Einsätze"
     */
    usageCountLabel(count) {
      const n = Number(count) || 0
      return n === 1 ? '1 Einsatz' : `${n} Einsätze`
    },
    /**
     * A quantity with its unit in German notation ("1,5 Std"), without trailing zeros.
     *
     * @param {number|string} quantity
     * @param {string} [unit]
     * @returns {string}
     */
    formatQuantity(quantity, unit) {
      const text = QUANTITY_FORMAT.format(Number(quantity) || 0)
      return unit ? `${text} ${unit}` : text
    },
    /**
     * An amount in euros that is always written out; `formatCurrency` shows "-" for zero, which
     * would read as "unknown" in a sum.
     *
     * @param {number|string} amount
     * @returns {string}
     */
    formatAmount(amount) {
      return CURRENCY_FORMAT.format(Number(amount) || 0)
    },
    formatDate(dateStr) {
      if (!dateStr) return '-'
      return new Date(dateStr).toLocaleDateString('de-DE')
    },
    formatCurrency(amount) {
      return amount ? new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(amount) : '-'
    }
  }
}
</script>

<style scoped>
.articles-services { padding: 20px; }
.catalog-section-tabs { display: flex; justify-content: center; gap: 0; max-width: 500px; margin: 0 auto 24px; padding: 4px; border-radius: 12px; background: var(--color-surface-muted); }
.catalog-section-tabs .tab-btn { padding: 12px 28px; border: none; border-radius: 10px; background: transparent; color: var(--color-text-muted); cursor: pointer; font-size: 15px; font-weight: 600; transition: all 0.2s; }
.catalog-section-tabs .tab-btn:hover { color: var(--color-text-strong); background: color-mix(in srgb, var(--color-surface) 50%, transparent); }
.catalog-section-tabs .tab-btn.active { background: var(--color-surface); color: var(--color-primary); box-shadow: var(--shadow-md); }
.mobile-catalog-picker { display: none; }
.filters { display: flex; gap: 15px; margin-bottom: 20px; align-items: center; }
.search-input { flex: 1; max-width: 300px; padding: 8px 12px; border: 1px solid var(--color-border-strong); border-radius: 4px; font-size: 14px; }
.loading, .error { padding: 20px; text-align: center; }
.error { color: var(--color-danger); }
.empty-state { padding: 20px; text-align: center; color: var(--color-text-muted); }
.modal { position: fixed; top: 0; left: 0; width: 100%; height: 100%; background: var(--color-backdrop); display: flex; align-items: center; justify-content: center; z-index: 1000; }
.modal-content { background: var(--color-surface); padding: 30px; border-radius: 8px; min-width: 400px; max-width: 600px; }
.modal-content h2 { margin: 0 0 20px 0; font-size: 20px; color: var(--color-text-strong); }
.form-group { margin-bottom: 15px; }
.form-group label { display: block; margin-bottom: 4px; font-size: 13px; color: var(--color-text-muted); }
.form-group input, .form-group select, .form-group textarea { width: 100%; padding: 8px; border: 1px solid var(--color-border-strong); border-radius: 4px; font-size: 14px; }
.form-actions { display: flex; flex-wrap: wrap; min-width: 0; gap: 8px; margin-top: 20px; }
.form-actions button { flex: 1 1 auto; min-width: 0; max-width: 100%; white-space: normal; overflow-wrap: anywhere; }
.amort-cell { display: block; width: 100%; padding: 0; border: 0; background: transparent; color: inherit; font: inherit; text-align: left; cursor: pointer; }
.amort-cell:hover { background: var(--color-primary-soft); }
.amort-cell:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.amort-row { display: flex; align-items: center; gap: 8px; }
.amort-bar { flex: 1; max-width: 120px; height: 8px; background: var(--color-border); border-radius: 4px; overflow: hidden; }
.amort-fill { height: 100%; background: var(--color-warning); border-radius: 4px; transition: width 0.3s; }
.amort-fill.paid { background: var(--color-success); }
.amort-text { font-size: 13px; font-weight: 600; white-space: nowrap; }
.amort-done { color: var(--color-success-hover); }
.amort-sub { font-size: 11px; color: var(--color-text-muted); margin-top: 2px; }
.amort-hint { max-width: 80ch; margin: 0 0 var(--space-3); color: var(--color-text-muted); font-size: var(--font-size-sm); line-height: 1.5; }
/* The usage dialog is a table of six columns: wide on a desktop, the table scrolls inside it. */
.modal-content.usage-modal { display: flex; flex-direction: column; box-sizing: border-box; width: min(1000px, 95vw); min-width: 0; max-width: min(1000px, 95vw); max-height: 90dvh; }
.usage-modal:focus { outline: none; }
.usage-modal .table-scroll { flex: 1 1 auto; min-height: 0; overflow: auto; }
/* Numbers, dates and amounts stay on one line; only the service name (third column) wraps. */
.usage-modal tr > :not(:nth-child(3)) { white-space: nowrap; }
.usage-modal .form-actions { flex: 0 0 auto; justify-content: flex-end; }
.usage-modal .form-actions button { flex: 0 1 auto; }
.usage-summary { margin: 0 0 var(--space-3); color: var(--color-text-muted); font-size: var(--font-size-md); }
.usage-summary strong { color: var(--color-text-strong); }
.usage-total th, .usage-total td { font-weight: var(--font-weight-semibold); background: var(--color-surface-subtle); color: var(--color-text-strong); }
.text-muted { color: var(--color-text-subtle); }

@media (max-width: 600px) {
  .articles-services { padding: 12px; }
  .catalog-section-tabs { display: none; }
  .mobile-catalog-picker { display: flex; flex-direction: column; gap: 6px; width: 100%; min-width: 0; margin: 0 0 14px; color: var(--color-text-strong); font-size: 14px; font-weight: 600; }
  .mobile-catalog-picker select { box-sizing: border-box; width: 100%; max-width: 100%; min-width: 0; min-height: 44px; padding: 9px 12px; border: 1px solid var(--color-border-strong); border-radius: 6px; background: var(--color-surface); color: var(--color-text); font: inherit; font-size: 16px; }
  .filters { flex-wrap: wrap; gap: 10px; }
  .search-input { flex: 1 1 100%; max-width: none; }
  .filters .btn-primary { flex: 1 1 100%; min-height: 42px; }
  .modal { padding: 12px; align-items: flex-end; }
  .modal-content { width: 100%; min-width: 0; max-height: 90dvh; padding: 20px; border-radius: 12px 12px 0 0; }
  .modal-content.usage-modal { width: 100%; max-width: none; }
  .usage-modal .form-actions button { flex: 1 1 auto; }
  .form-actions button { min-height: 42px; }
}
</style>

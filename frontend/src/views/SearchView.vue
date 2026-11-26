<template>
  <div class="search-view">
    <h1>{{ $t('nav.search') }}</h1>
    <div class="search-box">
      <input
        v-model="query"
        type="text"
        :placeholder="$t('search.placeholder')"
        class="search-input"
        @input="debouncedSearch"
      />
    </div>

    <div v-if="loading" class="loading">{{ $t('search.running') }}</div>
    <div v-if="error" class="error">{{ error }}</div>

    <div v-if="hasSearched && !loading" class="results">
      <div v-if="hasNoResults" class="no-results">{{ $t('spotlight.noResults', { query: lastQuery }) }}</div>

      <div v-if="result.customers?.length" class="result-section">
        <h2>{{ $t('search.customers', { count: result.customers.length }) }}</h2>
        <div class="table-scroll" role="region" :aria-label="$t('search.resultsLabel', { kind: $t('nav.customers') })" tabindex="0">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ $t('common.columns.name') }}</th>
              <th>{{ $t('common.columns.address') }}</th>
              <th>{{ $t('common.columns.phone') }}</th>
              <th>{{ $t('common.columns.email') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="c in result.customers"
              :key="'c-' + c.id"
              class="clickable-row"
              tabindex="0"
              :aria-label="$t('search.openCustomer', { name: `${c.firstName} ${c.lastName}` })"
              @click="goToCustomer(c.id)"
              @keydown.enter.prevent="goToCustomer(c.id)"
              @keydown.space.prevent="goToCustomer(c.id)"
            >
              <td>{{ c.firstName }} {{ c.lastName }}</td>
              <td>{{ c.street }} {{ c.houseNumber }}, {{ c.postalCode }} {{ c.city }}</td>
              <td>{{ c.phone }}</td>
              <td>{{ c.email }}</td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>

      <div v-if="result.articles?.length" class="result-section">
        <h2>{{ $t('search.articles', { count: result.articles.length }) }}</h2>
        <div class="table-scroll" role="region" :aria-label="$t('search.resultsLabel', { kind: $t('catalog.articles') })" tabindex="0">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ $t('common.columns.name') }}</th>
              <th>{{ $t('catalog.columns.salesPrice') }}</th>
              <th>{{ $t('catalog.columns.purchasePrice') }}</th>
              <th>{{ $t('catalog.columns.unit') }}</th>
              <th>{{ $t('catalog.columns.description') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="a in result.articles"
              :key="'a-' + a.id"
              class="clickable-row"
              tabindex="0"
              :aria-label="$t('catalog.editArticle', { name: a.name })"
              @click="goToCatalogItem(a.id, 'article')"
              @keydown.enter.prevent="goToCatalogItem(a.id, 'article')"
              @keydown.space.prevent="goToCatalogItem(a.id, 'article')"
            >
              <td>{{ a.name }}</td>
              <td>{{ formatPrice(a.salesValue) }} €</td>
              <td>{{ formatPrice(a.purchaseValue) }} €</td>
              <td>{{ a.unit }}</td>
              <td>{{ a.description }}</td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>

      <div v-if="result.services?.length" class="result-section">
        <h2>{{ $t('search.services', { count: result.services.length }) }}</h2>
        <div class="table-scroll" role="region" :aria-label="$t('search.resultsLabel', { kind: $t('catalog.services') })" tabindex="0">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ $t('common.columns.name') }}</th>
              <th>{{ $t('catalog.columns.unitPrice') }}</th>
              <th>{{ $t('catalog.columns.unit') }}</th>
              <th>{{ $t('catalog.columns.description') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="s in result.services"
              :key="'s-' + s.id"
              class="clickable-row"
              tabindex="0"
              :aria-label="$t('catalog.editService', { name: s.name })"
              @click="goToCatalogItem(s.id, 'service')"
              @keydown.enter.prevent="goToCatalogItem(s.id, 'service')"
              @keydown.space.prevent="goToCatalogItem(s.id, 'service')"
            >
              <td>{{ s.name }}</td>
              <td>{{ formatPrice(s.price) }} €</td>
              <td>{{ s.unit }}</td>
              <td>{{ s.description }}</td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>

      <div v-if="result.invoices?.length" class="result-section">
        <h2>{{ $t('search.invoices', { count: result.invoices.length }) }}</h2>
        <div class="table-scroll" role="region" :aria-label="$t('search.resultsLabel', { kind: $t('search.kinds.invoices') })" tabindex="0">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ $t('dashboard.columns.number') }}</th>
              <th>{{ $t('dashboard.columns.customer') }}</th>
              <th>{{ $t('documents.columns.date') }}</th>
              <th>{{ $t('dashboard.columns.due') }}</th>
              <th>{{ $t('dashboard.columns.amount') }}</th>
              <th>{{ $t('documents.columns.status') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="inv in result.invoices"
              :key="'inv-' + inv.id"
              class="clickable-row"
              tabindex="0"
              :aria-label="$t('dashboard.openInvoice', { number: inv.documentNumber || $t('dashboard.invoice') })"
              @click="goToInvoice(inv.id, 'invoice')"
              @keydown.enter.prevent="goToInvoice(inv.id, 'invoice')"
              @keydown.space.prevent="goToInvoice(inv.id, 'invoice')"
            >
              <td>{{ inv.documentNumber || 'RE-' + inv.id }}</td>
              <td>{{ inv.customerName }}</td>
              <td>{{ formatDate(inv.invoiceDate) }}</td>
              <td>{{ formatDate(inv.dueDate) }}</td>
              <td>{{ formatPrice(inv.totalPrice) }} €</td>
              <td><span :class="'status-' + inv.documentStatusId">{{ inv.documentStatusName }}</span></td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>

      <div v-if="result.offers?.length" class="result-section">
        <h2>{{ $t('search.offers', { count: result.offers.length }) }}</h2>
        <div class="table-scroll" role="region" :aria-label="$t('search.resultsLabel', { kind: $t('search.kinds.offers') })" tabindex="0">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ $t('dashboard.columns.number') }}</th>
              <th>{{ $t('dashboard.columns.customer') }}</th>
              <th>{{ $t('documents.columns.date') }}</th>
              <th>{{ $t('documents.columns.validUntil') }}</th>
              <th>{{ $t('dashboard.columns.amount') }}</th>
              <th>{{ $t('documents.columns.status') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="off in result.offers"
              :key="'off-' + off.id"
              class="clickable-row"
              tabindex="0"
              :aria-label="$t('dashboard.openInvoice', { number: off.documentNumber || $t('search.offer') })"
              @click="goToInvoice(off.id, 'offer')"
              @keydown.enter.prevent="goToInvoice(off.id, 'offer')"
              @keydown.space.prevent="goToInvoice(off.id, 'offer')"
            >
              <td>{{ off.documentNumber || 'AN-' + off.id }}</td>
              <td>{{ off.customerName }}</td>
              <td>{{ formatDate(off.invoiceDate) }}</td>
              <td>{{ formatDate(off.offerValidUntil) }}</td>
              <td>{{ formatPrice(off.totalPrice) }} €</td>
              <td><span :class="'status-' + off.documentStatusId">{{ off.documentStatusName }}</span></td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>

      <div v-if="result.calculations?.length" class="result-section">
        <h2>{{ $t('search.calculations', { count: result.calculations.length }) }}</h2>
        <div class="table-scroll" role="region" :aria-label="$t('search.resultsLabel', { kind: $t('search.kinds.calculations') })" tabindex="0">
        <table class="data-table">
          <thead>
            <tr>
              <th>{{ $t('dashboard.columns.number') }}</th>
              <th>{{ $t('dashboard.columns.customer') }}</th>
              <th>{{ $t('documents.columns.date') }}</th>
              <th>{{ $t('dashboard.columns.amount') }}</th>
              <th>{{ $t('documents.columns.status') }}</th>
            </tr>
          </thead>
          <tbody>
            <tr
              v-for="calc in result.calculations"
              :key="'calc-' + calc.id"
              class="clickable-row"
              tabindex="0"
              :aria-label="$t('dashboard.openInvoice', { number: calc.documentNumber || $t('search.calculation') })"
              @click="goToInvoice(calc.id, 'costCalculation')"
              @keydown.enter.prevent="goToInvoice(calc.id, 'costCalculation')"
              @keydown.space.prevent="goToInvoice(calc.id, 'costCalculation')"
            >
              <td>{{ calc.documentNumber || 'KV-' + calc.id }}</td>
              <td>{{ calc.customerName }}</td>
              <td>{{ formatDate(calc.invoiceDate) }}</td>
              <td>{{ formatPrice(calc.totalPrice) }} €</td>
              <td><span :class="'status-' + calc.documentStatusId">{{ calc.documentStatusName }}</span></td>
            </tr>
          </tbody>
        </table>
        </div>
      </div>
    </div>

    <div v-if="!hasSearched && !loading" class="initial-state">
      <p>{{ $t('search.hint') }}</p>
    </div>
  </div>
</template>

<script>
import searchService from '../services/searchService.js'

export default {
  name: 'SearchView',
  data() {
    return {
      // the debounce timer of the search (not shown)
      debounceTimer: null,
      query: '',
      result: {},
      loading: false,
      error: null,
      hasSearched: false,
      lastQuery: ''
    }
  },
  computed: {
    hasNoResults() {
      return !this.result.customers?.length &&
             !this.result.articles?.length &&
             !this.result.services?.length &&
             !this.result.invoices?.length &&
             !this.result.offers?.length &&
             !this.result.calculations?.length
    }
  },
  beforeUnmount() {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer)
    }
  },
  methods: {
    debouncedSearch() {
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer)
      }
      this.debounceTimer = setTimeout(() => {
        this.performSearch()
      }, 300)
    },
    async performSearch() {
      const q = this.query.trim()
      if (!q) {
        this.result = {}
        this.hasSearched = false
        return
      }

      this.loading = true
      this.error = null
      this.lastQuery = q

      try {
        const response = await searchService.search(q)
        this.result = response.data
        this.hasSearched = true
      } catch (err) {
        this.error = this.$t('search.failed', { reason: err.message })
      } finally {
        this.loading = false
      }
    },
    formatPrice(price) {
      if (price == null) return '-'
      return new Intl.NumberFormat('de-DE', { minimumFractionDigits: 2 }).format(price)
    },
    formatDate(dateStr) {
      if (!dateStr) return '-'
      try {
        const date = new Date(dateStr)
        return date.toLocaleDateString('de-DE')
      } catch {
        return dateStr
      }
    },
    goToCustomer(id) {
      this.$router.push(`/customers/${id}`)
    },
    goToInvoice(id, type) {
      this.$router.push({ path: '/invoices', query: { view: id, type } })
    },
    goToCatalogItem(id, type) {
      this.$router.push({
        path: '/articles-services',
        query: type === 'article' ? { article: id } : { service: id }
      })
    }
  }
}
</script>

<style scoped>
.search-view { padding: 20px; max-width: 1200px; margin: 0 auto; }
.search-box { margin-bottom: 30px; }
.search-input {
  width: 100%;
  padding: 12px 20px;
  font-size: 16px;
  border: 2px solid var(--color-border-strong);
  border-radius: 8px;
  transition: border-color 0.3s;
}
.search-input:focus { outline: none; border-color: var(--color-primary); }
.loading, .error, .no-results, .initial-state {
  text-align: center;
  padding: 40px;
  color: var(--color-text-muted);
}
.error { color: var(--color-danger); }
.result-section { margin-bottom: 30px; }
.data-table { margin-bottom: 20px; }
.data-table tr:hover { background: var(--color-surface-subtle); }
.result-section h2 {
  color: var(--color-text-strong);
  margin-bottom: 15px;
  padding-bottom: 10px;
  border-bottom: 2px solid var(--color-primary);
}
.status-1 { color: var(--color-text-subtle); }
.status-2 { color: var(--color-primary); }
.status-3 { color: var(--color-success); }
.status-4 { color: var(--color-danger); }
.status-5 { color: var(--color-archive); }
.status-6 { color: var(--color-success); }
.status-7 { color: var(--color-warning); }
.status-8 { color: var(--color-text-subtle); }

@media (max-width: 600px) {
  .search-view { padding: 12px; }
  .loading, .error, .no-results, .initial-state { padding: 24px 8px; }
  .result-section { margin-bottom: 24px; }
}
</style>

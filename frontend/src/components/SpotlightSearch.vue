<template>
  <Teleport to="body">
    <div v-if="isOpen" class="spotlight-overlay" @click.self="close">
      <div class="spotlight-container">
        <div class="spotlight-input-wrapper">
          <svg class="search-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="11" cy="11" r="8"/>
            <path d="M21 21l-4.35-4.35"/>
          </svg>
          <input
            ref="searchInput"
            v-model="query"
            type="text"
            placeholder="Suche..."
            class="spotlight-input"
            @input="debouncedSearch"
            @keydown.escape="close"
            @keydown.down.prevent="navigateDown"
            @keydown.up.prevent="navigateUp"
            @keydown.enter="selectCurrent"
          />
          <button v-if="query" class="clear-btn" @click="clearSearch">
            <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
              <path d="M18 6L6 18M6 6l12 12"/>
            </svg>
          </button>
        </div>

        <div v-if="loading" class="spotlight-loading">
          <div class="spinner"></div>
          <span>Suche läuft...</span>
        </div>

        <div v-else-if="error" class="spotlight-error">{{ error }}</div>

        <div v-else-if="hasResults" class="spotlight-results">
          <div v-if="result.customers?.length" class="result-group">
            <div class="result-group-header">
              <span class="group-icon">👤</span>
              Kunden
            </div>
            <div
              v-for="(c, idx) in result.customers.slice(0, 5)"
              :key="'c-' + c.id"
              :class="['result-item', { active: currentIndex === getItemIndex('customers', idx) }]"
              @click="goToCustomer(c.id)"
              @mouseenter="setCurrentIndex(getItemIndex('customers', idx))"
            >
              <div class="result-item-main">
                <span class="result-name">{{ c.firstName }} {{ c.lastName }}</span>
                <span class="result-detail">{{ c.email }}</span>
              </div>
              <span class="result-arrow">→</span>
            </div>
          </div>

          <div v-if="result.articles?.length" class="result-group">
            <div class="result-group-header">
              <span class="group-icon">📦</span>
              Artikel
            </div>
            <div
              v-for="(a, idx) in result.articles.slice(0, 5)"
              :key="'a-' + a.id"
              :class="['result-item', { active: currentIndex === getItemIndex('articles', idx) }]"
              @click="goToArticle(a.id)"
              @mouseenter="setCurrentIndex(getItemIndex('articles', idx))"
            >
              <div class="result-item-main">
                <span class="result-name">{{ a.name }}</span>
                <span class="result-detail">{{ formatPrice(a.salesValue) }} €</span>
              </div>
              <span class="result-arrow">→</span>
            </div>
          </div>

          <div v-if="result.services?.length" class="result-group">
            <div class="result-group-header">
              <span class="group-icon">🔧</span>
              Dienstleistungen
            </div>
            <div
              v-for="(s, idx) in result.services.slice(0, 5)"
              :key="'s-' + s.id"
              :class="['result-item', { active: currentIndex === getItemIndex('services', idx) }]"
              @click="goToService(s.id)"
              @mouseenter="setCurrentIndex(getItemIndex('services', idx))"
            >
              <div class="result-item-main">
                <span class="result-name">{{ s.name }}</span>
                <span class="result-detail">{{ formatPrice(s.price) }} € / {{ s.unit }}</span>
              </div>
              <span class="result-arrow">→</span>
            </div>
          </div>

          <div v-if="result.invoices?.length" class="result-group">
            <div class="result-group-header">
              <span class="group-icon">📄</span>
              Rechnungen
            </div>
            <div
              v-for="(inv, idx) in result.invoices.slice(0, 5)"
              :key="'inv-' + inv.id"
              :class="['result-item', { active: currentIndex === getItemIndex('invoices', idx) }]"
              @click="goToInvoice(inv.id)"
              @mouseenter="setCurrentIndex(getItemIndex('invoices', idx))"
            >
              <div class="result-item-main">
                <span class="result-name">{{ inv.documentNumber || 'RE-' + inv.id }}</span>
                <span class="result-detail">{{ inv.customerName }} - {{ formatPrice(inv.totalPrice) }} €</span>
              </div>
              <span class="result-arrow">→</span>
            </div>
          </div>

          <div v-if="result.offers?.length" class="result-group">
            <div class="result-group-header">
              <span class="group-icon">💼</span>
              Angebote
            </div>
            <div
              v-for="(off, idx) in result.offers.slice(0, 5)"
              :key="'off-' + off.id"
              :class="['result-item', { active: currentIndex === getItemIndex('offers', idx) }]"
              @click="goToInvoice(off.id)"
              @mouseenter="setCurrentIndex(getItemIndex('offers', idx))"
            >
              <div class="result-item-main">
                <span class="result-name">{{ off.documentNumber || 'AN-' + off.id }}</span>
                <span class="result-detail">{{ off.customerName }} - {{ formatPrice(off.totalPrice) }} €</span>
              </div>
              <span class="result-arrow">→</span>
            </div>
          </div>

          <div v-if="result.calculations?.length" class="result-group">
            <div class="result-group-header">
              <span class="group-icon">📊</span>
              Kostenvoranschläge
            </div>
            <div
              v-for="(calc, idx) in result.calculations.slice(0, 5)"
              :key="'calc-' + calc.id"
              :class="['result-item', { active: currentIndex === getItemIndex('calculations', idx) }]"
              @click="goToInvoice(calc.id)"
              @mouseenter="setCurrentIndex(getItemIndex('calculations', idx))"
            >
              <div class="result-item-main">
                <span class="result-name">{{ calc.documentNumber || 'KV-' + calc.id }}</span>
                <span class="result-detail">{{ calc.customerName }} - {{ formatPrice(calc.totalPrice) }} €</span>
              </div>
              <span class="result-arrow">→</span>
            </div>
          </div>
        </div>

        <div v-else-if="query.length >= 2 && hasSearched" class="spotlight-empty">
          Keine Ergebnisse für "{{ query }}"
        </div>

        <div v-else-if="query.length < 2" class="spotlight-hint">
          Geben Sie mindestens 2 Zeichen ein
        </div>

        <div v-else class="spotlight-hint">
          Drücken Sie <kbd>↑</kbd> <kbd>↓</kbd> zum Navigieren, <kbd>Enter</kbd> zum Auswählen, <kbd>Esc</kbd> zum Schließen
        </div>
      </div>
    </div>
  </Teleport>
</template>

<script>
import searchService from '../services/searchService.js'

export default {
  name: 'SpotlightSearch',
  props: {
    isOpen: {
      type: Boolean,
      default: false
    }
  },
  emits: ['close'],
  data() {
    return {
      query: '',
      result: {},
      loading: false,
      error: null,
      hasSearched: false,
      currentIndex: -1,
      debounceTimer: null
    }
  },
  computed: {
    totalItems() {
      let count = 0
      const groups = ['customers', 'articles', 'services', 'invoices', 'offers', 'calculations']
      groups.forEach(g => {
        if (this.result[g]) {
          count += Math.min(this.result[g].length, 5)
        }
      })
      return count
    },
    hasResults() {
      return this.result.customers?.length ||
             this.result.articles?.length ||
             this.result.services?.length ||
             this.result.invoices?.length ||
             this.result.offers?.length ||
             this.result.calculations?.length
    }
  },
  watch: {
    isOpen(val) {
      if (val) {
        this.$nextTick(() => {
          this.$refs.searchInput?.focus()
        })
      } else {
        this.clearSearch()
      }
    }
  },
  methods: {
    getItemIndex(group, idx) {
      let offset = 0
      const groups = ['customers', 'articles', 'services', 'invoices', 'offers', 'calculations']
      for (const g of groups) {
        if (g === group) break
        if (this.result[g]) {
          offset += Math.min(this.result[g].length, 5)
        }
      }
      return offset + idx
    },
    setCurrentIndex(idx) {
      this.currentIndex = idx
    },
    navigateDown() {
      if (this.currentIndex < this.totalItems - 1) {
        this.currentIndex++
      }
    },
    navigateUp() {
      if (this.currentIndex > 0) {
        this.currentIndex--
      }
    },
    selectCurrent() {
      if (this.currentIndex < 0) return
      
      let offset = 0
      const groups = ['customers', 'articles', 'services', 'invoices', 'offers', 'calculations']
      
      for (const g of groups) {
        if (!this.result[g]) continue
        const groupLength = Math.min(this.result[g].length, 5)
        if (this.currentIndex < offset + groupLength) {
          const itemIdx = this.currentIndex - offset
          const item = this.result[g][itemIdx]
          
          if (g === 'customers') this.goToCustomer(item.id)
          else if (g === 'articles') this.goToArticle(item.id)
          else if (g === 'services') this.goToService(item.id)
          else this.goToInvoice(item.id)
          return
        }
        offset += groupLength
      }
    },
    debouncedSearch() {
      if (this.debounceTimer) {
        clearTimeout(this.debounceTimer)
      }
      this.debounceTimer = setTimeout(() => {
        this.performSearch()
      }, 150)
    },
    async performSearch() {
      const q = this.query.trim()
      if (q.length < 2) {
        this.result = {}
        this.hasSearched = false
        this.currentIndex = -1
        return
      }

      this.loading = true
      this.error = null
      this.currentIndex = -1

      try {
        const response = await searchService.search(q)
        this.result = response.data
        this.hasSearched = true
      } catch (err) {
        this.error = 'Suche fehlgeschlagen: ' + err.message
      } finally {
        this.loading = false
      }
    },
    clearSearch() {
      this.query = ''
      this.result = {}
      this.hasSearched = false
      this.currentIndex = -1
    },
    close() {
      this.$emit('close')
    },
    formatPrice(price) {
      if (price == null) return '-'
      return new Intl.NumberFormat('de-DE', { minimumFractionDigits: 2 }).format(price)
    },
    goToCustomer(id) {
      this.close()
      this.$router.push(`/customers/${id}`)
    },
    goToArticle(id) {
      this.close()
      this.$router.push(`/articles-services?article=${id}`)
    },
    goToService(id) {
      this.close()
      this.$router.push(`/articles-services?service=${id}`)
    },
    goToInvoice(id) {
      this.close()
      this.$router.push(`/invoices?edit=${id}`)
    }
  },
  beforeUnmount() {
    if (this.debounceTimer) {
      clearTimeout(this.debounceTimer)
    }
  }
}
</script>

<style scoped>
.spotlight-overlay {
  position: fixed;
  top: 0;
  left: 0;
  right: 0;
  bottom: 0;
  background: var(--color-backdrop);
  display: flex;
  justify-content: center;
  padding-top: 15vh;
  z-index: 9999;
  animation: fadeIn 0.15s ease-out;
}

@keyframes fadeIn {
  from { opacity: 0; }
  to { opacity: 1; }
}

.spotlight-container {
  width: 100%;
  max-width: 680px;
  background: var(--color-surface);
  border-radius: 12px;
  box-shadow: var(--shadow-xl);
  overflow: hidden;
  max-height: 60vh;
  display: flex;
  flex-direction: column;
  animation: slideIn 0.2s ease-out;
}

@keyframes slideIn {
  from { 
    opacity: 0;
    transform: translateY(-20px) scale(0.95);
  }
  to { 
    opacity: 1;
    transform: translateY(0) scale(1);
  }
}

.spotlight-input-wrapper {
  display: flex;
  align-items: center;
  padding: 16px 20px;
  border-bottom: 1px solid var(--color-border);
  gap: 12px;
}

.search-icon {
  width: 20px;
  height: 20px;
  color: var(--color-text-subtle);
  flex-shrink: 0;
}

.spotlight-input {
  flex: 1;
  border: none;
  outline: none;
  font-size: 18px;
  color: var(--color-text-strong);
  background: transparent;
}

.spotlight-input::placeholder {
  color: var(--color-text-subtle);
}

.clear-btn {
  width: 24px;
  height: 24px;
  padding: 4px;
  border: none;
  background: var(--color-surface-muted);
  border-radius: 50%;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: background 0.15s;
}

.clear-btn:hover {
  background: var(--color-border);
}

.clear-btn svg {
  width: 14px;
  height: 14px;
  color: var(--color-text-muted);
}

.spotlight-loading,
.spotlight-error,
.spotlight-empty,
.spotlight-hint {
  padding: 40px 20px;
  text-align: center;
  color: var(--color-text-muted);
}

.spotlight-error {
  color: var(--color-danger);
}

.spotlight-loading {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 12px;
}

.spinner {
  width: 20px;
  height: 20px;
  border: 2px solid var(--color-border);
  border-top-color: var(--color-primary);
  border-radius: 50%;
  animation: spin 0.8s linear infinite;
}

@keyframes spin {
  to { transform: rotate(360deg); }
}

.spotlight-results {
  overflow-y: auto;
  flex: 1;
}

.result-group {
  padding: 8px 0;
}

.result-group-header {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 8px 20px;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-muted);
  text-transform: uppercase;
  letter-spacing: 0.05em;
}

.group-icon {
  font-size: 14px;
}

.result-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 10px 20px;
  cursor: pointer;
  transition: background 0.1s;
}

.result-item:hover,
.result-item.active {
  background: var(--color-surface-muted);
}

.result-item-main {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.result-name {
  font-size: 14px;
  color: var(--color-text-strong);
}

.result-detail {
  font-size: 12px;
  color: var(--color-text-muted);
}

.result-arrow {
  color: var(--color-text-subtle);
  font-size: 16px;
  opacity: 0;
  transition: opacity 0.15s;
}

.result-item:hover .result-arrow,
.result-item.active .result-arrow {
  opacity: 1;
}

kbd {
  display: inline-block;
  padding: 2px 6px;
  font-size: 11px;
  font-family: monospace;
  background: var(--color-surface-muted);
  border: 1px solid var(--color-border);
  border-radius: 4px;
  box-shadow: 0 1px 0 var(--color-border);
}
</style>

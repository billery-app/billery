<template>
  <div class="customers">
    <h1 class="nav-destination-page-title">{{ $t('customers.title') }}</h1>
    <div class="filters">
      <input v-model="searchQuery" type="text" :placeholder="$t('common.search')" class="search-input" />
      <button class="btn btn-primary" data-tour="customer-new" @click="openCreateModal">{{ $t('customers.new') }}</button>
    </div>
    <div v-if="loading" class="loading">{{ $t('customers.loading') }}</div>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="customers.length" class="table-scroll" role="region" :aria-label="$t('customers.tableLabel')" tabindex="0">
    <table class="data-table">
      <thead>
        <tr>
          <th>{{ $t('common.columns.id') }}</th>
          <th>{{ $t('common.columns.name') }}</th>
          <th>{{ $t('common.columns.address') }}</th>
          <th>{{ $t('common.columns.phone') }}</th>
          <th>{{ $t('common.columns.email') }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="customer in customers"
          :key="customer.id"
          class="clickable-row"
          tabindex="0"
          :aria-label="$t('customers.edit', { name: `${customer.firstName} ${customer.lastName}` })"
          @click="editCustomer(customer)"
          @keydown.enter.prevent="editCustomer(customer)"
          @keydown.space.prevent="editCustomer(customer)"
        >
          <td>{{ customer.id }}</td>
          <td>{{ customer.firstName }} {{ customer.lastName }}</td>
          <td>{{ customer.street }} {{ customer.houseNumber }}, {{ customer.postalCode }} {{ customer.city }}</td>
          <td>{{ customer.phone }}</td>
          <td>{{ customer.email }}</td>
        </tr>
      </tbody>
    </table>
    </div>
    <nav v-if="total > pageSize" class="pager" :aria-label="$t('common.pages')" data-testid="pager">
      <button class="btn btn-small btn-secondary" :disabled="page === 0 || loading" @click="goToPage(page - 1)">{{ $t('common.previous') }}</button>
      <span class="pager-info" data-testid="pager-info">{{ $t('customers.pager', { page: page + 1, pages: pageCount, total }) }}</span>
      <button class="btn btn-small btn-secondary" :disabled="page + 1 >= pageCount || loading" @click="goToPage(page + 1)">{{ $t('common.next') }}</button>
    </nav>
    <CustomerCreateModal
      v-model:visible="showModal"
      :customer="editingCustomer"
      :show-delete-action="!!editingCustomer"
      :show-details-action="!!editingCustomer"
      @created="onCustomerCreated"
      @updated="onCustomerUpdated"
      @delete="deleteCustomer"
      @details="viewCustomer"
    />
  </div>
</template>

<script>
import { numberParam } from '../utils/route.js'
import customerAPI from '../services/customerService.js'
import CustomerCreateModal from '../components/CustomerCreateModal.vue'

export default {
  name: 'CustomersView',
  components: { CustomerCreateModal },
  data() {
    return {
      // not shown: the debounce timer of the search and the number of the latest list request
      // (an older answer is dropped)
      searchTimer: null,
      listRequest: 0,
      // One page of customers, searched on the server (GET /api/customers/page).
      customers: [],
      page: 0,
      pageSize: 50,
      total: 0,
      searchQuery: '',
      loading: false,
      error: null,
      showModal: false,
      editingCustomer: null
    }
  },
  computed: {
    pageCount() {
      return Math.max(1, Math.ceil(this.total / this.pageSize))
    }
  },
  watch: {
    searchQuery() {
      clearTimeout(this.searchTimer)
      this.searchTimer = setTimeout(() => {
        this.page = 0
        this.loadCustomers()
      }, 300)
    }
  },
  mounted() {
    this.loadCustomers()
    if (this.$route.query.create === 'true') {
      this.openCreateModal()
    }
    const editId = numberParam(this.$route.query.edit)
    if (editId) {
      this.loadCustomerForEdit(editId)
    }
  },
  beforeUnmount() {
    clearTimeout(this.searchTimer)
  },
  methods: {
    /** Loads the current page; a response of a superseded request is dropped. */
    async loadCustomers() {
      const request = this.listRequest + 1
      this.listRequest = request
      this.loading = true
      try {
        const response = await customerAPI.getCustomerPage({
          page: this.page,
          size: this.pageSize,
          q: this.searchQuery.trim()
        })
        if (request !== this.listRequest) return
        const { items, total } = response.data
        if (!items.length && this.page > 0 && total > 0) {
          // The page became empty (e.g. its last customer was deleted): show the last page.
          this.page = Math.ceil(total / this.pageSize) - 1
          return this.loadCustomers()
        }
        this.customers = items
        this.total = total
      } catch (err) {
        if (request === this.listRequest) this.error = this.$t('common.loadFailed', { reason: err.message })
      } finally {
        if (request === this.listRequest) this.loading = false
      }
    },
    goToPage(page) {
      this.page = page
      this.loadCustomers()
    },
    openCreateModal() {
      this.editingCustomer = null
      this.showModal = true
    },
    viewCustomer(id) {
      this.showModal = false
      this.$router.push(`/customers/${id}`)
    },
    async loadCustomerForEdit(id) {
      try {
        const response = await customerAPI.getCustomer(id)
        this.editingCustomer = response.data
        this.showModal = true
      } catch (err) {
        this.error = this.$t('customers.loadOneFailed', { reason: err.message })
      }
    },
    editCustomer(customer) {
      this.editingCustomer = customer
      this.showModal = true
    },
    onCustomerCreated() {
      this.showModal = false
      this.loadCustomers()
    },
    onCustomerUpdated() {
      this.showModal = false
      this.editingCustomer = null
      this.loadCustomers()
    },
    async deleteCustomer(id) {
      if (!confirm(this.$t('common.confirmDelete'))) return
      try {
        await customerAPI.deleteCustomer(id)
        this.showModal = false
        this.editingCustomer = null
        this.loadCustomers()
      } catch (err) {
        this.error = this.$t('common.deleteFailed', { reason: err.message })
      }
    }
  }
}
</script>

<style scoped>
.customers { padding: 20px; }
.filters { display: flex; gap: 15px; margin-bottom: 20px; align-items: center; }
.search-input { flex: 1; max-width: 300px; padding: 8px 12px; border: 1px solid var(--color-border-strong); border-radius: 4px; font-size: 14px; }
.loading, .error { padding: 20px; text-align: center; }
.pager { display: flex; align-items: center; justify-content: center; gap: 12px; margin: 16px 0; flex-wrap: wrap; }
.pager-info { color: var(--color-text-muted); font-size: 14px; }
.error { color: var(--color-danger); }

@media (max-width: 600px) {
  .customers { padding: 12px; }
  .filters { flex-wrap: wrap; gap: 10px; }
  .search-input { flex: 1 1 100%; max-width: none; }
  .filters .btn-primary { flex: 1 1 auto; min-height: 42px; }
}
</style>

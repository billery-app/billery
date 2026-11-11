<template>
  <div class="owners">
    <h1>Inhaber-Verwaltung</h1>
    <div class="filters">
      <input v-model="searchQuery" type="text" placeholder="Suchen..." class="search-input" />
      <button @click="openCreateModal" class="btn btn-primary">Neuer Inhaber</button>
    </div>
    <div v-if="loading" class="loading">Lade Inhaber...</div>
    <div v-if="error" class="error">{{ error }}</div>
    <div v-if="filteredOwners.length" class="table-scroll" role="region" aria-label="Inhabertabelle" tabindex="0">
    <table class="data-table">
      <thead>
        <tr>
          <th>ID</th>
          <th>Name</th>
          <th>Firma</th>
          <th>Adresse</th>
          <th>Telefon</th>
          <th>Email</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="owner in filteredOwners"
          :key="owner.id"
          class="clickable-row"
          tabindex="0"
          :aria-label="`Inhaber ${owner.firstName} ${owner.lastName} bearbeiten`"
          @click="editOwner(owner)"
          @keydown.enter.prevent="editOwner(owner)"
          @keydown.space.prevent="editOwner(owner)"
        >
          <td>{{ owner.id }}</td>
          <td>{{ owner.firstName }} {{ owner.lastName }}</td>
          <td>{{ owner.companyName }} {{ owner.companyType }}</td>
          <td>{{ owner.street }} {{ owner.houseNumber }}, {{ owner.postalCode }} {{ owner.city }}</td>
          <td>{{ owner.phone }}</td>
          <td>{{ owner.email }}</td>
        </tr>
      </tbody>
    </table>
    </div>
    <p v-else-if="!loading" class="no-data">Keine Inhaber vorhanden.</p>
    <OwnerModal
      v-model:visible="showModal"
      :owner="editingOwner"
      :show-delete-action="!!editingOwner"
      @created="onOwnerCreated"
      @updated="onOwnerUpdated"
      @delete="deleteOwner"
    />
  </div>
</template>

<script>
import ownerAPI from '../services/ownerService.js'
import OwnerModal from '../components/OwnerModal.vue'

export default {
  name: 'OwnersView',
  components: { OwnerModal },
  data() {
    return {
      owners: [],
      searchQuery: '',
      loading: false,
      error: null,
      showModal: false,
      editingOwner: null
    }
  },
  mounted() {
    this.loadOwners()
  },
  computed: {
    filteredOwners() {
      if (!this.searchQuery) return this.owners
      const query = this.searchQuery.toLowerCase()
      return this.owners.filter(o =>
        (o.firstName && o.firstName.toLowerCase().includes(query)) ||
        (o.lastName && o.lastName.toLowerCase().includes(query)) ||
        (o.companyName && o.companyName.toLowerCase().includes(query)) ||
        (o.street && o.street.toLowerCase().includes(query)) ||
        (o.city && o.city.toLowerCase().includes(query)) ||
        (o.phone && o.phone.toLowerCase().includes(query)) ||
        (o.email && o.email.toLowerCase().includes(query))
      )
    }
  },
  methods: {
    async loadOwners() {
      this.loading = true
      try {
        const response = await ownerAPI.getOwners()
        this.owners = response.data
      } catch (err) {
        this.error = 'Fehler beim Laden: ' + err.message
      } finally {
        this.loading = false
      }
    },
    openCreateModal() {
      this.editingOwner = null
      this.showModal = true
    },
    editOwner(owner) {
      this.editingOwner = owner
      this.showModal = true
    },
    onOwnerCreated() {
      this.showModal = false
      this.loadOwners()
    },
    onOwnerUpdated() {
      this.showModal = false
      this.editingOwner = null
      this.loadOwners()
    },
    async deleteOwner(id) {
      if (!confirm('Wirklich löschen?')) return
      try {
        await ownerAPI.deleteOwner(id)
        this.showModal = false
        this.editingOwner = null
        this.loadOwners()
      } catch (err) {
        this.error = 'Fehler beim Löschen: ' + err.message
      }
    }
  }
}
</script>

<style scoped>
.owners { padding: 20px; }
.filters { display: flex; gap: 15px; margin-bottom: 20px; align-items: center; }
.search-input { flex: 1; max-width: 300px; padding: 8px 12px; border: 1px solid var(--color-border-strong); border-radius: 4px; font-size: 14px; }
.loading, .error, .no-data { padding: 20px; text-align: center; }
.error { color: var(--color-danger); }

@media (max-width: 600px) {
  .owners { padding: 12px; }
  .filters { flex-wrap: wrap; gap: 10px; }
  .search-input { flex: 1 1 100%; max-width: none; }
  .filters .btn-primary { flex: 1 1 auto; min-height: 42px; }
}
</style>

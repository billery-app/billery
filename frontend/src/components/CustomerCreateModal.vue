<template>
  <div v-if="visible" class="modal-backdrop" @click.self="cancel">
    <div class="modal-panel">
      <h2>{{ isEditMode ? 'Kunde bearbeiten' : 'Neuer Kunde' }}</h2>
      <form @submit.prevent="submit">
        <div class="form-group">
          <label>Vorname:</label>
          <input v-model="form.firstName" required />
        </div>
        <div class="form-group">
          <label>Nachname:</label>
          <input v-model="form.lastName" required />
        </div>
        <div class="form-group">
          <label>Straße:</label>
          <input v-model="form.street" />
        </div>
        <div class="form-group">
          <label>Hausnummer:</label>
          <input v-model="form.houseNumber" type="number" />
        </div>
        <div class="form-group">
          <label>PLZ:</label>
          <input v-model="form.postalCode" />
        </div>
        <div class="form-group">
          <label>Ort:</label>
          <input v-model="form.city" />
        </div>
        <div class="form-group">
          <label>Telefon:</label>
          <input v-model="form.phone" />
        </div>
        <div class="form-group">
          <label>Email:</label>
          <input v-model="form.email" type="email" />
        </div>
        <div class="form-group">
          <label>USt-IdNr. / Steuernummer:</label>
          <input v-model="form.taxId" placeholder="z. B. DE123456789" />
        </div>
        <div class="form-group">
          <label>Leitweg-ID / Käuferreferenz:</label>
          <input
            v-model="form.buyerReference"
            placeholder="nur für E-Rechnungen, z. B. an Behörden"
            data-testid="buyer-reference"
          />
        </div>
        <div class="form-group">
          <label>Notiz:</label>
          <textarea v-model="form.note"></textarea>
        </div>
        <div class="form-actions">
          <button type="submit" class="btn btn-primary">Speichern</button>
          <button v-if="isEditMode && showDetailsAction" type="button" class="btn btn-secondary" @click="emit('details', customer.id)">Kundendetails</button>
          <button v-if="isEditMode && showDeleteAction" type="button" class="btn btn-danger" @click="emit('delete', customer.id)">Löschen</button>
          <button type="button" @click="cancel" class="btn btn-secondary">Abbrechen</button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue'
import customerAPI from '../services/customerService.js'

const props = defineProps({
  visible: Boolean,
  customer: {
    type: Object,
    default: null
  },
  showDeleteAction: { type: Boolean, default: false },
  showDetailsAction: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'created', 'updated', 'delete', 'details'])

const isEditMode = computed(() => !!props.customer)

const form = ref({
  firstName: '',
  lastName: '',
  street: '',
  houseNumber: null,
  postalCode: '',
  city: '',
  phone: '',
  email: '',
  taxId: '',
  note: '',
  buyerReference: ''
})

watch(() => props.visible, (newVal) => {
  if (newVal) {
    if (props.customer) {
      form.value = { ...props.customer }
    } else {
      form.value = {
        firstName: '',
        lastName: '',
        street: '',
        houseNumber: null,
        postalCode: '',
        city: '',
        phone: '',
        email: '',
        taxId: '',
        note: '',
        buyerReference: ''
      }
    }
  }
})

function cancel() {
  emit('update:visible', false)
}

async function submit() {
  try {
    if (isEditMode.value && props.customer) {
      const response = await customerAPI.updateCustomer(props.customer.id, form.value)
      emit('updated', response.data)
    } else {
      const response = await customerAPI.createCustomer(form.value)
      emit('created', response.data)
    }
    cancel()
  } catch (error) {
    console.error('Failed to save customer:', error)
    alert('Fehler beim Speichern des Kunden')
  }
}
</script>



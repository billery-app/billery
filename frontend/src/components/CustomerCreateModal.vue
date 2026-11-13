<template>
  <div v-if="visible" class="modal-backdrop" @click.self="cancel">
    <div class="modal-panel">
      <h2>{{ isEditMode ? 'Kunde bearbeiten' : 'Neuer Kunde' }}</h2>
      <form @submit.prevent="submit">
        <div class="form-group">
          <label for="customer-firstName">Vorname:</label>
          <input id="customer-firstName" name="firstName" autocomplete="given-name" v-model="form.firstName" required />
        </div>
        <div class="form-group">
          <label for="customer-lastName">Nachname:</label>
          <input id="customer-lastName" name="lastName" autocomplete="family-name" v-model="form.lastName" required />
        </div>
        <div class="form-group">
          <label for="customer-street">Straße:</label>
          <input id="customer-street" name="street" autocomplete="address-line1" v-model="form.street" @change="splitStreetAndNumber(form)" />
        </div>
        <div class="form-group">
          <label for="customer-houseNumber">Hausnummer:</label>
          <input id="customer-houseNumber" name="houseNumber" autocomplete="off" inputmode="numeric" v-model="form.houseNumber" type="number" />
        </div>
        <div class="form-group">
          <label for="customer-postalCode">PLZ:</label>
          <input id="customer-postalCode" name="postalCode" autocomplete="postal-code" inputmode="numeric" v-model="form.postalCode" />
        </div>
        <div class="form-group">
          <label for="customer-city">Ort:</label>
          <input id="customer-city" name="city" autocomplete="address-level2" v-model="form.city" />
        </div>
        <div class="form-group">
          <label for="customer-phone">Telefon:</label>
          <input id="customer-phone" name="phone" autocomplete="tel" type="tel" v-model="form.phone" />
        </div>
        <div class="form-group">
          <label for="customer-email">Email:</label>
          <input id="customer-email" name="email" autocomplete="email" v-model="form.email" type="email" />
        </div>
        <div class="form-group">
          <label for="customer-taxId">USt-IdNr. / Steuernummer:</label>
          <input id="customer-taxId" name="taxId" autocomplete="off" v-model="form.taxId" placeholder="z. B. DE123456789" />
        </div>
        <div class="form-group">
          <label for="customer-buyerReference">Leitweg-ID / Käuferreferenz:</label>
          <input
            id="customer-buyerReference"
            v-model="form.buyerReference"
            name="buyerReference"
            autocomplete="off"
            placeholder="nur für E-Rechnungen, z. B. an Behörden"
            data-testid="buyer-reference"
          />
        </div>
        <div class="form-group">
          <label for="customer-note">Notiz:</label>
          <textarea id="customer-note" name="note" autocomplete="off" v-model="form.note"></textarea>
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
import { splitStreetAndNumber } from '../utils/addressFields.js'
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



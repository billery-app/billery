<template>
  <div v-if="visible" class="modal-backdrop" @click.self="cancel">
    <div class="modal-panel modal-panel-lg">
      <h2>{{ isEditMode ? 'Inhaber bearbeiten' : 'Neuer Inhaber' }}</h2>
      <form @submit.prevent="submit">
        <div class="form-row">
          <div class="form-group">
            <label for="owner-firstName">Vorname:</label>
            <input id="owner-firstName" name="firstName" autocomplete="given-name" v-model="form.firstName" required />
          </div>
          <div class="form-group">
            <label for="owner-lastName">Nachname:</label>
            <input id="owner-lastName" name="lastName" autocomplete="family-name" v-model="form.lastName" required />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label for="owner-companyName">Firmenname:</label>
            <input id="owner-companyName" name="companyName" autocomplete="organization" v-model="form.companyName" />
          </div>
          <div class="form-group">
            <label for="owner-companyType">Firmenart (z.B. GmbH):</label>
            <input id="owner-companyType" name="companyType" autocomplete="off" v-model="form.companyType" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label for="owner-street">Straße:</label>
            <input id="owner-street" name="street" autocomplete="address-line1" v-model="form.street" @change="splitStreetAndNumber(form)" />
          </div>
          <div class="form-group small">
            <label for="owner-houseNumber">Hausnr.:</label>
            <input id="owner-houseNumber" name="houseNumber" autocomplete="off" inputmode="numeric" v-model="form.houseNumber" type="number" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group small">
            <label for="owner-postalCode">PLZ:</label>
            <input id="owner-postalCode" name="postalCode" autocomplete="postal-code" inputmode="numeric" v-model="form.postalCode" />
          </div>
          <div class="form-group">
            <label for="owner-city">Ort:</label>
            <input id="owner-city" name="city" autocomplete="address-level2" v-model="form.city" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label for="owner-phone">Telefon:</label>
            <input id="owner-phone" name="phone" autocomplete="tel" type="tel" v-model="form.phone" />
          </div>
          <div class="form-group">
            <label for="owner-email">Email:</label>
            <input id="owner-email" name="email" autocomplete="email" v-model="form.email" type="email" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label for="owner-url">Website:</label>
            <input id="owner-url" name="url" autocomplete="url" inputmode="url" v-model="form.url" />
          </div>
          <div class="form-group">
            <label for="owner-taxId">Steuer-ID:</label>
            <input id="owner-taxId" name="taxId" autocomplete="off" v-model="form.taxId" />
          </div>
        </div>
        <div class="form-group">
          <label for="owner-iban">IBAN:</label>
          <input id="owner-iban" name="iban" autocomplete="off" v-model="form.iban" />
        </div>
        <div class="form-group toggle-group">
          <label class="toggle-label">
            <input v-model="form.isSmallBusiness" type="checkbox" />
            <span class="toggle-switch"></span>
            Kleinunternehmer gemäß § 19 UStG (keine Umsatzsteuer)
          </label>
        </div>
        <div class="form-actions">
          <button type="submit" class="btn btn-primary">Speichern</button>
          <button v-if="isEditMode && showDeleteAction" type="button" class="btn btn-danger" @click="emit('delete', owner.id)">Löschen</button>
          <button type="button" @click="cancel" class="btn btn-secondary">Abbrechen</button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { splitStreetAndNumber } from '../utils/addressFields.js'
import { ref, watch, computed } from 'vue'
import ownerAPI from '../services/ownerService.js'

const props = defineProps({
  visible: Boolean,
  owner: {
    type: Object,
    default: null
  },
  showDeleteAction: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'created', 'updated', 'delete'])

const isEditMode = computed(() => !!props.owner)

const form = ref({
  firstName: '',
  lastName: '',
  companyName: '',
  companyType: '',
  street: '',
  houseNumber: null,
  postalCode: '',
  city: '',
  phone: '',
  email: '',
  url: '',
  taxId: '',
  iban: '',
  isSmallBusiness: false
})

watch(() => props.visible, (newVal) => {
  if (newVal) {
    if (props.owner) {
      // The API names the flag smallBusiness; the form (and the request) use isSmallBusiness.
      const { smallBusiness, ...owner } = props.owner
      form.value = { ...owner, isSmallBusiness: Boolean(smallBusiness ?? owner.isSmallBusiness) }
    } else {
      form.value = {
        firstName: '',
        lastName: '',
        companyName: '',
        companyType: '',
        street: '',
        houseNumber: null,
        postalCode: '',
        city: '',
        phone: '',
        email: '',
        url: '',
        taxId: '',
        iban: '',
        isSmallBusiness: false
      }
    }
  }
})

function cancel() {
  emit('update:visible', false)
}

async function submit() {
  try {
    if (isEditMode.value && props.owner) {
      const response = await ownerAPI.updateOwner(props.owner.id, form.value)
      emit('updated', response.data)
    } else {
      const response = await ownerAPI.createOwner(form.value)
      emit('created', response.data)
    }
    cancel()
  } catch (error) {
    console.error('Failed to save owner:', error)
    const reason = error.response?.data?.message
    alert(reason ? `Fehler beim Speichern des Inhabers: ${reason}` : 'Fehler beim Speichern des Inhabers')
  }
}
</script>

<style scoped>
.form-row {
  display: flex;
  gap: 15px;
}

.form-row .form-group {
  flex: 1;
}

.form-row .form-group.small {
  flex: 0.4;
}

.toggle-group {
  margin-top: 5px;
}

.toggle-group label {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  font-weight: 500;
  color: var(--color-text-strong);
  margin-bottom: 0;
}

.toggle-label {
  display: flex;
  align-items: center;
  gap: 12px;
  cursor: pointer;
  font-weight: 500;
  color: var(--color-text-strong);
}

.toggle-label input[type="checkbox"] {
  display: none;
}

.toggle-switch {
  position: relative;
  width: 44px;
  min-width: 44px;
  height: 24px;
  background: var(--color-border-strong);
  border-radius: 12px;
  transition: background 0.2s;
  flex-shrink: 0;
}

.toggle-switch::after {
  content: '';
  position: absolute;
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: var(--color-surface);
  top: 2px;
  left: 2px;
  transition: transform 0.2s;
}

.toggle-label input[type="checkbox"]:checked + .toggle-switch {
  background: var(--color-primary);
}

.toggle-label input[type="checkbox"]:checked + .toggle-switch::after {
  transform: translateX(20px);
}

@media (max-width: 600px) {
  .form-row { flex-direction: column; gap: 0; }
}
</style>

<template>
  <div v-if="visible" class="modal-backdrop" @click.self="cancel">
    <div class="modal-panel modal-panel-lg">
      <h2>{{ isEditMode ? $t('ownerForm.edit') : $t('owners.new') }}</h2>
      <form @submit.prevent="submit">
        <div class="form-row">
          <div class="form-group">
            <label for="owner-firstName">{{ $t('form.firstName') }}</label>
            <input id="owner-firstName" v-model="form.firstName" name="firstName" autocomplete="given-name" required />
          </div>
          <div class="form-group">
            <label for="owner-lastName">{{ $t('form.lastName') }}</label>
            <input id="owner-lastName" v-model="form.lastName" name="lastName" autocomplete="family-name" required />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label for="owner-companyName">{{ $t('ownerForm.companyName') }}</label>
            <input id="owner-companyName" v-model="form.companyName" name="companyName" autocomplete="organization" />
          </div>
          <div class="form-group">
            <label for="owner-companyType">{{ $t('ownerForm.companyType') }}</label>
            <input id="owner-companyType" v-model="form.companyType" name="companyType" autocomplete="off" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label for="owner-street">{{ $t('form.street') }}</label>
            <input id="owner-street" v-model="form.street" name="street" autocomplete="address-line1" @change="splitStreetAndNumber(form)" />
          </div>
          <div class="form-group small">
            <label for="owner-houseNumber">{{ $t('form.houseNumber') }}</label>
            <input id="owner-houseNumber" v-model="form.houseNumber" name="houseNumber" autocomplete="off" inputmode="numeric" type="number" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group small">
            <label for="owner-postalCode">{{ $t('form.postalCode') }}</label>
            <input id="owner-postalCode" v-model="form.postalCode" name="postalCode" autocomplete="postal-code" inputmode="numeric" />
          </div>
          <div class="form-group">
            <label for="owner-city">{{ $t('form.city') }}</label>
            <input id="owner-city" v-model="form.city" name="city" autocomplete="address-level2" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label for="owner-phone">{{ $t('customerDetail.phone') }}</label>
            <input id="owner-phone" v-model="form.phone" name="phone" autocomplete="tel" type="tel" />
          </div>
          <div class="form-group">
            <label for="owner-email">{{ $t('customerDetail.email') }}</label>
            <input id="owner-email" v-model="form.email" name="email" autocomplete="email" type="email" />
          </div>
        </div>
        <div class="form-row">
          <div class="form-group">
            <label for="owner-url">{{ $t('ownerForm.website') }}</label>
            <input id="owner-url" v-model="form.url" name="url" autocomplete="url" inputmode="url" />
          </div>
          <div class="form-group">
            <label for="owner-taxId">{{ $t('ownerForm.taxId') }}</label>
            <input id="owner-taxId" v-model="form.taxId" name="taxId" autocomplete="off" />
          </div>
        </div>
        <div class="form-group">
          <label for="owner-iban">{{ $t('ownerForm.iban') }}</label>
          <input id="owner-iban" v-model="form.iban" name="iban" autocomplete="off" />
        </div>
        <div class="form-group toggle-group">
          <label class="toggle-label">
            <input v-model="form.isSmallBusiness" type="checkbox" />
            <span class="toggle-switch"></span>
            {{ $t('ownerForm.smallBusiness') }}
          </label>
        </div>
        <div class="form-actions">
          <button type="submit" class="btn btn-primary">{{ $t('common.save') }}</button>
          <button v-if="isEditMode && showDeleteAction" type="button" class="btn btn-danger" @click="emit('delete', owner.id)">{{ $t('common.delete') }}</button>
          <button type="button" class="btn btn-secondary" @click="cancel">{{ $t('common.cancel') }}</button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { splitStreetAndNumber } from '../utils/addressFields.js'
import { ref, watch, computed } from 'vue'
import { useI18n } from 'vue-i18n'
import ownerAPI from '../services/ownerService.js'

const { t } = useI18n()

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

const form = ref(/** @type {Record<string, any>} */ ({
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
}))

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
    alert(reason ? t('ownerForm.saveFailedReason', { reason }) : t('ownerForm.saveFailed'))
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

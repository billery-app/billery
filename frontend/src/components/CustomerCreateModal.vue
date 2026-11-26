<template>
  <div v-if="visible" class="modal-backdrop" @click.self="cancel">
    <div class="modal-panel">
      <h2>{{ isEditMode ? $t('customerForm.edit') : $t('customers.new') }}</h2>
      <form @submit.prevent="submit">
        <div class="form-group">
          <label for="customer-firstName">{{ $t('form.firstName') }}</label>
          <input id="customer-firstName" v-model="form.firstName" name="firstName" autocomplete="given-name" required />
        </div>
        <div class="form-group">
          <label for="customer-lastName">{{ $t('form.lastName') }}</label>
          <input id="customer-lastName" v-model="form.lastName" name="lastName" autocomplete="family-name" required />
        </div>
        <div class="form-group">
          <label for="customer-street">{{ $t('form.street') }}</label>
          <input id="customer-street" v-model="form.street" name="street" autocomplete="address-line1" @change="splitStreetAndNumber(form)" />
        </div>
        <div class="form-group">
          <label for="customer-houseNumber">{{ $t('form.houseNumber') }}</label>
          <input id="customer-houseNumber" v-model="form.houseNumber" name="houseNumber" autocomplete="off" inputmode="numeric" type="number" />
        </div>
        <div class="form-group">
          <label for="customer-postalCode">{{ $t('form.postalCode') }}</label>
          <input id="customer-postalCode" v-model="form.postalCode" name="postalCode" autocomplete="postal-code" inputmode="numeric" />
        </div>
        <div class="form-group">
          <label for="customer-city">{{ $t('form.city') }}</label>
          <input id="customer-city" v-model="form.city" name="city" autocomplete="address-level2" />
        </div>
        <div class="form-group">
          <label for="customer-phone">{{ $t('customerDetail.phone') }}</label>
          <input id="customer-phone" v-model="form.phone" name="phone" autocomplete="tel" type="tel" />
        </div>
        <div class="form-group">
          <label for="customer-email">{{ $t('customerDetail.email') }}</label>
          <input id="customer-email" v-model="form.email" name="email" autocomplete="email" type="email" />
        </div>
        <div class="form-group">
          <label for="customer-taxId">{{ $t('customerDetail.taxId') }}</label>
          <input id="customer-taxId" v-model="form.taxId" name="taxId" autocomplete="off" :placeholder="$t('customerForm.taxIdPlaceholder')" />
        </div>
        <div class="form-group">
          <label for="customer-buyerReference">{{ $t('customerDetail.buyerReference') }}</label>
          <input
            id="customer-buyerReference"
            v-model="form.buyerReference"
            name="buyerReference"
            autocomplete="off"
            :placeholder="$t('customerForm.buyerReferencePlaceholder')"
            data-testid="buyer-reference"
          />
        </div>
        <div class="form-group">
          <label for="customer-note">{{ $t('customerDetail.note') }}</label>
          <textarea id="customer-note" v-model="form.note" name="note" autocomplete="off"></textarea>
        </div>
        <div class="form-actions">
          <button type="submit" class="btn btn-primary">{{ $t('common.save') }}</button>
          <button v-if="isEditMode && showDetailsAction" type="button" class="btn btn-secondary" @click="emit('details', customer.id)">{{ $t('customerDetail.title') }}</button>
          <button v-if="isEditMode && showDeleteAction" type="button" class="btn btn-danger" @click="emit('delete', customer.id)">{{ $t('common.delete') }}</button>
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
import customerAPI from '../services/customerService.js'

const { t } = useI18n()

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

const form = ref(/** @type {Record<string, any>} */ ({
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
}))

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
    alert(t('customerForm.saveFailed'))
  }
}
</script>



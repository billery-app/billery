<template>
  <div v-if="visible" class="modal-backdrop" @click.self="cancel">
    <div class="modal-panel modal-panel-sm">
      <h2>{{ title }}</h2>
      <div class="form-group">
        <label>Rechnungsdatum:</label>
        <input v-model="form.invoiceDate" type="date" class="date-input" />
      </div>
      <div class="form-group">
        <label>Fälligkeitsdatum:</label>
        <input v-model="form.dueDate" type="date" class="date-input" />
      </div>
      <div v-if="validationError" class="error-msg">{{ validationError }}</div>
      <div class="form-actions">
        <button @click="confirm" class="btn btn-primary">Umwandeln</button>
        <button @click="cancel" class="btn btn-secondary">Abbrechen</button>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, computed } from 'vue'

const props = defineProps({
  visible: Boolean,
  source: { type: Object, default: null },
  targetType: { type: Number, default: 1 }
})

const emit = defineEmits(['update:visible', 'convert'])

const title = computed(() => {
  if (props.targetType === 1) return 'Angebot in Rechnung umwandeln'
  if (props.targetType === 2) return 'Kostenvoranschlag in Angebot umwandeln'
  return 'Umwandeln'
})

const form = ref({
  invoiceDate: new Date().toISOString().split('T')[0],
  dueDate: ''
})

const validationError = ref('')

watch(() => props.visible, (val) => {
  if (val && props.source) {
    form.value = {
      invoiceDate: new Date().toISOString().split('T')[0],
      dueDate: props.source.dueDate || ''
    }
    validationError.value = ''
  }
})

function confirm() {
  if (form.value.dueDate && form.value.invoiceDate && form.value.dueDate < form.value.invoiceDate) {
    validationError.value = 'Das Fälligkeitsdatum kann nicht vor dem Rechnungsdatum liegen.'
    return
  }
  validationError.value = ''
  emit('convert', {
    invoiceDate: form.value.invoiceDate,
    dueDate: form.value.dueDate
  })
  emit('update:visible', false)
}

function cancel() {
  emit('update:visible', false)
}
</script>

<style scoped>
h2 {
  margin-bottom: 20px;
}

.modal-panel .form-actions {
  justify-content: flex-end;
}

.error-msg {
  color: var(--color-danger);
  font-size: 13px;
  margin-bottom: 10px;
  padding: 8px 12px;
  background: var(--color-danger-soft);
  border-radius: 4px;
}
</style>

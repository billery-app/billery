<template>
  <div class="customer-picker" @keydown="onKeydown">
    <input
      ref="input"
      v-model="query"
      type="text"
      role="combobox"
      autocomplete="off"
      aria-autocomplete="list"
      :aria-expanded="open ? 'true' : 'false'"
      :aria-controls="listId"
      :aria-activedescendant="activeIndex >= 0 ? optionId(activeIndex) : undefined"
      :aria-label="ariaLabel"
      :placeholder="placeholder"
      data-testid="customer-picker-input"
      @focus="onFocus"
      @input="onInput"
      @blur="onBlur"
    />
    <button
      v-if="modelValue && !open"
      type="button"
      class="clear-btn"
      aria-label="Kundenauswahl entfernen"
      data-testid="customer-picker-clear"
      @mousedown.prevent
      @click="clear"
    >×</button>
    <ul v-if="open" :id="listId" role="listbox" class="options" data-testid="customer-picker-options">
      <li v-if="loading && !results.length" class="hint" role="presentation">Suche…</li>
      <li v-else-if="!results.length" class="hint" role="presentation">Kein Kunde gefunden</li>
      <li
        v-for="(customer, index) in results"
        :id="optionId(index)"
        :key="customer.id"
        role="option"
        :aria-selected="index === activeIndex ? 'true' : 'false'"
        :class="['option', { active: index === activeIndex }]"
        @mousedown.prevent
        @click="select(customer)"
        @mouseenter="activeIndex = index"
      >
        <span class="name">{{ label(customer) }}</span>
        <span v-if="customer.city" class="city">{{ customer.postalCode }} {{ customer.city }}</span>
      </li>
      <li v-if="total > results.length" class="hint" role="presentation">
        {{ total - results.length }} weitere – Suche verfeinern
      </li>
    </ul>
  </div>
</template>

<script setup>
/**
 * Customer selection for the document form, searching on the server (GET /api/customers/page)
 * instead of rendering every customer as an <option>. v-model is the customer id ('' for none);
 * v-model:customer optionally carries the selected customer object.
 */
import { ref, watch, onBeforeUnmount } from 'vue'
import customerAPI from '../services/customerService.js'

const props = defineProps({
  modelValue: { type: [Number, String], default: '' },
  customer: { type: Object, default: null },
  placeholder: { type: String, default: 'Kunde suchen…' },
  ariaLabel: { type: String, default: 'Kunde' }
})
const emit = defineEmits(['update:modelValue', 'update:customer'])

/** Results per search; the picker hints at more instead of listing everything. */
const PAGE_SIZE = 20
const DEBOUNCE_MS = 250

const listId = `customer-picker-${Math.random().toString(36).slice(2, 10)}`
const input = ref(null)
const query = ref('')
const results = ref([])
const total = ref(0)
const open = ref(false)
const loading = ref(false)
const activeIndex = ref(-1)
const selected = ref(props.customer)
let timer = null
let request = 0

function label(customer) {
  return `${customer.firstName || ''} ${customer.lastName || ''}`.trim()
}

function optionId(index) {
  return `${listId}-option-${index}`
}

/** Shows the selected customer's name; loads it when only the id is known (editing a document). */
async function syncSelection() {
  const id = props.modelValue
  if (!id) {
    selected.value = null
    if (!open.value) query.value = ''
    return
  }
  if (props.customer && Number(props.customer.id) === Number(id)) {
    selected.value = props.customer
  } else if (!selected.value || Number(selected.value.id) !== Number(id)) {
    try {
      selected.value = (await customerAPI.getCustomer(id)).data
    } catch {
      selected.value = { id, firstName: `Kunde #${id}`, lastName: '' }
    }
  }
  if (!open.value) query.value = label(selected.value)
}

watch(() => [props.modelValue, props.customer], syncSelection, { immediate: true })

async function search() {
  const current = ++request
  loading.value = true
  try {
    const typed = query.value.trim()
    const response = await customerAPI.getCustomerPage({ page: 0, size: PAGE_SIZE, q: typed, sort: 'name' })
    if (current !== request) return
    results.value = response.data.items
    total.value = response.data.total
    activeIndex.value = results.value.length ? 0 : -1
  } catch {
    if (current !== request) return
    results.value = []
    total.value = 0
  } finally {
    if (current === request) loading.value = false
  }
}

function onFocus() {
  open.value = true
  // Start from the full (alphabetical) list, also when a customer is already selected.
  query.value = ''
  search()
}

function onInput() {
  open.value = true
  clearTimeout(timer)
  timer = setTimeout(search, DEBOUNCE_MS)
}

function close() {
  open.value = false
  activeIndex.value = -1
  clearTimeout(timer)
  query.value = selected.value ? label(selected.value) : ''
}

function onBlur() {
  close()
}

function select(customer) {
  selected.value = customer
  emit('update:modelValue', customer.id)
  emit('update:customer', customer)
  close()
}

function clear() {
  selected.value = null
  emit('update:modelValue', '')
  emit('update:customer', null)
  query.value = ''
  input.value?.focus()
}

function onKeydown(event) {
  if (!open.value) {
    if (event.key === 'ArrowDown') {
      onFocus()
      event.preventDefault()
    }
    return
  }
  if (event.key === 'ArrowDown') {
    activeIndex.value = Math.min(activeIndex.value + 1, results.value.length - 1)
    event.preventDefault()
  } else if (event.key === 'ArrowUp') {
    activeIndex.value = Math.max(activeIndex.value - 1, 0)
    event.preventDefault()
  } else if (event.key === 'Enter') {
    // Never submits the document form while the list is open.
    event.preventDefault()
    if (activeIndex.value >= 0 && results.value[activeIndex.value]) {
      select(results.value[activeIndex.value])
    }
  } else if (event.key === 'Escape') {
    event.preventDefault()
    event.stopPropagation()
    close()
  }
}

onBeforeUnmount(() => clearTimeout(timer))

defineExpose({ select })
</script>

<style scoped>
.customer-picker { position: relative; flex: 1; min-width: 0; }
/* Same look as the document form's fields (DocumentModal's .form-group input, which is scoped and
   does not reach into this component). */
.customer-picker input {
  width: 100%;
  box-sizing: border-box;
  padding: 10px 28px 10px 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  font-size: 14px;
}
.clear-btn {
  position: absolute; right: 6px; top: 50%; transform: translateY(-50%);
  border: none; background: transparent; cursor: pointer; font-size: 18px; color: var(--color-text-muted); padding: 0 4px;
}
.options {
  position: absolute; z-index: 20; left: 0; right: 0; top: 100%; margin: 2px 0 0; padding: 4px 0;
  list-style: none; background: var(--color-surface); border: 1px solid var(--color-border-strong); border-radius: 6px;
  box-shadow: var(--shadow-lg); max-height: 280px; overflow-y: auto;
}
.option { display: flex; justify-content: space-between; gap: 12px; padding: 6px 10px; cursor: pointer; }
.option.active { background: var(--color-primary-soft); }
.city { color: var(--color-text-muted); font-size: 0.9em; white-space: nowrap; }
.hint { padding: 6px 10px; color: var(--color-text-muted); font-size: 0.9em; }
</style>

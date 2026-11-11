<template>
  <div class="invoice-item-row" :class="{ 'is-child': depth > 0, 'is-note': isNote }" :style="indentStyle">
    <div class="row-fields">
      <input
        v-model="item.itemName"
        :placeholder="isNote ? 'Notiz (Überschrift)' : 'Bezeichnung'"
        class="row-input"
        data-testid="item-name"
      />
      <textarea
        v-if="isNote"
        v-model="item.itemDescription"
        placeholder="Notiz (optional, mehrzeilig)"
        class="row-textarea"
        rows="2"
      />
      <input
        v-else
        v-model="item.itemDescription"
        placeholder="Beschreibung"
        class="row-input row-input-wide"
      />
      <template v-if="!isNote">
        <input
          v-model.number="item.quantity"
          type="number"
          min="0"
          step="1"
          placeholder="Menge"
          class="row-input row-input-num"
        />
        <input
          v-model="item.unit"
          placeholder="Einheit"
          class="row-input row-input-unit"
        />
        <input
          v-model.number="item.unitPrice"
          type="number"
          min="0"
          step="0.01"
          placeholder="Preis"
          class="row-input row-input-num"
        />
        <select
          v-if="showTaxRate"
          v-model.number="item.taxRate"
          class="row-input row-input-rate"
          title="Umsatzsteuersatz"
          data-testid="item-tax-rate"
        >
          <option v-for="rate in VAT_RATES" :key="rate" :value="rate">{{ rate }} %</option>
        </select>
        <span class="row-total">{{ formatCurrency(item.quantity * item.unitPrice) }}</span>
      </template>
      <button
        type="button"
        class="btn btn-small btn-danger"
        :title="removeTitle"
        @click="onRemoveClick"
      >×</button>
    </div>

    <div class="row-actions">
      <button
        v-if="canHaveChildren && !showChildPicker"
        type="button"
        class="btn btn-small btn-secondary"
        @click="showChildPicker = true"
      >+ Unterposition</button>
      <span v-else-if="!canHaveChildren" class="row-hint">max. Tiefe</span>
    </div>

    <div v-if="showChildPicker" class="child-picker">
      <div class="child-picker-tabs">
        <button
          type="button"
          :class="['tab', childTab === 'articles' ? 'active' : '']"
          @click="childTab = 'articles'"
        >Artikel</button>
        <button
          type="button"
          :class="['tab', childTab === 'services' ? 'active' : '']"
          @click="childTab = 'services'"
        >Leistungen</button>
        <button
          type="button"
          :class="['tab', childTab === 'custom' ? 'active' : '']"
          @click="childTab = 'custom'"
        >Freitext</button>
      </div>

      <div v-if="childTab === 'articles'" class="child-selector">
        <select v-model="childArticleId" @change="onChildArticleSelect">
          <option value="">Artikel auswählen…</option>
          <option v-for="a in articles" :key="a.id" :value="a.id">
            {{ a.name }} ({{ formatCurrency(a.salesPrice) }})
          </option>
        </select>
        <div v-if="childArticle" class="child-preview">
          <span>{{ childArticle.name }} — {{ childArticle.description }}</span>
          <button type="button" class="btn btn-small btn-primary" @click="addChildFromArticle">Hinzufügen</button>
        </div>
      </div>

      <div v-if="childTab === 'services'" class="child-selector">
        <select v-model="childServiceId" @change="onChildServiceSelect">
          <option value="">Leistung auswählen…</option>
          <option v-for="s in services" :key="s.id" :value="s.id">
            {{ s.name }} ({{ formatCurrency(s.hourlyRate) }}/h)
          </option>
        </select>
        <div v-if="childService" class="child-preview">
          <span>{{ childService.name }} — {{ childService.description }}</span>
          <button type="button" class="btn btn-small btn-primary" @click="addChildFromService">Hinzufügen</button>
        </div>
      </div>

      <div v-if="childTab === 'custom'" class="child-selector">
        <button type="button" class="btn btn-small btn-primary" @click="addCustomChild">+ Freie Unterposition</button>
      </div>

      <button type="button" class="btn btn-small btn-secondary child-picker-close" @click="closeChildPicker">Schließen</button>
    </div>

    <div v-if="item.children && item.children.length" class="children">
      <InvoiceItemRow
        v-for="(child, cIdx) in item.children"
        :key="child._key"
        :item="child"
        :parent-array="item.children"
        :index="cIdx"
        :articles="articles"
        :services="services"
        :depth="depth + 1"
        :max-depth="maxDepth"
        :show-tax-rate="showTaxRate"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, computed } from 'vue'
import { VAT_RATES } from '../../utils/vat.js'

const props = defineProps({
  item: { type: Object, required: true },
  parentArray: { type: Array, required: true },
  index: { type: Number, required: true },
  articles: { type: Array, default: () => [] },
  services: { type: Array, default: () => [] },
  depth: { type: Number, default: 0 },
  maxDepth: { type: Number, default: 2 },
  // Only regularly taxed documents have a VAT rate per line (see utils/vat.js).
  showTaxRate: { type: Boolean, default: true }
})

const canHaveChildren = computed(() => props.depth + 1 < props.maxDepth)

const isNote = computed(() => (props.item.itemType || '').toLowerCase() === 'custom')

const indentStyle = computed(() => ({
  marginLeft: `${props.depth * 24}px`
}))

const removeTitle = computed(() => {
  const hasChildren = props.item.children && props.item.children.length > 0
  return hasChildren ? 'Position inkl. Unterpositionen entfernen' : 'Position entfernen'
})

const showChildPicker = ref(false)
const childTab = ref('custom')
const childArticleId = ref('')
const childArticle = ref(null)
const childServiceId = ref('')
const childService = ref(null)

function onChildArticleSelect() {
  childArticle.value = props.articles.find(a => a.id === childArticleId.value) || null
}

function onChildServiceSelect() {
  childService.value = props.services.find(s => s.id === childServiceId.value) || null
}

function ensureChildrenArray() {
  if (!props.item.children) {
    props.item.children = []
  }
}

function addChild(child) {
  ensureChildrenArray()
  child._key = generateKey()
  child.parentItemId = props.item.id || null
  props.item.children.push(child)
}

function addChildFromArticle() {
  if (!childArticle.value) return
  addChild({
    itemType: 'article',
    itemId: childArticle.value.id,
    itemName: childArticle.value.name,
    itemDescription: childArticle.value.description,
    quantity: 1,
    unitPrice: childArticle.value.salesPrice,
    unit: childArticle.value.unit || 'Stk',
    taxRate: Number(childArticle.value.taxRate ?? 19),
    totalPrice: 0
  })
  childArticleId.value = ''
  childArticle.value = null
  showChildPicker.value = false
}

function addChildFromService() {
  if (!childService.value) return
  addChild({
    itemType: 'service',
    itemId: childService.value.id,
    itemName: childService.value.name,
    itemDescription: childService.value.description,
    quantity: 1,
    unitPrice: childService.value.hourlyRate,
    unit: childService.value.unit || 'Std',
    taxRate: Number(childService.value.taxRate ?? 19),
    totalPrice: 0
  })
  childServiceId.value = ''
  childService.value = null
  showChildPicker.value = false
}

function addCustomChild() {
  addChild({
    itemType: 'custom',
    itemId: 0,
    itemName: '',
    itemDescription: '',
    quantity: 1,
    unitPrice: 0,
    unit: '',
    taxRate: Number(props.item.taxRate ?? 19),
    totalPrice: 0
  })
  showChildPicker.value = false
}

function closeChildPicker() {
  showChildPicker.value = false
  childArticleId.value = ''
  childArticle.value = null
  childServiceId.value = ''
  childService.value = null
}

function onRemoveClick() {
  props.parentArray.splice(props.index, 1)
}

function formatCurrency(amount) {
  if (amount === null || amount === undefined || isNaN(amount)) return '-'
  return new Intl.NumberFormat('de-DE', { style: 'currency', currency: 'EUR' }).format(amount)
}

let _keyCounter = 0
function generateKey() {
  _keyCounter += 1
  return `item-${Date.now()}-${_keyCounter}`
}
</script>

<style scoped>
.invoice-item-row {
  box-sizing: border-box;
  width: auto;
  max-width: 100%;
  min-width: 0;
  margin-bottom: 6px;
  background: white;
  border: 1px solid var(--color-border-strong);
  border-radius: 4px;
  padding: 6px 8px;
}

.invoice-item-row.is-child {
  background: var(--color-surface-subtle);
  border-left: 3px solid var(--color-border-strong);
}

.row-fields {
  display: flex;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  gap: 6px;
  align-items: center;
  flex-wrap: nowrap;
}

.row-input {
  flex: 1;
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
  padding: 5px 6px;
  border: 1px solid var(--color-border-strong);
  border-radius: 4px;
  font-size: 13px;
}

.row-input-wide {
  flex: 2;
}

.row-input-num {
  flex: 0 0 70px;
  text-align: right;
}

.row-input-rate {
  flex: 0 0 72px;
}

.row-input-unit {
  flex: 0 0 70px;
}

.row-textarea {
  flex: 2;
  min-width: 0;
  max-width: 100%;
  box-sizing: border-box;
  padding: 5px 6px;
  border: 1px solid var(--color-border-strong);
  border-radius: 4px;
  font-size: 13px;
  font-family: inherit;
  resize: vertical;
  min-height: 32px;
}

.row-total {
  flex: 0 0 90px;
  min-width: 0;
  text-align: right;
  font-variant-numeric: tabular-nums;
  font-size: 13px;
  color: var(--color-text-strong);
}

.invoice-item-row.is-note {
  background: var(--color-warning-soft);
  border-left: 3px solid var(--color-warning);
}

.row-actions {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-top: 4px;
}

.row-hint {
  font-size: 11px;
  color: var(--color-text-subtle);
  font-style: italic;
}

.child-picker {
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  margin-top: 6px;
  padding: 8px;
  background: var(--color-surface-muted);
  border: 1px dashed var(--color-border-strong);
  border-radius: 4px;
  display: flex;
  flex-direction: column;
  gap: 8px;
}

.child-picker-tabs {
  display: flex;
  gap: 4px;
}

.child-picker-tabs .tab {
  padding: 4px 10px;
  border: none;
  background: var(--color-border);
  cursor: pointer;
  border-radius: 4px;
  font-size: 12px;
}

.child-picker-tabs .tab.active {
  background: var(--color-primary);
  color: white;
}

.child-selector {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.child-selector select {
  box-sizing: border-box;
  width: 100%;
  max-width: 100%;
  min-width: 0;
  padding: 5px;
  border: 1px solid var(--color-border-strong);
  border-radius: 4px;
  font-size: 13px;
}

.child-preview {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 8px;
  background: white;
  padding: 6px 8px;
  border-radius: 4px;
  border: 1px solid var(--color-border-strong);
  font-size: 12px;
  min-width: 0;
  max-width: 100%;
}

.child-preview > span {
  min-width: 0;
  overflow-wrap: anywhere;
}

.child-picker-close {
  align-self: flex-end;
}

.children {
  max-width: 100%;
  min-width: 0;
  margin-top: 6px;
  display: flex;
  flex-direction: column;
  gap: 4px;
}

@media (max-width: 800px) {
  .row-fields { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); align-items: center; }
  .row-input,
  .row-textarea,
  .child-selector select { font-size: 16px; }
  .row-fields > .row-input:first-child,
  .row-input-wide,
  .row-textarea { grid-column: 1 / -1; width: 100%; }
  .row-input-num, .row-input-unit, .row-input-rate { grid-column: auto; width: 100%; flex: auto; }
  .row-total { grid-column: 1; flex: auto; text-align: left; overflow-wrap: anywhere; }
  .row-fields > .btn-danger { grid-column: 2; justify-self: end; min-width: 0; min-height: 36px; }
  .row-fields > * { min-width: 0; }
  .child-picker-tabs > .tab { min-width: 0; padding-inline: 8px; }
  .child-picker-tabs { flex-wrap: wrap; }
  .child-preview { align-items: stretch; flex-direction: column; }
}
</style>

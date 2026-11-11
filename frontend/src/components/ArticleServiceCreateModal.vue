<template>
  <div v-if="visible" class="modal-backdrop" @click.self="cancel">
    <div class="modal-panel">
      <h2>{{ modalTitle }}</h2>
      <div v-if="!isEditMode" class="tabs">
        <button :class="['tab', activeTab === 'article' ? 'active' : '']" @click="activeTab = 'article'">Artikel</button>
        <button :class="['tab', activeTab === 'service' ? 'active' : '']" @click="activeTab = 'service'">Leistung</button>
      </div>
      <form @submit.prevent="submit">
        <template v-if="activeTab === 'article'">
          <div class="form-group">
            <label>Name:</label>
            <input v-model="articleForm.name" required />
          </div>
          <div class="form-group">
            <label>Beschreibung:</label>
            <textarea v-model="articleForm.description"></textarea>
          </div>
          <div class="form-group">
            <label>Einheit:</label>
            <input v-model="articleForm.unit" placeholder="z.B. Stk, kg, m" />
          </div>
          <div class="form-group">
            <label>Einkaufspreis:</label>
            <input v-model="articleForm.purchasePrice" type="number" step="0.01" />
          </div>
          <div class="form-group">
            <label>Verkaufspreis:</label>
            <input v-model="articleForm.salesPrice" type="number" step="0.01" required />
          </div>
          <div class="form-group">
            <label>USt.-Satz (Standard für neue Positionen):</label>
            <select v-model.number="articleForm.taxRate" data-testid="article-tax-rate">
              <option v-for="rate in VAT_RATES" :key="rate" :value="rate">{{ rate }} %</option>
            </select>
          </div>
          <div class="form-group">
            <label>Notiz (intern, erscheint nicht auf Dokumenten):</label>
            <textarea v-model="articleForm.note" data-testid="article-note"></textarea>
          </div>
        </template>
        <template v-if="activeTab === 'service'">
          <div class="form-group">
            <label>Name:</label>
            <input v-model="serviceForm.name" required />
          </div>
          <div class="form-group">
            <label>Beschreibung:</label>
            <textarea v-model="serviceForm.description"></textarea>
          </div>
          <div class="form-group">
            <label>Stundensatz:</label>
            <input v-model="serviceForm.hourlyRate" type="number" step="0.01" required />
          </div>
          <div class="form-group">
            <label>Einheit:</label>
            <input v-model="serviceForm.unit" placeholder="z.B. Std, h" />
          </div>
          <div class="form-group">
            <label>USt.-Satz (Standard für neue Positionen):</label>
            <select v-model.number="serviceForm.taxRate" data-testid="service-tax-rate">
              <option v-for="rate in VAT_RATES" :key="rate" :value="rate">{{ rate }} %</option>
            </select>
          </div>
          <div class="form-group">
            <label>Verknüpfter Artikel:</label>
            <select v-model="serviceForm.articleId">
              <option value="">Kein Artikel</option>
              <option v-for="a in articles" :key="a.id" :value="a.id">{{ a.name }}</option>
            </select>
          </div>
          <div class="form-group">
            <label>Verknüpfte Ausstattung:</label>
            <select v-model="serviceForm.equipmentId">
              <option :value="null">Keine Ausstattung</option>
              <option v-for="eq in equipmentList" :key="eq.id" :value="eq.id">{{ eq.name }}</option>
            </select>
          </div>
        </template>
        <div class="form-actions">
          <button type="submit" class="btn btn-primary">Speichern</button>
          <button v-if="isEditMode && showDeleteAction" type="button" class="btn btn-danger" @click="emit('delete', { id: item.id, itemType })">Löschen</button>
          <button type="button" @click="cancel" class="btn btn-secondary">Abbrechen</button>
        </div>
      </form>
    </div>
  </div>
</template>

<script setup>
import { ref, watch, computed, onMounted } from 'vue'
import articleAPI from '../services/articleService.js'
import equipmentAPI from '../services/equipmentService.js'
import { VAT_RATES } from '../utils/vat.js'

const props = defineProps({
  visible: Boolean,
  articles: {
    type: Array,
    default: () => []
  },
  item: {
    type: Object,
    default: null
  },
  itemType: {
    type: String,
    default: 'article'
  },
  showDeleteAction: { type: Boolean, default: false }
})

const emit = defineEmits(['update:visible', 'created', 'updated', 'delete'])

const isEditMode = computed(() => !!props.item)
const activeTab = ref('article')

const modalTitle = computed(() => {
  if (isEditMode.value) {
    return props.itemType === 'article' ? 'Artikel bearbeiten' : 'Leistung bearbeiten'
  }
  return 'Neuer Artikel / Leistung'
})

const articleForm = ref({
  name: '',
  description: '',
  unit: '',
  purchasePrice: 0,
  salesPrice: 0,
  taxRate: 19,
  // Internal remark about the article; stored, never printed.
  note: ''
})

const serviceForm = ref({
  name: '',
  description: '',
  hourlyRate: 0,
  unit: 'h',
  articleId: '',
  equipmentId: null,
  taxRate: 19
})

const equipmentList = ref([])

onMounted(async () => {
  try {
    const response = await equipmentAPI.getAll()
    equipmentList.value = response.data
  } catch (e) {
    // Equipment feature may not be available yet
  }
})

watch(() => props.visible, (newVal) => {
  if (newVal) {
    if (props.item) {
      activeTab.value = props.itemType
      if (props.itemType === 'article') {
        articleForm.value = { 
          name: props.item.name || '',
          description: props.item.description || '',
          unit: props.item.unit || '',
          purchasePrice: props.item.purchasePrice || 0,
          salesPrice: props.item.salesPrice || 0,
          taxRate: Number(props.item.taxRate ?? 19),
          note: props.item.note || ''
        }
      } else {
        serviceForm.value = {
          name: props.item.name || '',
          description: props.item.description || '',
          hourlyRate: props.item.hourlyRate || 0,
          unit: props.item.unit || 'h',
          articleId: props.item.articleId || '',
          equipmentId: props.item.equipmentId || null,
          taxRate: Number(props.item.taxRate ?? 19)
        }
      }
    } else {
      activeTab.value = 'article'
      articleForm.value = { name: '', description: '', unit: '', purchasePrice: 0, salesPrice: 0, taxRate: 19, note: '' }
      serviceForm.value = { name: '', description: '', hourlyRate: 0, unit: 'h', articleId: '', equipmentId: null, taxRate: 19 }
    }
  }
})

function cancel() {
  emit('update:visible', false)
}

async function submit() {
  try {
    let response
    if (activeTab.value === 'article') {
      if (isEditMode.value && props.item) {
        response = await articleAPI.updateArticle(props.item.id, articleForm.value)
        emit('updated', response.data, 'article')
      } else {
        response = await articleAPI.createArticle(articleForm.value)
        emit('created', response.data, 'article')
      }
    } else {
      if (isEditMode.value && props.item) {
        response = await articleAPI.updateService(props.item.id, serviceForm.value)
        emit('updated', response.data, 'service')
      } else {
        response = await articleAPI.createService(serviceForm.value)
        emit('created', response.data, 'service')
      }
    }
    cancel()
  } catch (error) {
    console.error('Failed to save:', error)
    alert('Fehler beim Speichern')
  }
}
</script>

<style scoped>
.tabs {
  display: flex;
  gap: 5px;
  margin-bottom: 20px;
}

.tab {
  padding: 8px 16px;
  border: none;
  background: var(--color-border);
  cursor: pointer;
  border-radius: 6px;
  font-size: 0.9em;
}

.tab.active {
  background: var(--color-primary);
  color: var(--color-on-primary);
}
</style>

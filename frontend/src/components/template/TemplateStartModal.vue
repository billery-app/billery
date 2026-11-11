<template>
  <div class="start-modal" role="dialog" aria-modal="true" aria-labelledby="start-title" @click.self="$emit('cancel')" @keydown.esc="$emit('cancel')">
    <div class="start-content">
      <h2 id="start-title">Neue Vorlage</h2>
      <p class="start-intro">Wählen Sie, wofür die Vorlage ist, und einen Stil als Ausgangspunkt. Alles lässt sich danach anpassen.</p>

      <div class="type-row" role="radiogroup" aria-label="Dokumenttyp">
        <button
          v-for="dt in documentTypeOptions"
          :key="dt.id"
          type="button"
          role="radio"
          class="type-option"
          :class="{ active: documentTypeId === dt.id }"
          :aria-checked="documentTypeId === dt.id"
          @click="documentTypeId = dt.id"
        >
          {{ dt.label }}
        </button>
      </div>

      <div class="preset-grid" role="radiogroup" aria-label="Stil">
        <button
          v-for="preset in presets"
          :key="preset.key"
          type="button"
          role="radio"
          class="preset-card"
          :class="{ active: presetKey === preset.key }"
          :aria-checked="presetKey === preset.key"
          @click="presetKey = preset.key"
          @dblclick="choose(preset.key)"
        >
          <TemplateThumbnail
            :blocks="built[preset.key].blocks"
            :document-type-id="documentTypeId"
            :margins="marginsOf(built[preset.key].margins)"
            :max-width="240"
          />
          <span class="preset-name">{{ preset.name }}</span>
          <span class="preset-description">{{ preset.description }}</span>
        </button>
      </div>

      <div class="start-actions">
        <button type="button" class="btn btn-secondary" @click="$emit('cancel')">Abbrechen</button>
        <button ref="confirm" type="button" class="btn btn-primary" @click="choose(presetKey)">Weiter</button>
      </div>
    </div>
  </div>
</template>

<script>
import TemplateThumbnail from './TemplateThumbnail.vue'
import { TEMPLATE_PRESETS, getPreset } from '../../utils/templatePresets.js'
import { DOCUMENT_TYPES, CREDIT_NOTE, INVOICE } from '../../data/documentTypes.js'
import { documentTitle } from '../../utils/blockRegistry.js'

/**
 * First step of "Neue Vorlage": pick the document type and a style preset
 * (with live thumbnails). Emits `choose(template)` with a ready-to-edit
 * template object without `id` (name, type, margins, blocks), or `cancel`.
 */
export default {
  name: 'TemplateStartModal',
  components: { TemplateThumbnail },
  emits: ['choose', 'cancel'],
  data() {
    return {
      documentTypeId: INVOICE,
      presetKey: TEMPLATE_PRESETS[0].key,
      presets: TEMPLATE_PRESETS
    }
  },
  computed: {
    documentTypeOptions() {
      return DOCUMENT_TYPES.filter(dt => dt.id !== CREDIT_NOTE)
    },
    built() {
      return Object.fromEntries(this.presets.map(p => [p.key, p.build(this.documentTypeId)]))
    }
  },
  mounted() {
    this.$nextTick(() => this.$refs.confirm && this.$refs.confirm.focus())
  },
  methods: {
    marginsOf(value) {
      return { marginTopMm: value, marginRightMm: value, marginBottomMm: value, marginLeftMm: value }
    },
    choose(key) {
      const preset = getPreset(key)
      const { blocks, margins } = preset.build(this.documentTypeId)
      this.$emit('choose', {
        name: `${documentTitle(this.documentTypeId)} – ${preset.name}`,
        documentTypeId: this.documentTypeId,
        isDefault: false,
        documentSpecific: false,
        ...this.marginsOf(margins),
        cssContent: '',
        blocks
      })
    }
  }
}
</script>

<style scoped>
.start-modal {
  position: fixed;
  inset: 0;
  background: var(--color-backdrop-dark);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1000;
  padding: 16px;
}

.start-content {
  width: 100%;
  max-width: 820px;
  max-height: 92vh;
  overflow-y: auto;
  background: var(--color-surface);
  border-radius: 10px;
  padding: 24px;
  box-shadow: var(--shadow-xl);
}

h2 {
  margin: 0 0 4px;
  font-size: 20px;
}

.start-intro {
  margin: 0 0 18px;
  color: var(--color-text-muted);
  font-size: 14px;
}

.type-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 18px;
}

.type-option {
  padding: 7px 14px;
  border: 1px solid var(--color-border-strong);
  border-radius: 18px;
  background: var(--color-surface);
  font-size: 13px;
  cursor: pointer;
}

.type-option.active {
  background: var(--color-primary);
  border-color: var(--color-primary);
  color: var(--color-on-primary);
}

.preset-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(170px, 1fr));
  gap: 14px;
}

.preset-card {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 6px;
  padding: 12px;
  border: 2px solid var(--color-border);
  border-radius: 10px;
  background: var(--color-surface-subtle);
  cursor: pointer;
  text-align: center;
}

.preset-card:hover {
  border-color: var(--color-primary-border);
}

.preset-card.active {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.preset-name {
  font-weight: 600;
  font-size: 14px;
  color: var(--color-text);
}

.preset-description {
  font-size: 12px;
  color: var(--color-text-muted);
  line-height: 1.4;
}

.start-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}
</style>

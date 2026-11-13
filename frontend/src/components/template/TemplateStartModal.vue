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

      <div class="import-row">
        <p class="import-hint">
          Oder die Texte einer Word- oder ODT-Datei in den gewählten Stil übernehmen. Abschnitte
          wie „Kopfzeile“ oder „Fußzeile“ mit Zeilen wie „Text: …“ (so sieht ein Export aus) landen
          in ihren Blöcken, übriger Text unter „Hinweise“.
        </p>
        <input
          ref="file"
          type="file"
          class="file-input"
          accept=".docx,.odt,application/vnd.openxmlformats-officedocument.wordprocessingml.document,application/vnd.oasis.opendocument.text"
          aria-label="Word- oder ODT-Datei für die Vorlage"
          data-testid="template-import-file"
          @change="onFile"
        />
        <button type="button" class="btn btn-outline" :disabled="importing" @click="$refs.file.click()">
          {{ importing ? 'Datei wird gelesen …' : 'Aus Datei übernehmen …' }}
        </button>
        <p v-if="importError" class="import-error" role="alert">{{ importError }}</p>
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
import { applySections } from '../../utils/documentExchange.js'
import { validateImportFile } from '../../utils/fileValidation.js'
import documentAPI from '../../services/documentService.js'

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
      presets: TEMPLATE_PRESETS,
      importing: false,
      importError: ''
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
    /**
     * Reads the chosen DOCX / ODT (nothing is stored), applies its sections to the blocks of the
     * selected style and opens the editor with the result, like a style without file.
     */
    async onFile(event) {
      const input = event.target
      const file = input.files && input.files[0]
      input.value = ''
      if (!file) return
      const check = validateImportFile(file)
      if (!check.isValid) {
        this.importError = check.errors.join(' ')
        return
      }
      this.importError = ''
      this.importing = true
      try {
        const { data } = await documentAPI.importDocument(file)
        const { blocks } = applySections(getPreset(this.presetKey).build(this.documentTypeId).blocks, data.sections)
        this.choose(this.presetKey, { name: data.name || file.name, blocks })
      } catch (error) {
        this.importError = error.response?.data?.message || 'Die Datei konnte nicht übernommen werden.'
      } finally {
        this.importing = false
      }
    },
    marginsOf(value) {
      return { marginTopMm: value, marginRightMm: value, marginBottomMm: value, marginLeftMm: value }
    },
    choose(key, imported = null) {
      const preset = getPreset(key)
      const built = preset.build(this.documentTypeId)
      const { margins } = built
      const blocks = imported ? imported.blocks : built.blocks
      this.$emit('choose', {
        name: imported ? imported.name : `${documentTitle(this.documentTypeId)} – ${preset.name}`,
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

.import-row {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px 12px;
  margin-top: 16px;
  padding-top: 16px;
  border-top: 1px solid var(--color-border);
}

.import-hint {
  flex: 1 1 100%;
  margin: 0;
  color: var(--color-text-muted);
  font-size: var(--font-size-sm);
  line-height: 1.5;
}

/* The native file input stays in the DOM (for the label and keyboard users of the button) but hidden. */
.file-input {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
}

.import-error {
  flex: 1 1 100%;
  margin: 0;
  color: var(--color-danger);
  font-size: var(--font-size-sm);
}

.start-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
  margin-top: 20px;
}
</style>

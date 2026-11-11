<template>
  <div class="design-panel">
    <section class="design-section">
      <h3>Logo</h3>
      <div class="logo-row">
        <div class="logo-box" :class="{ empty: !design.logoUrl }">
          <img v-if="design.logoUrl" :src="design.logoUrl" alt="Logo" />
          <span v-else>Kein Logo</span>
        </div>
        <div class="logo-actions">
          <label class="btn-secondary-sm">
            <input type="file" accept="image/png,image/jpeg,image/gif" :disabled="uploading" @change="onLogoFile" />
            {{ uploading ? 'Wird hochgeladen …' : (design.logoUrl ? 'Ändern' : 'Hochladen') }}
          </label>
          <button v-if="design.logoUrl" type="button" class="btn-link" @click="emitBlocks(applyLogo(blocks, ''))">Entfernen</button>
        </div>
      </div>
    </section>

    <section class="design-section">
      <h3>Akzentfarbe</h3>
      <p class="section-hint">Für Firmenname und Gesamtbetrag.</p>
      <div class="swatches" role="radiogroup" aria-label="Akzentfarbe">
        <button
          v-for="color in accentColors"
          :key="color"
          type="button"
          class="swatch"
          role="radio"
          :aria-checked="sameColor(design.accentColor, color)"
          :aria-label="color"
          :class="{ active: sameColor(design.accentColor, color) }"
          :style="{ background: color }"
          @click="emitBlocks(applyAccentColor(blocks, color))"
        ></button>
        <label class="swatch custom" title="Eigene Farbe">
          <input type="color" :value="design.accentColor" aria-label="Eigene Akzentfarbe" @input="emitBlocks(applyAccentColor(blocks, $event.target.value))" />
          +
        </label>
      </div>
    </section>

    <section class="design-section">
      <h3>Schrift</h3>
      <p class="section-hint">Im PDF wird die passende Standardschrift verwendet (Helvetica, Times oder Courier).</p>
      <select class="form-select" :value="design.fontFamily" aria-label="Schriftart" @change="emitBlocks(applyFontFamily(blocks, $event.target.value))">
        <option v-for="font in fonts" :key="font" :value="font" :style="{ fontFamily: font }">{{ font }}</option>
      </select>
      <div class="segmented" role="radiogroup" aria-label="Textgröße">
        <button
          v-for="size in textSizes"
          :key="size.key"
          type="button"
          role="radio"
          :aria-checked="design.textSize === size.key"
          :class="{ active: design.textSize === size.key }"
          @click="emitBlocks(applyTextSize(blocks, size.key))"
        >
          {{ size.label }}
        </button>
      </div>
    </section>

    <section class="design-section">
      <h3>Anordnung</h3>
      <p class="section-hint">Empfänger und Dokumentdaten (Nummer, Datum).</p>
      <div class="segmented" role="radiogroup" aria-label="Anordnung von Empfänger und Dokumentdaten">
        <button
          v-for="layout in layouts"
          :key="layout.key"
          type="button"
          role="radio"
          :aria-checked="design.layout === layout.key"
          :class="{ active: design.layout === layout.key }"
          @click="emitBlocks(applyLayout(blocks, layout.key))"
        >
          {{ layout.label }}
        </button>
      </div>
    </section>

    <section class="design-section">
      <h3>Inhalte</h3>
      <label v-for="part in parts" :key="part.key" class="check-row">
        <input type="checkbox" :checked="design.parts[part.key]" @change="emitBlocks(setPartVisible(blocks, part.key, $event.target.checked))" />
        {{ part.label }}
      </label>
    </section>

    <section class="design-section">
      <h3>Seitenränder</h3>
      <div class="segmented" role="radiogroup" aria-label="Seitenränder">
        <button
          v-for="preset in marginPresets"
          :key="preset.key"
          type="button"
          role="radio"
          :aria-checked="marginPreset === preset.key"
          :class="{ active: marginPreset === preset.key }"
          @click="setAllMargins(preset.value)"
        >
          {{ preset.label }}
        </button>
      </div>
      <details class="custom-margins" :open="marginPreset === 'custom'">
        <summary>Eigene Ränder (mm)</summary>
        <div class="margin-grid">
          <label v-for="side in marginSides" :key="side.key">
            {{ side.label }}
            <input
              type="number"
              min="0"
              max="50"
              :value="margins[side.key]"
              @change="setMargin(side.key, $event.target.value)"
            />
          </label>
        </div>
      </details>
    </section>

    <p class="design-footnote">
      Firmenname, Adresse und IBAN kommen aus den
      <router-link v-if="hasRouter" to="/owners">Firmendaten</router-link><span v-else>Firmendaten</span>.
      Für einzelne Texte und Bezeichnungen wechseln Sie oben auf „Blöcke“.
    </p>
  </div>
</template>

<script>
import {
  readDesign, applyAccentColor, applyFontFamily, applyTextSize, setPartVisible, applyLogo, applyLayout,
  TEXT_SIZES, MARGIN_PRESETS, OPTIONAL_PARTS, LAYOUTS
} from '../../utils/designActions.js'
import { FONT_FAMILIES } from '../../utils/blockRegistry.js'
import { uploadLogo } from '../../services/uploadService.js'

/**
 * Simple mode of the template editor: the whole document's look in a few
 * controls (logo, accent color, font, text size, optional parts, margins).
 * It edits the same blocks as the advanced mode through `designActions`.
 *
 * Emits `update:blocks(blocks)`, `update:margins({ marginTopMm, ... })` and
 * `notify({ type, message })`.
 */
export default {
  name: 'DesignPanel',
  props: {
    blocks: { type: Array, required: true },
    /** `{ marginTopMm, marginRightMm, marginBottomMm, marginLeftMm }` */
    margins: { type: Object, required: true }
  },
  emits: ['update:blocks', 'update:margins', 'notify'],
  data() {
    return {
      uploading: false,
      fonts: FONT_FAMILIES,
      textSizes: TEXT_SIZES,
      marginPresets: MARGIN_PRESETS,
      parts: OPTIONAL_PARTS,
      layouts: LAYOUTS,
      accentColors: ['#2563eb', '#0f766e', '#7c3aed', '#be123c', '#c2410c', '#374151', '#111827'],
      marginSides: [
        { key: 'marginTopMm', label: 'Oben' },
        { key: 'marginRightMm', label: 'Rechts' },
        { key: 'marginBottomMm', label: 'Unten' },
        { key: 'marginLeftMm', label: 'Links' }
      ]
    }
  },
  computed: {
    design() {
      return readDesign(this.blocks)
    },
    marginPreset() {
      const values = this.marginSides.map(s => this.margins[s.key])
      const preset = this.marginPresets.find(p => values.every(v => v === p.value))
      return preset ? preset.key : 'custom'
    },
    hasRouter() {
      return !!this.$router
    }
  },
  methods: {
    applyAccentColor,
    applyFontFamily,
    applyTextSize,
    setPartVisible,
    applyLogo,
    applyLayout,
    emitBlocks(blocks) {
      this.$emit('update:blocks', blocks)
    },
    sameColor(a, b) {
      return String(a).toLowerCase() === String(b).toLowerCase()
    },
    setAllMargins(value) {
      this.$emit('update:margins', Object.fromEntries(this.marginSides.map(s => [s.key, value])))
    },
    setMargin(key, raw) {
      const value = Number.parseInt(raw, 10)
      if (Number.isNaN(value)) return
      this.$emit('update:margins', { [key]: Math.max(0, Math.min(50, value)) })
    },
    async onLogoFile(event) {
      const file = event.target.files[0]
      if (!file) return
      this.uploading = true
      try {
        this.emitBlocks(applyLogo(this.blocks, await uploadLogo(file)))
      } catch (error) {
        this.$emit('notify', { type: 'error', message: error.message })
      } finally {
        this.uploading = false
        event.target.value = ''
      }
    }
  }
}
</script>

<style scoped>
.design-panel {
  padding: 14px 14px 20px;
}

.design-section {
  margin-bottom: 20px;
}

.design-section h3 {
  margin: 0 0 8px;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
}

.section-hint {
  margin: -4px 0 8px;
  font-size: 11px;
  color: var(--color-text-muted);
}

.logo-row {
  display: flex;
  align-items: center;
  gap: 12px;
}

.logo-box {
  width: 96px;
  height: 56px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-surface);
  overflow: hidden;
}

.logo-box.empty {
  border-style: dashed;
  color: var(--color-text-subtle);
  font-size: 11px;
}

.logo-box img {
  max-width: 90%;
  max-height: 90%;
}

.logo-actions {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: flex-start;
}

.btn-secondary-sm {
  display: inline-block;
  padding: 6px 12px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  background: var(--color-surface);
  font-size: 12px;
  cursor: pointer;
  color: var(--color-text-strong);
}

.btn-secondary-sm input {
  display: none;
}

.btn-link {
  border: none;
  background: none;
  color: var(--color-danger-hover);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
}

.swatches {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.swatch {
  width: 28px;
  height: 28px;
  border-radius: 50%;
  border: 2px solid var(--color-surface);
  box-shadow: 0 0 0 1px var(--color-border-strong);
  cursor: pointer;
  padding: 0;
}

.swatch.active {
  box-shadow: 0 0 0 2px var(--color-text);
}

.swatch.custom {
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-surface-muted);
  color: var(--color-text-strong);
  font-size: 16px;
  position: relative;
  overflow: hidden;
}

.swatch.custom input {
  position: absolute;
  inset: 0;
  opacity: 0;
  cursor: pointer;
}

.form-select {
  width: 100%;
  padding: 7px 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  font-size: 13px;
  background: var(--color-surface);
  margin-bottom: 8px;
}

.segmented {
  display: flex;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  overflow: hidden;
}

.segmented button {
  flex: 1;
  padding: 6px 4px;
  border: none;
  border-right: 1px solid var(--color-border-strong);
  background: var(--color-surface);
  font-size: 12px;
  cursor: pointer;
  color: var(--color-text-strong);
}

.segmented button:last-child {
  border-right: none;
}

.segmented button.active {
  background: var(--color-primary-soft);
  color: var(--color-primary-hover);
  font-weight: 600;
}

.check-row {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 13px;
  color: var(--color-text-strong);
  padding: 4px 0;
  cursor: pointer;
}

.custom-margins {
  margin-top: 8px;
  font-size: 12px;
  color: var(--color-text-strong);
}

.custom-margins summary {
  cursor: pointer;
  color: var(--color-text-strong);
}

.margin-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 8px;
  margin-top: 8px;
}

.margin-grid label {
  display: flex;
  flex-direction: column;
  gap: 3px;
  font-size: 11px;
  color: var(--color-text-muted);
}

.margin-grid input {
  padding: 5px 8px;
  border: 1px solid var(--color-border-strong);
  border-radius: 5px;
  font-size: 13px;
}

.design-footnote {
  font-size: 11px;
  color: var(--color-text-muted);
  line-height: 1.5;
  border-top: 1px solid var(--color-border);
  padding-top: 12px;
  margin: 0;
}
</style>

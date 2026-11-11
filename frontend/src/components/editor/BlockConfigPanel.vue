<template>
  <aside class="block-config-panel" aria-label="Block bearbeiten">
    <div class="panel-header">
      <div>
        <h3>{{ title }}</h3>
        <p v-if="blockType && blockType.description" class="panel-subtitle">{{ blockType.description }}</p>
      </div>
      <button class="btn-close" type="button" title="Schließen (Esc)" aria-label="Schließen" @click="$emit('close')">
        <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
          <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
        </svg>
      </button>
    </div>

    <div class="panel-tabs" role="tablist">
      <button
        v-for="tab in tabs"
        :key="tab.key"
        type="button"
        role="tab"
        class="panel-tab"
        :class="{ active: activeTab === tab.key }"
        :aria-selected="activeTab === tab.key"
        @click="activeTab = tab.key"
      >
        {{ tab.label }}
      </button>
    </div>

    <div class="panel-content">
      <ul v-if="issues.length" class="block-issues">
        <li v-for="(issue, i) in issues" :key="i">{{ issue.message }}</li>
      </ul>

      <!-- Inhalt -->
      <template v-if="activeTab === 'content'">
        <p v-if="!fields.length" class="hint">
          Dieser Block zeigt automatisch die Daten des Dokuments. Bezeichnungen und Darstellung finden Sie unter „Darstellung“.
        </p>
        <div v-for="field in fields" :key="field.key" class="form-group">
          <label :for="fieldId(field)">{{ field.label }}</label>

          <VariableTextInput
            v-if="field.kind === 'text' || field.kind === 'multiline'"
            :id="fieldId(field)"
            :label="field.label"
            :model-value="stringValue(block.data[field.key])"
            :multiline="field.kind === 'multiline'"
            :placeholder="field.placeholder || ''"
            :preview-values="previewValues"
            @update:model-value="setData(field.key, $event)"
          />

          <select
            v-else-if="field.kind === 'select'"
            :id="fieldId(field)"
            class="form-select"
            :value="block.data[field.key]"
            @change="onSelectData(field.key, $event.target.value)"
          >
            <option v-for="opt in field.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
          </select>

          <div v-else-if="field.kind === 'image'" class="logo-upload">
            <div v-if="block.data.logoUrl" class="logo-preview">
              <img :src="block.data.logoUrl" alt="Logo" />
              <button type="button" class="btn-remove-logo" title="Logo entfernen" @click="setData('logoUrl', '')">
                <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
                  <line x1="18" y1="6" x2="6" y2="18"/><line x1="6" y1="6" x2="18" y2="18"/>
                </svg>
              </button>
            </div>
            <div
              class="logo-drop-zone"
              :class="{ 'drag-over': logoDragOver, uploading: logoUploading }"
              @dragover.prevent="logoDragOver = true"
              @dragleave.prevent="logoDragOver = false"
              @drop.prevent="handleLogoDrop"
            >
              <label class="btn-upload" :class="{ uploading: logoUploading }">
                <input
                  :id="fieldId(field)"
                  type="file"
                  accept="image/png,image/jpeg,image/gif"
                  :disabled="logoUploading"
                  @change="handleLogoUpload"
                />
                <span>{{ logoUploading ? 'Wird hochgeladen …' : (block.data.logoUrl ? 'Anderes Logo wählen' : 'Logo hochladen') }}</span>
              </label>
              <p class="drop-hint">oder Bild hierher ziehen (PNG, JPG, GIF)</p>
            </div>
          </div>

          <div v-else-if="field.kind === 'columns'" class="columns-editor">
            <div v-for="(col, index) in columns" :key="col.key" class="column-row">
              <input
                type="checkbox"
                :checked="col.show !== false"
                :aria-label="`Spalte ${col.label} anzeigen`"
                @change="setColumn(index, { show: $event.target.checked })"
              />
              <input
                type="text"
                class="form-input"
                :value="col.label"
                :aria-label="`Überschrift der Spalte ${col.key}`"
                @input="setColumn(index, { label: $event.target.value })"
              />
              <button type="button" class="btn-mini" :disabled="index === 0" title="Nach links" @click="moveColumn(index, -1)">↑</button>
              <button type="button" class="btn-mini" :disabled="index === columns.length - 1" title="Nach rechts" @click="moveColumn(index, 1)">↓</button>
            </div>
          </div>

          <p v-if="field.help" class="field-help">{{ field.help }}</p>
        </div>
      </template>

      <!-- Darstellung -->
      <template v-else>
        <div v-for="setting in visibleSettings" :key="setting.key" class="form-group">
          <label v-if="setting.kind === 'toggle'" class="toggle-label">
            <input
              :id="settingId(setting)"
              type="checkbox"
              :checked="!!block.config[setting.key]"
              @change="setConfig(setting.key, $event.target.checked)"
            />
            <span class="toggle-switch"></span>
            {{ setting.label }}
          </label>
          <template v-else>
            <label :for="settingId(setting)">{{ setting.label }}</label>
            <select
              v-if="setting.kind === 'select'"
              :id="settingId(setting)"
              class="form-select"
              :value="block.config[setting.key] ?? setting.default"
              @change="setConfig(setting.key, $event.target.value)"
            >
              <option v-for="opt in setting.options" :key="opt.value" :value="opt.value">{{ opt.label }}</option>
            </select>
            <div v-else-if="setting.kind === 'color'" class="color-wrap">
              <input
                :id="settingId(setting)"
                type="color"
                :value="block.config[setting.key]"
                @input="setConfig(setting.key, $event.target.value)"
              />
              <span class="color-value">{{ block.config[setting.key] }}</span>
            </div>
            <input
              v-else
              :id="settingId(setting)"
              type="text"
              class="form-input"
              :value="block.config[setting.key]"
              @input="setConfig(setting.key, $event.target.value)"
            />
          </template>
          <p v-if="setting.help" class="field-help">{{ setting.help }}</p>
        </div>

        <fieldset class="format-section">
          <legend>Schrift</legend>
          <div class="format-row">
            <label for="fmt-family">Schriftart</label>
            <select id="fmt-family" class="form-select" :value="format.fontFamily" @change="setFormat({ fontFamily: $event.target.value })">
              <option v-for="font in fontFamilies" :key="font" :value="font">{{ font }}</option>
            </select>
          </div>
          <div class="format-row">
            <label for="fmt-size">Größe</label>
            <div class="size-control">
              <button type="button" class="btn-mini" aria-label="Kleiner" @click="changeSize(-0.5)">−</button>
              <input
                id="fmt-size"
                type="number"
                class="form-input size-input"
                min="6"
                max="72"
                step="0.5"
                :value="format.fontSize"
                @change="setSize($event.target.value)"
              />
              <button type="button" class="btn-mini" aria-label="Größer" @click="changeSize(0.5)">+</button>
            </div>
          </div>
          <div class="format-row">
            <label for="fmt-color">Farbe</label>
            <div class="color-wrap">
              <input id="fmt-color" type="color" :value="format.fontColor" @input="setFormat({ fontColor: $event.target.value })" />
              <span class="color-value">{{ format.fontColor }}</span>
            </div>
          </div>
          <label class="toggle-label">
            <input type="checkbox" :checked="format.fontWeight === 'bold'" @change="setFormat({ fontWeight: $event.target.checked ? 'bold' : 'normal' })" />
            <span class="toggle-switch"></span>
            Fett
          </label>
        </fieldset>
      </template>
    </div>

    <div class="panel-footer">
      <button type="button" class="btn-footer" title="Block duplizieren" @click="$emit('duplicate')">Duplizieren</button>
      <button type="button" class="btn-footer danger" title="Block entfernen (Entf)" @click="$emit('remove')">Entfernen</button>
    </div>
  </aside>
</template>

<script>
import { uploadLogo } from '../../services/uploadService.js'
import VariableTextInput from './VariableTextInput.vue'
import { FONT_FAMILIES, getFormat, blockDisplayName } from '../../utils/blockRegistry.js'
import { clone } from '../../utils/blockModel.js'

/**
 * Side panel to edit the selected block.
 *
 * The form is generated from the block type's `fields` (tab "Inhalt") and
 * `settings` + font format (tab "Darstellung") in `blockRegistry.js`. Every
 * change is applied immediately: the panel emits `update` with a new block
 * object and keeps no copy of its own, so preview, undo history and the
 * unsaved-changes guard always see the current state.
 *
 * Emits: `update(block)`, `close`, `duplicate`, `remove`, `notify({type, message})`.
 */
export default {
  name: 'BlockConfigPanel',
  components: { VariableTextInput },
  props: {
    block: { type: Object, required: true },
    blockType: { type: Object, default: null },
    /** Preview values, shown as examples in the variable picker. */
    previewValues: { type: Object, default: () => ({}) },
    /** Validation issues of this block ({ message }). */
    issues: { type: Array, default: () => [] }
  },
  emits: ['update', 'close', 'duplicate', 'remove', 'notify'],
  data() {
    return {
      activeTab: 'content',
      logoUploading: false,
      logoDragOver: false,
      fontFamilies: FONT_FAMILIES
    }
  },
  computed: {
    title() {
      return blockDisplayName(this.block)
    },
    tabs() {
      return [
        { key: 'content', label: 'Inhalt' },
        { key: 'style', label: 'Darstellung' }
      ]
    },
    fields() {
      return (this.blockType && this.blockType.fields) || []
    },
    visibleSettings() {
      const settings = (this.blockType && this.blockType.settings) || []
      return settings.filter(s => !s.showIf || this.block.config[s.showIf])
    },
    format() {
      return getFormat(this.block.config, this.blockType && this.blockType.formatDefaults)
    },
    columns() {
      return Array.isArray(this.block.data.columns) ? this.block.data.columns : []
    }
  },
  watch: {
    'block.id'() {
      this.activeTab = 'content'
    }
  },
  methods: {
    fieldId(field) {
      return `field-${this.block.id}-${field.key}`
    },
    settingId(setting) {
      return `setting-${this.block.id}-${setting.key}`
    },
    stringValue(value) {
      return value == null ? '' : String(value)
    },

    /**
     * Emits the block with a change applied to a deep copy.
     * @param {(block: Object) => void} change
     */
    emitChange(change) {
      const next = clone(this.block)
      next.data = next.data || {}
      next.config = next.config || {}
      change(next)
      this.$emit('update', next)
    },
    setData(key, value) {
      this.emitChange(b => { b.data[key] = value })
    },
    setConfig(key, value) {
      this.emitChange(b => { b.config[key] = value })
    },
    setFormat(patch) {
      this.emitChange(b => { b.config.format = { ...this.format, ...patch } })
    },
    setSize(value) {
      const size = Number(value)
      if (Number.isFinite(size)) this.setFormat({ fontSize: Math.max(6, Math.min(72, size)) })
    },
    changeSize(delta) {
      this.setSize(this.format.fontSize + delta)
    },

    /**
     * Select fields. Switching an address between sender and receiver also
     * swaps the field defaults and the heading, as long as the user has not
     * changed them (otherwise their texts are kept).
     */
    onSelectData(key, value) {
      const presets = this.blockType && this.blockType.rolePresets
      if (key !== 'role' || !presets) {
        this.setData(key, value)
        return
      }
      const oldPreset = presets[this.block.data.role] || { data: {}, config: {} }
      const newPreset = presets[value] || { data: {}, config: {} }
      this.emitChange(b => {
        b.data.role = value
        for (const [field, oldValue] of Object.entries(oldPreset.data || {})) {
          if ((b.data[field] || '') === oldValue) b.data[field] = newPreset.data[field] ?? ''
        }
        if ((b.config.label || '') === ((oldPreset.config || {}).label || '')) {
          b.config.label = (newPreset.config || {}).label || ''
        }
      })
    },

    setColumn(index, patch) {
      this.emitChange(b => {
        b.data.columns = (b.data.columns || []).map((col, i) => (i === index ? { ...col, ...patch } : col))
      })
    },
    moveColumn(index, delta) {
      this.emitChange(b => {
        const cols = b.data.columns || []
        const target = index + delta
        if (target < 0 || target >= cols.length) return
        const [col] = cols.splice(index, 1)
        cols.splice(target, 0, col)
      })
    },

    async uploadLogoFile(file) {
      this.logoUploading = true
      try {
        this.setData('logoUrl', await uploadLogo(file))
      } catch (error) {
        this.$emit('notify', { type: 'error', message: error.message })
      } finally {
        this.logoUploading = false
      }
    },
    async handleLogoUpload(event) {
      const file = event.target.files[0]
      if (!file) return
      await this.uploadLogoFile(file)
      event.target.value = ''
    },
    async handleLogoDrop(event) {
      this.logoDragOver = false
      const file = event.dataTransfer.files[0]
      if (!file || !file.type.startsWith('image/')) return
      await this.uploadLogoFile(file)
    }
  }
}
</script>

<style scoped>
.block-config-panel {
  width: 340px;
  flex-shrink: 0;
  background: var(--color-surface);
  border-left: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
  padding: 14px 16px 10px;
}

.panel-header h3 {
  margin: 0;
  font-size: 15px;
  font-weight: 600;
  color: var(--color-text);
}

.panel-subtitle {
  margin: 2px 0 0;
  font-size: 12px;
  color: var(--color-text-muted);
}

.btn-close {
  width: 28px;
  height: 28px;
  border: none;
  background: transparent;
  cursor: pointer;
  color: var(--color-text-muted);
  padding: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  border-radius: 4px;
}

.btn-close:hover {
  background: var(--color-surface-muted);
  color: var(--color-text-strong);
}

.btn-close svg {
  width: 16px;
  height: 16px;
}

.panel-tabs {
  display: flex;
  gap: 4px;
  padding: 0 16px;
  border-bottom: 1px solid var(--color-border);
}

.panel-tab {
  padding: 8px 12px;
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  font-size: 13px;
  color: var(--color-text-muted);
  cursor: pointer;
}

.panel-tab.active {
  color: var(--color-primary-hover);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}

.panel-content {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

.block-issues {
  margin: 0 0 14px;
  padding: 10px 12px 10px 28px;
  background: var(--color-warning-soft);
  border: 1px solid var(--color-warning-border);
  border-radius: 6px;
  font-size: 12px;
  color: var(--color-warning-text);
}

.hint {
  font-size: 13px;
  color: var(--color-text-muted);
  line-height: 1.5;
  margin: 0;
}

.form-group {
  margin-bottom: 14px;
}

.form-group > label {
  display: block;
  margin-bottom: 5px;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.field-help {
  margin: 4px 0 0;
  font-size: 11px;
  color: var(--color-text-muted);
  line-height: 1.4;
}

.form-input,
.form-select {
  width: 100%;
  box-sizing: border-box;
  padding: 7px 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  font-size: 13px;
  background: var(--color-surface);
}

.form-input:focus,
.form-select:focus {
  outline: none;
  border-color: var(--color-primary);
  box-shadow: var(--shadow-focus);
}

.form-group > label.toggle-label,
.toggle-label {
  display: flex;
  margin-bottom: 0;
  align-items: center;
  gap: 10px;
  font-size: 13px;
  color: var(--color-text-strong);
  cursor: pointer;
}

.toggle-label input {
  position: absolute;
  opacity: 0;
  width: 0;
  height: 0;
}

.toggle-switch {
  position: relative;
  width: 34px;
  height: 18px;
  flex-shrink: 0;
  background: var(--color-border-strong);
  border-radius: 9px;
  transition: background 0.2s;
}

.toggle-switch::after {
  content: '';
  position: absolute;
  top: 2px;
  left: 2px;
  width: 14px;
  height: 14px;
  background: var(--color-surface);
  border-radius: 50%;
  transition: transform 0.2s;
}

.toggle-label input:checked + .toggle-switch {
  background: var(--color-primary);
}

.toggle-label input:checked + .toggle-switch::after {
  transform: translateX(16px);
}

.toggle-label input:focus-visible + .toggle-switch {
  box-shadow: var(--shadow-focus-strong);
}

.color-wrap {
  display: flex;
  align-items: center;
  gap: 8px;
}

.color-wrap input[type="color"] {
  width: 36px;
  height: 30px;
  padding: 0;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  cursor: pointer;
  background: none;
}

.color-value {
  font-size: 12px;
  color: var(--color-text-muted);
  font-family: monospace;
}

.format-section {
  margin: 18px 0 0;
  padding: 12px;
  border: 1px solid var(--color-border);
  border-radius: 8px;
}

.format-section legend {
  padding: 0 6px;
  font-size: 12px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.format-row {
  margin-bottom: 10px;
}

.format-row > label {
  display: block;
  font-size: 12px;
  color: var(--color-text-muted);
  margin-bottom: 4px;
}

.size-control {
  display: flex;
  align-items: center;
  gap: 6px;
}

.size-input {
  width: 70px;
  text-align: center;
}

.btn-mini {
  min-width: 28px;
  height: 28px;
  border: 1px solid var(--color-border-strong);
  border-radius: 5px;
  background: var(--color-surface);
  cursor: pointer;
  font-size: 14px;
  color: var(--color-text-strong);
}

.btn-mini:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-mini:hover:not(:disabled) {
  background: var(--color-surface-muted);
}

.columns-editor {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.column-row {
  display: flex;
  align-items: center;
  gap: 6px;
}

.logo-preview {
  position: relative;
  display: inline-block;
  padding: 8px;
  margin-bottom: 8px;
  border: 1px solid var(--color-border);
  border-radius: 6px;
}

.logo-preview img {
  max-height: 60px;
  max-width: 200px;
  display: block;
}

.btn-remove-logo {
  position: absolute;
  top: -8px;
  right: -8px;
  width: 22px;
  height: 22px;
  border-radius: 50%;
  border: 1px solid var(--color-border-strong);
  background: var(--color-surface);
  cursor: pointer;
  padding: 3px;
  color: var(--color-danger);
}

.logo-drop-zone {
  padding: 14px;
  border: 2px dashed var(--color-border-strong);
  border-radius: 8px;
  text-align: center;
  transition: all 0.2s;
}

.logo-drop-zone.drag-over {
  border-color: var(--color-primary);
  background: var(--color-primary-soft);
}

.btn-upload {
  display: inline-block;
  padding: 7px 14px;
  background: var(--color-primary);
  color: var(--color-on-primary);
  border-radius: 6px;
  font-size: 13px;
  cursor: pointer;
}

.btn-upload input {
  display: none;
}

.btn-upload.uploading {
  opacity: 0.6;
  cursor: wait;
}

.drop-hint {
  margin: 6px 0 0;
  font-size: 11px;
  color: var(--color-text-muted);
}

.panel-footer {
  display: flex;
  gap: 8px;
  padding: 12px 16px;
  border-top: 1px solid var(--color-border);
}

.btn-footer {
  flex: 1;
  padding: 8px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  background: var(--color-surface);
  font-size: 13px;
  cursor: pointer;
  color: var(--color-text-strong);
}

.btn-footer:hover {
  background: var(--color-surface-subtle);
}

.btn-footer.danger {
  color: var(--color-danger-hover);
  border-color: var(--color-danger-border);
}

.btn-footer.danger:hover {
  background: var(--color-danger-soft);
}

@media (max-width: 900px) {
  .block-config-panel {
    width: 100%;
    border-left: none;
    border-top: 1px solid var(--color-border);
    max-height: 50vh;
  }
}
</style>

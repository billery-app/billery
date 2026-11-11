<template>
  <div class="variable-picker" role="dialog" aria-label="Platzhalter einfügen" @keydown="onKeydown" @focusout="onFocusOut">
    <input
      ref="search"
      v-model="query"
      class="picker-search"
      type="text"
      placeholder="Suchen, z. B. Kunde, Datum …"
      aria-label="Platzhalter suchen"
    />
    <div class="picker-list" role="listbox">
      <template v-for="group in filteredGroups" :key="group.key">
        <div class="picker-group">{{ group.label }}</div>
        <button
          v-for="variable in group.variables"
          :key="variable.name"
          type="button"
          class="picker-item"
          :class="{ active: flat[activeIndex] && flat[activeIndex].name === variable.name }"
          role="option"
          :aria-selected="flat[activeIndex] && flat[activeIndex].name === variable.name"
          @mousedown.prevent="pick(variable.name)"
        >
          {{ variable.label }}
          <span v-if="sampleValue(variable.name)" class="picker-sample">{{ sampleValue(variable.name) }}</span>
        </button>
      </template>
      <p v-if="flat.length === 0" class="picker-empty">Kein Platzhalter gefunden.</p>
    </div>
  </div>
</template>

<script>
import { getPickerGroups } from '../../utils/variableRegistry.js'

/**
 * Searchable list of the variables a template text can contain, grouped by
 * source (Ihre Firma, Kunde, Dokument, ...). Shows the value the preview uses
 * next to each entry so users see what they insert. Keyboard: ↑/↓, Enter, Esc.
 *
 * Emits `pick(name)` with the variable name, `close` (Esc: focus goes back
 * to the field) or `dismiss` (focus moved elsewhere, e.g. a click outside).
 */
export default {
  name: 'VariablePicker',
  props: {
    /** Preview values shown as examples (variable name → formatted value). */
    values: { type: Object, default: () => ({}) }
  },
  emits: ['pick', 'close', 'dismiss'],
  data() {
    return { query: '', activeIndex: 0 }
  },
  computed: {
    filteredGroups() {
      const q = this.query.trim().toLowerCase()
      return getPickerGroups()
        .map(group => ({
          ...group,
          variables: group.variables.filter(v =>
            !q || v.label.toLowerCase().includes(q) || group.label.toLowerCase().includes(q) || v.name.includes(q))
        }))
        .filter(group => group.variables.length > 0)
    },
    flat() {
      return this.filteredGroups.flatMap(group => group.variables)
    }
  },
  watch: {
    query() {
      this.activeIndex = 0
    }
  },
  mounted() {
    this.$nextTick(() => this.$refs.search && this.$refs.search.focus())
  },
  methods: {
    /**
     * Example value of a variable, shortened for the list.
     * @param {string} name
     * @returns {string}
     */
    sampleValue(name) {
      const value = this.values[name]
      if (value === undefined || value === null || value === '') return ''
      const text = String(value).replace(/\s+/g, ' ')
      return text.length > 28 ? `${text.slice(0, 27)}…` : text
    },
    onFocusOut(event) {
      if (!this.$el.contains(event.relatedTarget)) this.$emit('dismiss')
    },
    pick(name) {
      this.$emit('pick', name)
    },
    onKeydown(event) {
      if (event.key === 'ArrowDown') {
        event.preventDefault()
        this.activeIndex = Math.min(this.flat.length - 1, this.activeIndex + 1)
      } else if (event.key === 'ArrowUp') {
        event.preventDefault()
        this.activeIndex = Math.max(0, this.activeIndex - 1)
      } else if (event.key === 'Enter') {
        event.preventDefault()
        if (this.flat[this.activeIndex]) this.pick(this.flat[this.activeIndex].name)
      } else if (event.key === 'Escape') {
        event.preventDefault()
        event.stopPropagation()
        this.$emit('close')
      }
    }
  }
}
</script>

<style scoped>
.variable-picker {
  position: absolute;
  z-index: 50;
  top: calc(100% + 4px);
  left: 0;
  right: 0;
  min-width: 240px;
  background: var(--color-surface);
  border: 1px solid var(--color-border-strong);
  border-radius: 8px;
  box-shadow: var(--shadow-lg);
  padding: 8px;
}

.picker-search {
  width: 100%;
  box-sizing: border-box;
  padding: 7px 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  font-size: 13px;
  margin-bottom: 6px;
}

.picker-list {
  max-height: 260px;
  overflow-y: auto;
}

.picker-group {
  font-size: 11px;
  font-weight: 700;
  text-transform: uppercase;
  color: var(--color-text-muted);
  padding: 8px 6px 4px;
}

.picker-item {
  display: flex;
  justify-content: space-between;
  gap: 8px;
  width: 100%;
  padding: 6px 8px;
  border: none;
  background: none;
  border-radius: 5px;
  font-size: 13px;
  text-align: left;
  cursor: pointer;
  color: var(--color-text);
}

.picker-item:hover,
.picker-item.active {
  background: var(--color-primary-soft);
  color: var(--color-primary-hover);
}

.picker-sample {
  color: var(--color-text-subtle);
  font-size: 12px;
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.picker-empty {
  font-size: 13px;
  color: var(--color-text-muted);
  padding: 8px;
  margin: 0;
}
</style>

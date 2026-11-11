<template>
  <div class="variable-text-input" :class="{ multiline, focused }">
    <div class="field-row">
      <div
        :id="id"
        ref="editable"
        class="editable"
        contenteditable="true"
        role="textbox"
        :aria-multiline="multiline ? 'true' : 'false'"
        :aria-label="label"
        :data-placeholder="placeholder"
        :class="{ empty: !modelValue }"
        spellcheck="true"
        @input="onInput"
        @keydown="onKeydown"
        @paste="onPaste"
        @focus="focused = true"
        @blur="onBlur"
        @keyup="saveSelection"
        @mouseup="saveSelection"
      ></div>
      <button
        type="button"
        class="btn-insert"
        title="Platzhalter einfügen (oder @ tippen)"
        aria-label="Platzhalter einfügen"
        @mousedown.prevent="openPicker"
      >
        + Platzhalter
      </button>
    </div>
    <VariablePicker
      v-if="pickerOpen"
      :values="previewValues"
      @pick="insertVariable"
      @close="closePicker"
      @dismiss="pickerOpen = false"
    />
  </div>
</template>

<script>
import VariablePicker from './VariablePicker.vue'
import { parseVariableText } from '../../utils/variableText.js'
import { getChipLabel, isKnownVariable } from '../../utils/variableRegistry.js'

/**
 * Text field for template texts that may contain variables.
 *
 * Users never see `{{curly_braces}}`: every variable is shown as a chip with a
 * German label ("Kundenname"). Chips are inserted at the cursor through the
 * "+ Platzhalter" button or by typing `@` (the `@` is typed normally, so
 * e-mail addresses still work; picking a variable replaces it), and are
 * deleted as one unit with Backspace. Unknown variables (typos in old templates) appear as red chips.
 *
 * The stored value stays the plain `{{name}}` string the PDF renderer reads;
 * the DOM is rebuilt from `modelValue` only when the value changes from the
 * outside, so typing never moves the cursor.
 */
export default {
  name: 'VariableTextInput',
  components: { VariablePicker },
  props: {
    modelValue: { type: String, default: '' },
    /** Enter inserts a line break (otherwise Enter is ignored). */
    multiline: { type: Boolean, default: false },
    placeholder: { type: String, default: '' },
    id: { type: String, default: undefined },
    /** Accessible name of the field. */
    label: { type: String, default: '' },
    /** Preview values shown next to the picker entries. */
    previewValues: { type: Object, default: () => ({}) }
  },
  emits: ['update:modelValue'],
  data() {
    return {
      focused: false,
      pickerOpen: false,
      /** Last value this component emitted; external changes differ from it. */
      lastEmitted: null,
      savedRange: null,
      /** The picker was opened by typing `@`; picking replaces that character. */
      openedByAt: false
    }
  },
  watch: {
    modelValue(value) {
      if (value !== this.lastEmitted) this.render()
    }
  },
  mounted() {
    this.render()
  },
  methods: {
    /** Rebuilds the editable DOM from `modelValue`. */
    render() {
      const root = this.$refs.editable
      if (!root) return
      root.textContent = ''
      for (const segment of parseVariableText(this.modelValue)) {
        root.appendChild(segment.type === 'var' ? this.createChip(segment.name) : document.createTextNode(segment.value))
      }
      this.lastEmitted = this.modelValue
    },

    /**
     * Chip element for one variable.
     * @param {string} name
     * @returns {HTMLElement}
     */
    createChip(name) {
      const chip = document.createElement('span')
      chip.className = isKnownVariable(name) ? 'var-chip' : 'var-chip unknown'
      chip.setAttribute('contenteditable', 'false')
      chip.dataset.var = name
      chip.textContent = getChipLabel(name)
      chip.title = isKnownVariable(name)
        ? 'Wird beim Erstellen des Dokuments ersetzt'
        : `Unbekannter Platzhalter „${name}“: wird nicht gedruckt`
      return chip
    },

    /**
     * Reads the stored string back from the DOM: chips become `{{name}}`,
     * `<br>` and block elements (browsers insert `<div>` on Enter) become `\n`.
     * @returns {string}
     */
    serialize() {
      const root = this.$refs.editable
      let out = ''
      const walk = (node) => {
        for (const child of node.childNodes) {
          if (child.nodeType === Node.TEXT_NODE) {
            out += child.nodeValue.replace(/ /g, ' ')
          } else if (child.nodeType === Node.ELEMENT_NODE) {
            if (child.dataset && child.dataset.var) {
              out += `{{${child.dataset.var}}}`
            } else if (child.tagName === 'BR') {
              out += '\n'
            } else {
              const isBlock = child.tagName === 'DIV' || child.tagName === 'P'
              if (isBlock && out && !out.endsWith('\n')) out += '\n'
              walk(child)
            }
          }
        }
      }
      if (root) walk(root)
      if (!this.multiline) out = out.replace(/\n/g, ' ')
      return out
    },

    emitValue() {
      const value = this.serialize()
      this.lastEmitted = value
      this.$emit('update:modelValue', value)
    },

    onInput(event) {
      this.emitValue()
      if (event && event.data === '@') {
        this.saveSelection()
        this.openedByAt = true
        this.pickerOpen = true
      }
    },

    onBlur() {
      this.focused = false
      // Turn typed `{{name}}` into chips once the user leaves the field.
      if (/\{\{[^{}]+\}\}/.test(Array.from(this.$refs.editable.childNodes)
        .filter(n => n.nodeType === Node.TEXT_NODE).map(n => n.nodeValue).join(''))) {
        this.render()
      }
    },

    /** Remembers the cursor so a picked variable lands where the user was typing. */
    saveSelection() {
      const selection = window.getSelection && window.getSelection()
      if (selection && selection.rangeCount > 0 && this.$refs.editable.contains(selection.anchorNode)) {
        this.savedRange = selection.getRangeAt(0).cloneRange()
      }
    },

    onKeydown(event) {
      if (event.key === 'Enter') {
        event.preventDefault()
        if (this.multiline) this.insertNodeAtCursor(document.createTextNode('\n'))
        return
      }
    },

    onPaste(event) {
      event.preventDefault()
      const text = (event.clipboardData && event.clipboardData.getData('text/plain')) || ''
      this.insertNodeAtCursor(document.createTextNode(this.multiline ? text : text.replace(/\r?\n/g, ' ')))
    },

    openPicker() {
      this.saveSelection()
      this.openedByAt = false
      this.pickerOpen = true
    },

    closePicker() {
      this.pickerOpen = false
      this.restoreFocus()
    },

    /**
     * Inserts a variable chip at the saved cursor position (or at the end).
     * @param {string} name
     */
    insertVariable(name) {
      this.pickerOpen = false
      this.restoreFocus()
      if (this.openedByAt) this.removeCharBeforeCursor('@')
      this.openedByAt = false
      this.insertNodeAtCursor(this.createChip(name))
    },

    /**
     * Deletes the character before the cursor when it is `char`.
     * @param {string} char
     */
    removeCharBeforeCursor(char) {
      const selection = window.getSelection && window.getSelection()
      if (!selection || selection.rangeCount === 0) return
      const range = selection.getRangeAt(0)
      const node = range.startContainer
      const offset = range.startOffset
      if (node.nodeType === Node.TEXT_NODE && offset > 0 && node.nodeValue[offset - 1] === char) {
        node.deleteData(offset - 1, 1)
        range.setStart(node, offset - 1)
        range.collapse(true)
        selection.removeAllRanges()
        selection.addRange(range)
      }
    },

    restoreFocus() {
      const root = this.$refs.editable
      if (!root) return
      root.focus()
      const selection = window.getSelection && window.getSelection()
      if (!selection) return
      // No remembered cursor (the field was never focused): insert at the end.
      let range = this.savedRange
      if (!range || !root.contains(range.startContainer)) {
        range = document.createRange()
        range.selectNodeContents(root)
        range.collapse(false)
      }
      selection.removeAllRanges()
      selection.addRange(range)
    },

    /**
     * Inserts a node at the cursor (or appends it), moves the cursor behind it
     * and emits the new value.
     * @param {Node} node
     */
    insertNodeAtCursor(node) {
      const root = this.$refs.editable
      const selection = window.getSelection && window.getSelection()
      let range = selection && selection.rangeCount > 0 ? selection.getRangeAt(0) : null
      if (!range || !root.contains(range.commonAncestorContainer)) {
        range = document.createRange()
        range.selectNodeContents(root)
        range.collapse(false)
      }
      range.deleteContents()
      range.insertNode(node)
      // A trailing text node keeps the cursor placeable behind a chip / line break.
      if (!node.nextSibling) node.parentNode.appendChild(document.createTextNode(''))
      range.setStartAfter(node)
      range.collapse(true)
      if (selection) {
        selection.removeAllRanges()
        selection.addRange(range)
      }
      this.savedRange = range.cloneRange()
      this.emitValue()
    }
  }
}
</script>

<style scoped>
.variable-text-input {
  position: relative;
}

.field-row {
  display: flex;
  gap: 6px;
  align-items: flex-start;
}

.editable {
  flex: 1;
  min-width: 0;
  min-height: 34px;
  box-sizing: border-box;
  padding: 6px 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  background: var(--color-surface);
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  outline: none;
  cursor: text;
}

.multiline .editable {
  min-height: 72px;
}

.editable:focus {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-focus);
}

.editable.empty::before {
  content: attr(data-placeholder);
  color: var(--color-text-subtle);
  pointer-events: none;
}

.editable :deep(.var-chip) {
  display: inline-block;
  padding: 0 7px;
  margin: 0 1px;
  border-radius: 10px;
  background: var(--color-primary-tint);
  color: var(--color-primary-active);
  font-size: 12px;
  font-weight: 500;
  line-height: 1.6;
  user-select: all;
  white-space: nowrap;
}

.editable :deep(.var-chip.unknown) {
  background: var(--color-danger-soft);
  color: var(--color-danger-hover);
  text-decoration: line-through;
}

.btn-insert {
  flex-shrink: 0;
  padding: 6px 8px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  background: var(--color-surface-subtle);
  color: var(--color-text-strong);
  font-size: 12px;
  cursor: pointer;
  white-space: nowrap;
}

.btn-insert:hover {
  background: var(--color-primary-soft);
  border-color: var(--color-primary-border);
  color: var(--color-primary-hover);
}
</style>

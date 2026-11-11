<template>
  <div class="block-library">
    <h3 class="library-title">Block hinzufügen</h3>
    <p class="library-hint">Klicken oder in die Vorschau ziehen</p>
    <div class="block-list">
      <div
        v-for="block in availableBlocks"
        :key="block.type"
        class="block-item"
        :class="{ disabled: !isAddable(block) }"
        :draggable="isAddable(block) ? 'true' : 'false'"
        role="button"
        :tabindex="isAddable(block) ? 0 : -1"
        :aria-disabled="!isAddable(block)"
        :title="isAddable(block) ? block.description : `${block.name} ist bereits vorhanden`"
        @dragstart="onDragStart($event, block)"
        @dragend="onDragEnd"
        @click="add(block)"
        @keydown.enter.prevent="add(block)"
      >
        <div class="block-icon">
          <svg v-if="block.icon === 'header'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="3" width="18" height="6" rx="1"/>
            <line x1="3" y1="13" x2="21" y2="13"/>
          </svg>
          <svg v-else-if="block.icon === 'address'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M3 9l9-7 9 7v11a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2z"/>
            <polyline points="9 22 9 12 15 12 15 22"/>
          </svg>
          <svg v-else-if="block.icon === 'info'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <circle cx="12" cy="12" r="10"/>
            <line x1="12" y1="16" x2="12" y2="12"/>
            <line x1="12" y1="8" x2="12.01" y2="8"/>
          </svg>
          <svg v-else-if="block.icon === 'table'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="3" width="18" height="18" rx="2"/>
            <line x1="3" y1="9" x2="21" y2="9"/>
            <line x1="3" y1="15" x2="21" y2="15"/>
            <line x1="9" y1="3" x2="9" y2="21"/>
            <line x1="15" y1="3" x2="15" y2="21"/>
          </svg>
          <svg v-else-if="block.icon === 'calculator'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="4" y="2" width="16" height="20" rx="2"/>
            <line x1="8" y1="6" x2="16" y2="6"/>
            <line x1="8" y1="10" x2="8" y2="10"/>
            <line x1="12" y1="10" x2="12" y2="10"/>
            <line x1="16" y1="10" x2="16" y2="10"/>
            <line x1="8" y1="14" x2="8" y2="14"/>
            <line x1="12" y1="14" x2="12" y2="14"/>
            <line x1="16" y1="14" x2="16" y2="14"/>
            <line x1="8" y1="18" x2="8" y2="18"/>
            <line x1="12" y1="18" x2="12" y2="18"/>
            <line x1="16" y1="18" x2="16" y2="18"/>
          </svg>
          <svg v-else-if="block.icon === 'payment'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="1" y="4" width="22" height="16" rx="2"/>
            <line x1="1" y1="10" x2="23" y2="10"/>
          </svg>
          <svg v-else-if="block.icon === 'footer'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="15" width="18" height="6" rx="1"/>
            <line x1="3" y1="3" x2="21" y2="3"/>
          </svg>
          <svg v-else-if="block.icon === 'notes'" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <path d="M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z"/>
            <polyline points="14 2 14 8 20 8"/>
            <line x1="12" y1="18" x2="12" y2="12"/>
            <line x1="9" y1="15" x2="15" y2="15"/>
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2">
            <rect x="3" y="3" width="18" height="18" rx="2"/>
          </svg>
        </div>
        <span class="block-text">
          <span class="block-name">{{ block.name }}</span>
          <span v-if="block.description" class="block-description">{{ block.description }}</span>
        </span>
      </div>
    </div>
  </div>
</template>

<script>
import { canAddBlock } from '../../utils/blockValidation.js'

/**
 * Palette of block types. A block type whose limit is reached (e.g. a second
 * totals table) is shown disabled, so users cannot build an invalid template.
 *
 * Emits `add-block(blockType)`; block types can also be dragged into the
 * preview or the outline (`dataTransfer` type `block-type`).
 */
export default {
  name: 'BlockLibrary',
  props: {
    availableBlocks: {
      type: Array,
      required: true
    },
    /** Blocks of the template, to disable types that reached their limit. */
    blocks: {
      type: Array,
      default: () => []
    }
  },
  emits: ['add-block'],
  methods: {
    isAddable(block) {
      return canAddBlock(this.blocks, block.type)
    },
    add(block) {
      if (this.isAddable(block)) this.$emit('add-block', block)
    },
    onDragStart(event, block) {
      if (!this.isAddable(block)) {
        event.preventDefault()
        return
      }
      event.dataTransfer.setData('block-type', JSON.stringify(block))
      event.dataTransfer.setData('text/plain', block.name)
      event.dataTransfer.effectAllowed = 'copy'
      event.target.classList.add('dragging')
    },
    onDragEnd(event) {
      event.target.classList.remove('dragging')
    }
  }
}
</script>

<style scoped>
.block-library {
  padding: 12px;
}

.library-title {
  margin: 0 0 4px 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text-strong);
}

.library-hint {
  margin: 0 0 12px 0;
  font-size: 11px;
  color: var(--color-text-muted);
}

.block-list {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.block-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 10px;
  background: var(--color-surface);
  border: 1px solid var(--color-border);
  border-radius: 6px;
  cursor: pointer;
  transition: all 0.2s;
}

.block-item:hover {
  border-color: var(--color-primary);
  box-shadow: var(--shadow-sm);
}

.block-item.dragging {
  opacity: 0.5;
  border-color: var(--color-primary);
}

.block-icon {
  flex-shrink: 0;
  width: 20px;
  height: 20px;
  color: var(--color-text-muted);
}

.block-icon svg {
  width: 100%;
  height: 100%;
}

.block-item:hover .block-icon {
  color: var(--color-primary);
}

.block-text {
  display: flex;
  flex-direction: column;
  min-width: 0;
}

.block-name {
  font-size: 13px;
  color: var(--color-text-strong);
}

.block-description {
  font-size: 11px;
  color: var(--color-text-subtle);
  white-space: nowrap;
  overflow: hidden;
  text-overflow: ellipsis;
}

.block-item.disabled {
  opacity: 0.45;
  cursor: not-allowed;
}

.block-item.disabled:hover {
  border-color: var(--color-border);
  box-shadow: none;
}

.block-item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}
</style>

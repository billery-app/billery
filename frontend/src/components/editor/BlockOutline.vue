<template>
  <div class="block-outline">
    <h3 class="outline-title">Aufbau</h3>
    <p class="outline-hint">Reihenfolge per Ziehen oder mit den Pfeilen ändern.</p>
    <ol class="outline-list" role="listbox" aria-label="Blöcke des Dokuments">
      <li
        v-for="(block, index) in blocks"
        :key="block.id"
        class="outline-item"
        :class="{
          selected: block.id === selectedId,
          'drop-before': dropIndex === index && dragFrom !== index,
          dragging: dragFrom === index
        }"
        role="option"
        :aria-selected="block.id === selectedId"
        tabindex="0"
        draggable="true"
        @click="$emit('select', block.id)"
        @keydown="onKeydown($event, index, block)"
        @dragstart="onDragStart($event, index)"
        @dragover.prevent="onDragOver($event, index)"
        @drop.prevent="onDrop($event, index)"
        @dragend="resetDrag"
      >
        <span class="drag-handle" aria-hidden="true">⋮⋮</span>
        <span class="item-name">{{ displayName(block) }}</span>
        <span v-if="issueCount(block)" class="issue-badge" :title="issueTitle(block)">!</span>
        <span class="item-actions">
          <button type="button" :disabled="index === 0" title="Nach oben" aria-label="Nach oben" @click.stop="$emit('move', index, index - 1)">↑</button>
          <button type="button" :disabled="index === blocks.length - 1" title="Nach unten" aria-label="Nach unten" @click.stop="$emit('move', index, index + 1)">↓</button>
        </span>
      </li>
      <li
        class="outline-end"
        :class="{ 'drop-before': dropIndex === blocks.length }"
        @dragover.prevent="onDragOver($event, blocks.length)"
        @drop.prevent="onDrop($event, blocks.length)"
      ></li>
    </ol>
  </div>
</template>

<script>
import { blockDisplayName } from '../../utils/blockRegistry.js'

/** dataTransfer type of an outline drag (moving an existing block). */
export const MOVE_TYPE = 'application/x-template-block-index'

/**
 * Ordered list of the template's blocks (the "layers" of the document).
 * Selecting an entry selects the block in the preview; entries can be
 * reordered by drag and drop, the arrow buttons or Alt+↑/↓, and removed with
 * Delete. Blocks with validation issues carry a badge.
 *
 * Emits: `select(id)`, `move(from, to)`, `remove(id)`, `add(type, index)` (a
 * block dragged in from the library).
 */
export default {
  name: 'BlockOutline',
  props: {
    blocks: { type: Array, required: true },
    selectedId: { type: String, default: null },
    /** Validation issues ({ blockId, message }). */
    issues: { type: Array, default: () => [] }
  },
  emits: ['select', 'move', 'remove', 'add'],
  data() {
    return { dragFrom: null, dropIndex: null }
  },
  methods: {
    displayName: blockDisplayName,
    issueCount(block) {
      return this.issues.filter(i => i.blockId === block.id).length
    },
    issueTitle(block) {
      return this.issues.filter(i => i.blockId === block.id).map(i => i.message).join('\n')
    },
    onKeydown(event, index, block) {
      if (event.altKey && event.key === 'ArrowUp') {
        event.preventDefault()
        this.$emit('move', index, index - 1)
      } else if (event.altKey && event.key === 'ArrowDown') {
        event.preventDefault()
        this.$emit('move', index, index + 1)
      } else if (event.key === 'Delete') {
        event.preventDefault()
        this.$emit('remove', block.id)
      } else if (event.key === 'Enter' || event.key === ' ') {
        event.preventDefault()
        this.$emit('select', block.id)
      }
    },
    onDragStart(event, index) {
      this.dragFrom = index
      event.dataTransfer.effectAllowed = 'move'
      event.dataTransfer.setData(MOVE_TYPE, String(index))
      event.dataTransfer.setData('text/plain', this.displayName(this.blocks[index]))
    },
    onDragOver(event, index) {
      const types = Array.from(event.dataTransfer.types || [])
      event.dataTransfer.dropEffect = types.includes(MOVE_TYPE) ? 'move' : 'copy'
      this.dropIndex = index
    },
    onDrop(event, index) {
      const moved = event.dataTransfer.getData(MOVE_TYPE)
      const blockType = event.dataTransfer.getData('block-type')
      if (moved !== '') {
        const from = Number(moved)
        // Dropping "before index" after removing `from` shifts later targets by one.
        const to = from < index ? index - 1 : index
        if (from !== to) this.$emit('move', from, to)
      } else if (blockType) {
        try {
          this.$emit('add', JSON.parse(blockType).type, index)
        } catch {
          // not a block from the library
        }
      }
      this.resetDrag()
    },
    resetDrag() {
      this.dragFrom = null
      this.dropIndex = null
    }
  }
}
</script>

<style scoped>
.block-outline {
  padding: 14px 12px 6px;
}

.outline-title {
  margin: 0;
  font-size: 13px;
  font-weight: 600;
  color: var(--color-text);
}

.outline-hint {
  margin: 2px 0 10px;
  font-size: 11px;
  color: var(--color-text-muted);
}

.outline-list {
  list-style: none;
  margin: 0;
  padding: 0;
}

.outline-item {
  position: relative;
  display: flex;
  align-items: center;
  gap: 6px;
  padding: 7px 8px;
  margin-bottom: 3px;
  border: 1px solid transparent;
  border-radius: 6px;
  font-size: 13px;
  color: var(--color-text-strong);
  cursor: pointer;
  user-select: none;
}

.outline-item:hover {
  background: var(--color-surface-muted);
}

.outline-item:focus-visible {
  outline: 2px solid var(--color-primary);
  outline-offset: 1px;
}

.outline-item.selected {
  background: var(--color-primary-soft);
  border-color: var(--color-primary-border);
  color: var(--color-primary-active);
  font-weight: 600;
}

.outline-item.dragging {
  opacity: 0.4;
}

.outline-item.drop-before::before,
.outline-end.drop-before::before {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  top: -3px;
  height: 3px;
  border-radius: 2px;
  background: var(--color-primary);
}

.outline-end {
  position: relative;
  height: 14px;
}

.drag-handle {
  color: var(--color-text-subtle);
  font-size: 11px;
  letter-spacing: -2px;
  cursor: grab;
}

.item-name {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.issue-badge {
  width: 16px;
  height: 16px;
  border-radius: 50%;
  background: var(--color-warning);
  color: var(--color-on-primary);
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
}

.item-actions {
  display: flex;
  gap: 2px;
  opacity: 0;
}

.outline-item:hover .item-actions,
.outline-item.selected .item-actions,
.outline-item:focus-within .item-actions {
  opacity: 1;
}

.item-actions button {
  width: 22px;
  height: 22px;
  border: 1px solid var(--color-border-strong);
  border-radius: 4px;
  background: var(--color-surface);
  font-size: 12px;
  cursor: pointer;
  padding: 0;
  color: var(--color-text-strong);
}

.item-actions button:disabled {
  opacity: 0.35;
  cursor: not-allowed;
}
</style>

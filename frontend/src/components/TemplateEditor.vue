<template>
  <div class="template-editor" @keydown="onKeydown">
    <!-- Top bar: what the template is, history, save -->
    <header class="editor-header">
      <div class="header-fields">
        <input
          v-model="form.name"
          class="name-input"
          type="text"
          placeholder="Name der Vorlage"
          aria-label="Name der Vorlage"
          maxlength="120"
        />
        <select
          v-if="!isDocumentContext"
          class="type-select"
          :value="form.documentTypeId"
          aria-label="Dokumenttyp"
          @change="changeDocumentType(Number($event.target.value))"
        >
          <option v-for="dt in documentTypeOptions" :key="dt.id" :value="dt.id">{{ dt.label }}</option>
        </select>
        <label v-if="!isDocumentContext" class="default-check">
          <input v-model="form.isDefault" type="checkbox" />
          Standard für {{ documentTypeLabel }}
        </label>
        <span v-else class="doc-specific-badge" title="Diese Vorlage gilt nur für ein Dokument">Nur für dieses Dokument</span>
      </div>

      <div class="header-actions">
        <button type="button" class="btn-icon" :disabled="!history.canUndo.value" title="Rückgängig (Strg+Z)" aria-label="Rückgängig" @click="undo">↶</button>
        <button type="button" class="btn-icon" :disabled="!history.canRedo.value" title="Wiederholen (Strg+Y)" aria-label="Wiederholen" @click="redo">↷</button>
        <span class="header-divider"></span>
        <button type="button" class="btn btn-outline" :disabled="pdfLoading || validation.errors.length > 0" title="So sieht das fertige PDF aus" @click="openPdfPreview">
          {{ pdfLoading ? 'PDF wird erstellt …' : 'PDF-Vorschau' }}
        </button>
        <button type="button" class="btn btn-secondary" @click="handleCancelRequest">Abbrechen</button>
        <button type="button" class="btn btn-primary" :disabled="!canSave" :title="saveHint" @click="saveTemplate">Speichern</button>
      </div>
    </header>

    <div class="mobile-workspace-tabs" role="tablist" aria-label="Editorbereich">
      <button
        type="button"
        role="tab"
        aria-controls="template-editor-preview"
        :aria-selected="mobileWorkspace === 'preview'"
        :class="{ active: mobileWorkspace === 'preview' }"
        @click="setMobileWorkspace('preview')"
      >Vorschau</button>
      <button
        type="button"
        role="tab"
        aria-controls="template-editor-tools"
        :aria-selected="mobileWorkspace === 'design'"
        :class="{ active: mobileWorkspace === 'design' }"
        @click="setMobileWorkspace('design')"
      >Werkzeuge</button>
      <button
        type="button"
        role="tab"
        aria-controls="template-editor-block-settings"
        :aria-selected="mobileWorkspace === 'settings'"
        :class="{ active: mobileWorkspace === 'settings' }"
        :disabled="!selectedBlock"
        @click="setMobileWorkspace('settings')"
      >Block</button>
    </div>

    <div
      class="editor-body"
      :class="{
        'tools-open': toolsOpen,
        'mobile-preview': mobileWorkspace === 'preview',
        'mobile-design': mobileWorkspace === 'design',
        'mobile-settings': mobileWorkspace === 'settings'
      }"
    >
      <!-- Left: simple design controls or the block structure -->
      <aside id="template-editor-tools" class="editor-sidebar" role="tabpanel" aria-label="Werkzeuge" tabindex="-1">
        <div class="sidebar-tabs" role="tablist">
          <button
            type="button"
            role="tab"
            class="sidebar-tab"
            :class="{ active: sidebarTab === 'design' }"
            :aria-selected="sidebarTab === 'design'"
            @click="sidebarTab = 'design'"
          >Design</button>
          <button
            type="button"
            role="tab"
            class="sidebar-tab"
            :class="{ active: sidebarTab === 'blocks' }"
            :aria-selected="sidebarTab === 'blocks'"
            @click="sidebarTab = 'blocks'"
          >Blöcke</button>
        </div>
        <div class="sidebar-content">
          <template v-if="sidebarTab === 'design'">
            <DesignPanel
              :blocks="form.blocks"
              :margins="margins"
              @update:blocks="setBlocks"
              @update:margins="updateMargins"
              @notify="notify"
            />
            <details class="css-section">
              <summary>Für Fortgeschrittene: eigenes CSS</summary>
              <p class="css-hint">Gilt für das gedruckte Dokument. Externe Dateien (@import, url(...)) werden nicht geladen.</p>
              <textarea v-model="form.globalCss" class="css-input" rows="6" spellcheck="false" aria-label="Eigenes CSS"></textarea>
            </details>
          </template>
          <template v-else>
            <BlockOutline
              :blocks="form.blocks"
              :selected-id="selectedBlockId"
              :issues="allIssues"
              @select="selectBlockById"
              @move="moveBlockTo"
              @remove="removeBlock"
              @add="addBlockAt"
            />
            <BlockLibrary :available-blocks="availableBlocks" :blocks="form.blocks" @add-block="addBlock" />
          </template>
        </div>
      </aside>

      <!-- Center: the page -->
      <main id="template-editor-preview" class="preview-pane" role="tabpanel" aria-label="Vorschau" tabindex="-1">
        <div class="preview-toolbar">
          <div class="preview-context">
            <button
              type="button"
              class="toolbar-toggle"
              :aria-expanded="toolsOpen"
              aria-controls="template-editor-tools"
              @click="toolsOpen = !toolsOpen"
            >{{ toolsOpen ? 'Werkzeuge schließen' : 'Werkzeuge' }}</button>
            <div v-if="liveData" class="segmented" role="radiogroup" aria-label="Vorschaudaten">
              <button type="button" role="radio" :aria-checked="previewMode === 'live'" :class="{ active: previewMode === 'live' }" @click="previewMode = 'live'">Dieses Dokument</button>
              <button type="button" role="radio" :aria-checked="previewMode === 'sample'" :class="{ active: previewMode === 'sample' }" @click="previewMode = 'sample'">Beispieldaten</button>
            </div>
            <span v-else class="preview-label">Vorschau mit {{ owner ? 'Ihren Firmendaten und ' : '' }}Beispieldaten</span>
          </div>
          <div class="zoom-controls">
            <button type="button" class="btn-icon" title="Verkleinern" aria-label="Verkleinern" @click="zoomBy(-0.1)">−</button>
            <span class="zoom-level">{{ Math.round(zoom * 100) }}%</span>
            <button type="button" class="btn-icon" title="Vergrößern" aria-label="Vergrößern" @click="zoomBy(0.1)">+</button>
            <button type="button" class="btn-text" title="Seite an die Breite des Fensters anpassen" @click="fitZoom">Seitenbreite</button>
          </div>
        </div>

        <div
          ref="previewContainer"
          class="preview-container"
          :class="{ 'drag-over': dropIndex !== null }"
          @dragover.prevent="onSheetDragOver"
          @dragleave="onSheetDragLeave"
          @drop.prevent="onSheetDrop"
          @click.self="deselectBlock"
        >
          <div class="sheet-zoom" :style="{ zoom }">
            <div class="sheet" @click.self="deselectBlock">
              <!-- nosemgrep: javascript.vue.security.audit.xss.templates.avoid-v-html.avoid-v-html -- scoped template CSS (scopeCss strips @import and remote url()) -->
              <div v-html="previewStyleTag"></div>
              <div class="invoice-box" :style="previewMarginStyle" @click.self="deselectBlock">
                <div
                  v-for="row in previewRows"
                  :key="row.items[0].item.block.id"
                  class="preview-row"
                  :class="{ half: row.half }"
                >
                <div
                  v-for="{ item, index } in row.items"
                  :key="item.block.id"
                  class="preview-block-wrapper"
                  :class="{
                    selected: selectedBlockId === item.block.id,
                    'has-issue': issueBlockIds.has(item.block.id),
                    'drop-before': dropIndex === index,
                    'drop-after': dropIndex === index + 1 && index === renderedBlocks.length - 1,
                    'empty-render': !item.html.trim()
                  }"
                  :data-index="index"
                  draggable="true"
                  @click.stop="selectBlockById(item.block.id)"
                  @dragstart="onBlockDragStart($event, index)"
                  @dragend="dropIndex = null"
                >
                  <div class="block-label">{{ item.name }}</div>
                  <div v-if="selectedBlockId === item.block.id" class="block-actions" @click.stop>
                    <button type="button" :disabled="index === 0" title="Nach oben (Alt+↑)" aria-label="Nach oben" @click="moveBlockTo(index, index - 1)">↑</button>
                    <button type="button" :disabled="index === renderedBlocks.length - 1" title="Nach unten (Alt+↓)" aria-label="Nach unten" @click="moveBlockTo(index, index + 1)">↓</button>
                    <button type="button" title="Duplizieren (Strg+D)" aria-label="Duplizieren" @click="duplicateSelected">⧉</button>
                    <button type="button" title="Entfernen (Entf)" aria-label="Entfernen" @click="removeBlock(item.block.id)">✕</button>
                  </div>
                  <!-- nosemgrep: javascript.vue.security.audit.xss.templates.avoid-v-html.avoid-v-html -- block HTML built from escapeBlockData() output; ctx values are escaped in render() -->
                  <div class="preview-block" v-html="item.html || emptyBlockHtml(item.block)"></div>
                </div>
                <div v-if="row.half && row.items.length === 1" class="half-filler"></div>
                </div>

                <div v-if="form.blocks.length === 0" class="empty-preview">
                  <p>Die Vorlage ist leer. Fügen Sie unter „Werkzeuge“ bei „Blöcke“ Inhalte hinzu.</p>
                </div>
              </div>
            </div>
          </div>
        </div>

        <!-- Problems: errors block saving, warnings are hints -->
        <div v-if="allIssues.length" class="issues-bar" role="status">
          <button
            v-for="(issue, i) in allIssues"
            :key="i"
            type="button"
            class="issue"
            :class="issue.level"
            @click="issue.blockId && selectBlockById(issue.blockId)"
          >
            <span class="issue-icon">{{ issue.level === 'error' ? '⛔' : '⚠' }}</span>{{ issue.message }}
          </button>
        </div>
      </main>

      <!-- Right: settings of the selected block -->
      <BlockConfigPanel
        v-if="selectedBlock"
        id="template-editor-block-settings"
        role="tabpanel"
        :block="selectedBlock"
        :block-type="getBlockType(selectedBlock.type)"
        :preview-values="previewContext.variables"
        :issues="selectedIssues"
        @update="updateBlock"
        @close="deselectBlock"
        @duplicate="duplicateSelected"
        @remove="removeBlock(selectedBlock.id)"
        @notify="notify"
      />
    </div>

    <!-- Exact PDF preview -->
    <div v-if="pdfUrl" class="pdf-preview-modal" role="dialog" aria-modal="true" aria-label="PDF-Vorschau" @click.self="closePdfPreview">
      <div class="pdf-preview-content">
        <div class="pdf-preview-header">
          <span class="pdf-preview-title">PDF-Vorschau {{ pdfIsSample ? '(Beispieldaten)' : '(dieses Dokument)' }} – noch nicht gespeichert</span>
          <span class="pdf-actions">
            <!-- Fallback for webviews without a built-in PDF viewer -->
            <a class="btn btn-outline" :href="pdfUrl" download="vorschau.pdf">Herunterladen</a>
            <button type="button" class="btn btn-secondary" @click="closePdfPreview">Schließen</button>
          </span>
        </div>
        <iframe :src="pdfFrameSrc" title="PDF-Vorschau" class="pdf-frame"></iframe>
      </div>
    </div>

    <!-- Notifications -->
    <div class="toasts" aria-live="polite">
      <div v-for="toast in toasts" :key="toast.id" class="toast" :class="toast.type">{{ toast.message }}</div>
    </div>

    <div
      v-if="guard.showConfirm.value"
      ref="confirmDialog"
      class="unsaved-changes-dialog"
      tabindex="-1"
      @click.self="guard.resolve('cancel')"
      @keydown.esc.stop="guard.resolve('cancel')"
    >
      <div class="dialog-content" role="dialog" aria-modal="true" aria-labelledby="unsaved-changes-title">
        <h3 id="unsaved-changes-title">Ungespeicherte Änderungen</h3>
        <p>Sie haben ungespeicherte Änderungen an dieser Vorlage. Möchten Sie diese vor dem Schließen speichern?</p>
        <div class="dialog-actions">
          <button type="button" class="btn btn-secondary" @click="guard.resolve('cancel')">Abbrechen</button>
          <button type="button" class="btn btn-danger" @click="guard.resolve('discard')">Verwerfen</button>
          <button type="button" class="btn btn-primary" :disabled="!canSave" @click="guard.resolve('save')">Speichern</button>
        </div>
      </div>
    </div>
  </div>
</template>

<script>
import { markRaw } from 'vue'
import BlockLibrary from './editor/BlockLibrary.vue'
import BlockConfigPanel from './editor/BlockConfigPanel.vue'
import BlockOutline, { MOVE_TYPE } from './editor/BlockOutline.vue'
import DesignPanel from './editor/DesignPanel.vue'
import ownerService from '../services/ownerService.js'
import templateAPI from '../services/templateService.js'
import { BLOCK_TYPES, getBlockByType, blockDisplayName, documentTitle } from '../utils/blockRegistry.js'
import {
  createBlock, createDefaultBlocks, normalizeBlocks, serializeBlocks, moveBlock, duplicateBlock, clone,
  layoutRows, composePageHtml
} from '../utils/blockModel.js'
import { validateBlocks, canAddBlock } from '../utils/blockValidation.js'
import { buildPreviewContext } from '../utils/previewData.js'
import { renderBlockPreview } from '../utils/renderBlockPreview.js'
import { scopeCss } from '../utils/scopeCss.js'
import { TEMPLATE_BASE_CSS } from '../utils/templateBaseCss.js'
import { escapeBlockData } from '../utils/sanitize.js'
import { INVOICE, DOCUMENT_TYPES, CREDIT_NOTE } from '../data/documentTypes.js'
import { useUnsavedChangesGuard } from '../composables/useUnsavedChangesGuard.js'
import { useHistory } from '../composables/useHistory.js'

/** Selector the preview CSS is confined to (see scopeCss). */
const PREVIEW_SCOPE = '.template-editor .invoice-box'

/** Width of an A4 page in CSS pixels (210 mm at 96 dpi). */
const A4_WIDTH_PX = 793.7

/**
 * Block-based template editor.
 *
 * Layout: the page preview is the primary workspace. On wide screens the
 * design / block tools are collapsed until requested; selecting a block
 * opens its settings beside the preview. On narrow screens the preview,
 * tools, and selected-block settings use separate full-width workspaces.
 *
 * State model: `form.blocks` is an array whose order is the block order
 * (`order` is written only on save). The selected block is referenced by id
 * and derived from `form.blocks`, so there is never a stale copy. Every change
 * goes through `form`, which feeds the undo history and the unsaved-changes
 * guard.
 *
 * Preview: blocks are rendered with the registry's `render()` from a preview
 * context (sample document → company data → the real document when
 * `liveData` is given). The saved `blocks` are never mutated with preview
 * data. "PDF-Vorschau" renders the unsaved draft on the server through the
 * real PDF pipeline.
 *
 * Props: `template` (to edit, or a preset for a new one), `liveData` (real
 * document data for per-document overrides: `variables`, `items`, `totals`,
 * `invoiceId`). Emits `save(templateData)` and `cancel`; exposes
 * `requestClose()` for parents' backdrop / close handlers.
 */
export default {
  name: 'TemplateEditor',
  components: { BlockLibrary, BlockConfigPanel, BlockOutline, DesignPanel },
  props: {
    template: { type: Object, default: () => null },
    liveData: { type: Object, default: null }
  },
  emits: ['save', 'cancel'],
  data() {
    return {
      form: {
        name: '',
        documentTypeId: INVOICE,
        isDefault: false,
        documentSpecific: false,
        marginTopMm: 15,
        marginBottomMm: 15,
        marginLeftMm: 15,
        marginRightMm: 15,
        globalCss: '',
        blocks: []
      },
      selectedBlockId: null,
      sidebarTab: 'design',
      toolsOpen: false,
      mobileWorkspace: 'preview',
      previewMode: 'live',
      owner: null,
      zoom: 0.7,
      /** 'fit' follows the preview width (panel opened, window resized) until the user zooms. */
      zoomMode: 'fit',
      dropIndex: null,
      pdfUrl: null,
      pdfIsSample: true,
      pdfLoading: false,
      toasts: [],
      guard: null,
      history: null
    }
  },

  created() {
    this.guard = markRaw(useUnsavedChangesGuard(() => this.form))
    this.history = markRaw(useHistory())
    this.loadTemplate()
  },

  computed: {
    isEditing() {
      return !!(this.template && this.template.id)
    },
    /**
     * Editing the layout of one document (per-document override): document
     * type and "default" flag do not apply; the server forces them on save.
     */
    isDocumentContext() {
      return this.form.documentSpecific || !!this.liveData
    },
    availableBlocks() {
      return BLOCK_TYPES
    },
    documentTypeOptions() {
      // A credit note renders with the template of the invoice it cancels.
      return DOCUMENT_TYPES.filter(dt => dt.id !== CREDIT_NOTE)
    },
    documentTypeLabel() {
      return documentTitle(this.form.documentTypeId)
    },
    margins() {
      const { marginTopMm, marginRightMm, marginBottomMm, marginLeftMm } = this.form
      return { marginTopMm, marginRightMm, marginBottomMm, marginLeftMm }
    },
    selectedBlock() {
      return this.form.blocks.find(b => b.id === this.selectedBlockId) || null
    },
    validation() {
      return validateBlocks(this.form.blocks)
    },
    allIssues() {
      return [
        ...this.validation.errors.map(i => ({ ...i, level: 'error' })),
        ...this.validation.warnings.map(i => ({ ...i, level: 'warning' }))
      ]
    },
    issueBlockIds() {
      return new Set(this.allIssues.map(i => i.blockId).filter(Boolean))
    },
    selectedIssues() {
      return this.allIssues.filter(i => i.blockId === this.selectedBlockId)
    },
    canSave() {
      return !!this.form.name.trim() && this.form.blocks.length > 0 && this.validation.errors.length === 0
    },
    saveHint() {
      if (!this.form.name.trim()) return 'Bitte geben Sie der Vorlage einen Namen.'
      if (this.validation.errors.length) return this.validation.errors[0].message
      return 'Vorlage speichern'
    },
    previewContext() {
      return buildPreviewContext({
        owner: this.owner,
        liveData: this.liveData && this.previewMode === 'live' ? this.liveData : null,
        documentTypeId: this.form.documentTypeId
      })
    },
    renderedBlocks() {
      return this.form.blocks.map(block => ({
        block,
        name: blockDisplayName(block),
        html: this.renderBlock(block)
      }))
    },
    /** Page rows: two consecutive half-width blocks share a row, like in the PDF. */
    previewRows() {
      const rendered = this.renderedBlocks
      return layoutRows(this.form.blocks).map(row => ({
        half: row.half,
        items: row.items.map(({ index }) => ({ item: rendered[index], index }))
      }))
    },
    previewStyleTag() {
      const css = scopeCss(TEMPLATE_BASE_CSS + '\n' + (this.form.globalCss || ''), PREVIEW_SCOPE)
      // A literal "</style" in user CSS must not close the element.
      return `<style>${css.replace(/<\/style/gi, '<\\/style')}</style>`
    },
    previewMarginStyle() {
      const m = this.form
      return { padding: `${m.marginTopMm}mm ${m.marginRightMm}mm ${m.marginBottomMm}mm ${m.marginLeftMm}mm` }
    },
    /** Ask built-in PDF viewers to fit the complete PDF page inside the frame. */
    pdfFrameSrc() {
      return this.pdfUrl ? `${this.pdfUrl}#view=Fit` : ''
    }
  },

  watch: {
    form: {
      deep: true,
      handler() {
        this.history.record(this.form)
      }
    },
    'guard.showConfirm.value'(open) {
      if (open) this.$nextTick(() => this.$refs.confirmDialog && this.$refs.confirmDialog.focus())
    }
  },

  async mounted() {
    window.addEventListener('keydown', this.onWindowKeydown)
    if (typeof ResizeObserver !== 'undefined' && this.$refs.previewContainer) {
      this.resizeObserver = new ResizeObserver(() => {
        if (this.zoomMode === 'fit') this.fitZoom()
      })
      this.resizeObserver.observe(this.$refs.previewContainer)
    }
    this.$nextTick(this.fitZoom)
    await this.loadOwner()
  },

  beforeUnmount() {
    window.removeEventListener('keydown', this.onWindowKeydown)
    if (this.resizeObserver) this.resizeObserver.disconnect()
    this.closePdfPreview()
  },

  methods: {
    /** Initialises `form` from the `template` prop (or a default template). */
    loadTemplate() {
      const t = this.template
      if (t) {
        // `??` keeps a stored 0 mm margin (`||` turned it into the default).
        this.form = {
          name: t.name || '',
          documentTypeId: t.documentTypeId || INVOICE,
          isDefault: !!t.isDefault,
          documentSpecific: !!t.documentSpecific,
          marginTopMm: t.marginTopMm ?? 15,
          marginBottomMm: t.marginBottomMm ?? 15,
          marginLeftMm: t.marginLeftMm ?? 15,
          marginRightMm: t.marginRightMm ?? 15,
          globalCss: t.cssContent || '',
          blocks: normalizeBlocks(t.blocks)
        }
      }
      if (this.form.blocks.length === 0) {
        this.form.blocks = createDefaultBlocks()
        const meta = this.form.blocks.find(b => b.type === 'invoice-meta')
        if (meta) meta.data.invoiceTitle = documentTitle(this.form.documentTypeId)
      }
      if (!t || !t.id) this.sidebarTab = 'design'
      this.guard.snapshot()
      this.history.reset(this.form)
    },

    /** Loads the company data for the preview; without it the sample company is shown. */
    async loadOwner() {
      try {
        const response = await ownerService.getPrimaryOwner()
        const owner = response && response.data
        this.owner = owner && Object.keys(owner).length ? owner : null
      } catch {
        this.owner = null
      }
    },

    getBlockType(type) {
      return getBlockByType(type)
    },

    /**
     * Preview HTML of one block: variables resolved with the preview values,
     * then HTML-escaped, then rendered with document data from the context.
     * @param {Object} block
     * @returns {string}
     */
    renderBlock(block) {
      return renderBlockPreview(block, this.previewContext)
    },

    /** Placeholder for blocks that render nothing (e.g. notes without text). */
    emptyBlockHtml(block) {
      return `<div class="empty-block-hint">${blockDisplayName(block)}: wird nur gedruckt, wenn Text vorhanden ist</div>`
    },

    // --- block operations -------------------------------------------------

    setBlocks(blocks) {
      this.form.blocks = blocks
      if (this.selectedBlockId && !blocks.some(b => b.id === this.selectedBlockId)) {
        this.selectedBlockId = null
        if (this.mobileWorkspace === 'settings') this.mobileWorkspace = 'preview'
      }
    },

    updateBlock(updated) {
      const index = this.form.blocks.findIndex(b => b.id === updated.id)
      if (index !== -1) this.form.blocks.splice(index, 1, updated)
    },

    selectBlockById(id) {
      this.selectedBlockId = id
      if (this.sidebarTab === 'design') this.sidebarTab = 'blocks'
      this.toolsOpen = false
      this.mobileWorkspace = 'settings'
    },

    deselectBlock() {
      this.selectedBlockId = null
      if (this.mobileWorkspace === 'settings') this.mobileWorkspace = 'preview'
    },

    /** Selects one full-width workspace on narrow screens and refits the page when shown. */
    setMobileWorkspace(workspace) {
      if (workspace === 'settings' && !this.selectedBlock) return
      this.mobileWorkspace = workspace
      if (workspace === 'preview') this.$nextTick(this.fitZoom)
    },

    /**
     * Adds a block of a type at the end (or at `index`), if its limit allows.
     * @param {Object} blockType - Registry entry
     */
    addBlock(blockType) {
      this.addBlockAt(blockType.type, this.form.blocks.length)
    },

    addBlockAt(type, index) {
      if (!canAddBlock(this.form.blocks, type)) {
        this.notify({ type: 'warning', message: `„${getBlockByType(type)?.name || type}“ ist bereits vorhanden.` })
        return
      }
      const block = createBlock(type)
      if (!block) return
      if (type === 'invoice-meta') block.data.invoiceTitle = documentTitle(this.form.documentTypeId)
      if (type === 'address' && this.form.blocks.some(b => b.type === 'address' && (b.data.role || 'sender') === 'sender')) {
        Object.assign(block, createBlock('address', { role: 'receiver' }), { id: block.id })
      }
      const at = Math.max(0, Math.min(this.form.blocks.length, index))
      this.form.blocks.splice(at, 0, block)
      this.selectBlockById(block.id)
    },

    moveBlockTo(from, to) {
      moveBlock(this.form.blocks, from, to)
    },

    removeBlock(id) {
      const index = this.form.blocks.findIndex(b => b.id === id)
      if (index === -1) return
      const [removed] = this.form.blocks.splice(index, 1)
      if (this.selectedBlockId === id) {
        this.selectedBlockId = null
        if (this.mobileWorkspace === 'settings') this.mobileWorkspace = 'preview'
      }
      this.notify({ type: 'info', message: `„${blockDisplayName(removed)}“ entfernt – Strg+Z macht es rückgängig.` })
    },

    duplicateSelected() {
      const block = this.selectedBlock
      if (!block) return
      if (!canAddBlock(this.form.blocks, block.type)) {
        this.notify({ type: 'warning', message: `„${blockDisplayName(block)}“ darf nur einmal vorkommen.` })
        return
      }
      const copy = duplicateBlock(block)
      const index = this.form.blocks.findIndex(b => b.id === block.id)
      this.form.blocks.splice(index + 1, 0, copy)
      this.selectedBlockId = copy.id
    },

    updateMargins(patch) {
      Object.assign(this.form, patch)
    },

    /** Changes the document type; the title follows when it was the old type's name. */
    changeDocumentType(typeId) {
      const meta = this.form.blocks.find(b => b.type === 'invoice-meta')
      if (meta && (meta.data.invoiceTitle || '') === documentTitle(this.form.documentTypeId)) {
        meta.data.invoiceTitle = documentTitle(typeId)
      }
      this.form.documentTypeId = typeId
    },

    // --- history ------------------------------------------------------------

    applyHistoryState(state) {
      this.form = state
      if (this.selectedBlockId && !state.blocks.some(b => b.id === this.selectedBlockId)) {
        this.selectedBlockId = null
        if (this.mobileWorkspace === 'settings') this.mobileWorkspace = 'preview'
      }
    },
    undo() {
      this.history.undo(this.applyHistoryState)
    },
    redo() {
      this.history.redo(this.applyHistoryState)
    },

    // --- keyboard -----------------------------------------------------------

    /** Whether the event comes from a text field (which handles its own keys). */
    isTyping(event) {
      const el = event.target
      return !!el && (el.isContentEditable || ['INPUT', 'TEXTAREA', 'SELECT'].includes(el.tagName))
    },

    onKeydown(event) {
      if (this.guard.showConfirm.value || this.pdfUrl) return
      const mod = event.ctrlKey || event.metaKey
      if (mod && event.key.toLowerCase() === 's') {
        event.preventDefault()
        this.saveTemplate()
        return
      }
      if (this.isTyping(event)) return
      if (mod && event.key.toLowerCase() === 'z') {
        event.preventDefault()
        event.shiftKey ? this.redo() : this.undo()
      } else if (mod && event.key.toLowerCase() === 'y') {
        event.preventDefault()
        this.redo()
      } else if (mod && event.key.toLowerCase() === 'd' && this.selectedBlock) {
        event.preventDefault()
        this.duplicateSelected()
      } else if ((event.key === 'Delete' || event.key === 'Backspace') && this.selectedBlock && !event.target.closest('.block-outline')) {
        event.preventDefault()
        this.removeBlock(this.selectedBlock.id)
      } else if (event.altKey && (event.key === 'ArrowUp' || event.key === 'ArrowDown') && this.selectedBlock && !event.target.closest('.block-outline')) {
        event.preventDefault()
        const index = this.form.blocks.findIndex(b => b.id === this.selectedBlockId)
        this.moveBlockTo(index, index + (event.key === 'ArrowUp' ? -1 : 1))
      }
    },

    /** Esc closes the PDF preview, then deselects (dialogs handle Esc themselves). */
    onWindowKeydown(event) {
      if (event.key !== 'Escape' || this.guard.showConfirm.value) return
      if (this.pdfUrl) {
        this.closePdfPreview()
      } else if (this.selectedBlockId && !(event.target && event.target.closest && event.target.closest('.variable-picker'))) {
        this.deselectBlock()
      }
    },

    // --- drag and drop in the preview ---------------------------------------

    onBlockDragStart(event, index) {
      event.dataTransfer.effectAllowed = 'move'
      event.dataTransfer.setData(MOVE_TYPE, String(index))
      event.dataTransfer.setData('text/plain', blockDisplayName(this.form.blocks[index]))
    },

    /** Drop position from the pointer: before the block under it, or after the last one. */
    dropIndexFromEvent(event) {
      const wrappers = Array.from(this.$refs.previewContainer.querySelectorAll('.preview-block-wrapper'))
      for (const [i, el] of wrappers.entries()) {
        const rect = el.getBoundingClientRect()
        if (event.clientY < rect.top + rect.height / 2) return i
      }
      return wrappers.length
    },

    onSheetDragOver(event) {
      const types = Array.from(event.dataTransfer.types || [])
      event.dataTransfer.dropEffect = types.includes(MOVE_TYPE) ? 'move' : 'copy'
      this.dropIndex = this.dropIndexFromEvent(event)
    },

    onSheetDragLeave(event) {
      // dragleave also fires when entering a child; only reset when leaving the container.
      if (!this.$refs.previewContainer.contains(event.relatedTarget)) this.dropIndex = null
    },

    onSheetDrop(event) {
      const index = this.dropIndex ?? this.form.blocks.length
      this.dropIndex = null
      const moved = event.dataTransfer.getData(MOVE_TYPE)
      if (moved !== '') {
        const from = Number(moved)
        this.moveBlockTo(from, from < index ? index - 1 : index)
        return
      }
      const raw = event.dataTransfer.getData('block-type')
      if (!raw) return
      try {
        this.addBlockAt(JSON.parse(raw).type, index)
      } catch (e) {
        console.error('Failed to parse dropped block:', e)
      }
    },

    // --- zoom -----------------------------------------------------------------

    zoomBy(delta) {
      this.zoomMode = 'manual'
      this.zoom = Math.round(Math.min(1.5, Math.max(0.3, this.zoom + delta)) * 10) / 10
    },

    /** Fits the page to the preview width (at most 100 %) and keeps following it. */
    fitZoom() {
      this.zoomMode = 'fit'
      const container = this.$refs.previewContainer
      const styles = container ? window.getComputedStyle(container) : null
      const horizontalPadding = styles
        ? (parseFloat(styles.paddingLeft) || 0) + (parseFloat(styles.paddingRight) || 0)
        : 48
      const width = container ? container.clientWidth - horizontalPadding : 0
      if (width > 0) this.zoom = Math.round(Math.min(1, Math.max(0.3, width / A4_WIDTH_PX)) * 100) / 100
    },

    // --- notifications ----------------------------------------------------------

    /**
     * Shows a short message.
     * @param {{type: string, message: string}} toast
     */
    notify({ type = 'info', message }) {
      const id = Date.now() + Math.random()
      this.toasts.push({ id, type, message })
      setTimeout(() => { this.toasts = this.toasts.filter(t => t.id !== id) }, type === 'error' ? 7000 : 4000)
    },

    // --- PDF preview -----------------------------------------------------------

    async openPdfPreview() {
      this.pdfLoading = true
      const useLive = !!(this.liveData && this.previewMode === 'live' && this.liveData.invoiceId)
      try {
        const response = await templateAPI.previewPdf(this.buildPayload(), useLive ? this.liveData.invoiceId : null)
        this.closePdfPreview()
        this.pdfIsSample = !useLive
        this.pdfUrl = URL.createObjectURL(new Blob([response.data], { type: 'application/pdf' }))
      } catch (error) {
        let message = error.message
        const data = error.response && error.response.data
        if (data instanceof Blob) {
          try { message = JSON.parse(await data.text()).message || message } catch { /* keep message */ }
        } else if (data && data.message) {
          message = data.message
        }
        this.notify({ type: 'error', message: 'PDF-Vorschau fehlgeschlagen: ' + message })
      } finally {
        this.pdfLoading = false
      }
    },

    closePdfPreview() {
      if (this.pdfUrl) URL.revokeObjectURL(this.pdfUrl)
      this.pdfUrl = null
    },

    // --- save / close ----------------------------------------------------------

    /**
     * HTML of the template with placeholders kept, stored in the legacy column
     * `htmlContent`. Nothing reads it: the PDF renders the blocks, and the
     * DOCX/ODT exchange (utils/documentExchange.js) works from the blocks too.
     * @returns {string}
     */
    generateRenderedHtml() {
      const css = TEMPLATE_BASE_CSS + (this.form.globalCss || '')
      const m = this.form
      const ctx = { variables: {}, items: [], totals: null, documentTypeId: m.documentTypeId }
      const body = composePageHtml(this.form.blocks, block => {
        const type = getBlockByType(block.type)
        return type ? type.render(escapeBlockData(block.data || {}), block.config || {}, ctx) : ''
      })
      return `<style>${css.replace(/<\/style/gi, '<\\/style')}</style><div class="invoice-box" style="padding: ${m.marginTopMm}mm ${m.marginRightMm}mm ${m.marginBottomMm}mm ${m.marginLeftMm}mm;">${body}</div>`
    },

    /** The `InvoiceTemplateDto` sent to the server. */
    buildPayload() {
      const { globalCss, blocks, ...rest } = clone(this.form)
      return {
        ...rest,
        cssContent: globalCss || '',
        htmlContent: this.generateRenderedHtml(),
        blocks: JSON.stringify(serializeBlocks(blocks))
      }
    },

    saveTemplate() {
      if (!this.canSave) {
        this.notify({ type: 'warning', message: this.saveHint })
        return
      }
      this.$emit('save', this.buildPayload())
    },

    async handleCancelRequest() {
      const action = await this.guard.requestClose()
      if (action === 'save') {
        this.saveTemplate()
      } else if (action === 'discard') {
        this.guard.markSaved()
        this.$emit('cancel')
      }
      return action
    },

    requestClose() {
      return this.handleCancelRequest()
    }
  }
}
</script>

<style scoped>
.template-editor {
  position: relative;
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
  background: var(--color-surface);
  border-radius: 8px;
  overflow: hidden;
  color: var(--color-text);
}

/* top bar */
.editor-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  flex-wrap: wrap;
  padding: 10px 16px;
  border-bottom: 1px solid var(--color-border);
  background: var(--color-surface);
}

.header-fields {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
  min-width: 0;
  flex: 1;
}

.name-input {
  flex: 1 1 220px;
  max-width: 340px;
  padding: 7px 10px;
  border: 1px solid transparent;
  border-radius: 6px;
  font-size: 16px;
  font-weight: 600;
  color: var(--color-text);
  background: var(--color-surface-subtle);
}

.name-input:hover {
  border-color: var(--color-border);
}

.name-input:focus {
  outline: none;
  border-color: var(--color-primary);
  background: var(--color-surface);
  box-shadow: var(--shadow-focus);
}

.type-select {
  padding: 7px 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  font-size: 13px;
  background: var(--color-surface);
}

.default-check {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  color: var(--color-text-strong);
  white-space: nowrap;
}

.doc-specific-badge {
  padding: 4px 10px;
  border-radius: 12px;
  background: var(--color-warning-soft);
  color: var(--color-warning-text);
  font-size: 12px;
  font-weight: 600;
}

.header-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-divider {
  width: 1px;
  height: 24px;
  background: var(--color-border);
}

.editor-header .btn,
.pdf-preview-header .btn,
.unsaved-changes-dialog .btn {
  padding: 8px 16px;
  white-space: nowrap;
}

.btn-icon {
  width: 32px;
  height: 32px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  background: var(--color-surface);
  cursor: pointer;
  font-size: 16px;
  color: var(--color-text-strong);
}

.btn-icon:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.btn-icon:hover:not(:disabled) {
  background: var(--color-surface-muted);
}

.btn-text {
  border: none;
  background: none;
  color: var(--color-primary);
  font-size: 12px;
  cursor: pointer;
  padding: 4px 6px;
}

/* body */
.editor-body {
  flex: 1;
  display: flex;
  min-height: 0;
  overflow: hidden;
}

.editor-sidebar {
  display: none;
  width: clamp(240px, 20vw, 320px);
  flex-shrink: 0;
  flex-direction: column;
  border-right: 1px solid var(--color-border);
  background: var(--color-surface-subtle);
  min-height: 0;
}

.editor-body.tools-open .editor-sidebar {
  display: flex;
}

.sidebar-tabs {
  display: flex;
  padding: 8px 8px 0;
  gap: 4px;
  border-bottom: 1px solid var(--color-border);
}

.sidebar-tab {
  flex: 1;
  padding: 8px;
  border: none;
  border-bottom: 2px solid transparent;
  background: none;
  font-size: 13px;
  font-weight: 500;
  color: var(--color-text-muted);
  cursor: pointer;
}

.sidebar-tab.active {
  color: var(--color-primary-hover);
  border-bottom-color: var(--color-primary);
  font-weight: 600;
}

.sidebar-content {
  flex: 1;
  overflow-y: auto;
}

.css-section {
  margin: 0 14px 16px;
  font-size: 12px;
  color: var(--color-text-strong);
}

.css-section summary {
  cursor: pointer;
}

.css-hint {
  margin: 6px 0;
  color: var(--color-text-muted);
  font-size: 11px;
}

.css-input {
  width: 100%;
  box-sizing: border-box;
  font-family: monospace;
  font-size: 12px;
  padding: 8px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
}

/* preview */
.preview-pane {
  flex: 1;
  min-width: 0;
  display: flex;
  flex-direction: column;
  background: var(--color-border);
}

.preview-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  padding: 8px 14px;
  background: var(--color-surface);
  border-bottom: 1px solid var(--color-border);
}

.preview-context {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.toolbar-toggle {
  flex-shrink: 0;
  padding: 6px 10px;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  background: var(--color-surface);
  color: var(--color-text-strong);
  font-size: 12px;
  cursor: pointer;
}

.toolbar-toggle:hover {
  background: var(--color-surface-subtle);
}

.preview-label {
  font-size: 12px;
  color: var(--color-text-muted);
}

.segmented {
  display: inline-flex;
  border: 1px solid var(--color-border-strong);
  border-radius: 6px;
  overflow: hidden;
}

.segmented button {
  padding: 5px 12px;
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

.zoom-controls {
  display: flex;
  align-items: center;
  gap: 6px;
}

.zoom-controls .btn-icon {
  width: 28px;
  height: 28px;
}

.zoom-level {
  min-width: 42px;
  text-align: center;
  font-size: 12px;
  color: var(--color-text-muted);
}

.preview-container {
  flex: 1;
  overflow: auto;
  padding: 24px;
  transition: background 0.2s;
}

.preview-container.drag-over {
  background: var(--color-primary-tint);
}

.sheet-zoom {
  width: fit-content;
  margin: 0 auto;
}

.sheet {
  width: 210mm;
  min-height: 297mm;
  text-align: left;
  background: var(--color-paper);
  box-shadow: var(--shadow-lg);
  /* page-break guide every A4 page */
  background-image: repeating-linear-gradient(
    to bottom,
    transparent 0,
    transparent calc(297mm - 1px),
    var(--color-danger-border) calc(297mm - 1px),
    var(--color-danger-border) 297mm
  );
}

.invoice-box {
  min-height: 297mm;
  box-sizing: border-box;
}

.preview-row.half {
  display: flex;
  gap: 24px;
}

.preview-row.half > * {
  flex: 1 1 0;
  min-width: 0;
}

.preview-block-wrapper {
  position: relative;
  border: 1px dashed transparent;
  border-radius: 3px;
  cursor: pointer;
  transition: border-color 0.15s, box-shadow 0.15s;
}

.preview-block-wrapper:hover {
  border-color: var(--color-primary-border);
}

.preview-block-wrapper.selected {
  border: 1px solid var(--color-primary);
  box-shadow: var(--shadow-focus);
}

.preview-block-wrapper.has-issue {
  border-color: var(--color-warning);
}

.preview-block-wrapper.drop-before::before,
.preview-block-wrapper.drop-after::after {
  content: '';
  position: absolute;
  left: 0;
  right: 0;
  height: 4px;
  border-radius: 2px;
  background: var(--color-primary);
  z-index: 5;
}

.preview-block-wrapper.drop-before::before {
  top: -4px;
}

.preview-block-wrapper.drop-after::after {
  bottom: -4px;
}

.block-label {
  position: absolute;
  top: 0;
  left: 0;
  transform: translateY(-100%);
  padding: 1px 6px;
  border-radius: 3px 3px 0 0;
  background: var(--color-primary);
  color: var(--color-on-primary);
  font: 600 10px/1.6 system-ui, sans-serif;
  display: none;
  z-index: 6;
  pointer-events: none;
}

.preview-block-wrapper:hover .block-label,
.preview-block-wrapper.selected .block-label {
  display: block;
}

.preview-block-wrapper:hover:not(.selected) .block-label {
  background: var(--color-primary-border);
}

.block-actions {
  position: absolute;
  top: 4px;
  right: 4px;
  display: flex;
  gap: 3px;
  z-index: 7;
}

.block-actions button {
  width: 26px;
  height: 26px;
  border: 1px solid var(--color-border-strong);
  border-radius: 5px;
  background: var(--color-surface);
  cursor: pointer;
  font-size: 13px;
  color: var(--color-text-strong);
  box-shadow: var(--shadow-sm);
}

.block-actions button:disabled {
  opacity: 0.4;
  cursor: not-allowed;
}

.block-actions button:hover:not(:disabled) {
  background: var(--color-surface-muted);
}

.preview-block {
  min-height: 16px;
}

.preview-block :deep(.empty-block-hint) {
  padding: 8px;
  font: italic 12px system-ui, sans-serif;
  color: var(--color-text-subtle);
  border: 1px dashed var(--color-border);
  border-radius: 4px;
  margin: 6px 0;
}

.empty-preview {
  min-height: 200px;
  border: 2px dashed var(--color-border-strong);
  border-radius: 8px;
  display: flex;
  align-items: center;
  justify-content: center;
  text-align: center;
  padding: 40px;
  color: var(--color-text-muted);
  font: 14px system-ui, sans-serif;
}

/* issues */
.issues-bar {
  max-height: 110px;
  overflow-y: auto;
  padding: 6px 12px;
  background: var(--color-surface);
  border-top: 1px solid var(--color-border);
  display: flex;
  flex-direction: column;
  gap: 3px;
}

.issue {
  display: flex;
  gap: 6px;
  align-items: baseline;
  border: none;
  background: none;
  text-align: left;
  font-size: 12px;
  padding: 3px 4px;
  border-radius: 4px;
  cursor: pointer;
}

.issue:hover {
  background: var(--color-surface-subtle);
}

.issue.error {
  color: var(--color-danger-hover);
}

.issue.warning {
  color: var(--color-warning-text);
}

.issue-icon {
  flex-shrink: 0;
}

/* PDF preview */
.pdf-preview-modal {
  position: fixed;
  inset: 0;
  box-sizing: border-box;
  padding: clamp(12px, 2.4vw, 32px);
  background: var(--color-backdrop-dark);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1050;
}

.pdf-preview-content {
  width: min(1480px, 100%);
  height: 100%;
  min-width: 0;
  min-height: 0;
  background: var(--color-surface);
  border-radius: 8px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.pdf-preview-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-bottom: 1px solid var(--color-border);
  font-size: 13px;
  color: var(--color-text-strong);
}

.pdf-preview-title {
  min-width: 0;
}

.pdf-actions {
  display: flex;
  gap: 8px;
}

.pdf-actions a {
  text-decoration: none;
}

.pdf-frame {
  flex: 1;
  border: none;
  width: 100%;
  min-height: 0;
}

@media (max-width: 600px) {
  .pdf-preview-modal {
    padding: 12px;
  }

  .pdf-preview-content {
    width: min(100%, 520px);
    height: min(78dvh, 680px);
    max-height: calc(100dvh - 24px);
    border-radius: 10px;
  }

  .pdf-preview-header {
    align-items: stretch;
    flex-direction: column;
    gap: 8px;
    padding: 8px 10px;
  }

  .pdf-actions {
    justify-content: flex-end;
  }
}

/* toasts */
.toasts {
  position: absolute;
  bottom: 16px;
  left: 50%;
  transform: translateX(-50%);
  display: flex;
  flex-direction: column;
  gap: 8px;
  z-index: 1060;
  pointer-events: none;
}

.toast {
  padding: 10px 16px;
  border-radius: 6px;
  background: var(--color-text);
  color: var(--color-on-primary);
  font-size: 13px;
  box-shadow: var(--shadow-lg);
  max-width: 520px;
}

.toast.error {
  background: var(--color-danger-hover);
}

.toast.warning {
  background: var(--color-warning-text);
}

/* unsaved changes */
.unsaved-changes-dialog {
  position: fixed;
  inset: 0;
  background: var(--color-backdrop-dark);
  display: flex;
  justify-content: center;
  align-items: center;
  z-index: 1100;
  outline: none;
}

.unsaved-changes-dialog .dialog-content {
  background: var(--color-surface);
  border-radius: 8px;
  box-shadow: var(--shadow-xl);
  padding: 24px;
  max-width: 440px;
  width: 90%;
}

.unsaved-changes-dialog h3 {
  margin: 0 0 12px;
  font-size: 1.15rem;
}

.unsaved-changes-dialog p {
  margin: 0 0 20px;
  color: var(--color-text-strong);
  line-height: 1.5;
}

.unsaved-changes-dialog .dialog-actions {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

.mobile-workspace-tabs {
  display: none;
}

@media (max-width: 1100px) and (min-width: 901px) {
  .editor-body.tools-open .editor-sidebar {
    width: 230px;
  }

  .editor-body :deep(.block-config-panel) {
    width: 300px;
  }
}

/* Narrow screens use one full-width workspace at a time. */
@media (max-width: 900px) {
  .mobile-workspace-tabs {
    display: flex;
    flex: 0 0 auto;
    gap: 4px;
    padding: 5px 8px;
    border-bottom: 1px solid var(--color-border-strong);
    background: var(--color-surface-subtle);
  }

  .mobile-workspace-tabs button {
    flex: 1;
    min-width: 0;
    min-height: 40px;
    padding: 7px 10px;
    border: 1px solid transparent;
    border-radius: 6px;
    background: transparent;
    color: var(--color-text-strong);
    font-size: 13px;
    font-weight: 600;
    cursor: pointer;
  }

  .mobile-workspace-tabs button.active {
    border-color: var(--color-primary-border);
    background: var(--color-primary-soft);
    color: var(--color-primary-hover);
  }

  .mobile-workspace-tabs button:disabled {
    color: var(--color-text-subtle);
    cursor: not-allowed;
  }

  .editor-body {
    flex-direction: column;
    overflow: hidden;
  }

  .editor-sidebar {
    width: 100%;
    flex: 1;
    min-height: 0;
    max-height: none;
    border-right: none;
    border-bottom: none;
  }

  .preview-pane {
    display: none;
    flex: 1;
    min-height: 0;
  }

  .editor-body.mobile-preview .preview-pane,
  .editor-body.mobile-design .editor-sidebar {
    display: flex;
  }

  .editor-body.mobile-settings :deep(.block-config-panel) {
    display: flex;
    flex: 1;
    width: 100%;
    min-height: 0;
    max-height: none;
    border: 0;
  }

  .editor-body.mobile-preview .editor-sidebar,
  .editor-body.mobile-design .preview-pane,
  .editor-body.mobile-settings .editor-sidebar,
  .editor-body.mobile-settings .preview-pane,
  .editor-body:not(.mobile-settings) :deep(.block-config-panel) {
    display: none;
  }

  .preview-toolbar {
    flex-wrap: wrap;
    gap: 8px;
    padding: 7px 10px;
  }

  .preview-context {
    flex: 1 1 100%;
    justify-content: space-between;
  }

  .toolbar-toggle {
    display: none;
  }

  .preview-label {
    overflow: hidden;
    text-overflow: ellipsis;
    white-space: nowrap;
  }

  .zoom-controls {
    width: 100%;
    justify-content: flex-end;
  }

  .preview-container {
    padding: 12px;
  }

  .issues-bar {
    max-height: 88px;
  }

  .block-actions button {
    width: 32px;
    height: 32px;
  }
}

@media (max-width: 600px) {
  .editor-header {
    gap: 8px;
    padding: 8px;
  }

  .header-fields {
    gap: 6px;
  }

  .name-input {
    flex-basis: 100%;
    max-width: none;
    min-width: 0;
  }

  .type-select {
    flex: 1;
    min-width: 0;
  }

  .default-check {
    white-space: normal;
    font-size: 12px;
  }

  .header-actions {
    width: 100%;
    flex-wrap: wrap;
    justify-content: flex-end;
    gap: 6px;
  }

  .header-divider {
    display: none;
  }

  .header-actions .btn-outline,
  .header-actions .btn-secondary,
  .header-actions .btn-primary {
    padding: 8px 10px;
    font-size: 12px;
  }

  .preview-toolbar {
    padding: 6px 8px;
  }

  .segmented button {
    padding: 6px 8px;
    font-size: 11px;
  }

  .preview-container {
    padding: 8px;
  }

  .unsaved-changes-dialog .dialog-content {
    padding: 18px;
  }

  .unsaved-changes-dialog .dialog-actions {
    flex-wrap: wrap;
  }

  .unsaved-changes-dialog .dialog-actions button {
    flex: 1 1 auto;
  }
}
</style>

<template>
  <div ref="thumbnail" class="template-thumbnail" :class="scopeClass" :style="frameStyle" aria-hidden="true">
    <div class="thumb-page" :style="{ transform: `scale(${scale})` }">
      <!-- nosemgrep: javascript.vue.security.audit.xss.templates.avoid-v-html.avoid-v-html -- scoped template CSS (scopeCss strips @import and remote url()) -->
      <div v-html="styleTag"></div>
      <!-- nosemgrep: javascript.vue.security.audit.xss.templates.avoid-v-html.avoid-v-html -- block HTML built from escapeBlockData() output -->
      <div class="invoice-box" :style="padding" v-html="bodyHtml"></div>
    </div>
  </div>
</template>

<script>
import { normalizeBlocks, composePageHtml } from '../../utils/blockModel.js'
import { buildPreviewContext } from '../../utils/previewData.js'
import { renderBlockPreview } from '../../utils/renderBlockPreview.js'
import { scopeCss } from '../../utils/scopeCss.js'
import { TEMPLATE_BASE_CSS } from '../../utils/templateBaseCss.js'

let instanceCounter = 0

/** Width of an A4 page in CSS pixels (210 mm at 96 dpi). */
const A4_WIDTH_PX = 793.7

/**
 * Small, non-interactive picture of a template's first page, rendered with
 * the same block renderer and sample data as the editor preview. Used in the
 * template list and the "new template" gallery so users recognise layouts.
 */
export default {
  name: 'TemplateThumbnail',
  props: {
    /** Blocks as stored (JSON string) or as an array. */
    blocks: { type: [String, Array], default: () => [] },
    cssContent: { type: String, default: '' },
    documentTypeId: { type: Number, default: 1 },
    /** `{ marginTopMm, marginRightMm, marginBottomMm, marginLeftMm }` */
    margins: { type: Object, default: () => ({}) },
    /** Maximum thumbnail width in CSS pixels; narrower cards scale down to fit. */
    maxWidth: { type: Number, default: 240 }
  },
  data() {
    instanceCounter += 1
    return {
      scopeClass: `template-thumb-${instanceCounter}`,
      measuredWidth: this.maxWidth,
      resizeObserver: null
    }
  },
  computed: {
    scale() {
      return this.measuredWidth / A4_WIDTH_PX
    },
    frameStyle() {
      return {
        width: '100%',
        maxWidth: `${this.maxWidth}px`,
        aspectRatio: '210 / 297'
      }
    },
    padding() {
      const m = this.margins
      const v = (x) => (x ?? 15)
      return { padding: `${v(m.marginTopMm)}mm ${v(m.marginRightMm)}mm ${v(m.marginBottomMm)}mm ${v(m.marginLeftMm)}mm` }
    },
    styleTag() {
      const css = scopeCss(TEMPLATE_BASE_CSS + '\n' + (this.cssContent || ''), `.${this.scopeClass} .invoice-box`)
      return `<style>${css.replace(/<\/style/gi, '<\\/style')}</style>`
    },
    bodyHtml() {
      const ctx = buildPreviewContext({ documentTypeId: this.documentTypeId })
      return composePageHtml(normalizeBlocks(this.blocks), block => renderBlockPreview(block, ctx))
    }
  },
  mounted() {
    this.$nextTick(() => {
      this.updateScale()
      if (typeof ResizeObserver !== 'undefined') {
        this.resizeObserver = new ResizeObserver(this.updateScale)
        this.resizeObserver.observe(this.$refs.thumbnail)
      } else {
        window.addEventListener('resize', this.updateScale)
      }
    })
  },
  beforeUnmount() {
    if (this.resizeObserver) this.resizeObserver.disconnect()
    else window.removeEventListener('resize', this.updateScale)
  },
  methods: {
    /** Scale the fixed-size A4 rendering to the thumbnail's available width. */
    updateScale() {
      const width = this.$refs.thumbnail?.clientWidth
      if (width > 0 && width !== this.measuredWidth) this.measuredWidth = width
    }
  }
}
</script>

<style scoped>
.template-thumbnail {
  position: relative;
  box-sizing: border-box;
  overflow: hidden;
  background: #fff;
  border-radius: 4px;
  box-shadow: inset 0 0 0 1px #e5e7eb, 0 1px 3px rgba(0, 0, 0, 0.08);
  pointer-events: none;
  user-select: none;
}

.thumb-page {
  position: absolute;
  inset: 0 auto auto 0;
  width: 210mm;
  height: 297mm;
  transform-origin: top left;
  text-align: left;
  line-height: normal;
}

.invoice-box {
  box-sizing: border-box;
}
</style>

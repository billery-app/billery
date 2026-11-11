import { getBlockByType } from './blockRegistry.js'
import { resolveVariableText } from './variableText.js'
import { escapeBlockData } from './sanitize.js'

/**
 * Replaces variables in every string of a block's data (recursively).
 * @param {*} value
 * @param {Object} values - Variable values from the preview context
 * @returns {*}
 */
function resolveDeep(value, values) {
  if (typeof value === 'string') return resolveVariableText(value, values)
  if (Array.isArray(value)) return value.map(v => resolveDeep(v, values))
  if (value && typeof value === 'object') {
    return Object.fromEntries(Object.entries(value).map(([k, v]) => [k, resolveDeep(v, values)]))
  }
  return value
}

/**
 * Preview HTML of one block, the way the editor, the thumbnails and the
 * renderer parity test render it: variables resolved with the context
 * values, then HTML-escaped, then rendered with the context's document data
 * (line items, VAT totals, number label). The stored block is not modified.
 *
 * @param {Object} block - Block as stored (`type`, `data`, `config`)
 * @param {Object} ctx - Preview context (`previewData.js > buildPreviewContext`)
 * @returns {string} HTML, or '' for unknown block types
 */
export function renderBlockPreview(block, ctx) {
  const blockType = getBlockByType(block.type)
  if (!blockType) return ''
  const data = escapeBlockData(resolveDeep(block.data || {}, ctx.variables || {}))
  return blockType.render(data, block.config || {}, ctx)
}

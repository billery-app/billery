import { BLOCK_TYPES, getBlockByType } from './blockRegistry.js'
import { generateId } from './helpers.js'

/**
 * Block list model of the template editor.
 *
 * Inside the editor the **array order is the only source of truth** for the
 * position of a block. The stored JSON still carries an `order` field because
 * the PDF renderer sorts by it, so `order` is read once on load
 * ({@link normalizeBlocks}) and written once on save ({@link serializeBlocks}).
 * Keeping both in sync while editing caused blocks to jump back after a move.
 */

/**
 * Literal sample values older editor versions stored in new blocks. The PDF
 * printed them instead of the real document data. Mirrors
 * `DatabaseMigration.migrateTemplateSampleLiterals` for templates that are
 * opened before the backend migration ran (or come from an import).
 */
const LEGACY_SAMPLE_LITERALS = {
  'invoice-meta': {
    invoiceId: ['INV-2026-001', '{{rechnungsnummer}}'],
    invoiceDate: ['2026-03-17', '{{rechnungsdatum}}'],
    dueDate: ['2026-03-31', '{{faelligkeitsdatum}}']
  },
  header: {
    companyName: ['Ihr Firmenname', '{{firma}}']
  }
}

/** Config keys that were removed from the block registry. */
const REMOVED_CONFIG_KEYS = ['showUnit', 'showRate', 'decimalPlaces']

/**
 * Deep-copies a JSON-compatible value.
 * @param {*} value
 * @returns {*}
 */
export function clone(value) {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value))
}

/**
 * Creates a new block with the registry defaults of its type.
 * @param {string} type - Block type key (`header`, `address`, ...)
 * @param {Object} [extraData] - Data overriding the defaults (e.g. `{ role: 'receiver' }`)
 * @returns {Object|null} The block, or `null` for an unknown type
 */
export function createBlock(type, extraData = {}) {
  const blockType = getBlockByType(type)
  if (!blockType) return null
  // Role presets: a receiver address starts with the customer variables, a sender with the company's.
  const role = extraData.role ?? blockType.defaultData.role
  const preset = (role && blockType.rolePresets && blockType.rolePresets[role]) || {}
  return {
    id: generateId(),
    type,
    data: { ...clone(blockType.defaultData), ...clone(preset.data || {}), ...extraData },
    config: { ...clone(blockType.defaultConfig), ...clone(preset.config || {}) }
  }
}

/**
 * The block set a new template starts with.
 * @returns {Object[]}
 */
export function createDefaultBlocks() {
  return [
    createBlock('header'),
    createBlock('address', { role: 'sender' }),
    createBlock('address', { role: 'receiver' }),
    createBlock('invoice-meta'),
    createBlock('line-items'),
    createBlock('totals'),
    createBlock('notes'),
    createBlock('payment'),
    createBlock('footer')
  ]
}

/**
 * Brings one stored block up to the current registry shape: fills missing
 * config keys with defaults, drops removed keys and replaces legacy sample
 * literals by variables. Returns a new object; the input is not mutated.
 * @param {Object} block
 * @returns {Object}
 */
export function migrateBlock(block) {
  const migrated = clone(block)
  migrated.id = migrated.id || generateId()
  migrated.data = migrated.data || {}
  migrated.config = migrated.config || {}
  delete migrated.order

  const blockType = getBlockByType(migrated.type)
  if (blockType) {
    for (const [key, value] of Object.entries(blockType.defaultConfig)) {
      if (!(key in migrated.config)) migrated.config[key] = clone(value)
    }
  }
  for (const key of REMOVED_CONFIG_KEYS) delete migrated.config[key]

  // Older editors stored the 18pt title size as the invoice-meta font size, which rendered the
  // whole block at that size; the size now means the body text (same rule as the PDF renderer,
  // `TemplateBasedPdfGenerator.metaBodyFontSizeOf`).
  const format = migrated.config.format
  if (migrated.type === 'invoice-meta' && format && Number(format.fontSize) >= 16) {
    format.fontSize = Math.round(Number(format.fontSize) * 7 / 12 * 2) / 2
  }

  const literals = LEGACY_SAMPLE_LITERALS[migrated.type] || {}
  for (const [field, [literal, variable]] of Object.entries(literals)) {
    if (migrated.data[field] === literal) migrated.data[field] = variable
  }
  return migrated
}

/**
 * Parses stored blocks (JSON string or array), sorts them by their stored
 * `order` (stable, so equal orders keep their array position) and migrates
 * each block. The legacy header title moves to the invoice-meta block.
 * @param {string|Object[]|null|undefined} raw
 * @returns {Object[]} Blocks in display order, without `order` fields
 */
export function normalizeBlocks(raw) {
  let blocks = raw
  if (typeof raw === 'string') {
    try {
      blocks = JSON.parse(raw)
    } catch {
      blocks = []
    }
  }
  if (!Array.isArray(blocks)) return []

  const sorted = blocks
    .filter(b => b && typeof b === 'object' && b.type)
    .map((block, index) => ({ block, index }))
    .sort((a, b) => (Number(a.block.order) || 0) - (Number(b.block.order) || 0) || a.index - b.index)
    .map(({ block }) => block)

  const migrated = sorted.map(migrateBlock)

  // Legacy: the document title lived in the header block.
  const header = migrated.find(b => b.type === 'header')
  if (header && header.data.invoiceTitle) {
    const meta = migrated.find(b => b.type === 'invoice-meta')
    if (meta && !meta.data.invoiceTitle) meta.data.invoiceTitle = header.data.invoiceTitle
    delete header.data.invoiceTitle
    delete header.config.layout
  }
  return migrated
}

/**
 * Prepares blocks for storage: deep copy with `order` = array index.
 * @param {Object[]} blocks
 * @returns {Object[]}
 */
export function serializeBlocks(blocks) {
  return blocks.map((block, index) => ({ ...clone(block), order: index }))
}

/**
 * Moves the block at `from` to position `to` (both clamped) in place.
 * @param {Object[]} blocks
 * @param {number} from
 * @param {number} to
 * @returns {boolean} Whether anything moved
 */
export function moveBlock(blocks, from, to) {
  if (from < 0 || from >= blocks.length) return false
  const target = Math.max(0, Math.min(blocks.length - 1, to))
  if (target === from) return false
  const [block] = blocks.splice(from, 1)
  blocks.splice(target, 0, block)
  return true
}

/**
 * Copy of a block with a fresh id.
 * @param {Object} block
 * @returns {Object}
 */
export function duplicateBlock(block) {
  return { ...clone(block), id: generateId() }
}

/**
 * Whether a block is half width (`config.width === 'half'`).
 * @param {Object} block
 * @returns {boolean}
 */
export function isHalfWidth(block) {
  return !!(block && block.config && block.config.width === 'half')
}

/**
 * Groups blocks into page rows: full-width blocks get a row of their own,
 * two consecutive half-width blocks share one, a half-width block without a
 * partner takes the left half. Same rule as the PDF renderer
 * (`TemplateBasedPdfGenerator.layoutBlocks`).
 * @param {Object[]} blocks
 * @returns {{half: boolean, items: {block: Object, index: number}[]}[]}
 */
export function layoutRows(blocks) {
  const rows = []
  for (let i = 0; i < blocks.length; i++) {
    if (!isHalfWidth(blocks[i])) {
      rows.push({ half: false, items: [{ block: blocks[i], index: i }] })
    } else if (i + 1 < blocks.length && isHalfWidth(blocks[i + 1])) {
      rows.push({ half: true, items: [{ block: blocks[i], index: i }, { block: blocks[i + 1], index: i + 1 }] })
      i++
    } else {
      rows.push({ half: true, items: [{ block: blocks[i], index: i }] })
    }
  }
  return rows
}

/**
 * Page body HTML from rendered blocks, with half-width rows as tables,
 * byte-for-byte like `TemplateBasedPdfGenerator.layoutBlocks`. Used for
 * thumbnails and the stored `htmlContent`; the editor preview lays out its
 * interactive wrappers itself.
 * @param {Object[]} blocks
 * @param {(block: Object) => string} renderOne
 * @returns {string}
 */
export function composePageHtml(blocks, renderOne) {
  return layoutRows(blocks).map(row => {
    if (!row.half) return renderOne(row.items[0].block)
    const [left, right] = row.items
    return '<table class="block-row" style="width: 100%; border-collapse: collapse; margin: 0;">'
      + '<tr><td style="width: 50%; vertical-align: top; padding: 0 12px 0 0; border: none;">'
      + renderOne(left.block)
      + '</td><td style="width: 50%; vertical-align: top; padding: 0 0 0 12px; border: none;">'
      + (right ? renderOne(right.block) : '')
      + '</td></tr></table>'
  }).join('')
}

/** All registered block types, re-exported for the editor. */
export { BLOCK_TYPES }

import { BLOCK_TYPES, getBlockByType, type Block, type BlockValues } from './blockRegistry.ts'
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
const LEGACY_SAMPLE_LITERALS: Readonly<Record<string, Record<string, [string, string]>>> = {
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
const REMOVED_CONFIG_KEYS: readonly string[] = ['showUnit', 'showRate', 'decimalPlaces']

/** Deep-copies a JSON-compatible value. */
export function clone<T>(value: T): T {
  return value === undefined ? undefined : JSON.parse(JSON.stringify(value))
}

/**
 * Creates a new block with the registry defaults of its type.
 * @param type - Block type key (`header`, `address`, ...)
 * @param extraData - Data overriding the defaults (e.g. `{ role: 'receiver' }`)
 * @returns The block, or `null` for an unknown type
 */
export function createBlock(type: string, extraData: BlockValues = {}): Block | null {
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

/** The block set a new template starts with. */
export function createDefaultBlocks(): Block[] {
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
  ].filter((block): block is Block => block !== null)
}

/**
 * Brings one stored block up to the current registry shape: fills missing
 * config keys with defaults, drops removed keys and replaces legacy sample
 * literals by variables. Returns a new object; the input is not mutated.
 */
export function migrateBlock(block: Partial<Block> & { type: string }): Block {
  const migrated = clone(block) as Block
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
 * @returns Blocks in display order, without `order` fields
 */
export function normalizeBlocks(raw: string | unknown[] | null | undefined): Block[] {
  let blocks: unknown = raw
  if (typeof raw === 'string') {
    try {
      blocks = JSON.parse(raw)
    } catch {
      blocks = []
    }
  }
  if (!Array.isArray(blocks)) return []

  const sorted = (blocks as (Block | null)[])
    .filter((b): b is Block => !!b && typeof b === 'object' && !!b.type)
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
 */
export function serializeBlocks(blocks: Block[]): Block[] {
  return blocks.map((block, index) => ({ ...clone(block), order: index }))
}

/**
 * Moves the block at `from` to position `to` (both clamped) in place.
 * @returns Whether anything moved
 */
export function moveBlock<T>(blocks: T[], from: number, to: number): boolean {
  if (from < 0 || from >= blocks.length) return false
  const target = Math.max(0, Math.min(blocks.length - 1, to))
  if (target === from) return false
  const [block] = blocks.splice(from, 1)
  blocks.splice(target, 0, block)
  return true
}

/**
 * Copy of a block with a fresh id.
 */
export function duplicateBlock(block: Block): Block {
  return { ...clone(block), id: generateId() }
}

/**
 * Whether a block is half width (`config.width === 'half'`).
 */
export function isHalfWidth(block: Pick<Block, 'config'> | null | undefined): boolean {
  return !!(block && block.config && block.config.width === 'half')
}

/** One row of the page: one full-width block, or one or two half-width blocks. */
export interface LayoutRow<T> {
  half: boolean
  items: { block: T, index: number }[]
}

/**
 * Groups blocks into page rows: full-width blocks get a row of their own,
 * two consecutive half-width blocks share one, a half-width block without a
 * partner takes the left half. Same rule as the PDF renderer
 * (`TemplateBasedPdfGenerator.layoutBlocks`).
 */
export function layoutRows<T extends Pick<Block, 'config'>>(blocks: T[]): LayoutRow<T>[] {
  const rows: LayoutRow<T>[] = []
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
 */
export function composePageHtml(blocks: Block[], renderOne: (block: Block) => string): string {
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

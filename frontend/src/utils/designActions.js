import { getBlockByType, getFormat } from './blockRegistry.js'
import { clone, createBlock } from './blockModel.js'

/**
 * Template-wide design operations behind the editor's simple mode ("Design").
 *
 * Non-technical users change the look of the whole document with a few
 * controls (accent color, font, text size, which parts are shown) instead of
 * editing blocks one by one. Every operation is a pure function from a block
 * list to a new block list that writes the same per-block settings the
 * advanced editor and the PDF renderer use, so both modes stay one model.
 */

/** Blocks whose font color follows the accent color. */
const ACCENT_BLOCKS = ['header', 'totals']

/** Text size presets: factor applied to each block type's default font size. */
export const TEXT_SIZES = [
  { key: 'small', label: 'Klein', factor: 0.9 },
  { key: 'normal', label: 'Normal', factor: 1 },
  { key: 'large', label: 'Groß', factor: 1.15 }
]

/** Margin presets in mm (top, right, bottom, left). */
export const MARGIN_PRESETS = [
  { key: 'narrow', label: 'Schmal', value: 10 },
  { key: 'normal', label: 'Normal', value: 20 },
  { key: 'wide', label: 'Breit', value: 25 }
]

/** Page arrangements of the simple mode. */
export const LAYOUTS = [
  { key: 'stacked', label: 'Untereinander' },
  { key: 'compact', label: 'Nebeneinander' }
]

/**
 * Optional document parts the simple mode can switch on and off. `match`
 * identifies the block, `create` builds it, `after` lists block types the new
 * block is inserted behind (first match wins; otherwise appended).
 */
export const OPTIONAL_PARTS = [
  {
    key: 'sender',
    label: 'Absenderadresse',
    match: (b) => b.type === 'address' && (b.data.role || 'sender') === 'sender',
    create: () => createBlock('address', { role: 'sender' }),
    after: ['header']
  },
  {
    key: 'notes',
    label: 'Hinweise (Notizen des Dokuments)',
    match: (b) => b.type === 'notes',
    create: () => createBlock('notes'),
    after: ['totals', 'line-items']
  },
  {
    key: 'payment',
    label: 'Bankverbindung',
    match: (b) => b.type === 'payment',
    create: () => createBlock('payment'),
    after: ['notes', 'totals']
  },
  {
    key: 'footer',
    label: 'Fußzeile',
    match: (b) => b.type === 'footer',
    create: () => createBlock('footer'),
    after: []
  }
]

/**
 * Format of a block with defaults, as a fresh object.
 * @param {Object} block
 * @returns {Object}
 */
function formatOf(block) {
  const type = getBlockByType(block.type)
  return { ...getFormat(block.config, type && type.formatDefaults) }
}

/**
 * Applies `patch(block)` to a copy of every block.
 * @param {Object[]} blocks
 * @param {(block: Object) => void} patch
 * @returns {Object[]}
 */
function mapBlocks(blocks, patch) {
  return blocks.map(block => {
    const next = clone(block)
    next.config = next.config || {}
    patch(next)
    return next
  })
}

/**
 * Reads the current design from the blocks (for the controls' values).
 * @param {Object[]} blocks
 * @returns {{accentColor: string, fontFamily: string, textSize: string, parts: Object<string, boolean>, logoUrl: string}}
 */
export function readDesign(blocks) {
  const header = blocks.find(b => b.type === 'header')
  const accentSource = header || blocks.find(b => b.type === 'totals')
  const accentColor = accentSource ? formatOf(accentSource).fontColor : '#2563eb'
  const fontFamily = blocks.length ? formatOf(blocks[0]).fontFamily : 'Inter'
  const items = blocks.find(b => b.type === 'line-items')
  let textSize = 'normal'
  if (items) {
    const ratio = formatOf(items).fontSize / getBlockByType('line-items').formatDefaults.fontSize
    textSize = TEXT_SIZES.reduce((best, s) => (Math.abs(s.factor - ratio) < Math.abs(best.factor - ratio) ? s : best)).key
  }
  const parts = {}
  for (const part of OPTIONAL_PARTS) parts[part.key] = blocks.some(part.match)
  return { accentColor, fontFamily, textSize, parts, layout: readLayout(blocks), logoUrl: header ? header.data.logoUrl || '' : '' }
}

/** Index of the receiver address block, or -1. */
function receiverIndex(blocks) {
  return blocks.findIndex(b => b.type === 'address' && b.data && b.data.role === 'receiver')
}

/**
 * Current arrangement: `compact` when the receiver address and the document
 * data are half width and directly next to each other.
 * @param {Object[]} blocks
 * @returns {'stacked'|'compact'}
 */
export function readLayout(blocks) {
  const r = receiverIndex(blocks)
  const meta = blocks[r + 1]
  const half = (b) => b && b.config && b.config.width === 'half'
  return r >= 0 && meta && meta.type === 'invoice-meta' && half(blocks[r]) && half(meta) ? 'compact' : 'stacked'
}

/**
 * Arranges receiver address and document data: side by side (`compact`, the
 * document data moves right behind the receiver) or one below the other.
 * @param {Object[]} blocks
 * @param {'stacked'|'compact'} layout
 * @returns {Object[]}
 */
export function applyLayout(blocks, layout) {
  const next = mapBlocks(blocks, b => {
    if (b.type === 'address' || b.type === 'invoice-meta') delete b.config.width
  })
  if (layout !== 'compact') return next
  const r = receiverIndex(next)
  const m = next.findIndex(b => b.type === 'invoice-meta')
  if (r < 0 || m < 0) return next
  const [meta] = next.splice(m, 1)
  const target = receiverIndex(next) + 1
  next.splice(target, 0, meta)
  next[target - 1].config.width = 'half'
  meta.config.width = 'half'
  return next
}

/**
 * Sets the accent color (company name, total amount).
 * @param {Object[]} blocks
 * @param {string} color - Hex color
 * @returns {Object[]}
 */
export function applyAccentColor(blocks, color) {
  return mapBlocks(blocks, b => {
    if (ACCENT_BLOCKS.includes(b.type)) b.config.format = { ...formatOf(b), fontColor: color }
  })
}

/**
 * Sets one font for the whole document.
 * @param {Object[]} blocks
 * @param {string} fontFamily
 * @returns {Object[]}
 */
export function applyFontFamily(blocks, fontFamily) {
  return mapBlocks(blocks, b => { b.config.format = { ...formatOf(b), fontFamily } })
}

/**
 * Scales every block's font size relative to its type default.
 * @param {Object[]} blocks
 * @param {string} sizeKey - Key of {@link TEXT_SIZES}
 * @returns {Object[]}
 */
export function applyTextSize(blocks, sizeKey) {
  const size = TEXT_SIZES.find(s => s.key === sizeKey) || TEXT_SIZES[1]
  return mapBlocks(blocks, b => {
    const type = getBlockByType(b.type)
    if (!type) return
    const fontSize = Math.round(type.formatDefaults.fontSize * size.factor * 2) / 2
    b.config.format = { ...formatOf(b), fontSize }
  })
}

/**
 * Shows or hides an optional part (adds the block at a sensible position or
 * removes every matching block).
 * @param {Object[]} blocks
 * @param {string} partKey - Key of {@link OPTIONAL_PARTS}
 * @param {boolean} visible
 * @returns {Object[]}
 */
export function setPartVisible(blocks, partKey, visible) {
  const part = OPTIONAL_PARTS.find(p => p.key === partKey)
  if (!part) return blocks
  const present = blocks.some(part.match)
  if (!visible) return blocks.filter(b => !part.match(b))
  if (present) return blocks
  const next = [...blocks]
  let index = next.length
  for (const type of part.after) {
    const found = next.map(b => b.type).lastIndexOf(type)
    if (found >= 0) {
      index = found + 1
      break
    }
  }
  next.splice(index, 0, part.create())
  return next
}

/**
 * Sets the logo; adds a header block at the top when there is none.
 * @param {Object[]} blocks
 * @param {string} url
 * @returns {Object[]}
 */
export function applyLogo(blocks, url) {
  if (!blocks.some(b => b.type === 'header')) {
    const header = createBlock('header')
    header.data.logoUrl = url
    return [header, ...blocks]
  }
  return mapBlocks(blocks, b => { if (b.type === 'header') b.data.logoUrl = url })
}

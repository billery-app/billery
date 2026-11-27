import { createDefaultBlocks, createBlock } from './blockModel.ts'
import { documentTitle } from './blockRegistry.ts'
import { applyAccentColor, applyFontFamily, applyLayout, setPartVisible } from './designActions.js'
import { t } from '../i18n/index.js'

/**
 * Starting points offered when a new template is created ("Vorlage
 * erstellen"). Each preset builds a complete, valid block list from the
 * registry defaults plus a few design choices, so users start from a
 * finished document instead of an empty page.
 *
 * `build(documentTypeId)` returns `{ blocks, margins }`; the invoice-meta
 * title is set to the document type name ("Rechnung", "Angebot", ...; German, it is printed).
 * Name and description are getters over the message files (`presets.<key>`, #15).
 */

/**
 * Sets the title of the invoice-meta block.
 * @param {Object[]} blocks
 * @param {number} documentTypeId
 * @returns {Object[]}
 */
function withTitle(blocks, documentTypeId) {
  const meta = blocks.find(b => b.type === 'invoice-meta')
  if (meta) meta.data.invoiceTitle = documentTitle(documentTypeId)
  return blocks
}

export const TEMPLATE_PRESETS = [
  {
    key: 'modern',
    get name() { return t('presets.modern.name') },
    get description() { return t('presets.modern.description') },
    build(documentTypeId) {
      return { blocks: withTitle(createDefaultBlocks(), documentTypeId), margins: 15 }
    }
  },
  {
    key: 'classic',
    get name() { return t('presets.classic.name') },
    get description() { return t('presets.classic.description') },
    build(documentTypeId) {
      let blocks = applyFontFamily(createDefaultBlocks(), 'Georgia')
      blocks = applyAccentColor(blocks, '#111827')
      blocks = applyLayout(blocks, 'compact')
      for (const block of blocks) {
        if (block.type === 'notes') block.config.showBox = false
        if (block.type === 'header') block.config.logoAlignment = 'right'
      }
      return { blocks: withTitle(blocks, documentTypeId), margins: 20 }
    }
  },
  {
    key: 'minimal',
    get name() { return t('presets.minimal.name') },
    get description() { return t('presets.minimal.description') },
    build(documentTypeId) {
      let blocks = applyFontFamily(createDefaultBlocks(), 'Helvetica')
      blocks = applyAccentColor(blocks, '#374151')
      blocks = setPartVisible(blocks, 'sender', false)
      blocks = setPartVisible(blocks, 'notes', false)
      blocks = setPartVisible(blocks, 'footer', false)
      for (const block of blocks) {
        if (block.type === 'address') block.config.showLabels = false
      }
      return { blocks: withTitle(blocks, documentTypeId), margins: 20 }
    }
  },
  {
    key: 'empty',
    get name() { return t('presets.empty.name') },
    get description() { return t('presets.empty.description') },
    build(documentTypeId) {
      const blocks = [
        createBlock('address', { role: 'receiver' }),
        createBlock('invoice-meta'),
        createBlock('line-items'),
        createBlock('totals')
      ]
      return { blocks: withTitle(blocks, documentTypeId), margins: 20 }
    }
  }
]

/**
 * Looks up a preset.
 * @param {string} key
 * @returns {Object|undefined}
 */
export function getPreset(key) {
  return TEMPLATE_PRESETS.find(p => p.key === key)
}

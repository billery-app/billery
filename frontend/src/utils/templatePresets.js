import { createDefaultBlocks, createBlock } from './blockModel.js'
import { documentTitle } from './blockRegistry.js'
import { applyAccentColor, applyFontFamily, applyLayout, setPartVisible } from './designActions.js'

/**
 * Starting points offered when a new template is created ("Vorlage
 * erstellen"). Each preset builds a complete, valid block list from the
 * registry defaults plus a few design choices, so users start from a
 * finished document instead of an empty page.
 *
 * `build(documentTypeId)` returns `{ blocks, margins }`; the invoice-meta
 * title is set to the document type name ("Rechnung", "Angebot", ...).
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
    name: 'Modern',
    description: 'Klare Schrift, blaue Akzentfarbe, Hinweise in farbiger Box.',
    build(documentTypeId) {
      return { blocks: withTitle(createDefaultBlocks(), documentTypeId), margins: 15 }
    }
  },
  {
    key: 'classic',
    name: 'Klassisch',
    description: 'Serifenschrift, dezentes Schwarz, Empfänger und Dokumentdaten nebeneinander.',
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
    name: 'Minimal',
    description: 'Nur das Nötigste: ohne Absenderblock, Hinweise und Fußzeile.',
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
    name: 'Leer',
    description: 'Nur die Pflichtbestandteile – für eigene Gestaltung.',
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

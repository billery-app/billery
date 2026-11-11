import { BLOCK_TYPES, getBlockByType, blockDisplayName } from './blockRegistry.js'
import { unknownVariablesIn } from './variableText.js'

/**
 * Checks a template's block list before it is saved.
 *
 * - **errors** block saving: a required block is missing or a block occurs
 *   more often than allowed (the PDF renderer would print a document without
 *   line items, or two totals tables).
 * - **warnings** are shown but do not block: unknown variables (they are
 *   removed from the PDF), a missing sender or receiver address.
 *
 * Every issue carries the `blockId` it belongs to (when there is one) so the
 * editor can mark the block and jump to it.
 *
 * @param {Object[]} blocks
 * @returns {{errors: Issue[], warnings: Issue[]}}
 * @typedef {{message: string, blockId: (string|null)}} Issue
 */
export function validateBlocks(blocks) {
  const errors = []
  const warnings = []
  const list = Array.isArray(blocks) ? blocks : []

  for (const type of BLOCK_TYPES) {
    const instances = list.filter(b => b.type === type.type)
    const { max = Infinity, required = false } = type.limits || {}
    if (required && instances.length === 0) {
      errors.push({ message: `Der Block „${type.name}“ fehlt. Jedes Dokument braucht ihn.`, blockId: null })
    }
    if (instances.length > max) {
      errors.push({
        message: `„${type.name}“ darf nur ${max === 1 ? 'einmal' : `${max}-mal`} vorkommen.`,
        blockId: instances[max].id
      })
    }
  }

  const addresses = list.filter(b => b.type === 'address')
  const roles = addresses.map(b => (b.data && b.data.role) || 'sender')
  if (!roles.includes('receiver')) {
    warnings.push({ message: 'Es gibt keine Empfänger-Adresse: der Kunde erscheint nicht auf dem Dokument.', blockId: null })
  }
  if (roles.filter(r => r === 'receiver').length > 1 || roles.filter(r => r === 'sender').length > 1) {
    warnings.push({ message: 'Zwei Adressblöcke haben dieselbe Art (Absender/Empfänger).', blockId: addresses[1] && addresses[1].id })
  }

  for (const block of list) {
    const type = getBlockByType(block.type)
    if (!type) {
      warnings.push({ message: `Unbekannter Block „${block.type}“ wird nicht gedruckt.`, blockId: block.id })
      continue
    }
    for (const field of type.fields || []) {
      const value = block.data && block.data[field.key]
      if (typeof value !== 'string') continue
      for (const name of unknownVariablesIn(value)) {
        warnings.push({
          message: `${blockDisplayName(block)} › ${field.label}: Platzhalter „${name}“ ist unbekannt und wird nicht gedruckt.`,
          blockId: block.id
        })
      }
    }
  }
  return { errors, warnings }
}

/**
 * Whether one more block of a type may be added.
 * @param {Object[]} blocks
 * @param {string} type
 * @returns {boolean}
 */
export function canAddBlock(blocks, type) {
  const blockType = getBlockByType(type)
  if (!blockType) return false
  const max = (blockType.limits && blockType.limits.max) ?? Infinity
  return blocks.filter(b => b.type === type).length < max
}

import { BLOCK_TYPES, getBlockByType, blockDisplayName, type Block } from './blockRegistry.ts'
import { unknownVariablesIn } from './variableText.js'
import { t } from '../i18n/index.js'

/** A problem of a template, with the block it belongs to (null: the template as a whole). */
export interface Issue {
  message: string
  blockId: string | null
}

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
 */
export function validateBlocks(blocks: Block[] | null | undefined): { errors: Issue[], warnings: Issue[] } {
  const errors: Issue[] = []
  const warnings: Issue[] = []
  const list = Array.isArray(blocks) ? blocks : []

  for (const type of BLOCK_TYPES) {
    const instances = list.filter(b => b.type === type.type)
    const { max = Infinity, required = false } = type.limits || {}
    if (required && instances.length === 0) {
      errors.push({ message: t('editor.issues.required', { name: type.name }), blockId: null })
    }
    if (instances.length > max) {
      errors.push({
        message: max === 1 ? t('editor.issues.once', { name: type.name }) : t('editor.issues.max', { name: type.name, max }),
        blockId: instances[max].id
      })
    }
  }

  const addresses = list.filter(b => b.type === 'address')
  const roles = addresses.map(b => (b.data && b.data.role) || 'sender')
  if (!roles.includes('receiver')) {
    warnings.push({ message: t('editor.issues.noReceiver'), blockId: null })
  }
  if (roles.filter(r => r === 'receiver').length > 1 || roles.filter(r => r === 'sender').length > 1) {
    warnings.push({ message: t('editor.issues.sameRole'), blockId: addresses[1] && addresses[1].id })
  }

  for (const block of list) {
    const type = getBlockByType(block.type)
    if (!type) {
      warnings.push({ message: t('editor.issues.unknownBlock', { type: block.type }), blockId: block.id })
      continue
    }
    for (const field of type.fields || []) {
      const value = block.data && block.data[field.key]
      if (typeof value !== 'string') continue
      for (const name of unknownVariablesIn(value)) {
        warnings.push({
          message: t('editor.issues.unknownVariable', { block: blockDisplayName(block), field: field.label, name }),
          blockId: block.id
        })
      }
    }
  }
  return { errors, warnings }
}

/**
 * Whether one more block of a type may be added.
 */
export function canAddBlock(blocks: Block[], type: string): boolean {
  const blockType = getBlockByType(type)
  if (!blockType) return false
  const max = (blockType.limits && blockType.limits.max) ?? Infinity
  return blocks.filter(b => b.type === type).length < max
}

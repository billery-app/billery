import { describe, it, expect } from 'vitest'
import { validateBlocks, canAddBlock } from './blockValidation.js'
import { createBlock, createDefaultBlocks } from './blockModel.js'

describe('blockValidation', () => {
  it('accepts the default template', () => {
    const { errors, warnings } = validateBlocks(createDefaultBlocks())
    expect(errors).toEqual([])
    expect(warnings).toEqual([])
  })

  it('reports missing required blocks as errors', () => {
    const blocks = createDefaultBlocks().filter(b => b.type !== 'line-items')
    const { errors } = validateBlocks(blocks)
    expect(errors).toHaveLength(1)
    expect(errors[0].message).toContain('Positionen')
  })

  it('reports too many instances and points at the extra block', () => {
    const blocks = createDefaultBlocks()
    const extra = createBlock('totals')
    blocks.push(extra)
    const { errors } = validateBlocks(blocks)
    expect(errors[0].blockId).toBe(extra.id)
  })

  it('warns about unknown variables and a missing receiver', () => {
    const blocks = createDefaultBlocks().filter(b => !(b.type === 'address' && b.data.role === 'receiver'))
    blocks.find(b => b.type === 'footer').data.text = 'Danke {{kundenname}}'
    const { errors, warnings } = validateBlocks(blocks)
    expect(errors).toEqual([])
    expect(warnings.map(w => w.message).join('\n')).toContain('kundenname')
    expect(warnings.map(w => w.message).join('\n')).toContain('Empfänger')
  })

  it('canAddBlock respects the limits', () => {
    const blocks = createDefaultBlocks()
    expect(canAddBlock(blocks, 'totals')).toBe(false)
    expect(canAddBlock(blocks, 'notes')).toBe(true)
    expect(canAddBlock([], 'header')).toBe(true)
    expect(canAddBlock(blocks, 'unknown')).toBe(false)
  })
})

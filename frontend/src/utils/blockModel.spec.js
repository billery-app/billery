import { describe, it, expect } from 'vitest'
import {
  layoutRows, composePageHtml, isHalfWidth,
  createBlock, createDefaultBlocks, migrateBlock, normalizeBlocks, serializeBlocks, moveBlock, duplicateBlock
} from './blockModel.js'

describe('blockModel', () => {
  it('createBlock applies role presets for addresses', () => {
    const receiver = createBlock('address', { role: 'receiver' })
    expect(receiver.data.name).toBe('{{kunde}}')
    expect(receiver.config.label).toBe('Empfänger')
    const sender = createBlock('address')
    expect(sender.data.name).toBe('{{firma}}')
    expect(sender.config.label).toBe('Absender')
    expect(createBlock('nope')).toBeNull()
  })

  it('createBlock deep-copies defaults (no shared column arrays)', () => {
    const a = createBlock('line-items')
    const b = createBlock('line-items')
    a.data.columns[0].label = 'X'
    expect(b.data.columns[0].label).toBe('Beschreibung')
  })

  it('the default template has every required block', () => {
    const types = createDefaultBlocks().map(b => b.type)
    expect(types).toEqual(expect.arrayContaining(['invoice-meta', 'line-items', 'totals']))
  })

  it('normalizeBlocks sorts by stored order (stable) and strips order', () => {
    const blocks = normalizeBlocks(JSON.stringify([
      { id: 'c', type: 'footer', order: 2 },
      { id: 'a', type: 'header', order: 0 },
      { id: 'b1', type: 'notes', order: 1 },
      { id: 'b2', type: 'notes', order: 1 }
    ]))
    expect(blocks.map(b => b.id)).toEqual(['a', 'b1', 'b2', 'c'])
    expect(blocks.every(b => !('order' in b))).toBe(true)
  })

  it('normalizeBlocks tolerates broken input', () => {
    expect(normalizeBlocks('not json')).toEqual([])
    expect(normalizeBlocks(null)).toEqual([])
    expect(normalizeBlocks([null, { foo: 1 }])).toEqual([])
  })

  it('migrateBlock fills config defaults, drops removed keys and replaces sample literals', () => {
    const block = migrateBlock({
      id: 'm', type: 'invoice-meta', order: 3,
      data: { invoiceId: 'INV-2026-001', invoiceDate: '2026-03-17', dueDate: 'eigener Text' },
      config: { showUnit: true }
    })
    expect(block.data.invoiceId).toBe('{{rechnungsnummer}}')
    expect(block.data.invoiceDate).toBe('{{rechnungsdatum}}')
    expect(block.data.dueDate).toBe('eigener Text')
    expect(block.config.alignment).toBe('right')
    expect(block.config).not.toHaveProperty('showUnit')
    expect(block).not.toHaveProperty('order')
  })

  it('migrateBlock reads a legacy 18pt invoice-meta size as 10.5pt body text', () => {
    const block = migrateBlock({ id: 'm', type: 'invoice-meta', data: {}, config: { format: { fontSize: 18 } } })
    expect(block.config.format.fontSize).toBe(10.5)
    const custom = migrateBlock({ id: 'm', type: 'invoice-meta', data: {}, config: { format: { fontSize: 12 } } })
    expect(custom.config.format.fontSize).toBe(12)
  })

  it('normalizeBlocks moves the legacy header title to invoice-meta', () => {
    const blocks = normalizeBlocks([
      { id: 'h', type: 'header', data: { invoiceTitle: 'Angebot' }, config: { layout: 'x' } },
      { id: 'm', type: 'invoice-meta', data: {}, config: {} }
    ])
    expect(blocks[0].data).not.toHaveProperty('invoiceTitle')
    expect(blocks[1].data.invoiceTitle).toBe('Angebot')
  })

  it('serializeBlocks writes order from the array position', () => {
    const out = serializeBlocks([{ id: 'x', type: 'header' }, { id: 'y', type: 'footer' }])
    expect(out.map(b => b.order)).toEqual([0, 1])
  })

  it('moveBlock moves and clamps', () => {
    const list = ['a', 'b', 'c']
    expect(moveBlock(list, 0, 2)).toBe(true)
    expect(list).toEqual(['b', 'c', 'a'])
    expect(moveBlock(list, 2, 99)).toBe(false)
    expect(moveBlock(list, 5, 0)).toBe(false)
  })

  it('duplicateBlock gives a new id and an independent copy', () => {
    const original = createBlock('notes')
    const copy = duplicateBlock(original)
    expect(copy.id).not.toBe(original.id)
    copy.data.content = 'x'
    expect(original.data.content).toBe('{{notizen}}')
  })

  it('layoutRows pairs consecutive half-width blocks like the PDF renderer', () => {
    const half = (id) => ({ id, type: 'notes', config: { width: 'half' } })
    const full = (id) => ({ id, type: 'notes', config: {} })
    const rows = layoutRows([full('a'), half('b'), half('c'), half('d'), full('e')])
    expect(rows.map(r => [r.half, r.items.map(i => i.block.id)])).toEqual([
      [false, ['a']], [true, ['b', 'c']], [true, ['d']], [false, ['e']]
    ])
    expect(rows[1].items[1].index).toBe(2)
    expect(isHalfWidth(half('x'))).toBe(true)
  })

  it('composePageHtml wraps half rows in the same table markup as the PDF', () => {
    const html = composePageHtml(
      [{ id: 'a', config: { width: 'half' } }, { id: 'b', config: {} }],
      (block) => `<p>${block.id}</p>`
    )
    expect(html).toBe(
      '<table class="block-row" style="width: 100%; border-collapse: collapse; margin: 0;">'
      + '<tr><td style="width: 50%; vertical-align: top; padding: 0 12px 0 0; border: none;"><p>a</p></td>'
      + '<td style="width: 50%; vertical-align: top; padding: 0 0 0 12px; border: none;"></td></tr></table><p>b</p>'
    )
  })
})

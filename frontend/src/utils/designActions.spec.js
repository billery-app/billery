import { describe, it, expect } from 'vitest'
import {
  readLayout, applyLayout,
  readDesign, applyAccentColor, applyFontFamily, applyTextSize, setPartVisible, applyLogo
} from './designActions.js'
import { createDefaultBlocks } from './blockModel.js'
import { TEMPLATE_PRESETS, getPreset } from './templatePresets.js'
import { validateBlocks } from './blockValidation.js'

describe('designActions', () => {
  it('reads the design of the default template', () => {
    const design = readDesign(createDefaultBlocks())
    expect(design).toMatchObject({ accentColor: '#2563eb', fontFamily: 'Inter', textSize: 'normal', logoUrl: '' })
    expect(design.parts).toEqual({ sender: true, notes: true, payment: true, footer: true })
  })

  it('accent color changes header and totals only, without mutating the input', () => {
    const blocks = createDefaultBlocks()
    const next = applyAccentColor(blocks, '#ff0000')
    expect(next.find(b => b.type === 'header').config.format.fontColor).toBe('#ff0000')
    expect(next.find(b => b.type === 'totals').config.format.fontColor).toBe('#ff0000')
    expect(next.find(b => b.type === 'footer').config.format.fontColor).toBe('#9ca3af')
    expect(blocks.find(b => b.type === 'header').config.format.fontColor).toBe('#2563eb')
    expect(readDesign(next).accentColor).toBe('#ff0000')
  })

  it('font family applies to every block', () => {
    const next = applyFontFamily(createDefaultBlocks(), 'Georgia')
    expect(next.every(b => b.config.format.fontFamily === 'Georgia')).toBe(true)
  })

  it('text size scales each block from its own default', () => {
    const next = applyTextSize(createDefaultBlocks(), 'large')
    expect(next.find(b => b.type === 'header').config.format.fontSize).toBe(24)
    expect(next.find(b => b.type === 'line-items').config.format.fontSize).toBe(12)
    expect(readDesign(next).textSize).toBe('large')
  })

  it('switches optional parts off and on at a sensible position', () => {
    let blocks = setPartVisible(createDefaultBlocks(), 'payment', false)
    expect(blocks.some(b => b.type === 'payment')).toBe(false)
    blocks = setPartVisible(blocks, 'payment', true)
    const types = blocks.map(b => b.type)
    expect(types.indexOf('payment')).toBe(types.indexOf('notes') + 1)
    blocks = setPartVisible(blocks, 'footer', false)
    blocks = setPartVisible(blocks, 'footer', true)
    expect(blocks[blocks.length - 1].type).toBe('footer')
    expect(setPartVisible(blocks, 'unknown', true)).toBe(blocks)
  })

  it('compact layout puts the document data right of the receiver, stacked undoes it', () => {
    const blocks = createDefaultBlocks()
    expect(readLayout(blocks)).toBe('stacked')
    const compact = applyLayout(blocks, 'compact')
    const r = compact.findIndex(b => b.type === 'address' && b.data.role === 'receiver')
    expect(compact[r + 1].type).toBe('invoice-meta')
    expect(compact[r].config.width).toBe('half')
    expect(compact[r + 1].config.width).toBe('half')
    expect(readDesign(compact).layout).toBe('compact')
    const stacked = applyLayout(compact, 'stacked')
    expect(stacked.some(b => b.config.width === 'half')).toBe(false)
    expect(readLayout(stacked)).toBe('stacked')
  })

  it('logo adds a header when there is none', () => {
    const withoutHeader = createDefaultBlocks().filter(b => b.type !== 'header')
    const next = applyLogo(withoutHeader, '/uploads/l.png')
    expect(next[0].type).toBe('header')
    expect(readDesign(next).logoUrl).toBe('/uploads/l.png')
  })
})

describe('templatePresets', () => {
  it.each(TEMPLATE_PRESETS.map(p => [p.key, p]))('%s builds a valid template with the type title', (key, preset) => {
    const { blocks, margins } = preset.build(2)
    expect(validateBlocks(blocks).errors).toEqual([])
    expect(blocks.find(b => b.type === 'invoice-meta').data.invoiceTitle).toBe('Angebot')
    expect(margins).toBeGreaterThan(0)
  })

  it('getPreset finds presets by key', () => {
    expect(getPreset('classic').name).toBe('Klassisch')
    expect(getPreset('nope')).toBeUndefined()
  })
})

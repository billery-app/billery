import { describe, it, expect } from 'vitest'
import { applySections, templateToSections } from './documentExchange.js'
import { TEMPLATE_PRESETS } from './templatePresets.js'
import { createBlock } from './blockModel.js'

const presetBlocks = () => TEMPLATE_PRESETS[0].build(1).blocks
const byType = (blocks, type) => blocks.filter(b => b.type === type)

describe('templateToSections', () => {
  it('writes one section per block with text, "Label: Wert" lines and the variables as they are', () => {
    const blocks = [
      createBlock('header', { companyName: '{{firma}}', companySlogan: '' }),
      createBlock('line-items'),
      createBlock('notes', { content: 'Zahlbar in 14 Tagen.\nDanke!' })
    ]

    expect(templateToSections(blocks)).toEqual([
      { heading: 'Kopfzeile', paragraphs: ['Firmenname: {{firma}}'] },
      { heading: 'Hinweise', paragraphs: ['Text: Zahlbar in 14 Tagen.', 'Danke!'] }
    ])
  })

  it('numbers a second block of the same kind', () => {
    const sections = templateToSections([
      createBlock('notes', { content: 'A' }),
      createBlock('notes', { content: 'B' })
    ])
    expect(sections.map(s => s.heading)).toEqual(['Hinweise', 'Hinweise (2)'])
  })
})

describe('applySections', () => {
  it('brings an exported template back with every text (round trip)', () => {
    const original = presetBlocks()
    const footer = byType(original, 'footer')[0]
    footer.data.text = 'Vielen Dank!\nBis bald.'
    byType(original, 'notes').forEach(n => { n.data.content = 'Zahlbar sofort.' })

    const { blocks } = applySections(presetBlocks(), templateToSections(original))

    expect(templateToSections(blocks)).toEqual(templateToSections(original))
  })

  it('maps labelled lines into the block the heading names, case and colon ignored', () => {
    const { blocks, fields, freeText } = applySections(presetBlocks(), [
      { heading: 'fußzeile:', paragraphs: ['text: Danke für Ihren Auftrag', 'Website: www.example.de'] }
    ])
    const footer = byType(blocks, 'footer')[0]
    expect(footer.data.text).toBe('Danke für Ihren Auftrag')
    expect(footer.data.website).toBe('www.example.de')
    expect(fields).toBe(2)
    expect(freeText).toBe(0)
  })

  it('puts an ordinary letter into the notes, before the document notes', () => {
    const { blocks, freeText } = applySections(presetBlocks(), [
      { heading: null, paragraphs: ['Sehr geehrte Damen und Herren,', 'anbei unsere Rechnung.'] },
      { heading: 'Zahlungsbedingungen', paragraphs: ['14 Tage netto.'] }
    ])
    const notes = byType(blocks, 'notes')
    expect(notes).toHaveLength(1)
    expect(notes[0].data.content).toBe(
      'Sehr geehrte Damen und Herren,\nanbei unsere Rechnung.\nZahlungsbedingungen\n14 Tage netto.\n\n{{notizen}}'
    )
    expect(freeText).toBe(4)
  })

  it('adds a block the file names but the preset lacks, before the footer', () => {
    const preset = presetBlocks().filter(b => b.type !== 'notes')
    const { blocks } = applySections(preset, [{ heading: 'Hinweise', paragraphs: ['Text: Neu'] }])
    const types = blocks.map(b => b.type)
    expect(types).toContain('notes')
    expect(types.indexOf('notes')).toBeLessThan(types.indexOf('footer'))
    expect(byType(blocks, 'notes')[0].data.content).toBe('Neu')
  })

  it('does not change the preset blocks it was given', () => {
    const preset = presetBlocks()
    const before = JSON.stringify(preset)
    applySections(preset, [{ heading: 'Fußzeile', paragraphs: ['Text: anders'] }])
    expect(JSON.stringify(preset)).toBe(before)
  })
})

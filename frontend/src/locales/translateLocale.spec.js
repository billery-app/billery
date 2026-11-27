import { describe, expect, it } from 'vitest'
import de from './de.json'
import en from './en.json'
import {
  flatten, isShort, matchInitialCase, planRequests, protect, restore, unflatten
} from '../../../scripts/translate-locale.mjs'

/**
 * The DeepL script (`scripts/translate-locale.mjs`) hides the vue-i18n syntax from the
 * translation. Every message we have must come back unchanged, and a lost or invented
 * placeholder must be refused, never written into a message file.
 */
describe('translate-locale.mjs', () => {
  it('brings every message of both files back unchanged', () => {
    for (const [key, text] of [...flatten(de), ...flatten(en)]) {
      const { xml, tokens } = protect(text)
      expect(xml, key).not.toMatch(/\{|\}| \| /)
      expect(restore(xml, tokens), key).toBe(text)
    }
  })

  it('refuses a translation that lost, doubled or invented a placeholder', () => {
    const { tokens } = protect('Hallo {name}, {n} Einsatz | {n} Einsätze')
    expect(restore('Hello <x i="0"/>', tokens)).toBeNull()
    expect(restore('<x i="0"/> <x i="0"/> <x i="1"/><x i="2"/><x i="3"/>', tokens)).toBeNull()
    expect(restore('<x i="0"/> <x i="1"/><x i="2"/><x i="3"/> <x i="9"/>', tokens)).toBeNull()
    expect(restore('Hi <x i="0"/>, <x i="1"/> job<x i="2"/><x i="3"/> jobs', tokens)).toBe('Hi {name}, {n} job | {n} jobs')
  })

  it('keeps literals with braces whole', () => {
    expect(protect("{'{{current}}'} von {'{{total}}'}").tokens).toEqual(["{'{{current}}'}", "{'{{total}}'}"])
  })

  it('keeps the initial capital of a German label, but not before a placeholder or number', () => {
    expect(matchInitialCase('Rechnung RE-1', 'invoice RE-1')).toBe('Invoice RE-1')
    expect(matchInitialCase('„Kopfzeile“ fehlt', '“header” missing')).toBe('“Header” missing')
    expect(matchInitialCase('{n} Einsätze', '{n} jobs')).toBe('{n} jobs')
    expect(matchInitialCase('oder Bild hierher ziehen', 'or drag an image here')).toBe('or drag an image here')
    expect(matchInitialCase('3 Tage', '3 days')).toBe('3 days')
  })

  it('keeps exchange words in German quotation marks, and only those', () => {
    const text = 'Abschnitte wie „Kopfzeile“ mit Zeilen wie „Text: …“, nicht „Werkzeuge“ oder „Textblock“.'
    const { xml, tokens } = protect(text, ['Kopfzeile', 'Text'])
    expect(tokens).toEqual(['Kopfzeile', 'Text: …'])
    expect(xml).toContain('„Werkzeuge“')
    expect(xml).toContain('„Textblock“')
    expect(restore(xml, tokens)).toBe(text)
  })

  it('sends short labels grouped by their screen area, with the German neighbours as context', () => {
    expect(isShort('Links')).toBe(true)
    expect(isShort("{'{{current}}'} von {'{{total}}'}")).toBe(true)
    expect(isShort('Bitte eine Datei auswählen.')).toBe(false)
    const all = flatten(de)
    const requests = planRequests(all, all)
    const sides = requests.find(r => r.items.some(([key]) => key === 'design.sides.left'))
    expect(sides.context).toContain('Oben')
    expect(sides.context.length).toBeLessThanOrEqual(500)
    expect(requests.flatMap(r => r.items).length).toBe(all.length)
  })

  it('rebuilds the message tree in the same order', () => {
    expect(JSON.stringify(unflatten(flatten(de)))).toBe(JSON.stringify(de))
  })
})

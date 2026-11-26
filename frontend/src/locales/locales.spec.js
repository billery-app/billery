import { describe, expect, it } from 'vitest'
import de from './de.json'
import en from './en.json'
import { t, applyLanguage } from '../i18n/index.js'

/** All keys of a message tree, dotted. */
function keys(tree, prefix = '') {
  return Object.entries(tree).flatMap(([key, value]) =>
    value && typeof value === 'object' ? keys(value, `${prefix}${key}.`) : [`${prefix}${key}`]
  )
}

// every source file of the app, as text (the spec files are left out)
const sources = import.meta.glob(['../**/*.{js,vue}', '!../**/*.spec.js', '!../test/**'], {
  query: '?raw',
  import: 'default',
  eager: true
})

describe('message files (#15)', () => {
  it('German and English have the same keys', () => {
    expect(keys(en).sort()).toEqual(keys(de).sort())
  })

  it('no message is empty', () => {
    for (const [name, tree] of Object.entries({ de, en })) {
      const flat = keys(tree)
      for (const key of flat) {
        const value = key.split('.').reduce((node, part) => node[part], tree)
        expect(value.trim(), `${name}: ${key}`).not.toBe('')
      }
    }
  })

  it('every key the code uses exists', () => {
    const known = new Set(keys(de))
    const used = new Map()
    // $t('a.b'), t('a.b'), t(`a.b`) with a literal key; keys built at run time are checked where
    // they are built (settingsAreas, tour)
    const pattern = /(?:\$t|\bt)\(\s*['`]([a-zA-Z][\w.]*)['`]/g
    for (const [file, text] of Object.entries(sources)) {
      for (const match of text.matchAll(pattern)) used.set(match[1], file)
    }
    expect(used.size).toBeGreaterThan(20)
    const missing = [...used].filter(([key]) => !known.has(key)).map(([key, file]) => `${key} (${file})`)
    expect(missing).toEqual([])
  })

  it('every message compiles in both languages', () => {
    for (const code of ['de', 'en']) {
      applyLanguage(code)
      for (const key of keys(de)) {
        expect(() => t(key), `${code}: ${key}`).not.toThrow()
        expect(t(key), `${code}: ${key}`).not.toBe(key)
      }
    }
    applyLanguage('de')
  })

  it('keeps driver.js placeholders and literal characters', () => {
    expect(t('tour.progress')).toBe('{{current}} von {{total}}')
    expect(t('tour.templates.preview.description')).toContain(' @ ')
    applyLanguage('en')
    expect(t('tour.progress')).toBe('{{current}} of {{total}}')
    applyLanguage('de')
  })
})

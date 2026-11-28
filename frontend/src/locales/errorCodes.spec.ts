import { describe, expect, it } from 'vitest'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import de from './de.json'
import en from './en.json'

/**
 * The backend's message codes (#15) and the SPA's message files must agree: every code of
 * `ErrorCode.java` / `SaasErrorCode.java` is in both files under `errors.<code>`, the German text
 * is the Java template word for word (it is the fallback the backend sends), the English text has
 * the same placeholders, and the files hold no code the backend no longer has.
 */
const ENUMS = [
  '../../../backend/src/main/java/de/billery/api/error/ErrorCode.java',
  '../../../backend/src/saas/java/de/billery/saas/api/SaasErrorCode.java'
]

/** `NAME(\n "code",\n "template")` entries of the generated enums. */
const ENTRY = /^ {2}[A-Z0-9_]+\(\s*"([^"]+)",\s*"((?:[^"\\]|\\.)*)"\)[,;]/gm

function javaCodes(): Map<string, string> {
  const codes = new Map<string, string>()
  for (const file of ENUMS) {
    const source = readFileSync(fileURLToPath(new URL(file, import.meta.url)), 'utf8')
    for (const [, code, template] of source.matchAll(ENTRY)) {
      codes.set(code, template.replace(/\\(["\\])/g, '$1'))
    }
  }
  return codes
}

type Tree = { [key: string]: string | Tree }

function messagesUnder(tree: Tree, prefix = ''): Map<string, string> {
  const out = new Map<string, string>()
  for (const [key, value] of Object.entries(tree)) {
    const path = prefix ? `${prefix}.${key}` : key
    if (typeof value === 'string') out.set(path, value)
    else for (const [k, v] of messagesUnder(value, path)) out.set(k, v)
  }
  return out
}

/** vue-i18n writes a literal `@` as `{'@'}`; the Java template has the plain character. */
const plain = (message: string) => message.replace(/\{'@'\}/g, '@')
const placeholders = (text: string) => [...text.matchAll(/\{(\w+)\}/g)].map(m => m[1]).sort()

describe('backend message codes', () => {
  const codes = javaCodes()
  const german = messagesUnder((de as unknown as { errors: Tree }).errors)
  const english = messagesUnder((en as unknown as { errors: Tree }).errors)

  it('reads the codes of both enums', () => {
    expect(codes.size).toBeGreaterThan(100)
    expect(codes.has('invoice.issueNotDraft')).toBe(true)
    expect(codes.has('saas.lastOwnerRemove')).toBe(true)
  })

  it('has every code in German, word for word as the Java template, and in English', () => {
    for (const [code, template] of codes) {
      expect(german.has(code), `de: errors.${code}`).toBe(true)
      expect(english.has(code), `en: errors.${code}`).toBe(true)
      expect(plain(german.get(code)!), code).toBe(template)
      expect(placeholders(plain(english.get(code)!)), `${code} placeholders`).toEqual(placeholders(template))
    }
  })

  it('keeps no code the backend no longer sends', () => {
    expect([...german.keys()].filter(code => !codes.has(code))).toEqual([])
    expect([...english.keys()].filter(code => !codes.has(code))).toEqual([])
  })
})

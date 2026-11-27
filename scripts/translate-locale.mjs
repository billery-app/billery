#!/usr/bin/env node
/**
 * Drafts the interface texts of a language with DeepL (#15): reads the German message file
 * (`frontend/src/locales/de.json`) and writes `<target>.json` next to it, in the same key order.
 *
 * A developer tool, never part of the app or of CI: only our own interface texts go to DeepL,
 * never customer data. The result is a draft that a person who speaks the language reviews
 * before a release (`locales.spec.js` then checks keys and syntax).
 *
 * Usage (Node 24, from the repository root):
 *   node scripts/translate-locale.mjs <target> [options]
 *     <target>          language code of the message file, e.g. fr, es, it, en
 *     --all             translate every key (default: only keys missing in the target file, so
 *                       corrections made by hand are never overwritten)
 *     --out <file>      write here instead of frontend/src/locales/<target>.json
 *     --source <code>   source message file (default de)
 *     --formality <f>   DeepL formality: default, more, less, prefer_more, prefer_less
 *     --dry-run         only count what would be sent
 *
 * The key comes from DEEPL_API_KEY or the file ~/.config/billery/deepl.key (never from the
 * repository; `.gitignore` refuses `*.deepl` / `deepl.key`). A free key (ending in `:fx`) uses
 * api-free.deepl.com.
 *
 * What the script protects:
 * - vue-i18n syntax: placeholders `{name}`, literals `{'@'}` and the plural separator ` | ` are
 *   replaced by XML tags DeepL leaves alone (`tag_handling=xml`, `ignore_tags=x`) and put back;
 *   a translation whose placeholders differ from the source is reported and left out.
 * - Terms: `scripts/i18n-glossary/<source>-<target>.tsv` (one `German<TAB>translation` per
 *   line), sent as a DeepL glossary for this run and deleted afterwards.
 * - Initial capitals: a message that starts with a capital letter keeps one (DeepL writes
 *   glossary terms in their listed case, also at the start).
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const LOCALES = join(ROOT, 'frontend', 'src', 'locales')
const GLOSSARIES = join(ROOT, 'scripts', 'i18n-glossary')
/** Texts per request (DeepL accepts up to 50). */
const BATCH = 50
/** Context for every request: not translated or billed, it steers the wording. */
const CONTEXT = 'User interface texts of an invoicing web application for small German businesses '
  + '(invoices, offers, cost estimates, customers, templates). Short labels, buttons and messages.'
/** DeepL target codes where a plain language code is ambiguous. */
const TARGET_CODES = { en: 'EN-GB', pt: 'PT-PT', zh: 'ZH-HANS' }

/**
 * Reads the command line.
 * @param {string[]} argv
 * @returns {{target: string, all: boolean, out: ?string, source: string, formality: ?string, dryRun: boolean}}
 */
function parseArgs(argv) {
  const options = { target: '', all: false, out: null, source: 'de', formality: null, dryRun: false }
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i]
    if (arg === '--all') options.all = true
    else if (arg === '--dry-run') options.dryRun = true
    else if (arg === '--out') options.out = argv[++i]
    else if (arg === '--source') options.source = argv[++i]
    else if (arg === '--formality') options.formality = argv[++i]
    else if (!arg.startsWith('--') && !options.target) options.target = arg
    else throw new Error(`Unknown argument: ${arg}`)
  }
  if (!/^[a-z]{2}(-[a-z]{2})?$/i.test(options.target)) {
    throw new Error('Usage: node scripts/translate-locale.mjs <target> [--all] [--out file] [--source de] [--formality f] [--dry-run]')
  }
  return options
}

/** @returns {string} the API key from the environment or the user's config file */
function apiKey() {
  if (process.env.DEEPL_API_KEY) return process.env.DEEPL_API_KEY.trim()
  const file = join(homedir(), '.config', 'billery', 'deepl.key')
  if (existsSync(file)) return readFileSync(file, 'utf8').trim()
  throw new Error('No DeepL key: set DEEPL_API_KEY or put it into ~/.config/billery/deepl.key')
}

/**
 * The messages as `[dotted key, text]` pairs in file order.
 * @param {Object} tree
 * @param {string} [prefix]
 * @returns {[string, string][]}
 */
export function flatten(tree, prefix = '') {
  return Object.entries(tree).flatMap(([key, value]) => {
    const path = prefix ? `${prefix}.${key}` : key
    return typeof value === 'object' && value !== null ? flatten(value, path) : [[path, value]]
  })
}

/**
 * Builds the nested message tree from pairs, in their order.
 * @param {[string, string][]} pairs
 * @returns {Object}
 */
export function unflatten(pairs) {
  const tree = {}
  for (const [path, value] of pairs) {
    const parts = path.split('.')
    let node = tree
    for (const part of parts.slice(0, -1)) node = node[part] ??= {}
    node[parts.at(-1)] = value
  }
  return tree
}

/**
 * vue-i18n syntax that must reach the translation unchanged: literals first (`{'@'}`,
 * `{'{{current}}'}`, which contain braces themselves), then placeholders `{name}` and the plural
 * separator ` | `.
 */
const SYNTAX = /\{'[^']*'\}|\{[^{}]*\}| \| /g

/**
 * Replaces the vue-i18n syntax of a message by numbered XML tags DeepL does not touch.
 * @param {string} text
 * @returns {{xml: string, tokens: string[]}}
 */
export function protect(text) {
  const tokens = []
  const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  const xml = escaped.replace(SYNTAX, (match) => {
    tokens.push(match)
    return `<x i="${tokens.length - 1}"/>`
  })
  return { xml, tokens }
}

/**
 * Puts the syntax back into a translation.
 * @param {string} xml - DeepL's answer
 * @param {string[]} tokens - from {@link protect}
 * @returns {string|null} the message, or null when a tag was lost, doubled or invented
 */
export function restore(xml, tokens) {
  const seen = new Set()
  let broken = false
  const text = xml.replace(/<x i="(\d+)"\s*\/>/g, (_, index) => {
    const i = Number(index)
    if (i >= tokens.length || seen.has(i)) broken = true
    seen.add(i)
    return tokens[i] ?? ''
  })
  if (broken || seen.size !== tokens.length || /<\/?x\b/.test(text)) return null
  return text.replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
}

/**
 * A label or sentence that starts with a capital letter in German starts with one in the
 * translation too. DeepL writes glossary terms as listed (lower case), also at the start:
 * "Rechnung RE-1" would become "invoice RE-1".
 * @param {string} source - the German message
 * @param {string} translation
 * @returns {string}
 */
export function matchInitialCase(source, translation) {
  // the first letter must open the text: only punctuation or spaces before it, no placeholder
  // ("{n} Einsätze") and no digit, in the German text and in the translation
  const opening = /^[\s\p{P}]*?(\p{L})/u
  const sourceStart = opening.exec(source)
  const start = opening.exec(translation)
  if (!sourceStart || !start || /[{}]/.test(start[0]) || /[{}]/.test(sourceStart[0])) return translation
  if (sourceStart[1] === sourceStart[1].toLowerCase()) return translation
  const at = start[0].length - 1
  return translation.slice(0, at) + translation[at].toUpperCase() + translation.slice(at + 1)
}

/**
 * One call of the DeepL API.
 * @param {string} base - https://api(-free).deepl.com
 * @param {string} key
 * @param {string} path - e.g. /v2/translate
 * @param {Object} [body] - JSON body (POST); without it a GET
 * @param {string} [method]
 * @returns {Promise<any>}
 */
async function deepl(base, key, path, body, method = body ? 'POST' : 'GET') {
  const response = await fetch(base + path, {
    method,
    headers: { Authorization: `DeepL-Auth-Key ${key}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
    body: body ? JSON.stringify(body) : undefined
  })
  if (!response.ok) throw new Error(`DeepL ${method} ${path}: ${response.status} ${await response.text()}`)
  return response.status === 204 ? null : response.json()
}

/**
 * Creates the glossary of this language pair for the run, if a TSV file exists.
 * @returns {Promise<string|null>} the glossary id
 */
async function createGlossary(base, key, source, target) {
  const file = join(GLOSSARIES, `${source}-${target}.tsv`)
  if (!existsSync(file)) return null
  const entries = readFileSync(file, 'utf8').split('\n')
    .filter(line => line.trim() && !line.startsWith('#'))
    .join('\n')
  const glossary = await deepl(base, key, '/v2/glossaries', {
    name: `billery-${source}-${target}-${Date.now()}`,
    source_lang: source,
    target_lang: target.split('-')[0],
    entries,
    entries_format: 'tsv'
  })
  return glossary.glossary_id
}

async function main() {
  const options = parseArgs(process.argv.slice(2))
  const sourcePairs = flatten(JSON.parse(readFileSync(join(LOCALES, `${options.source}.json`), 'utf8')))
  const outFile = resolve(options.out || join(LOCALES, `${options.target}.json`))
  const existing = !options.all && existsSync(outFile)
    ? new Map(flatten(JSON.parse(readFileSync(outFile, 'utf8'))))
    : new Map()
  const todo = sourcePairs.filter(([path]) => !existing.has(path))
  const characters = todo.reduce((sum, [, text]) => sum + text.length, 0)
  console.log(`${todo.length} of ${sourcePairs.length} texts to translate, ${characters} characters`)
  if (options.dryRun || todo.length === 0) return

  const key = apiKey()
  const base = key.endsWith(':fx') ? 'https://api-free.deepl.com' : 'https://api.deepl.com'
  const targetCode = TARGET_CODES[options.target.toLowerCase()] || options.target.toUpperCase()
  const glossaryId = await createGlossary(base, key, options.source, options.target.toLowerCase())
  const translated = new Map()
  const rejected = []
  try {
    for (let start = 0; start < todo.length; start += BATCH) {
      const batch = todo.slice(start, start + BATCH)
      const protectedTexts = batch.map(([, text]) => protect(text))
      const result = await deepl(base, key, '/v2/translate', {
        text: protectedTexts.map(p => p.xml),
        source_lang: options.source.toUpperCase(),
        target_lang: targetCode,
        tag_handling: 'xml',
        ignore_tags: ['x'],
        context: CONTEXT,
        ...(glossaryId ? { glossary_id: glossaryId } : {}),
        ...(options.formality ? { formality: options.formality } : {})
      })
      result.translations.forEach((translation, i) => {
        const [path, original] = batch[i]
        const text = restore(translation.text, protectedTexts[i].tokens)
        if (text === null) rejected.push([path, original, translation.text])
        else translated.set(path, matchInitialCase(original, text))
      })
      process.stdout.write(`\r${Math.min(start + BATCH, todo.length)} / ${todo.length}`)
    }
    process.stdout.write('\n')
  } finally {
    if (glossaryId) await deepl(base, key, `/v2/glossaries/${glossaryId}`, undefined, 'DELETE')
  }

  // Source order; keys the translation lost stay missing, so locales.spec.js names them.
  const pairs = sourcePairs
    .map(([path]) => [path, existing.get(path) ?? translated.get(path)])
    .filter(([, text]) => text !== undefined)
  writeFileSync(outFile, JSON.stringify(unflatten(pairs), null, 2) + '\n')
  console.log(`wrote ${outFile}: ${translated.size} translated, ${existing.size} kept`)
  for (const [path, original, answer] of rejected) {
    console.log(`  not taken (syntax changed): ${path}\n    ${original}\n    ${answer}`)
  }
  if (rejected.length) process.exitCode = 1
}

if (process.argv[1] === fileURLToPath(import.meta.url)) {
  main().catch((error) => {
    console.error(error.message)
    process.exit(1)
  })
}

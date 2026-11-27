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
 * - Short labels: texts of up to three words are sent grouped by the screen area of their key,
 *   with the German texts around them as context ("Links" next to "Oben, Rechts, Unten"); one
 *   that comes back unchanged is asked once more on its own with its direct neighbours.
 * - German words that must stay German: a quotation „…“ starting with a word of
 *   `scripts/i18n-glossary/keep.txt` (the DOCX / ODT exchange words) is not translated.
 */
import { readFileSync, writeFileSync, existsSync } from 'node:fs'
import { homedir } from 'node:os'
import { dirname, join, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'

const ROOT = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const LOCALES = resolve(ROOT, 'frontend', 'src', 'locales')
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
  // only language codes: they become file names in frontend/src/locales, and what the source
  // file holds is sent to DeepL (a path such as ../../private.json must never be read)
  if (!/^[a-z]{2}(-[a-z]{2})?$/i.test(options.target) || !/^[a-z]{2}(-[a-z]{2})?$/i.test(options.source)) {
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
 * Replaces the vue-i18n syntax of a message, and German words in „…“ that must stay German (the
 * DOCX / ODT exchange words such as „Kopfzeile“ or „Text: …“), by numbered XML tags DeepL does
 * not touch. The quotation marks stay outside, so DeepL still sets the target language's ones.
 * @param {string} text
 * @param {string[]} [keep] - words that stay German when a quotation starts with them
 * @returns {{xml: string, tokens: string[]}}
 */
export function protect(text, keep = []) {
  const tokens = []
  const token = (match) => {
    tokens.push(match)
    return `<x i="${tokens.length - 1}"/>`
  }
  const escaped = text.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  // a quotation „…“ whose first word is a keep word stays as it is (fixed pattern, no RegExp
  // built from the word list)
  const kept = escaped.replace(/„([^“]*)“/g, (quote, inner) => {
    const first = /^\p{L}+/u.exec(inner)
    return first && keep.includes(first[0]) ? `„${token(inner)}“` : quote
  })
  return { xml: kept.replace(SYNTAX, token), tokens }
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

/** A text of at most this many words (syntax not counted) is translated with its neighbours as context. */
const SHORT_WORDS = 3
/**
 * Longest context sent with one group of short texts (characters; DeepL does not bill context).
 * Short and plain works best: a long list with key names made DeepL leave words untranslated.
 */
const CONTEXT_LIMIT = 500

/**
 * Whether a message is a short label ("Links", "An", "{n} von {m}") that DeepL cannot translate
 * well without knowing where it stands.
 * @param {string} text
 * @returns {boolean}
 */
export function isShort(text) {
  const words = text.replace(SYNTAX, ' ').split(/\s+/).filter(word => /\p{L}/u.test(word))
  return words.length <= SHORT_WORDS
}

/**
 * Splits the texts into requests. Long texts go in batches of {@link BATCH} with the general
 * context; short texts are grouped by the screen area of their key (the key without its last
 * part, a small area together with the level above), each group in a request of its own whose
 * context lists the German texts of that area (plain, at most {@link CONTEXT_LIMIT} characters),
 * so "Links" next to "Oben, Rechts, Unten" reads as a side, not as links.
 * @param {[string, string][]} todo - the texts to translate
 * @param {[string, string][]} all - every source text, for the neighbours
 * @returns {{items: [string, string][], context: string}[]}
 */
export function planRequests(todo, all) {
  const parent = (path) => path.split('.').slice(0, -1).join('.')
  const requests = []
  const long = todo.filter(([, text]) => !isShort(text))
  for (let start = 0; start < long.length; start += BATCH) {
    requests.push({ items: long.slice(start, start + BATCH), context: CONTEXT })
  }
  const groups = new Map()
  for (const pair of todo.filter(([, text]) => isShort(text))) {
    const area = parent(pair[0])
    if (!groups.has(area)) groups.set(area, [])
    groups.get(area).push(pair)
  }
  const under = (scope) => all.filter(([path]) => !scope || path.startsWith(`${scope}.`))
  for (const [area, items] of groups) {
    // a small area (the four sides of a margin) is read with the level above it (the margins)
    const scope = under(area).length < 6 && area.includes('.') ? parent(area) : area
    const own = (path) => (path.startsWith(`${area}.`) ? 0 : 1)
    const neighbours = under(scope)
      .sort(([a], [b]) => own(a) - own(b)) // the area's own texts first, before the limit cuts
      .map(([, text]) => text)
      .filter(text => text.length <= 60)
    let context = 'Short labels on one screen of an invoicing web application. Other labels on that screen: '
    for (const text of neighbours) {
      if (context.length + text.length + 2 > CONTEXT_LIMIT) break
      context += `${text}, `
    }
    context = context.replace(/, $/, '.')
    for (let start = 0; start < items.length; start += BATCH) {
      requests.push({ items: items.slice(start, start + BATCH), context })
    }
  }
  return requests
}

/**
 * Reads the words that stay German in quotations (`scripts/i18n-glossary/keep.txt`, one per line).
 * @returns {string[]}
 */
function keepWords() {
  const file = join(GLOSSARIES, 'keep.txt')
  if (!existsSync(file)) return []
  return readFileSync(file, 'utf8').split('\n').map(line => line.trim()).filter(line => line && !line.startsWith('#'))
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
  for (let attempt = 1; ; attempt++) {
    const response = await fetch(base + path, {
      method,
      headers: { Authorization: `DeepL-Auth-Key ${key}`, ...(body ? { 'Content-Type': 'application/json' } : {}) },
      body: body ? JSON.stringify(body) : undefined
    })
    // too many requests or briefly unavailable: wait as told (or 2, 4, 8 … s) and try again
    if ((response.status === 429 || response.status === 503) && attempt < 8) {
      const wait = Number(response.headers.get('retry-after')) || 2 ** attempt
      await new Promise(done => setTimeout(done, wait * 1000))
      continue
    }
    if (!response.ok) throw new Error(`DeepL ${method} ${path}: ${response.status} ${await response.text()}`)
    return response.status === 204 ? null : response.json()
  }
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
  const sourceFile = resolve(LOCALES, `${options.source}.json`)
  if (dirname(sourceFile) !== LOCALES) throw new Error(`The source must be a message file in ${LOCALES}`)
  const sourcePairs = flatten(JSON.parse(readFileSync(sourceFile, 'utf8')))
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
  const keep = keepWords()
  const requests = planRequests(todo, sourcePairs)
  let done = 0
  try {
    for (const request of requests) {
      const protectedTexts = request.items.map(([, text]) => protect(text, keep))
      const result = await deepl(base, key, '/v2/translate', {
        text: protectedTexts.map(p => p.xml),
        source_lang: options.source.toUpperCase(),
        target_lang: targetCode,
        tag_handling: 'xml',
        ignore_tags: ['x'],
        context: request.context,
        ...(glossaryId ? { glossary_id: glossaryId } : {}),
        ...(options.formality ? { formality: options.formality } : {})
      })
      result.translations.forEach((translation, i) => {
        const [path, original] = request.items[i]
        const text = restore(translation.text, protectedTexts[i].tokens)
        if (text === null) rejected.push([path, original, translation.text])
        else translated.set(path, matchInitialCase(original, text))
      })
      done += request.items.length
      process.stdout.write(`\r${done} / ${todo.length} (${requests.length} requests)`)
    }
    process.stdout.write('\n')
    // Safety net: a word that came back unchanged ("Links") is asked once more on its own, with
    // only its direct neighbours as context. Words that are the same in both languages
    // ("Status", "Logo") simply come back unchanged again.
    const parent = (path) => path.split('.').slice(0, -1).join('.')
    for (const [path, original] of todo) {
      if (translated.get(path) !== original || !/\p{L}/u.test(original)) continue
      const siblings = sourcePairs.filter(([other]) => parent(other) === parent(path) && other !== path)
        .map(([, text]) => text).filter(text => text.length <= 40).slice(0, 12)
      const { xml, tokens } = protect(original, keep)
      const result = await deepl(base, key, '/v2/translate', {
        text: [xml],
        source_lang: options.source.toUpperCase(),
        target_lang: targetCode,
        tag_handling: 'xml',
        ignore_tags: ['x'],
        context: `A short label in an invoicing web application, shown next to: ${siblings.join(', ')}.`,
        ...(options.formality ? { formality: options.formality } : {})
      })
      const text = restore(result.translations[0].text, tokens)
      if (text !== null && text !== original) {
        translated.set(path, matchInitialCase(original, text))
        console.log(`  second try: ${path}: ${original} -> ${translated.get(path)}`)
      }
    }
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

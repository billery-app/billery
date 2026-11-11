import { describe, it, expect } from 'vitest'
import { writeFileSync } from 'node:fs'
import { resolve } from 'node:path'
import baseline from './colorLiterals.baseline.json'

/**
 * Guard of the design system: colours live in `tokens.css`; a `.vue` file does not write
 * its own hex / rgb() / hsl() literal.
 *
 * Existing literals are tolerated per file (`colorLiterals.baseline.json`) and may only go down:
 * - a file with MORE literals than its baseline fails (new literal: use a token from tokens.css);
 * - a file with FEWER fails too, until the baseline is lowered, so the gain cannot be lost again
 *   (`UPDATE_COLOR_BASELINE=1 npm run test:run -- colorLiterals` rewrites the file);
 * - a file that is not in the baseline must have none.
 *
 * Exempt: the document preview of the template editor. It mirrors the PDF, and its colours come
 * from the template, not from the app (renderer parity, see AGENTS.md).
 */
const EXEMPT = new Set(['components/template/TemplateThumbnail.vue'])

const LITERAL = /#(?:[0-9a-fA-F]{8}|[0-9a-fA-F]{6}|[0-9a-fA-F]{3,4})\b|\b(?:rgba?|hsla?)\(/g

const sources = import.meta.glob('../**/*.vue', { query: '?raw', import: 'default', eager: true })

/** Literals in the style blocks of a single-file component, comments removed. */
export function countLiterals(source) {
  let count = 0
  for (const block of source.matchAll(/<style\b[^>]*>([\s\S]*?)<\/style>/g)) {
    const css = block[1].replace(/\/\*[\s\S]*?\*\//g, '')
    count += (css.match(LITERAL) || []).length
  }
  return count
}

function actualCounts() {
  const counts = {}
  for (const [path, source] of Object.entries(sources)) {
    const file = path.replace('../', '')
    if (EXEMPT.has(file)) continue
    const count = countLiterals(source)
    if (count > 0) counts[file] = count
  }
  return Object.fromEntries(Object.entries(counts).sort(([a], [b]) => a.localeCompare(b)))
}

describe('colour literals in .vue files', () => {
  it('counts hex, rgb(a) and hsl(a) literals in style blocks only', () => {
    const source = `<template><a href="#top" id="x">&#39;</a></template>
<script>const c = '#fff'</script>
<style scoped>
/* #000 in a comment */
a { color: #3498db; background: rgba(0,0,0,.5); border: 1px solid #ddd; }
#app { color: var(--color-text); }
</style>`
    expect(countLiterals(source)).toBe(3)
  })

  it('has no more and no fewer literals than the baseline', () => {
    const actual = actualCounts()
    if (process.env.UPDATE_COLOR_BASELINE) {
      writeFileSync(
        resolve(process.cwd(), 'src/styles/colorLiterals.baseline.json'), // vitest runs in frontend/
        JSON.stringify(actual, null, 2) + '\n'
      )
      return
    }
    const problems = []
    for (const file of new Set([...Object.keys(actual), ...Object.keys(baseline)])) {
      const now = actual[file] || 0
      const allowed = baseline[file] || 0
      if (now > allowed) {
        problems.push(`${file}: ${now} colour literals, baseline ${allowed} - use a token from styles/tokens.css`)
      } else if (now < allowed) {
        problems.push(`${file}: ${now} colour literals, baseline ${allowed} - lower the baseline (UPDATE_COLOR_BASELINE=1)`)
      }
    }
    expect(problems).toEqual([])
  })
})

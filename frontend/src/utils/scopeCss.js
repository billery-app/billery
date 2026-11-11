/**
 * Confines user-written template CSS to the editor's page preview.
 *
 * The preview lives inside the app's own DOM, so an unscoped rule such as
 * `table { ... }` in a template would restyle the whole application while the
 * editor is open. Every selector is therefore prefixed with `scope`.
 *
 * Template CSS is written for the PDF document, where the page is
 * `<body><div class="invoice-box">`. Selectors that address that root
 * (`html`, `body`, `:root`, `.invoice-box`) are rebased onto `scope` itself,
 * so `.invoice-box h1` and `h1` both become `<scope> h1`.
 *
 * Also removes what the preview must not load: `@import` and `url(...)` other
 * than `data:` and uploaded files (the PDF renderer refuses them too).
 */

/** Selector prefixes that mean "the page" in the PDF document. */
const ROOT_SELECTOR = /^(?::root|html|body|\.invoice-box)(?=$|[\s>+~.:#[])/

/** At-rules whose body contains rules (scoped recursively). */
const NESTING_AT_RULES = /^@(media|supports|container|layer|document)\b/i

/**
 * Prefixes one selector with the scope.
 * @param {string} selector
 * @param {string} scope
 * @returns {string}
 */
function scopeSelector(selector, scope) {
  let s = selector.trim()
  if (!s) return ''
  // `body .invoice-box h1` → strip every leading page-root part.
  let match = s.match(ROOT_SELECTOR)
  while (match) {
    const rest = s.slice(match[0].length)
    // Compound on the root itself (`body.dark h1`) stays attached to the scope.
    if (/^[.:#[]/.test(rest)) return `${scope}${rest}`
    s = rest.trim()
    match = s.match(ROOT_SELECTOR)
  }
  return s ? `${scope} ${s}` : scope
}

/**
 * Splits a selector list on top-level commas (not inside `:is(a, b)`).
 * @param {string} list
 * @returns {string[]}
 */
function splitSelectors(list) {
  const parts = []
  let depth = 0
  let current = ''
  for (const ch of list) {
    if (ch === '(' || ch === '[') depth++
    else if (ch === ')' || ch === ']') depth--
    if (ch === ',' && depth === 0) {
      parts.push(current)
      current = ''
    } else {
      current += ch
    }
  }
  parts.push(current)
  return parts
}

/**
 * Removes `url(...)` references to anything but `data:` URIs and `/uploads/`.
 * @param {string} declarations
 * @returns {string}
 */
function stripRemoteUrls(declarations) {
  return declarations.replace(/url\(\s*(['"]?)(.*?)\1\s*\)/gi, (match, quote, url) => {
    const value = url.trim()
    if (/^data:/i.test(value) || /^(?:https?:\/\/[^/]+)?\/uploads\/[\w.-]+$/i.test(value)) return match
    return 'none'
  })
}

/**
 * Finds the index of the `}` that closes the block opened at `open`.
 * Skips strings and comments.
 * @param {string} css
 * @param {number} open - Index of `{`
 * @returns {number} Index of the matching `}` or `css.length`
 */
function findBlockEnd(css, open) {
  let depth = 0
  for (let i = open; i < css.length; i++) {
    const ch = css[i]
    if (ch === '"' || ch === "'") {
      const end = css.indexOf(ch, i + 1)
      i = end < 0 ? css.length : end
    } else if (ch === '{') {
      depth++
    } else if (ch === '}') {
      depth--
      if (depth === 0) return i
    }
  }
  return css.length
}

/**
 * Scopes a stylesheet.
 * @param {string} css - User CSS
 * @param {string} scope - Selector of the preview page, e.g. `.template-editor .invoice-box`
 * @returns {string} Scoped CSS
 */
export function scopeCss(css, scope) {
  if (!css || typeof css !== 'string') return ''
  const source = css.replace(/\/\*[\s\S]*?\*\//g, '')
  let out = ''
  let i = 0
  while (i < source.length) {
    const open = source.indexOf('{', i)
    const semicolon = source.indexOf(';', i)
    // Statement at-rules (`@import url(x);`, `@charset`) end with `;` before any `{`.
    if (semicolon >= 0 && (open < 0 || semicolon < open) && source.slice(i, semicolon).trim().startsWith('@')) {
      i = semicolon + 1 // dropped: @import / @charset / @namespace
      continue
    }
    if (open < 0) break
    const prelude = source.slice(i, open).trim()
    const close = findBlockEnd(source, open)
    const body = source.slice(open + 1, close)
    if (NESTING_AT_RULES.test(prelude)) {
      out += `${prelude} {\n${scopeCss(body, scope)}}\n`
    } else if (/^@(font-face|keyframes|-webkit-keyframes|page)\b/i.test(prelude)) {
      out += /^@font-face/i.test(prelude) ? `${prelude} {${stripRemoteUrls(body)}}\n` : /^@page/i.test(prelude) ? '' : `${prelude} {${body}}\n`
    } else if (prelude.startsWith('@')) {
      // Unknown at-rule: drop it rather than leak it unscoped.
    } else if (prelude) {
      const selectors = splitSelectors(prelude).map(sel => scopeSelector(sel, scope)).filter(Boolean)
      out += `${selectors.join(', ')} {${stripRemoteUrls(body)}}\n`
    }
    i = close + 1
  }
  return out
}

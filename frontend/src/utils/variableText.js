import { isKnownVariable, canonicalVariableName } from './variableRegistry.js'

/**
 * Text with `{{variables}}`, as stored in template block fields.
 *
 * The editor never shows the curly-brace syntax: `VariableTextInput` turns
 * every placeholder into a chip. These helpers convert between the stored
 * string and the segment list the input renders.
 */

const VARIABLE_PATTERN = /\{\{\s*([^{}]+?)\s*\}\}/g

/**
 * Splits stored text into text and variable segments.
 * @param {string} text
 * @returns {({type: 'text', value: string}|{type: 'var', name: string})[]}
 */
export function parseVariableText(text) {
  const source = text == null ? '' : String(text)
  const segments = []
  let last = 0
  for (const match of source.matchAll(VARIABLE_PATTERN)) {
    if (match.index > last) segments.push({ type: 'text', value: source.slice(last, match.index) })
    segments.push({ type: 'var', name: match[1] })
    last = match.index + match[0].length
  }
  if (last < source.length) segments.push({ type: 'text', value: source.slice(last) })
  return segments
}

/**
 * Joins segments back into the stored form.
 * @param {({type: 'text', value: string}|{type: 'var', name: string})[]} segments
 * @returns {string}
 */
export function serializeVariableText(segments) {
  return segments.map(s => (s.type === 'var' ? `{{${s.name}}}` : s.value)).join('')
}

/**
 * Names of all variables in a text.
 * @param {string} text
 * @returns {string[]}
 */
export function variablesIn(text) {
  return parseVariableText(text).filter(s => s.type === 'var').map(s => s.name)
}

/**
 * Variables the PDF renderer does not know (typos, removed names). They are
 * removed from the PDF, so the editor warns about them.
 * @param {string} text
 * @returns {string[]}
 */
export function unknownVariablesIn(text) {
  return variablesIn(text).filter(name => !isKnownVariable(name))
}

/**
 * Replaces every variable with its value; unknown ones stay visible as
 * `{{name}}` so the preview shows the mistake.
 * @param {string} text
 * @param {Object} values - Variable name → value (German names; aliases are mapped)
 * @returns {string}
 */
export function resolveVariableText(text, values) {
  if (typeof text !== 'string' || !text.includes('{{')) return text
  return text.replace(VARIABLE_PATTERN, (match, name) => {
    const value = values[name] ?? values[canonicalVariableName(name)]
    return value === undefined || value === null ? match : String(value)
  })
}

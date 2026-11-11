import DOMPurify from 'dompurify'

/**
 * Sanitizes HTML content to prevent XSS attacks.
 * Use this when rendering user-provided HTML.
 * @param {string} dirtyHtml - The HTML content to sanitize
 * @returns {string} - The sanitized HTML safe for rendering
 */
export function sanitizeHtml(dirtyHtml) {
  if (!dirtyHtml || typeof dirtyHtml !== 'string') {
    return ''
  }
  return DOMPurify.sanitize(dirtyHtml, {
    ALLOWED_TAGS: [
      'p', 'br', 'span', 'div', 'h1', 'h2', 'h3', 'h4', 'h5', 'h6',
      'strong', 'b', 'em', 'i', 'u', 'strike', 'del', 'ins',
      'table', 'thead', 'tbody', 'tr', 'th', 'td',
      'ul', 'ol', 'li',
      'a', 'img',
      'style'
    ],
    ALLOWED_ATTR: [
      'class', 'id', 'style',
      'href', 'target', 'rel',
      'src', 'alt', 'width', 'height',
      'border', 'cellpadding', 'cellspacing', 'colspan', 'rowspan'
    ],
    ALLOW_DATA_ATTR: false
  })
}

/**
 * Escapes the HTML special characters of a plain-text value.
 * @param {string} text - Plain text (user input, customer data, ...)
 * @returns {string} - Text that renders literally when inserted into HTML
 */
export function escapeHtml(text) {
  return String(text)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

/**
 * Returns a copy of block data with every string value HTML-escaped (recursively through arrays
 * and objects; numbers and booleans are left alone). Block fields are plain text, so this must
 * run on the variable-resolved data right before a block's `render()` builds its HTML. The
 * backend PDF renderer escapes the same fields, so preview and PDF show the same text.
 * @param {*} data - Resolved block data
 * @returns {*} - Escaped copy
 */
export function escapeBlockData(data) {
  if (typeof data === 'string') return escapeHtml(data)
  if (Array.isArray(data)) return data.map(escapeBlockData)
  if (data && typeof data === 'object') {
    const escaped = {}
    for (const [key, value] of Object.entries(data)) {
      escaped[key] = escapeBlockData(value)
    }
    return escaped
  }
  return data
}

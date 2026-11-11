import { describe, it, expect } from 'vitest'
import { sanitizeHtml, escapeHtml, escapeBlockData } from './sanitize.js'
import { BLOCK_TYPES } from './blockRegistry.js'

describe('sanitize - Real Workflows', () => {
  describe('Basic Sanitization', () => {
    it('allows safe HTML tags', () => {
      const result = sanitizeHtml('<p>Hello</p><strong>World</strong>')
      expect(result).toContain('<p>')
      expect(result).toContain('<strong>')
    })

    it('allows paragraph tags', () => {
      const result = sanitizeHtml('<p>Text</p>')
      expect(result).toContain('<p>')
    })

    it('allows heading tags', () => {
      const result = sanitizeHtml('<h1>Title</h1><h2>Subtitle</h2>')
      expect(result).toContain('<h1>')
      expect(result).toContain('<h2>')
    })

    it('allows bold and italic', () => {
      const result = sanitizeHtml('<b>Bold</b><i>Italic</i><em>Em</em>')
      expect(result).toContain('<b>')
      expect(result).toContain('<i>')
      expect(result).toContain('<em>')
    })
  })

  describe('Table Handling', () => {
    it('allows table elements', () => {
      const result = sanitizeHtml('<table><tr><td>Cell</td></tr></table>')
      expect(result).toContain('<table>')
      expect(result).toContain('<td>')
    })

    it('allows thead and tbody', () => {
      const result = sanitizeHtml('<table><thead><tr><th>Header</th></tr></thead><tbody><tr><td>Data</td></tr></tbody></table>')
      expect(result).toContain('<thead>')
      expect(result).toContain('<tbody>')
    })

    it('allows table attributes', () => {
      const result = sanitizeHtml('<table border="1" cellpadding="5" cellspacing="0"><tr><td>Cell</td></tr></table>')
      expect(result).toContain('border="1"')
      expect(result).toContain('cellpadding="5"')
    })
  })

  describe('List Handling', () => {
    it('allows unordered lists', () => {
      const result = sanitizeHtml('<ul><li>Item 1</li><li>Item 2</li></ul>')
      expect(result).toContain('<ul>')
      expect(result).toContain('<li>')
    })

    it('allows ordered lists', () => {
      const result = sanitizeHtml('<ol><li>Item</li></ol>')
      expect(result).toContain('<ol>')
    })
  })

  describe('Script and Event Removal', () => {
    it('removes script tags', () => {
      const result = sanitizeHtml('<p>Text</p><script>alert(1)</script>')
      expect(result).not.toContain('<script>')
      expect(result).not.toContain('alert')
    })

    it('removes onerror attributes', () => {
      const result = sanitizeHtml('<img src="x" onerror="alert(1)">')
      expect(result).not.toContain('onerror')
    })

    it('removes onclick attributes', () => {
      const result = sanitizeHtml('<button onclick="evil()">Click</button>')
      expect(result).not.toContain('onclick')
    })

    it('removes javascript: URLs', () => {
      const result = sanitizeHtml('<a href="javascript:alert(1)">Link</a>')
      expect(result).not.toContain('javascript:')
    })
  })

  describe('Attribute Handling', () => {
    it('allows class attribute', () => {
      const result = sanitizeHtml('<p class="highlight">Text</p>')
      expect(result).toContain('class="highlight"')
    })

    it('allows style attribute', () => {
      const result = sanitizeHtml('<p style="color: red;">Text</p>')
      expect(result).toContain('style="')
    })

    it('allows href attribute', () => {
      const result = sanitizeHtml('<a href="https://example.com">Link</a>')
      expect(result).toContain('href="https://example.com"')
    })

    it('allows img src and alt', () => {
      const result = sanitizeHtml('<img src="image.jpg" alt="Description">')
      expect(result).toContain('src="image.jpg"')
      expect(result).toContain('alt="Description"')
    })

    it('removes data attributes', () => {
      const result = sanitizeHtml('<p data-id="123">Text</p>')
      expect(result).not.toContain('data-id')
    })
  })

  describe('Edge Cases', () => {
    it('handles empty string', () => {
      expect(sanitizeHtml('')).toBe('')
    })

    it('handles null input', () => {
      expect(sanitizeHtml(null)).toBe('')
    })

    it('handles undefined input', () => {
      expect(sanitizeHtml(undefined)).toBe('')
    })

    it('handles non-string input', () => {
      expect(sanitizeHtml(123)).toBe('')
    })

    it('handles line breaks', () => {
      const result = sanitizeHtml('Line 1<br/>Line 2')
      expect(result).toContain('<br')
    })
  })

  describe('Allowed Tags Verification', () => {
    it('allows span tags', () => {
      const result = sanitizeHtml('<span>Text</span>')
      expect(result).toContain('<span>')
    })

    it('allows div tags', () => {
      const result = sanitizeHtml('<div>Content</div>')
      expect(result).toContain('<div>')
    })

    it('allows a tags', () => {
      const result = sanitizeHtml('<a href="url">Link</a>')
      expect(result).toContain('<a ')
    })

    it('allows img tags', () => {
      const result = sanitizeHtml('<img src="test.jpg">')
      expect(result).toContain('<img')
    })

    it('allows u and strike tags', () => {
      const result = sanitizeHtml('<u>Underline</u><strike>Strikethrough</strike>')
      expect(result).toContain('<u>')
      expect(result).toContain('<strike>')
    })
  })
})
describe('escapeHtml / escapeBlockData', () => {
  it('escapes the HTML special characters of plain text', () => {
    expect(escapeHtml('Müller & Söhne <GmbH> "x"')).toBe(
      'Müller &amp; Söhne &lt;GmbH&gt; &quot;x&quot;'
    )
  })

  it('escapes strings recursively and leaves numbers and booleans alone', () => {
    const data = {
      companyName: '<img src=x onerror=alert(1)>',
      items: [{ description: 'A & B', quantity: 2, rate: 1.5 }],
      show: true
    }
    expect(escapeBlockData(data)).toEqual({
      companyName: '&lt;img src=x onerror=alert(1)&gt;',
      items: [{ description: 'A &amp; B', quantity: 2, rate: 1.5 }],
      show: true
    })
  })

  it('makes a real block render user text inert', () => {
    const header = BLOCK_TYPES.find(b => b.type === 'header')
    const html = header.render(
      escapeBlockData({ ...header.defaultData, companyName: '<img src=x onerror=alert(1)>' }),
      header.defaultConfig
    )
    expect(html).not.toContain('<img src=x')
    expect(html).toContain('&lt;img src=x onerror=alert(1)&gt;')
  })
})

import { describe, it, expect } from 'vitest'
import { scopeCss } from './scopeCss.js'

const S = '.ed .invoice-box'
const squash = (css) => css.replace(/\s+/g, ' ').trim()

describe('scopeCss', () => {
  it('prefixes plain selectors and selector lists', () => {
    expect(squash(scopeCss('table, h1 > span { color: red }', S)))
      .toBe('.ed .invoice-box table, .ed .invoice-box h1 > span { color: red }')
  })

  it('rebases page-root selectors onto the scope', () => {
    expect(squash(scopeCss('body { margin: 0 }', S))).toBe('.ed .invoice-box { margin: 0 }')
    expect(squash(scopeCss('.invoice-box h1 { x: y }', S))).toBe('.ed .invoice-box h1 { x: y }')
    expect(squash(scopeCss('body .invoice-box td { x: y }', S))).toBe('.ed .invoice-box td { x: y }')
    expect(squash(scopeCss('.invoice-box.dark p { x: y }', S))).toBe('.ed .invoice-box.dark p { x: y }')
    expect(squash(scopeCss(':root > div { x: y }', S))).toBe('.ed .invoice-box > div { x: y }')
  })

  it('does not treat similar class names as the root', () => {
    expect(squash(scopeCss('.invoice-boxed { x: y }', S))).toBe('.ed .invoice-box .invoice-boxed { x: y }')
  })

  it('scopes inside @media and keeps @keyframes', () => {
    const out = squash(scopeCss('@media print { p { a: b } } @keyframes k { from { o: 0 } }', S))
    expect(out).toContain('@media print { .ed .invoice-box p { a: b } }')
    expect(out).toContain('@keyframes k { from { o: 0 } }')
  })

  it('drops @import, @page and remote urls; keeps data: and uploads', () => {
    const out = scopeCss(
      "@import url('http://x/a.css'); @page { margin: 0 } " +
      "div { background: url(http://169.254.169.254/) } " +
      "p { background: url('data:image/png;base64,AA') } " +
      'h1 { background: url(http://127.0.0.1:8080/uploads/logo.png) }',
      S
    )
    expect(out).not.toContain('@import')
    expect(out).not.toContain('@page')
    expect(out).not.toContain('169.254')
    expect(out).toContain('background: none')
    expect(out).toContain('data:image/png')
    expect(out).toContain('/uploads/logo.png')
  })

  it('handles comments, strings with braces and :is() lists', () => {
    const out = squash(scopeCss('/* a { } */ p:is(.a, .b)::after { content: "}" }', S))
    expect(out).toBe('.ed .invoice-box p:is(.a, .b)::after { content: "}" }')
  })

  it('returns an empty string for empty input', () => {
    expect(scopeCss('', S)).toBe('')
    expect(scopeCss(null, S)).toBe('')
  })
})

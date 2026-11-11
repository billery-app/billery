import { describe, it, expect } from 'vitest'
import { readFileSync } from 'node:fs'
import { resolve } from 'node:path'
import { normalizeBlocks } from './blockModel.js'
import { buildPreviewContext } from './previewData.js'
import { buildPreviewVariables, buildLineItemsData } from './invoiceVariables.js'
import { renderBlockPreview } from './renderBlockPreview.js'

/**
 * Renderer parity: the editor preview must show what the PDF shows.
 *
 * The cases live next to the Java test (`TemplateParityTest`), which renders
 * them with the PDF renderer. This spec renders the same cases with the
 * editor's path (stored block → normalizeBlocks → preview context from the
 * invoice/customer/owner DTOs → renderBlockPreview) and checks the same
 * expectations. A case failing on one side only means the renderers drifted.
 */
// Vitest runs from frontend/ (jsdom gives import.meta.url an http: scheme, so no URL here).
const fixture = JSON.parse(readFileSync(
  resolve(process.cwd(), '../backend/src/test/resources/template-parity/cases.json'),
  'utf8'
))

/** Text a reader sees: tags removed, entities decoded, whitespace collapsed. */
function visibleText(html) {
  return html
    .replace(/<[^>]+>/g, ' ')
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&nbsp;/g, ' ')
    .replace(/&amp;/g, '&')
    .replace(/ /g, ' ')
    .replace(/\s+/g, ' ')
    .trim()
}

/** `10.50pt` → `10.5pt` (same rule as the Java test). */
function normalizeSizes(html) {
  return html.replace(/(\d+)\.(\d*?)0+pt/g, (m, int, frac) => (frac ? `${int}.${frac}pt` : `${int}pt`))
}

/** Item DTOs as the invoice API returns them (`totalPrice` computed like the server). */
function itemDtos(items) {
  return items.map(item => ({
    ...item,
    totalPrice: item.itemType.toUpperCase() === 'CUSTOM' ? 0 : item.quantity * item.unitPrice,
    children: item.children ? itemDtos(item.children) : []
  }))
}

describe('renderer parity with the PDF (template-parity/cases.json)', () => {
  const { context } = fixture

  it.each(fixture.cases.map(c => [c.name, c]))('%s', (name, testCase) => {
    const invoice = { ...context.invoice, ...(testCase.invoice || {}) }
    const { variables, totals } = buildPreviewVariables({ invoice, customer: context.customer })
    const ctx = buildPreviewContext({
      owner: context.owner,
      liveData: { variables, totals, items: buildLineItemsData(itemDtos(context.items)) },
      documentTypeId: invoice.documentTypeId
    })
    const [block] = normalizeBlocks([testCase.block])
    const html = renderBlockPreview(block, ctx)
    const text = visibleText(html)
    const styles = normalizeSizes(html)

    for (const expected of testCase.expectText || []) expect(text, `in: ${text}`).toContain(expected)
    for (const absent of testCase.expectAbsent || []) expect(text, `in: ${text}`).not.toContain(absent)
    for (const style of testCase.expectStyle || []) expect(styles, `in: ${styles}`).toContain(style)
  })
})

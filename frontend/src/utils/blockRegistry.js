import { escapeHtml } from './sanitize.js'
import { DOCUMENT_TYPES, CREDIT_NOTE } from '../data/documentTypes.js'

/**
 * Block types of the template editor.
 *
 * Each entry describes one block completely:
 * - `defaultData` / `defaultConfig`: what a new block starts with. Text fields
 *   hold `{{variables}}` (never sample literals: the PDF prints what is stored).
 * - `rolePresets`: data/config overrides per `data.role` (address blocks).
 * - `fields` / `settings`: the form the editor shows (label, input kind,
 *   options, help text). The config panel is generated from them, so a new
 *   option is added here only.
 * - `limits`: `max` instances per template and whether the block is required.
 * - `formatDefaults`: font defaults when the block has no `config.format`;
 *   identical to the defaults of `TemplateBasedPdfGenerator` (Java).
 * - `render(data, config, ctx)`: preview HTML. `data` is already
 *   variable-resolved and HTML-escaped; `ctx` carries document data that is
 *   not stored in the block (line items, VAT totals, number label, resolved
 *   variables) and must be escaped here. The markup mirrors the Java renderer
 *   (tables instead of flexbox, same sizes) so the preview matches the PDF.
 */

/** Fonts the PDF renderer can use. */
export const FONT_FAMILIES = ['Inter', 'Arial', 'Helvetica', 'Times New Roman', 'Courier New', 'Georgia']

const COLOR_PATTERN = /^#[0-9a-fA-F]{3,8}$/
const FONT_PATTERN = /^[A-Za-z0-9 ,'-]{1,60}$/

/**
 * Returns a CSS color from user input, or the fallback when it is not a hex color.
 * @param {*} value
 * @param {string} fallback
 * @returns {string}
 */
export function safeColor(value, fallback) {
  return typeof value === 'string' && COLOR_PATTERN.test(value) ? value : fallback
}

/**
 * Format settings of a block with its defaults.
 * @param {Object} config - Block config (`config.format` is optional)
 * @param {Object} [defaults] - Block-specific defaults (`formatDefaults`)
 * @returns {{fontFamily: string, fontSize: number, fontColor: string, fontWeight: string}}
 */
export function getFormat(config, defaults = {}) {
  const f = (config && config.format) || {}
  const d = { fontFamily: 'Inter', fontSize: 10.5, fontColor: '#1f2937', ...defaults }
  const size = Number(f.fontSize)
  return {
    fontFamily: typeof f.fontFamily === 'string' && FONT_PATTERN.test(f.fontFamily) ? f.fontFamily : d.fontFamily,
    fontSize: Number.isFinite(size) && size > 0 ? size : d.fontSize,
    fontColor: safeColor(f.fontColor, d.fontColor),
    fontWeight: f.fontWeight === 'bold' ? 'bold' : 'normal'
  }
}

/**
 * Formats a point size like the Java renderer (`10.5pt`, `9pt`).
 * @param {number} pt
 * @returns {string}
 */
function pt(pt) {
  return `${Math.round(pt * 100) / 100}pt`
}

/**
 * Generic CSS family of a font. The PDF renderer only has the standard PDF
 * fonts and picks one by the generic family (serif → Times, monospace →
 * Courier, else Helvetica); mirrors `TemplateBasedPdfGenerator.genericFontFamily`.
 * @param {string} family
 * @returns {'serif'|'monospace'|'sans-serif'}
 */
export function genericFontFamily(family) {
  const f = String(family || '').toLowerCase()
  if (f.includes('georgia') || f.includes('times')) return 'serif'
  if (f.includes('courier')) return 'monospace'
  return 'sans-serif'
}

/**
 * Body-text CSS of a block; mirrors `TemplateBasedPdfGenerator.buildBodyStyle`.
 * @param {Object} config
 * @param {Object} defaults
 * @returns {string}
 */
function bodyStyle(config, defaults) {
  const fmt = getFormat(config, defaults)
  return `font-size: ${pt(fmt.fontSize)}; color: ${fmt.fontColor}; font-weight: ${fmt.fontWeight}; font-family: ${fmt.fontFamily}, ${genericFontFamily(fmt.fontFamily)};`
}

/**
 * Escaped config string (labels and titles are user text, never HTML).
 * @param {Object} config
 * @param {string} key
 * @param {string} fallback - Used when the key is missing (not when it is empty)
 * @returns {string}
 */
function label(config, key, fallback) {
  const value = config && key in config ? config[key] : fallback
  return escapeHtml(value == null ? '' : value)
}

/**
 * Formats an amount like the PDF (`1.234,56 €`).
 * @param {number|string} amount
 * @returns {string}
 */
export function formatCurrency(amount) {
  const n = Number(amount)
  const value = Number.isFinite(n) ? n : 0
  return value.toLocaleString('de-DE', { minimumFractionDigits: 2, maximumFractionDigits: 2 }) + ' €'
}

/**
 * Formats a percentage like the PDF (`19 %`, `7,5 %`).
 * @param {number|string} rate
 * @returns {string}
 */
function formatPercent(rate) {
  const n = Number(rate)
  return `${String(Number.isFinite(n) ? n : 0).replace('.', ',')} %`
}

/**
 * Label of the document number per document type (`Angebotsnummer`, ...);
 * mirrors the Java document strategies.
 * @param {number} documentTypeId
 * @returns {string}
 */
export function numberLabel(documentTypeId) {
  const labels = { 2: 'Angebotsnummer', 3: 'Kostenberechnungsnummer', [CREDIT_NOTE]: 'Stornorechnungsnummer' }
  return labels[documentTypeId] || 'Rechnungsnummer'
}

/**
 * Display name of a document type for the title default.
 * @param {number} documentTypeId
 * @returns {string}
 */
export function documentTitle(documentTypeId) {
  const type = DOCUMENT_TYPES.find(dt => dt.id === documentTypeId)
  return type ? type.label : 'Rechnung'
}

/** Help text for invoice-meta fields that the document fills in when left empty. */
const AUTO_HINT = 'Leer lassen: wird automatisch aus dem Dokument übernommen.'

/**
 * Width of a block on the page. Two consecutive half-width blocks share a
 * row (see `blockModel.js > layoutRows` and `TemplateBasedPdfGenerator.layoutBlocks`).
 */
const WIDTH_SETTING = {
  key: 'width',
  label: 'Breite',
  kind: 'select',
  default: 'full',
  options: [
    { value: 'full', label: 'Ganze Breite' },
    { value: 'half', label: 'Halbe Breite (neben dem nächsten halben Block)' }
  ]
}

const ALIGNMENT_OPTIONS = [
  { value: 'left', label: 'Links' },
  { value: 'center', label: 'Mitte' },
  { value: 'right', label: 'Rechts' }
]

export const DEFAULT_COLUMNS = [
  { key: 'description', label: 'Beschreibung', show: true, width: '50%' },
  { key: 'quantity', label: 'Menge', show: true, width: '15%' },
  { key: 'unit', label: 'Einheit', show: true, width: '10%' },
  { key: 'rate', label: 'Preis', show: true, width: '12%' },
  { key: 'amount', label: 'Gesamt', show: true, width: '13%' }
]

export const BLOCK_TYPES = [
  {
    type: 'header',
    name: 'Kopfzeile',
    description: 'Firmenname, Slogan und Logo',
    icon: 'header',
    limits: { max: 1 },
    formatDefaults: { fontSize: 21, fontColor: '#2563eb' },
    defaultData: {
      companyName: '{{firma}}',
      companySlogan: '',
      logoUrl: ''
    },
    defaultConfig: {
      showSlogan: true,
      logoAlignment: 'left',
      format: { fontFamily: 'Inter', fontSize: 21, fontColor: '#2563eb', fontWeight: 'normal' }
    },
    fields: [
      { key: 'companyName', label: 'Firmenname', kind: 'text' },
      { key: 'companySlogan', label: 'Slogan', kind: 'text', placeholder: 'z. B. Ihr Partner für ...' },
      { key: 'logoUrl', label: 'Logo', kind: 'image' }
    ],
    settings: [
      { key: 'showSlogan', label: 'Slogan anzeigen', kind: 'toggle' },
      {
        key: 'logoAlignment',
        label: 'Logo-Position',
        kind: 'select',
        options: [
          { value: 'left', label: 'Links neben dem Namen' },
          { value: 'right', label: 'Rechts' },
          { value: 'top', label: 'Oberhalb, zentriert' }
        ]
      },
      WIDTH_SETTING
    ],
    render(data, config) {
      const fmt = getFormat(config, this.formatDefaults)
      const logoAlign = config.logoAlignment || 'left'
      const img = data.logoUrl
        ? `<img src="${data.logoUrl}" alt="Logo" style="max-height: 80px; max-width: 200px;">`
        : ''
      // No font-weight: the heading is bold by default in the browser and in the PDF renderer.
      const h1Style = `margin: 0; font-size: ${pt(fmt.fontSize)}; color: ${fmt.fontColor}; font-family: ${fmt.fontFamily}, ${genericFontFamily(fmt.fontFamily)};`
      const slogan = config.showSlogan && data.companySlogan
        ? `<p style="margin: 5px 0 0 0; color: #6b7280; font-size: ${pt(Math.max(6, fmt.fontSize - 6))};">${data.companySlogan}</p>`
        : ''
      const brand = `<div class="brand"><h1 style="${h1Style}">${data.companyName || ''}</h1>${slogan}</div>`

      if (logoAlign === 'top') {
        return `<div class="header" style="margin-bottom: 40px;">${img ? `<div style="text-align: center; margin-bottom: 10px;">${img}</div>` : ''}${brand}</div>`
      }
      if (logoAlign === 'right') {
        return `<div class="header" style="margin-bottom: 40px;"><table style="width: 100%; border-collapse: collapse; margin: 0;"><tr><td style="vertical-align: middle; padding: 0; border: none;">${brand}</td><td style="width: 1%; text-align: right; vertical-align: middle; padding: 0; border: none;">${img}</td></tr></table></div>`
      }
      return `<div class="header" style="margin-bottom: 40px;"><table style="width: 100%; border-collapse: collapse; margin: 0;"><tr>${img ? `<td style="width: 1%; vertical-align: middle; padding: 0 20px 0 0; border: none;">${img}</td>` : ''}<td style="vertical-align: middle; padding: 0; border: none;">${brand}</td></tr></table></div>`
    }
  },
  {
    type: 'address',
    name: 'Adresse',
    description: 'Absender oder Empfänger',
    icon: 'address',
    limits: { max: 2 },
    formatDefaults: { fontSize: 10.5, fontColor: '#1f2937' },
    defaultData: {
      role: 'sender',
      name: '',
      address: '',
      email: '',
      phone: '',
      vatId: ''
    },
    rolePresets: {
      sender: {
        data: { name: '{{firma}}', address: '{{adresse}}', email: '{{email}}', phone: '{{telefon}}', vatId: '{{ust_id}}' },
        config: { label: 'Absender' }
      },
      receiver: {
        data: { name: '{{kunde}}', address: '{{kunde_strasse}}\n{{kunde_plz_ort}}', email: '', phone: '', vatId: '' },
        config: { label: 'Empfänger' }
      }
    },
    defaultConfig: {
      showLabels: true,
      label: 'Absender',
      format: { fontFamily: 'Inter', fontSize: 10.5, fontColor: '#1f2937', fontWeight: 'normal' }
    },
    fields: [
      {
        key: 'role',
        label: 'Art',
        kind: 'select',
        options: [
          { value: 'sender', label: 'Absender (Ihre Firma)' },
          { value: 'receiver', label: 'Empfänger (Kunde)' }
        ]
      },
      { key: 'name', label: 'Name', kind: 'text' },
      { key: 'address', label: 'Adresse', kind: 'multiline' },
      { key: 'email', label: 'E-Mail', kind: 'text' },
      { key: 'phone', label: 'Telefon', kind: 'text' },
      { key: 'vatId', label: 'USt-IdNr.', kind: 'text' }
    ],
    settings: [
      { key: 'showLabels', label: 'Überschrift anzeigen', kind: 'toggle' },
      { key: 'label', label: 'Überschrift', kind: 'text', showIf: 'showLabels' },
      WIDTH_SETTING
    ],
    render(data, config) {
      const fallbackLabel = data.role === 'receiver' ? 'Empfänger' : 'Absender'
      const fmt = getFormat(config, this.formatDefaults)
      const heading = config.showLabels
        ? `<span style="font-size: ${pt(Math.max(6, fmt.fontSize - 2))}; font-weight: 700; text-transform: uppercase; color: #6b7280; display: block; margin-bottom: 8px;">${label(config, 'label', fallbackLabel)}</span>`
        : ''
      const lines = [
        data.name ? `<strong>${data.name}</strong><br>` : '',
        data.address ? `${data.address.replace(/\n/g, '<br>')}<br>` : '',
        data.email ? `${data.email}<br>` : '',
        data.phone ? `${data.phone}<br>` : '',
        data.vatId ? `UST-ID: ${data.vatId}` : ''
      ].join('')
      return `<div class="address-block" style="margin-bottom: 30px;">${heading}<div style="${bodyStyle(config, this.formatDefaults)} line-height: 1.6;">${lines}</div></div>`
    }
  },
  {
    type: 'invoice-meta',
    name: 'Dokumentdaten',
    description: 'Titel, Nummer, Datum, Fälligkeit',
    icon: 'info',
    limits: { max: 1, required: true },
    // `format` is the body text; the title is derived (12/7 × body = 18pt at 10.5pt).
    formatDefaults: { fontSize: 10.5, fontColor: '#1f2937' },
    defaultData: {
      invoiceTitle: '',
      invoiceId: '{{rechnungsnummer}}',
      invoiceDate: '{{rechnungsdatum}}',
      dueDate: '{{faelligkeitsdatum}}',
      orderNumber: '',
      leistungsdatum: '',
      leistungszeitraum: ''
    },
    defaultConfig: {
      showOrderNumber: true,
      alignment: 'right',
      format: { fontFamily: 'Inter', fontSize: 10.5, fontColor: '#1f2937', fontWeight: 'normal' }
    },
    fields: [
      { key: 'invoiceTitle', label: 'Titel', kind: 'text', placeholder: 'z. B. Rechnung', help: 'Leer lassen: kein Titel.' },
      { key: 'invoiceId', label: 'Dokumentnummer', kind: 'text', help: AUTO_HINT },
      { key: 'invoiceDate', label: 'Datum', kind: 'text', help: AUTO_HINT },
      { key: 'dueDate', label: 'Fällig am', kind: 'text', help: AUTO_HINT },
      { key: 'orderNumber', label: 'Bestellnummer', kind: 'text' },
      { key: 'leistungsdatum', label: 'Leistungsdatum', kind: 'text', help: AUTO_HINT },
      { key: 'leistungszeitraum', label: 'Leistungszeitraum', kind: 'text', help: AUTO_HINT }
    ],
    settings: [
      { key: 'showOrderNumber', label: 'Bestellnummer anzeigen', kind: 'toggle' },
      { key: 'alignment', label: 'Ausrichtung', kind: 'select', options: ALIGNMENT_OPTIONS },
      WIDTH_SETTING
    ],
    render(data, config, ctx = {}) {
      const v = ctx.variables || {}
      const esc = (value) => escapeHtml(value == null ? '' : value)
      const fmt = getFormat(config, this.formatDefaults)
      const align = ['left', 'center'].includes(config.alignment) ? config.alignment : 'right'
      // Empty fields fall back to the document data, exactly like the PDF renderer.
      const invoiceId = data.invoiceId || esc(v.rechnungsnummer)
      const invoiceDate = data.invoiceDate || esc(v.rechnungsdatum)
      const dueDate = data.dueDate || esc(v.faelligkeitsdatum)
      const leistungsdatum = data.leistungsdatum || esc(v.leistungsdatum)
      const leistungszeitraum = data.leistungszeitraum || esc(v.leistungszeitraum)
      const title = data.invoiceTitle
        ? `<h2 style="margin: 0 0 16px 0; font-size: ${pt(fmt.fontSize * 12 / 7)}; font-weight: 600; color: ${fmt.fontColor}; text-align: ${align};">${data.invoiceTitle}</h2>`
        : ''
      const row = (text) => `<div style="margin-bottom: 8px;">${text}</div>`
      const isCreditNote = ctx.documentTypeId === CREDIT_NOTE
      return `<div class="invoice-meta-section" style="margin-bottom: 30px;">${title}<div style="${bodyStyle(config, this.formatDefaults)} text-align: ${align};">`
        + row(`<strong>${numberLabel(ctx.documentTypeId)}:</strong> ${invoiceId}`)
        + row(`<strong>Datum:</strong> ${invoiceDate}`)
        + (isCreditNote ? '' : row(`<strong>Fällig am:</strong> ${dueDate}`))
        + (config.showOrderNumber && data.orderNumber ? `<div><strong>Bestellnummer:</strong> ${data.orderNumber}</div>` : '')
        + (leistungsdatum && !leistungszeitraum ? row(`<strong>Leistungsdatum:</strong> ${leistungsdatum}`) : '')
        + (leistungszeitraum ? row(`<strong>Leistungszeitraum:</strong> ${leistungszeitraum}`) : '')
        + '</div></div>'
    }
  },
  {
    type: 'line-items',
    name: 'Positionen',
    description: 'Tabelle der Artikel und Leistungen',
    icon: 'table',
    limits: { max: 1, required: true },
    formatDefaults: { fontSize: 10.5, fontColor: '#1f2937' },
    defaultData: {
      columns: DEFAULT_COLUMNS.map(col => ({ ...col }))
    },
    defaultConfig: {
      format: { fontFamily: 'Inter', fontSize: 10.5, fontColor: '#1f2937', fontWeight: 'normal' }
    },
    fields: [
      { key: 'columns', label: 'Spalten', kind: 'columns', help: 'Bei gemischten Steuersätzen wird automatisch eine USt.-Spalte ergänzt.' }
    ],
    settings: [],
    render(data, config, ctx = {}) {
      const fmt = getFormat(config, this.formatDefaults)
      const fontCss = `font-size: ${pt(fmt.fontSize)}; color: ${fmt.fontColor};`
      const columns = (Array.isArray(data.columns) && data.columns.length ? data.columns : DEFAULT_COLUMNS)
        .filter(col => col.show !== false)
      const items = Array.isArray(ctx.items) ? ctx.items : []
      const rates = new Set()
      const collectRates = (list) => list.forEach(item => {
        if (!item.isNote && item.taxRate != null) rates.add(Number(item.taxRate))
        if (Array.isArray(item.children)) collectRates(item.children)
      })
      collectRates(items)
      // Mixed VAT rates: the rate of each line must be identifiable (§14 Abs. 4 Nr. 8 UStG).
      if (rates.size > 1 && !columns.some(col => col.key === 'taxRate')) {
        const amountIndex = columns.findIndex(col => col.key === 'amount')
        columns.splice(amountIndex < 0 ? columns.length : amountIndex, 0, { key: 'taxRate', label: 'USt.' })
      }

      const header = columns
        .map(col => `<th style="background: #f3f4f6; text-align: left; padding: 12px; border-bottom: 2px solid #e5e7eb; ${fontCss}">${escapeHtml(col.label || '')}</th>`)
        .join('')

      const cellValue = (item, key) => {
        if (key === 'rate' || key === 'amount') return formatCurrency(item[key])
        if (key === 'taxRate') return item.taxRate == null ? '' : formatPercent(item.taxRate)
        if (key === 'quantity') return String(item.quantity ?? '').replace('.', ',')
        return escapeHtml(item[key] ?? '')
      }
      const row = (item, isChild) => {
        if (item.isNote) {
          const mergeStart = Math.max(1, Math.min(columns.length, columns.findIndex(c => c.key === 'quantity') < 0 ? 1 : columns.findIndex(c => c.key === 'quantity')))
          const indent = isChild ? ' padding-left: 32px;' : ''
          const base = `padding: 12px; border-bottom: 1px solid #f3f4f6; font-size: 13px; text-align: left; background: #fffbeb; vertical-align: top; white-space: pre-wrap;${indent}`
          const prefix = columns.slice(0, mergeStart)
            .map(col => `<td style="${base} color: #92400e;">${escapeHtml(col.key === 'description' ? item.description || '' : '')}</td>`).join('')
          return `<tr>${prefix}<td colspan="${Math.max(1, columns.length - mergeStart)}" style="${base} color: #374151;">${escapeHtml(item.noteText || '')}</td></tr>`
        }
        return '<tr>' + columns.map(col => {
          const numeric = ['quantity', 'rate', 'amount', 'taxRate'].includes(col.key)
          const style = isChild && col.key === 'description'
            ? 'padding: 8px 12px; border-bottom: 1px solid #f3f4f6; font-size: 12px; text-align: left; padding-left: 32px; color: #6b7280;'
            : `padding: 12px; border-bottom: 1px solid #f3f4f6; ${fontCss} text-align: ${numeric ? 'right' : 'left'};`
          return `<td style="${style}">${cellValue(item, col.key)}</td>`
        }).join('') + '</tr>'
      }
      const rows = []
      const walk = (list, isChild) => list.forEach(item => {
        rows.push(row(item, isChild))
        if (Array.isArray(item.children)) walk(item.children, true)
      })
      walk(items, false)

      return `<table style="width: 100%; border-collapse: collapse; margin-top: 20px;"><thead><tr>${header}</tr></thead><tbody>${rows.join('')}</tbody></table>`
    }
  },
  {
    type: 'totals',
    name: 'Summen',
    description: 'Netto, Steuer je Satz, Gesamtbetrag',
    icon: 'calculator',
    limits: { max: 1, required: true },
    formatDefaults: { fontSize: 10.5, fontColor: '#1f2937' },
    defaultData: {},
    defaultConfig: {
      showTax: true,
      labelSubtotal: 'Zwischensumme',
      labelTax: 'MwSt.',
      labelTotal: 'Gesamtbetrag',
      alignment: 'right',
      format: { fontFamily: 'Inter', fontSize: 10.5, fontColor: '#1f2937', fontWeight: 'normal' }
    },
    fields: [],
    settings: [
      { key: 'showTax', label: 'Steuer anzeigen', kind: 'toggle', help: 'Bei Kleinunternehmern oder Reverse Charge wird keine Steuer ausgewiesen, sondern der gesetzliche Hinweis gedruckt.' },
      { key: 'labelSubtotal', label: 'Bezeichnung Netto', kind: 'text' },
      { key: 'labelTax', label: 'Bezeichnung Steuer', kind: 'text' },
      { key: 'labelTotal', label: 'Bezeichnung Gesamt', kind: 'text' },
      { key: 'alignment', label: 'Ausrichtung', kind: 'select', options: ALIGNMENT_OPTIONS },
      WIDTH_SETTING
    ],
    render(data, config, ctx = {}) {
      const totals = ctx.totals || { net: 0, gross: 0, groups: [], chargesVat: true, legalNote: '' }
      const fmt = getFormat(config, this.formatDefaults)
      const rowPt = pt(fmt.fontSize)
      const totalPt = pt(Math.min(24, fmt.fontSize + 3))
      const margin = config.alignment === 'left'
        ? 'margin-left: 0; margin-right: auto;'
        : config.alignment === 'center' ? 'margin-left: auto; margin-right: auto;' : 'margin-left: auto; margin-right: 0;'
      const row = (text, value) => `<tr><td style="padding: 8px; font-weight: 600; color: #6b7280; font-size: ${rowPt};">${text}</td><td style="padding: 8px; text-align: right; font-size: ${rowPt};">${value}</td></tr>`
      const groups = Array.isArray(totals.groups) ? totals.groups : []
      const mixed = groups.length > 1
      const taxRows = config.showTax && totals.chargesVat !== false
        ? groups.map(g => row(`${label(config, 'labelTax', 'MwSt.')} ${formatPercent(g.ratePercent)}${mixed ? ` auf ${formatCurrency(g.net)}` : ''}`, formatCurrency(g.tax))).join('')
        : ''
      const note = totals.legalNote
        ? `<p class="tax-note" style="margin-top: 12px; font-size: 10pt;">${escapeHtml(totals.legalNote)}</p>`
        : ''
      return `<div style="margin-top: 30px;"><table style="width: 300px; border-collapse: collapse; ${margin}">`
        + row(label(config, 'labelSubtotal', 'Zwischensumme'), formatCurrency(totals.net))
        + taxRows
        + `<tr style="border-top: 2px solid ${fmt.fontColor};"><td style="padding: 12px 8px; font-size: ${totalPt}; font-weight: 800; color: ${fmt.fontColor};">${label(config, 'labelTotal', 'Gesamtbetrag')}</td><td style="padding: 12px 8px; text-align: right; font-size: ${totalPt}; font-weight: 800; color: ${fmt.fontColor};">${formatCurrency(totals.gross)}</td></tr>`
        + `</table>${note}</div>`
    }
  },
  {
    type: 'payment',
    name: 'Zahlungsdaten',
    description: 'Bankverbindung und Verwendungszweck',
    icon: 'payment',
    limits: { max: 1 },
    formatDefaults: { fontSize: 10.5, fontColor: '#1f2937' },
    defaultData: {
      paymentMethod: 'Banküberweisung',
      bankName: '',
      iban: '{{iban}}',
      bic: '',
      reference: '{{verwendungszweck}}'
    },
    defaultConfig: {
      showBic: true,
      format: { fontFamily: 'Inter', fontSize: 10.5, fontColor: '#1f2937', fontWeight: 'normal' }
    },
    fields: [
      { key: 'paymentMethod', label: 'Zahlungsart', kind: 'text' },
      { key: 'bankName', label: 'Bank', kind: 'text', placeholder: 'z. B. Sparkasse ...' },
      { key: 'iban', label: 'IBAN', kind: 'text' },
      { key: 'bic', label: 'BIC', kind: 'text' },
      { key: 'reference', label: 'Verwendungszweck', kind: 'text' }
    ],
    settings: [
      { key: 'showBic', label: 'BIC anzeigen', kind: 'toggle' },
      WIDTH_SETTING
    ],
    render(data, config) {
      const fmt = getFormat(config, this.formatDefaults)
      const line = (title, value) => value ? `<div><strong>${title}:</strong> ${value}</div>` : ''
      return `<div class="payment-block" style="margin-top: 40px; padding: 20px; background: #f9fafb; border-radius: 8px;">`
        + `<h3 style="margin: 0 0 15px 0; font-size: ${pt(Math.max(6, fmt.fontSize - 2))}; font-weight: 700; text-transform: uppercase; color: #6b7280;">Zahlungsinformationen</h3>`
        + `<div style="${bodyStyle(config, this.formatDefaults)} line-height: 1.8;">`
        + line('Zahlungsart', data.paymentMethod)
        + line('Bank', data.bankName)
        + line('IBAN', data.iban)
        + (config.showBic ? line('BIC', data.bic) : '')
        + line('Verwendungszweck', data.reference)
        + '</div></div>'
    }
  },
  {
    type: 'notes',
    name: 'Hinweise',
    description: 'Freitext, z. B. Notizen oder Zahlungsbedingungen',
    icon: 'notes',
    limits: { max: Infinity },
    formatDefaults: { fontSize: 9.5, fontColor: '#78350f' },
    defaultData: {
      content: '{{notizen}}'
    },
    defaultConfig: {
      showTitle: true,
      title: 'Notizen',
      showBox: true,
      backgroundColor: '#fef3c7',
      borderColor: '#f59e0b',
      format: { fontFamily: 'Inter', fontSize: 9.5, fontColor: '#78350f', fontWeight: 'normal' }
    },
    fields: [
      { key: 'content', label: 'Text', kind: 'multiline', help: 'Ist der Text leer, wird der Block nicht gedruckt.' }
    ],
    settings: [
      { key: 'showTitle', label: 'Überschrift anzeigen', kind: 'toggle' },
      { key: 'title', label: 'Überschrift', kind: 'text', showIf: 'showTitle' },
      { key: 'showBox', label: 'Farbige Box', kind: 'toggle' },
      { key: 'backgroundColor', label: 'Hintergrundfarbe', kind: 'color', showIf: 'showBox' },
      { key: 'borderColor', label: 'Randfarbe', kind: 'color', showIf: 'showBox' },
      WIDTH_SETTING
    ],
    render(data, config) {
      if (!data.content || !String(data.content).trim()) return ''
      const fmt = getFormat(config, this.formatDefaults)
      const box = config.showBox !== false
        ? `margin-top: 30px; padding: 12px 16px; background-color: ${safeColor(config.backgroundColor, '#fef3c7')}; border-left: 4px solid ${safeColor(config.borderColor, '#f59e0b')}; border-radius: 4px;`
        : 'margin-top: 30px;'
      const title = config.showTitle
        ? `<p style="margin: 0; font-size: ${pt(Math.max(7, fmt.fontSize))}; color: #92400e; font-weight: 600;">${label(config, 'title', 'Notizen')}</p>`
        : ''
      return `<div class="notes-block" style="${box}">${title}<p style="margin: 4px 0 0 0; ${bodyStyle(config, this.formatDefaults)} white-space: pre-wrap;">${data.content}</p></div>`
    }
  },
  {
    type: 'footer',
    name: 'Fußzeile',
    description: 'Dank, Website, Register- und Steuernummer',
    icon: 'footer',
    limits: { max: 1 },
    formatDefaults: { fontSize: 9, fontColor: '#9ca3af' },
    defaultData: {
      text: 'Vielen Dank für Ihr Vertrauen!',
      website: '{{website}}',
      registrationNumber: '',
      taxNumber: ''
    },
    defaultConfig: {
      layout: 'centered',
      format: { fontFamily: 'Inter', fontSize: 9, fontColor: '#9ca3af', fontWeight: 'normal' }
    },
    fields: [
      { key: 'text', label: 'Text', kind: 'multiline' },
      { key: 'website', label: 'Website', kind: 'text' },
      { key: 'registrationNumber', label: 'Registernummer', kind: 'text', placeholder: 'z. B. HRB 12345' },
      { key: 'taxNumber', label: 'Steuernummer', kind: 'text' }
    ],
    settings: [
      {
        key: 'layout',
        label: 'Ausrichtung',
        kind: 'select',
        options: [
          { value: 'left', label: 'Links' },
          { value: 'centered', label: 'Zentriert' }
        ]
      },
      WIDTH_SETTING
    ],
    render(data, config) {
      const p = (text, margin) => text ? `<p style="margin: ${margin};">${text}</p>` : ''
      return `<div class="footer" style="margin-top: 60px; padding-top: 20px; border-top: 1px solid #eee; ${bodyStyle(config, this.formatDefaults)} text-align: ${config.layout === 'centered' ? 'center' : 'left'};">`
        + p(data.text, '0 0 10px 0')
        + p(data.website, '0 0 5px 0')
        + p(data.registrationNumber && `Registernummer: ${data.registrationNumber}`, '0 0 5px 0')
        + p(data.taxNumber && `Steuernummer: ${data.taxNumber}`, '0')
        + '</div>'
    }
  }
]

/**
 * Looks up a block type.
 * @param {string} type
 * @returns {Object|undefined}
 */
export function getBlockByType(type) {
  return BLOCK_TYPES.find(b => b.type === type)
}

/**
 * Human-readable name of a block instance (`Adresse – Empfänger`).
 * @param {Object} block
 * @returns {string}
 */
export function blockDisplayName(block) {
  const type = getBlockByType(block.type)
  if (!type) return block.type
  if (block.type === 'address') {
    return `${type.name} – ${block.data && block.data.role === 'receiver' ? 'Empfänger' : 'Absender'}`
  }
  return type.name
}

/** Documented for tests and the title placeholder. */
export { documentTitle as defaultDocumentTitle }

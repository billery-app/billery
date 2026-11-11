import { describe, it, expect } from 'vitest'
import { SAMPLE_DATA, processTemplateContent, generateSafePreviewHtml } from './templatePreview.js'

describe('templatePreview - Real Workflows', () => {
  describe('SAMPLE_DATA', () => {
    it('contains company data', () => {
      expect(SAMPLE_DATA.firma).toBe('Musterfirma GmbH')
      expect(SAMPLE_DATA.adresse).toContain('Musterstraße')
      expect(SAMPLE_DATA.telefon).toBeDefined()
      expect(SAMPLE_DATA.email).toBeDefined()
      expect(SAMPLE_DATA.website).toBeDefined()
      expect(SAMPLE_DATA.ust_id).toBeDefined()
    })

    it('contains client data', () => {
      expect(SAMPLE_DATA.kunde).toBe('Anna Müller')
      expect(SAMPLE_DATA.kunde_strasse).toBeDefined()
      expect(SAMPLE_DATA.kunde_plz_ort).toBeDefined()
      expect(SAMPLE_DATA.kunde_email).toBeDefined()
    })

    it('contains invoice data', () => {
      expect(SAMPLE_DATA.rechnungsnummer).toBe('RE-2025-001')
      expect(SAMPLE_DATA.rechnungsdatum).toBeDefined()
      expect(SAMPLE_DATA.faelligkeitsdatum).toBeDefined()
    })

    it('contains totals data', () => {
      expect(SAMPLE_DATA.netto).toBe(1000)
      expect(SAMPLE_DATA.steuersatz).toBe(19)
      expect(SAMPLE_DATA.steuer).toBe(190)
      expect(SAMPLE_DATA.betrag).toBe(1190)
    })

    it('contains payment data', () => {
      expect(SAMPLE_DATA.bank_name).toBe('Commerzbank')
      expect(SAMPLE_DATA.iban).toBeDefined()
      expect(SAMPLE_DATA.bic).toBeDefined()
    })

    it('contains positionen with table HTML', () => {
      expect(SAMPLE_DATA.positionen).toContain('table')
      expect(SAMPLE_DATA.positionen).toContain('items-table')
    })
  })

  describe('processTemplateContent', () => {
    it('replaces single variable in content', () => {
      const content = '<h1>{{firma}}</h1>'
      const result = processTemplateContent(content)

      expect(result).toContain('Musterfirma GmbH')
      expect(result).not.toContain('{{firma}}')
    })

    it('replaces multiple variables in content', () => {
      const content = '{{firma}} - {{rechnungsnummer}}'
      const result = processTemplateContent(content)

      expect(result).toContain('Musterfirma GmbH')
      expect(result).toContain('RE-2025-001')
    })

    it('replaces all occurrences of same variable', () => {
      const content = '{{firma}} and {{firma}} again'
      const result = processTemplateContent(content)

      expect(result.split('Musterfirma GmbH').length).toBe(3)
    })

    it('returns empty string for empty content', () => {
      expect(processTemplateContent('')).toBe('')
    })

    it('returns empty string for null content', () => {
      expect(processTemplateContent(null)).toBe('')
    })

    it('returns empty string for undefined content', () => {
      expect(processTemplateContent(undefined)).toBe('')
    })

    it('merges custom data with sample data', () => {
      const content = '{{firma}} - {{rechnungsnummer}}'
      const customData = { firma: 'Custom Company' }
      const result = processTemplateContent(content, customData)

      expect(result).toContain('Custom Company')
      expect(result).toContain('RE-2025-001')
    })

    it('custom data overrides sample data', () => {
      const content = '{{firma}}'
      const customData = { firma: 'Override' }
      const result = processTemplateContent(content, customData)

      expect(result).toContain('Override')
      expect(result).not.toContain('Musterfirma')
    })

    it('leaves unreplaced variables if not in data', () => {
      const content = '{{unknown_var}}'
      const result = processTemplateContent(content)

      expect(result).toContain('{{unknown_var}}')
    })

    it('handles numeric variables', () => {
      const content = 'Amount: {{netto}}'
      const result = processTemplateContent(content)

      expect(result).toContain('1000')
    })

    it('handles multiline content', () => {
      const content = 'Line 1\n{{firma}}\nLine 3'
      const result = processTemplateContent(content)

      expect(result).toContain('\n')
      expect(result).toContain('Musterfirma')
    })
  })

  describe('generateSafePreviewHtml', () => {
    it('wraps content in style tags', () => {
      const result = generateSafePreviewHtml('<p>Test</p>', '.custom {}')

      expect(result).toContain('<style>')
      expect(result).toContain('.custom')
    })

    it('processes template variables in content', () => {
      const result = generateSafePreviewHtml('{{firma}}')

      expect(result).toContain('Musterfirma')
    })

    it('sanitizes HTML content', () => {
      const result = generateSafePreviewHtml('<script>alert(1)</script><p>Test</p>')

      expect(result).not.toContain('<script>')
      expect(result).toContain('<p>')
    })

    it('works without custom CSS', () => {
      const result = generateSafePreviewHtml('<p>Test</p>')

      expect(result).toContain('<p>Test</p>')
    })

    it('works without custom data', () => {
      const result = generateSafePreviewHtml('<p>{{firma}}</p>')

      expect(result).toContain('Musterfirma')
    })
  })

  describe('Variable Replacement Logic', () => {
    it('handles all SAMPLE_DATA keys', () => {
      const content = Object.keys(SAMPLE_DATA).map(k => `{{${k}}}`).join(' ')
      const result = processTemplateContent(content)

      Object.keys(SAMPLE_DATA).forEach(key => {
        expect(result).not.toContain(`{{${key}}}`)
      })
    })

    it('preserves HTML structure', () => {
      const content = '<div class="header">{{firma}}</div>'
      const result = processTemplateContent(content)

      expect(result).toContain('<div')
      expect(result).toContain('class="header"')
      expect(result).toContain('Musterfirma')
    })
  })
})
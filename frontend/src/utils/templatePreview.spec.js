import { describe, it, expect } from 'vitest'
import { SAMPLE_DATA } from './templatePreview.js'

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
})

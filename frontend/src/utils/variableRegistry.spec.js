import { describe, it, expect } from 'vitest'
import { VARIABLE_REGISTRY, DEFAULT_VARIABLES, VARIABLE_TYPES, getVariableLabel, getVariablesByCategory } from './variableRegistry.js'

describe('variableRegistry - User Workflows', () => {
  describe('User Workflow: View Available Variables', () => {
    it('provides all company variables', () => {
      const companyVars = VARIABLE_REGISTRY.filter(v => v.category === 'Company')
      expect(companyVars.length).toBeGreaterThan(0)
      expect(companyVars.some(v => v.name === 'firma')).toBe(true)
      expect(companyVars.some(v => v.name === 'adresse')).toBe(true)
      expect(companyVars.some(v => v.name === 'telefon')).toBe(true)
      expect(companyVars.some(v => v.name === 'email')).toBe(true)
    })

    it('provides all client variables', () => {
      const clientVars = VARIABLE_REGISTRY.filter(v => v.category === 'Client')
      expect(clientVars.length).toBeGreaterThan(0)
      expect(clientVars.some(v => v.name === 'kunde')).toBe(true)
      expect(clientVars.some(v => v.name === 'kunde_strasse')).toBe(true)
      expect(clientVars.some(v => v.name === 'kunde_plz_ort')).toBe(true)
    })

    it('provides all invoice variables', () => {
      const invoiceVars = VARIABLE_REGISTRY.filter(v => v.category === 'Invoice')
      expect(invoiceVars.length).toBeGreaterThan(0)
      expect(invoiceVars.some(v => v.name === 'rechnungsnummer')).toBe(true)
      expect(invoiceVars.some(v => v.name === 'rechnungsdatum')).toBe(true)
      expect(invoiceVars.some(v => v.name === 'faelligkeitsdatum')).toBe(true)
    })

    it('provides all totals variables', () => {
      const totalsVars = VARIABLE_REGISTRY.filter(v => v.category === 'Totals')
      expect(totalsVars.length).toBeGreaterThan(0)
      expect(totalsVars.some(v => v.name === 'netto')).toBe(true)
      expect(totalsVars.some(v => v.name === 'steuersatz')).toBe(true)
      expect(totalsVars.some(v => v.name === 'steuer')).toBe(true)
      expect(totalsVars.some(v => v.name === 'betrag')).toBe(true)
    })

    it('provides all payment variables', () => {
      const paymentVars = VARIABLE_REGISTRY.filter(v => v.category === 'Payment')
      expect(paymentVars.length).toBeGreaterThan(0)
      expect(paymentVars.some(v => v.name === 'bank_name')).toBe(true)
      expect(paymentVars.some(v => v.name === 'iban')).toBe(true)
      expect(paymentVars.some(v => v.name === 'bic')).toBe(true)
      expect(paymentVars.some(v => v.name === 'verwendungszweck')).toBe(true)
    })
  })

  describe('User Workflow: Get Variables by Category', () => {
    it('groups variables by category', () => {
      const categories = getVariablesByCategory()

      expect(categories.Company).toBeDefined()
      expect(categories.Client).toBeDefined()
      expect(categories.Invoice).toBeDefined()
      expect(categories.Totals).toBeDefined()
      expect(categories.Payment).toBeDefined()
    })

    it('contains Company variables in correct category', () => {
      const categories = getVariablesByCategory()
      const companyVars = categories.Company.map(v => v.name)

      expect(companyVars).toContain('firma')
      expect(companyVars).toContain('adresse')
    })

    it('contains Client variables in correct category', () => {
      const categories = getVariablesByCategory()
      const clientVars = categories.Client.map(v => v.name)

      expect(clientVars).toContain('kunde')
      expect(clientVars).toContain('kunde_strasse')
    })
  })

  describe('User Workflow: Get Variable Labels', () => {
    it('returns label for known variable', () => {
      expect(getVariableLabel('firma')).toBe('Firma')
      expect(getVariableLabel('adresse')).toBe('Adresse')
      expect(getVariableLabel('rechnungsnummer')).toBe('Rechnungsnummer')
    })

    it('returns name for unknown variable', () => {
      expect(getVariableLabel('unknown_var')).toBe('unknown_var')
    })
  })

  describe('Variable Type Definitions', () => {
    it('defines STRING type', () => {
      expect(VARIABLE_TYPES.STRING).toBe('string')
    })

    it('defines NUMBER type', () => {
      expect(VARIABLE_TYPES.NUMBER).toBe('number')
    })

    it('defines DATE type', () => {
      expect(VARIABLE_TYPES.DATE).toBe('date')
    })

    it('defines ARRAY type', () => {
      expect(VARIABLE_TYPES.ARRAY).toBe('array')
    })

    it('defines OBJECT type', () => {
      expect(VARIABLE_TYPES.OBJECT).toBe('object')
    })

    it('defines BOOLEAN type', () => {
      expect(VARIABLE_TYPES.BOOLEAN).toBe('boolean')
    })

    it('variables have correct types', () => {
      const firmaVar = VARIABLE_REGISTRY.find(v => v.name === 'firma')
      expect(firmaVar.type).toBe(VARIABLE_TYPES.STRING)

      const nettoVar = VARIABLE_REGISTRY.find(v => v.name === 'netto')
      expect(nettoVar.type).toBe(VARIABLE_TYPES.NUMBER)

      const datumVar = VARIABLE_REGISTRY.find(v => v.name === 'rechnungsdatum')
      expect(datumVar.type).toBe(VARIABLE_TYPES.DATE)
    })
  })

  describe('Default Variables', () => {
    it('provides default company values', () => {
      expect(DEFAULT_VARIABLES.firma).toBe('Musterfirma GmbH')
      expect(DEFAULT_VARIABLES.adresse).toContain('Musterstraße')
      expect(DEFAULT_VARIABLES.telefon).toBeDefined()
      expect(DEFAULT_VARIABLES.email).toBeDefined()
    })

    it('provides default client values', () => {
      expect(DEFAULT_VARIABLES.kunde).toBe('Anna Müller')
      expect(DEFAULT_VARIABLES.kunde_strasse).toBeDefined()
      expect(DEFAULT_VARIABLES.kunde_plz_ort).toBeDefined()
    })

    it('provides default invoice values', () => {
      expect(DEFAULT_VARIABLES.rechnungsnummer).toBe('RE-2025-001')
      expect(DEFAULT_VARIABLES.rechnungsdatum).toBeDefined()
      expect(DEFAULT_VARIABLES.faelligkeitsdatum).toBeDefined()
    })

    it('provides default totals values', () => {
      expect(DEFAULT_VARIABLES.netto).toBe(1000)
      expect(DEFAULT_VARIABLES.steuersatz).toBe(19)
      expect(DEFAULT_VARIABLES.steuer).toBe(190)
      expect(DEFAULT_VARIABLES.betrag).toBe(1190)
      expect(DEFAULT_VARIABLES.waehrung).toBe('EUR')
    })

    it('provides default payment values', () => {
      expect(DEFAULT_VARIABLES.bank_name).toBe('Commerzbank')
      expect(DEFAULT_VARIABLES.iban).toBeDefined()
      expect(DEFAULT_VARIABLES.bic).toBeDefined()
      expect(DEFAULT_VARIABLES.verwendungszweck).toBeDefined()
    })
  })

  describe('Variable Completeness', () => {
    it('all variables have name property', () => {
      VARIABLE_REGISTRY.forEach(v => {
        expect(v.name).toBeDefined()
        expect(typeof v.name).toBe('string')
      })
    })

    it('all variables have label property', () => {
      VARIABLE_REGISTRY.forEach(v => {
        expect(v.label).toBeDefined()
        expect(typeof v.label).toBe('string')
      })
    })

    it('all variables have type property', () => {
      VARIABLE_REGISTRY.forEach(v => {
        expect(v.type).toBeDefined()
      })
    })

    it('all variables have category property', () => {
      VARIABLE_REGISTRY.forEach(v => {
        expect(v.category).toBeDefined()
      })
    })

    it('all variable types are valid', () => {
      const validTypes = Object.values(VARIABLE_TYPES)
      VARIABLE_REGISTRY.forEach(v => {
        expect(validTypes).toContain(v.type)
      })
    })

    it('all variable categories are valid', () => {
      const validCategories = ['Company', 'Client', 'Invoice', 'Totals', 'Payment']
      VARIABLE_REGISTRY.forEach(v => {
        expect(validCategories).toContain(v.category)
      })
    })
  })
})
/**
 * Template variables data and utilities
 */

export const VARIABLE_CATEGORIES = [
  {
    name: 'absender',
    label: 'Absender',
    variables: [
      { name: '{{OWNER_COMPANY}}', label: 'Firma', description: 'Ihr Firmenname' },
      { name: '{{OWNER_FIRST_NAME}} {{OWNER_LAST_NAME}}', label: 'Name', description: 'Ihr vollständiger Name' },
      { name: '{{OWNER_ADDRESS}}', label: 'Adresse', description: 'Ihre Straßenadresse' },
      { name: '{{OWNER_POSTAL_CODE}} {{OWNER_CITY}}', label: 'PLZ/Ort', description: 'Ihre Postleitzahl und Stadt' },
      { name: '{{OWNER_PHONE}}', label: 'Telefon', description: 'Ihre Telefonnummer' },
      { name: '{{OWNER_EMAIL}}', label: 'Email', description: 'Ihre Email-Adresse' }
    ]
  },
  {
    name: 'bank',
    label: 'Bankdaten',
    variables: [
      { name: '{{OWNER_IBAN}}', label: 'IBAN', description: 'Ihre IBAN' },
      { name: '{{OWNER_BIC}}', label: 'BIC', description: 'Ihre BIC/SWIFT' },
      { name: '{{OWNER_BANK_NAME}}', label: 'Bank', description: 'Ihr Kreditinstitut' }
    ]
  },
  {
    name: 'kunde',
    label: 'Empfänger',
    variables: [
      { name: '{{CUSTOMER_FIRST_NAME}} {{CUSTOMER_LAST_NAME}}', label: 'Name', description: 'Kundenname' },
      { name: '{{CUSTOMER_ADDRESS}} {{CUSTOMER_HOUSE_NUMBER}}', label: 'Adresse', description: 'Kundenadresse' },
      { name: '{{CUSTOMER_POSTAL_CODE}} {{CUSTOMER_CITY}}', label: 'PLZ/Ort', description: 'Kunden PLZ/Ort' }
    ]
  },
  {
    name: 'rechnung',
    label: 'Rechnung',
    variables: [
      { name: '{{INVOICE_NUMBER}}', label: 'Rechnungsnr.', description: 'Eindeutige Rechnungsnummer' },
      { name: '{{INVOICE_DATE}}', label: 'Datum', description: 'Rechnungsdatum' },
      { name: '{{DUE_DATE}}', label: 'Fällig', description: 'Fälligkeitsdatum' },
      { name: '{{SERVICE_DATE}}', label: 'Leistungsdatum', description: 'Datum der Leistungserbringung' },
      { name: '{{SERVICE_PERIOD}}', label: 'Leistungszeitraum', description: 'Zeitraum der Leistung' },
      { name: '{{NET_AMOUNT}}', label: 'Netto', description: 'Nettobetrag' },
      { name: '{{TAX_RATE}}', label: 'MwSt%', description: 'Steuersatz' },
      { name: '{{TAX_AMOUNT}}', label: 'MwSt', description: 'Steuerbetrag' },
      { name: '{{GROSS_AMOUNT}}', label: 'Brutto', description: 'Gesamtbetrag' },
      { name: '{{NOTES}}', label: 'Notizen', description: 'Zusätzliche Hinweise' },
      { name: '{{INVOICE_ITEMS}}', label: 'Positionen', description: 'Rechnungspositionen' }
    ]
  },
  {
    name: 'layout',
    label: 'Layout',
    variables: [
      { name: '{{PAGE_BREAK}}', label: 'Seitenwechsel', description: 'Neue Seite beginnen' },
      { name: '{{HEADER}}', label: 'Kopfzeile', description: 'Kopfzeile auf jeder Seite' },
      { name: '{{FOOTER}}', label: 'Fußzeile', description: 'Fußzeile auf jeder Seite' },
      { name: '{{PAGE_NUMBER}}', label: 'Seitenzahl', description: 'Aktuelle Seitenzahl' },
      { name: '{{TOTAL_PAGES}}', label: 'Gesamte Seiten', description: 'Anzahl der Seiten' }
    ]
  }
]

export const SPECIAL_VARIABLES = {
  pageNumber: '{{pageNumber}}',
  totalPages: '{{totalPages}}'
}

export function getAllVariables() {
  return VARIABLE_CATEGORIES.flatMap(category => category.variables)
}

export function getVariablesByCategory(categoryName) {
  const category = VARIABLE_CATEGORIES.find(cat => cat.name === categoryName)
  return category ? category.variables : []
}

export function isValidVariable(variable) {
  const allVariables = getAllVariables()
  const specialVars = Object.values(SPECIAL_VARIABLES)
  return [...allVariables.map(v => v.name), ...specialVars].includes(variable)
}

export function extractVariablesFromText(text) {
  if (!text) return []
  
  const variableRegex = /\{\{([^}]+)\}\}/g
  const matches = text.match(variableRegex) || []
  
  return [...new Set(matches)].filter(isValidVariable)
}

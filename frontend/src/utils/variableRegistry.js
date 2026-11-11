export const DEFAULT_VARIABLES = {
  firma: 'Musterfirma GmbH',
  adresse: 'Musterstraße 1\n12345 Musterstadt',
  telefon: '+49 123 456789',
  email: 'info@musterfirma.de',
  website: 'www.musterfirma.de',
  ust_id: 'DE123456789',
  
  kunde: 'Anna Müller',
  kunde_strasse: 'Beispielweg 5',
  kunde_plz_ort: '54321 Beispielstadt',
  kunde_email: 'anna@example.de',
  kunde_telefon: '+49 987 654321',
  
  rechnungsnummer: 'RE-2025-001',
  rechnungsdatum: '15.01.2025',
  faelligkeitsdatum: '15.02.2025',
  leistungsdatum: '10.01.2025',
  leistungszeitraum: '10.01.2025 – 12.01.2025',
  
  positionen: [],
  
  netto: 1000,
  steuersatz: 19,
  steuer: 190,
  betrag: 1190,
  rabatt: 0,
 waehrung: 'EUR',
  
  Zahlungsart: 'Banküberweisung',
  bank_name: 'Commerzbank',
  iban: 'DE89 3704 0044 0532 0130 00',
  bic: 'COBADEFFXXX',
  verwendungszweck: 'RE-2025-001',
  
  notizen: 'Hier können zusätzliche Hinweise oder Notizen stehen.',
  kleinunternehmer_hinweis: 'Hinweis: Als Kleinunternehmer im Sinne von § 19 Abs. 1 UStG wird Umsatzsteuer nicht berechnet.',
  footer_notiz: 'Vielen Dank für Ihr Vertrauen!',
}

export const VARIABLE_TYPES = {
  STRING: 'string',
  NUMBER: 'number',
  DATE: 'date',
  ARRAY: 'array',
  OBJECT: 'object',
  BOOLEAN: 'boolean'
}

export const VARIABLE_REGISTRY = [
  {
    name: 'firma',
    label: 'Firma',
    type: VARIABLE_TYPES.STRING,
    category: 'Company'
  },
  {
    name: 'adresse',
    label: 'Adresse',
    type: VARIABLE_TYPES.STRING,
    category: 'Company'
  },
  {
    name: 'telefon',
    label: 'Telefon',
    type: VARIABLE_TYPES.STRING,
    category: 'Company'
  },
  {
    name: 'email',
    label: 'E-Mail',
    type: VARIABLE_TYPES.STRING,
    category: 'Company'
  },
  {
    name: 'website',
    label: 'Website',
    type: VARIABLE_TYPES.STRING,
    category: 'Company'
  },
  {
    name: 'ust_id',
    label: 'UST-ID',
    type: VARIABLE_TYPES.STRING,
    category: 'Company'
  },
  {
    name: 'kunde',
    label: 'Kunde',
    type: VARIABLE_TYPES.STRING,
    category: 'Client'
  },
  {
    name: 'kunde_strasse',
    label: 'Straße',
    type: VARIABLE_TYPES.STRING,
    category: 'Client'
  },
  {
    name: 'kunde_plz_ort',
    label: 'PLZ/Ort',
    type: VARIABLE_TYPES.STRING,
    category: 'Client'
  },
  {
    name: 'kunde_email',
    label: 'E-Mail',
    type: VARIABLE_TYPES.STRING,
    category: 'Client'
  },
  {
    name: 'kunde_telefon',
    label: 'Telefon',
    type: VARIABLE_TYPES.STRING,
    category: 'Client'
  },
  {
    name: 'rechnungsnummer',
    label: 'Rechnungsnummer',
    type: VARIABLE_TYPES.STRING,
    category: 'Invoice'
  },
  {
    name: 'rechnungsdatum',
    label: 'Rechnungsdatum',
    type: VARIABLE_TYPES.DATE,
    category: 'Invoice'
  },
  {
    name: 'faelligkeitsdatum',
    label: 'Fälligkeitsdatum',
    type: VARIABLE_TYPES.DATE,
    category: 'Invoice'
  },
  {
    name: 'leistungsdatum',
    label: 'Leistungsdatum',
    type: VARIABLE_TYPES.DATE,
    category: 'Invoice'
  },
  {
    name: 'leistungszeitraum',
    label: 'Leistungszeitraum',
    type: VARIABLE_TYPES.STRING,
    category: 'Invoice'
  },
  {
    name: 'notizen',
    label: 'Notizen',
    type: VARIABLE_TYPES.STRING,
    category: 'Invoice'
  },
  {
    name: 'kleinunternehmer_hinweis',
    label: 'Kleinunternehmer-Hinweis',
    type: VARIABLE_TYPES.STRING,
    category: 'Invoice'
  },
  {
    name: 'netto',
    label: 'Netto',
    type: VARIABLE_TYPES.NUMBER,
    category: 'Totals'
  },
  {
    name: 'steuersatz',
    label: 'MwSt.-Satz',
    type: VARIABLE_TYPES.NUMBER,
    category: 'Totals'
  },
  {
    name: 'steuer',
    label: 'MwSt.-Betrag',
    type: VARIABLE_TYPES.NUMBER,
    category: 'Totals'
  },
  {
    name: 'betrag',
    label: 'Gesamtbetrag',
    type: VARIABLE_TYPES.NUMBER,
    category: 'Totals'
  },
  {
    name: 'rabatt',
    label: 'Rabatt',
    type: VARIABLE_TYPES.NUMBER,
    category: 'Totals'
  },
  {
    name: 'waehrung',
    label: 'Währung',
    type: VARIABLE_TYPES.STRING,
    category: 'Totals'
  },
  {
    name: 'bank_name',
    label: 'Bank',
    type: VARIABLE_TYPES.STRING,
    category: 'Payment'
  },
  {
    name: 'iban',
    label: 'IBAN',
    type: VARIABLE_TYPES.STRING,
    category: 'Payment'
  },
  {
    name: 'bic',
    label: 'BIC',
    type: VARIABLE_TYPES.STRING,
    category: 'Payment'
  },
  {
    name: 'verwendungszweck',
    label: 'Verwendungszweck',
    type: VARIABLE_TYPES.STRING,
    category: 'Payment'
  }
]

export function getVariableLabel(name) {
  const variable = VARIABLE_REGISTRY.find(v => v.name === name)
  return variable ? variable.label : name
}

export function getVariablesByCategory() {
  const categories = {}
  for (const variable of VARIABLE_REGISTRY) {
    if (!categories[variable.category]) {
      categories[variable.category] = []
    }
    categories[variable.category].push(variable)
  }
  return categories
}

/** German group headings of the variable picker. */
export const CATEGORY_LABELS = {
  Company: 'Ihre Firma',
  Client: 'Kunde',
  Invoice: 'Dokument',
  Totals: 'Beträge',
  Payment: 'Zahlung'
}

/**
 * Unambiguous chip labels: the picker and the chips in text fields show these
 * instead of the bare label, so "E-Mail" of the company and of the customer
 * cannot be confused.
 */
const CHIP_LABELS = {
  firma: 'Firmenname',
  adresse: 'Firmenadresse',
  telefon: 'Firmentelefon',
  email: 'Firmen-E-Mail',
  website: 'Website',
  ust_id: 'USt-IdNr.',
  kunde: 'Kundenname',
  kunde_strasse: 'Kunde: Straße',
  kunde_plz_ort: 'Kunde: PLZ/Ort',
  kunde_email: 'Kunde: E-Mail',
  kunde_telefon: 'Kunde: Telefon'
}

/**
 * Variables the PDF renderer resolves to empty text today (the company data
 * has no bank name or BIC). They stay valid in old templates but are not
 * offered in the picker.
 */
const HIDDEN_VARIABLES = new Set(['bank_name', 'bic'])

/**
 * English aliases the PDF renderer also resolves
 * (`TemplateBasedPdfGenerator.replaceVariables`); valid in stored templates.
 */
const ALIASES = {
  company_name: 'firma',
  company_address: 'adresse',
  company_phone: 'telefon',
  company_email: 'email',
  company_website: 'website',
  company_tax_id: 'ust_id',
  client_name: 'kunde',
  client_address: 'kunde_strasse',
  client_email: 'kunde_email',
  client_phone: 'kunde_telefon',
  invoice_number: 'rechnungsnummer',
  invoice_date: 'rechnungsdatum',
  due_date: 'faelligkeitsdatum',
  net_amount: 'netto',
  tax_rate: 'steuersatz',
  tax_amount: 'steuer',
  gross_amount: 'betrag',
  discount: 'rabatt',
  currency: 'waehrung',
  notes: 'notizen'
}

/**
 * Canonical name of a variable (aliases map to their German name).
 * @param {string} name
 * @returns {string}
 */
export function canonicalVariableName(name) {
  return ALIASES[name] || name
}

/**
 * Whether the PDF renderer knows the variable (German name or alias).
 * @param {string} name
 * @returns {boolean}
 */
export function isKnownVariable(name) {
  const canonical = canonicalVariableName(name)
  return VARIABLE_REGISTRY.some(v => v.name === canonical)
}

/**
 * Label shown on a variable chip; unknown names are shown as they are.
 * @param {string} name
 * @returns {string}
 */
export function getChipLabel(name) {
  const canonical = canonicalVariableName(name)
  return CHIP_LABELS[canonical] || getVariableLabel(canonical)
}

/**
 * Variables offered in the picker, grouped under German headings, in
 * registry order.
 * @returns {{key: string, label: string, variables: {name: string, label: string}[]}[]}
 */
export function getPickerGroups() {
  const groups = []
  for (const variable of VARIABLE_REGISTRY) {
    if (HIDDEN_VARIABLES.has(variable.name)) continue
    let group = groups.find(g => g.key === variable.category)
    if (!group) {
      group = { key: variable.category, label: CATEGORY_LABELS[variable.category] || variable.category, variables: [] }
      groups.push(group)
    }
    group.variables.push({ name: variable.name, label: getChipLabel(variable.name) })
  }
  return groups
}

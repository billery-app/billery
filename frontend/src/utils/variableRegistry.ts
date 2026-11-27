import { t } from '../i18n/index.js'

/**
 * The template variables (`{{firma}}`, …): their German ids, the sample values of the editor
 * preview, the labels and picker groups in the interface language (TypeScript since #39, step 3).
 */

/** Sample values of the preview (German: documents stay German). */
export const DEFAULT_VARIABLES: Readonly<Record<string, string | number | unknown[]>> = {
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
} as const

export type VariableType = typeof VARIABLE_TYPES[keyof typeof VARIABLE_TYPES]

/** Group of the variable picker; its heading is `variables.groups.<category>`. */
export type VariableCategory = 'Company' | 'Client' | 'Invoice' | 'Totals' | 'Payment'

/** One variable; `label` reads the message files, so it follows the interface language. */
export interface VariableDefinition {
  readonly name: string
  readonly type: VariableType
  readonly category: VariableCategory
  readonly label: string
}

function variable(name: string, type: VariableType, category: VariableCategory): VariableDefinition {
  return {
    name,
    type,
    category,
    get label() {
      return getVariableLabel(name)
    }
  }
}

export const VARIABLE_REGISTRY: readonly VariableDefinition[] = [
  variable('firma', VARIABLE_TYPES.STRING, 'Company'),
  variable('adresse', VARIABLE_TYPES.STRING, 'Company'),
  variable('telefon', VARIABLE_TYPES.STRING, 'Company'),
  variable('email', VARIABLE_TYPES.STRING, 'Company'),
  variable('website', VARIABLE_TYPES.STRING, 'Company'),
  variable('ust_id', VARIABLE_TYPES.STRING, 'Company'),
  variable('kunde', VARIABLE_TYPES.STRING, 'Client'),
  variable('kunde_strasse', VARIABLE_TYPES.STRING, 'Client'),
  variable('kunde_plz_ort', VARIABLE_TYPES.STRING, 'Client'),
  variable('kunde_email', VARIABLE_TYPES.STRING, 'Client'),
  variable('kunde_telefon', VARIABLE_TYPES.STRING, 'Client'),
  variable('rechnungsnummer', VARIABLE_TYPES.STRING, 'Invoice'),
  variable('rechnungsdatum', VARIABLE_TYPES.DATE, 'Invoice'),
  variable('faelligkeitsdatum', VARIABLE_TYPES.DATE, 'Invoice'),
  variable('leistungsdatum', VARIABLE_TYPES.DATE, 'Invoice'),
  variable('leistungszeitraum', VARIABLE_TYPES.STRING, 'Invoice'),
  variable('notizen', VARIABLE_TYPES.STRING, 'Invoice'),
  variable('kleinunternehmer_hinweis', VARIABLE_TYPES.STRING, 'Invoice'),
  variable('netto', VARIABLE_TYPES.NUMBER, 'Totals'),
  variable('steuersatz', VARIABLE_TYPES.NUMBER, 'Totals'),
  variable('steuer', VARIABLE_TYPES.NUMBER, 'Totals'),
  variable('betrag', VARIABLE_TYPES.NUMBER, 'Totals'),
  variable('rabatt', VARIABLE_TYPES.NUMBER, 'Totals'),
  variable('waehrung', VARIABLE_TYPES.STRING, 'Totals'),
  variable('bank_name', VARIABLE_TYPES.STRING, 'Payment'),
  variable('iban', VARIABLE_TYPES.STRING, 'Payment'),
  variable('bic', VARIABLE_TYPES.STRING, 'Payment'),
  variable('verwendungszweck', VARIABLE_TYPES.STRING, 'Payment')
]

/**
 * Label of a variable in the interface language (`variables.labels.<name>`); the variable names
 * themselves stay German ids in every language (#15).
 * @returns the label, or the name for an unknown variable
 */
export function getVariableLabel(name: string): string {
  return VARIABLE_REGISTRY.some(v => v.name === name) ? t(`variables.labels.${name}`) : name
}

export function getVariablesByCategory(): Partial<Record<VariableCategory, VariableDefinition[]>> {
  const categories: Partial<Record<VariableCategory, VariableDefinition[]>> = {}
  for (const variable of VARIABLE_REGISTRY) {
    (categories[variable.category] ??= []).push(variable)
  }
  return categories
}


/**
 * Variables with an unambiguous chip label (`variables.chips.<name>`): the picker and the chips in
 * text fields show it instead of the bare label, so "E-Mail" of the company and of the customer
 * cannot be confused.
 */
const CHIP_LABELS: ReadonlySet<string> = new Set([
  'firma', 'adresse', 'telefon', 'email', 'website', 'ust_id',
  'kunde', 'kunde_strasse', 'kunde_plz_ort', 'kunde_email', 'kunde_telefon'
])

/**
 * Variables the PDF renderer resolves to empty text today (the company data
 * has no bank name or BIC). They stay valid in old templates but are not
 * offered in the picker.
 */
const HIDDEN_VARIABLES: ReadonlySet<string> = new Set(['bank_name', 'bic'])

/**
 * English aliases the PDF renderer also resolves
 * (`TemplateBasedPdfGenerator.replaceVariables`); valid in stored templates.
 */
const ALIASES: Readonly<Record<string, string>> = {
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
 */
export function canonicalVariableName(name: string): string {
  return ALIASES[name] || name
}

/**
 * Whether the PDF renderer knows the variable (German name or alias).
 */
export function isKnownVariable(name: string): boolean {
  const canonical = canonicalVariableName(name)
  return VARIABLE_REGISTRY.some(v => v.name === canonical)
}

/**
 * Label shown on a variable chip; unknown names are shown as they are.
 */
export function getChipLabel(name: string): string {
  const canonical = canonicalVariableName(name)
  return CHIP_LABELS.has(canonical) ? t(`variables.chips.${canonical}`) : getVariableLabel(canonical)
}

/** One group of the variable picker: its heading and the chip labels of its variables. */
export interface PickerGroup {
  key: VariableCategory
  label: string
  variables: { name: string, label: string }[]
}

/**
 * Variables offered in the picker, grouped under translated headings, in
 * registry order.
 */
export function getPickerGroups(): PickerGroup[] {
  const groups: PickerGroup[] = []
  for (const variable of VARIABLE_REGISTRY) {
    if (HIDDEN_VARIABLES.has(variable.name)) continue
    let group = groups.find(g => g.key === variable.category)
    if (!group) {
      group = { key: variable.category, label: t(`variables.groups.${variable.category}`), variables: [] }
      groups.push(group)
    }
    group.variables.push({ name: variable.name, label: getChipLabel(variable.name) })
  }
  return groups
}

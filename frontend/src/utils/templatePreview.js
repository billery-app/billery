import { sanitizeHtml } from './sanitize.js'

export const SAMPLE_DATA = {
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
  rechnungsdatum: '2025-01-15',
  faelligkeitsdatum: '2025-02-15',
  leistungsdatum: '10.01.2025',
  leistungszeitraum: '10.01.2025 – 12.01.2025',
  notizen: 'Hier können zusätzliche Hinweise oder Notizen stehen.',
  kleinunternehmer_hinweis: 'Hinweis: Als Kleinunternehmer im Sinne von § 19 Abs. 1 UStG wird Umsatzsteuer nicht berechnet',
  
  netto: 1000,
  steuersatz: 19,
  steuer: 190,
  betrag: 1190,
  rabatt: 0,
  waehrung: 'EUR',
  
  bank_name: 'Commerzbank',
  iban: 'DE89 3704 0044 0532 0130 00',
  bic: 'COBADEFFXXX',
  verwendungszweck: 'RE-2025-001',
  
  positionen: `
    <table class="items-table" border="1" cellpadding="8" cellspacing="0" style="width:100%; border-collapse: collapse;">
      <tr style="background: #f0f0f0;">
        <th>Pos.</th>
        <th>Beschreibung</th>
        <th>Menge</th>
        <th>Einheit</th>
        <th>Einzelpreis</th>
        <th>Gesamtpreis</th>
      </tr>
      <tr>
        <td>1</td>
        <td>Beratung und Planung</td>
        <td>5</td>
        <td>Std.</td>
        <td>100,00 €</td>
        <td>500,00 €</td>
      </tr>
      <tr>
        <td>2</td>
        <td>Materialkosten</td>
        <td>1</td>
        <td>Pauschal</td>
        <td>500,00 €</td>
        <td>500,00 €</td>
      </tr>
      <tr style="background: #f8f8f8; font-weight: bold;">
        <td colspan="4" style="text-align: right;">Gesamt:</td>
        <td>1.000,00 €</td>
        <td>1.190,00 €</td>
      </tr>
    </table>
  `
}

export function processTemplateContent(templateContent, customData = {}) {
  if (!templateContent) return ''
  
  const data = { ...SAMPLE_DATA, ...customData }
  
  return templateContent.replace(/\{\{([^}]+)\}\}/g, (match, varName) => {
    return data[varName] !== undefined ? data[varName] : match
  })
}

export function generateSafePreviewHtml(content, cssContent = '', customData = {}) {
  const processedContent = processTemplateContent(content, customData)
  const sanitizedContent = sanitizeHtml(processedContent)
  
  return `
    <style>${cssContent || ''}</style>
    ${sanitizedContent}
  `
}

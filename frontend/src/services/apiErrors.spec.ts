import { afterEach, describe, expect, it } from 'vitest'
import { applyLanguage } from '../i18n/index.js'
import { errorText, reasonText } from './apiErrors.ts'
import { extractErrorMessage } from './baseService.js'

describe('apiErrors', () => {
  afterEach(() => applyLanguage('de'))

  it('shows a coded message in the interface language with its parameters', () => {
    const body = { message: 'Der Zeitraum ist zu lang (höchstens 3660 Tage pro Export).', code: 'export.periodTooLong', params: { max: 3660 } }
    expect(errorText(body)).toBe('Der Zeitraum ist zu lang (höchstens 3660 Tage pro Export).')
    applyLanguage('en')
    expect(errorText(body)).toBe('The period is too long (at most 3660 days per export).')
  })

  it('translates nested messages and lists of them', () => {
    applyLanguage('en')
    const body = {
      message: 'Der Status „Angenommen“ kann nicht in „Entwurf“ geändert werden. Möglich: Umgewandelt, Storniert.',
      code: 'status.changeRefused',
      params: {
        from: { code: 'statusName.accepted', params: {} },
        to: { code: 'statusName.draft', params: {} },
        possible: [{ code: 'statusName.converted', params: {} }, { code: 'statusName.cancelled', params: {} }]
      }
    }
    expect(errorText(body)).toBe('The status “Accepted” cannot be changed to “Draft”. Possible: Converted, Cancelled.')
  })

  it('falls back to the German message for an unknown code or none', () => {
    applyLanguage('en')
    expect(errorText({ message: 'Neue Meldung', code: 'not.yet.known' })).toBe('Neue Meldung')
    expect(errorText({ message: 'Ohne Code' })).toBe('Ohne Code')
    expect(errorText(null)).toBeUndefined()
    expect(errorText('text')).toBeUndefined()
  })

  it('reads coded messages out of a blob answer (downloads)', async () => {
    applyLanguage('en')
    const blob = new Blob([JSON.stringify({ message: 'Dokument 7 wurde nicht gefunden.', code: 'document.notFound', params: { id: 7 } })], { type: 'application/json' })
    expect(await extractErrorMessage(blob)).toBe('Document 7 was not found.')
  })

  it('translates a reason sent next to a success', () => {
    applyLanguage('en')
    expect(reasonText('Das Tageslimit für Einladungsmails ist erreicht.', 'saas.inviteMailLimit')).toBe('The daily limit for invitation mails is reached.')
    expect(reasonText('Unbekannt', null)).toBe('Unbekannt')
    expect(reasonText(null, null)).toBeUndefined()
  })
})

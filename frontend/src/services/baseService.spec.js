import { describe, it, expect } from 'vitest'
import { extractErrorMessage } from './baseService.js'

describe('extractErrorMessage', () => {
  it('reads the message from a JSON error body', async () => {
    expect(await extractErrorMessage({ message: 'Datenbankfehler' })).toBe('Datenbankfehler')
  })

  it('reads the message from a JSON Blob (PDF download error)', async () => {
    const blob = new Blob([JSON.stringify({ message: 'Bitte zuerst die Firmendaten anlegen' })], {
      type: 'application/json'
    })
    expect(await extractErrorMessage(blob)).toBe('Bitte zuerst die Firmendaten anlegen')
  })

  it('ignores non-JSON blobs and missing bodies', async () => {
    expect(await extractErrorMessage(new Blob(['%PDF'], { type: 'application/pdf' }))).toBeUndefined()
    expect(await extractErrorMessage(undefined)).toBeUndefined()
  })
})

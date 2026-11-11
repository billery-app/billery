import { describe, expect, it, vi } from 'vitest'
import { captureKeyboardZoomSnapshot, createKeyboardZoomTelemetry } from './keyboardZoomTelemetry.js'

describe('keyboard zoom telemetry', () => {
  it('captures rendered field and viewport dimensions without including the field value', () => {
    const field = document.createElement('textarea')
    field.className = 'document-notes-input'
    field.value = 'private customer note'
    document.body.appendChild(field)
    field.focus()

    try {
      const snapshot = captureKeyboardZoomSnapshot()
      const serialized = JSON.stringify(snapshot)

      expect(snapshot.activeElement.tag).toBe('textarea')
      expect(snapshot.activeElement.className).toBe('document-notes-input')
      expect(snapshot.activeElement.rect).toHaveProperty('width')
      expect(snapshot.window).toHaveProperty('innerHeight')
      expect(serialized).not.toContain('private customer note')
      expect(snapshot.activeElement).not.toHaveProperty('value')
    } finally {
      field.remove()
    }
  })

  it('bounds the event buffer and removes sensitive values before upload', async () => {
    const fetchImpl = vi.fn(() => Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ logFile: 'logs/keyboard-zoom-diagnostics.jsonl' })
    }))
    const telemetry = createKeyboardZoomTelemetry({ enabled: true, fetchImpl, maxEvents: 2 })

    telemetry.record('first', { source: 'test' }, { window: { innerWidth: 390 } })
    telemetry.record('second', { inputValue: 'secret text', customerId: 42, threshold: 100 })
    telemetry.record('third', { source: 'close-handler' })
    expect(telemetry.eventCount).toBe(2)

    const result = await telemetry.send()
    const [endpoint, request] = fetchImpl.mock.calls[0]
    const payload = JSON.parse(request.body)

    expect(endpoint).toBe('/__keyboard-zoom-logs')
    expect(result.eventCount).toBe(2)
    expect(result.logFile).toBe('logs/keyboard-zoom-diagnostics.jsonl')
    expect(payload.events.map(event => event.event)).toEqual(['second', 'third'])
    expect(payload.events[0].details).toEqual({ threshold: 100 })
    expect(request.body).not.toContain('secret text')
    expect(request.body).not.toContain('customerId')
  })

  it('does not upload when diagnostics are disabled', async () => {
    const fetchImpl = vi.fn()
    const telemetry = createKeyboardZoomTelemetry({ enabled: false, fetchImpl })

    telemetry.record('ignored')
    await expect(telemetry.send()).rejects.toThrow('disabled')
    expect(telemetry.eventCount).toBe(0)
    expect(fetchImpl).not.toHaveBeenCalled()
  })
})

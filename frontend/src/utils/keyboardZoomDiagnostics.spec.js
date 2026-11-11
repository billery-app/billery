import { EventEmitter } from 'node:events'
import { mkdtemp, readFile, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { describe, expect, it, vi } from 'vitest'
import { keyboardZoomDiagnosticsPlugin, sanitizeKeyboardZoomPayload } from '../../vite-plugins/keyboardZoomDiagnostics.js'

describe('Vite keyboard zoom diagnostics receiver', () => {
  it('keeps only approved diagnostic fields and excludes user-entered values', () => {
    const payload = sanitizeKeyboardZoomPayload({
      sessionId: 'session-1',
      browser: { userAgent: 'iPhone Safari', platform: 'iPhone', email: 'private@example.com' },
      invoice: { customer: 'Private customer' },
      events: [{
        sequence: 1,
        atMs: 42,
        event: 'viewport.resize',
        details: { source: 'Browser', threshold: 100, inputValue: 'private note', invoiceId: 17 },
        snapshot: {
          window: { innerWidth: 390, innerHeight: 844 },
          visualViewport: { width: 390, height: 600, scale: 1.14 },
          activeElement: {
            tag: 'textarea',
            type: null,
            className: 'document-notes-input',
            rect: { x: 16, y: 200, width: 340, height: 80 },
            fontSize: '16px',
            value: 'private note'
          },
          layoutRegions: {
            modalForm: {
              rect: { x: 0, y: 50, width: 390, height: 700 },
              clientWidth: 390,
              scrollHeight: 900
            }
          },
          viewportMeta: 'width=device-width, initial-scale=1.0'
        }
      }]
    })
    const serialized = JSON.stringify(payload)

    expect(payload.events[0].details).toEqual({ source: 'Browser', threshold: 100 })
    expect(payload.events[0].snapshot.activeElement.rect.width).toBe(340)
    expect(payload.events[0].snapshot.layoutRegions.modalForm.scrollHeight).toBe(900)
    expect(serialized).not.toContain('private')
    expect(serialized).not.toContain('email')
    expect(serialized).not.toContain('invoice')
  })

  it('accepts same-origin posts and appends the sanitized complete session to a logfile', async () => {
    const directory = await mkdtemp(join(tmpdir(), 'keyboard-zoom-diagnostics-'))
    const logFile = join(directory, 'keyboard-zoom-diagnostics.jsonl')
    const plugin = keyboardZoomDiagnosticsPlugin({ logFile })
    const use = vi.fn()
    const log = vi.spyOn(console, 'log').mockImplementation(() => {})
    try {
      plugin.configureServer({ middlewares: { use } })
      const [path, middleware] = use.mock.calls[0]
      const request = new EventEmitter()
      request.method = 'POST'
      request.headers = {
        origin: 'http://192.168.1.20:5173',
        host: '192.168.1.20:5173'
      }
      const response = {
        headers: {},
        setHeader(name, value) { this.headers[name] = value },
        end(body) { this.body = body; finish() }
      }
      let finish
      const responseFinished = new Promise(resolve => { finish = resolve })
      const payload = {
        sessionId: 'session-2',
        browser: { userAgent: 'iPhone Safari', platform: 'iPhone' },
        events: [{
          sequence: 1,
          atMs: 1,
          event: 'keyboard.close',
          details: { source: 'Browser', inputValue: 'private data' },
          snapshot: null
        }]
      }

      expect(path).toBe('/__keyboard-zoom-logs')
      middleware(request, response, vi.fn())
      request.emit('data', Buffer.from(JSON.stringify(payload)))
      request.emit('end')
      await responseFinished

      expect(response.statusCode).toBe(200)
      expect(JSON.parse(response.body)).toEqual({
        accepted: 1,
        logFile: expect.stringContaining('keyboard-zoom-diagnostics.jsonl')
      })
      const entries = (await readFile(logFile, 'utf8')).trim().split('\n').map(line => JSON.parse(line))
      expect(entries).toHaveLength(1)
      expect(entries[0].events[0].event).toBe('keyboard.close')
      expect(entries[0].events[0].details).toEqual({ source: 'Browser' })
      expect(JSON.stringify(entries)).not.toContain('private data')
      expect(log).toHaveBeenCalledTimes(1)
      expect(log.mock.calls[0][0]).toContain('Saved 1 events to')
    } finally {
      log.mockRestore()
      await rm(directory, { recursive: true, force: true })
    }
  })

  it('rejects posts from a different origin', () => {
    const plugin = keyboardZoomDiagnosticsPlugin()
    const use = vi.fn()
    plugin.configureServer({ middlewares: { use } })
    const middleware = use.mock.calls[0][1]
    const request = new EventEmitter()
    request.method = 'POST'
    request.headers = { origin: 'http://attacker.example', host: '192.168.1.20:5173' }
    const response = {
      headers: {},
      setHeader(name, value) { this.headers[name] = value },
      end(body) { this.body = body }
    }

    middleware(request, response, vi.fn())

    expect(response.statusCode).toBe(403)
    expect(JSON.parse(response.body).error).toMatch(/same-origin/i)
  })
})

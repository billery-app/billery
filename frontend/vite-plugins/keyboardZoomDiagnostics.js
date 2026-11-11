import { appendFile, mkdir } from 'node:fs/promises'
import { dirname, join, relative, resolve, sep } from 'node:path'

const MAX_BODY_BYTES = 128 * 1024
const MAX_EVENTS = 250
const UPLOAD_COOLDOWN_MS = 500

const DETAIL_KEYS = new Set([
  'source', 'reason', 'result', 'status', 'eventSource', 'hasViewportMeta',
  'targetTag', 'targetType', 'targetClass', 'targetScale', 'originalScale',
  'initialScale',
  'height', 'viewportHeight', 'keyboardHeight', 'keyboardOpenHeight',
  'baselineHeight', 'delta', 'threshold', 'timerMs', 'hadStoredMeta',
  'metaBefore', 'metaAfter', 'visible', 'supported', 'eventType', 'scale',
  'focusScale', 'currentScale', 'timerPending', 'keyboardOpen', 'focusRemainsInForm'
])

function finiteNumber(value) {
  return typeof value === 'number' && Number.isFinite(value) ? Math.round(value * 10) / 10 : null
}

function safeText(value, maxLength = 160) {
  if (typeof value !== 'string') return null
  return value.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, maxLength)
}

function safeRect(value) {
  if (!value || typeof value !== 'object') return null
  return {
    x: finiteNumber(value.x),
    y: finiteNumber(value.y),
    width: finiteNumber(value.width),
    height: finiteNumber(value.height)
  }
}

function safeViewportMeta(value) {
  if (typeof value !== 'string') return null
  return value.split(',')
    .map(part => part.trim())
    .filter(part => /^(?:width\s*=\s*device-width|height\s*=\s*device-height|initial-scale\s*=\s*[\d.]+|minimum-scale\s*=\s*[\d.]+|maximum-scale\s*=\s*[\d.]+|viewport-fit\s*=\s*(?:cover|contain|auto))$/i.test(part))
    .slice(0, 12)
    .join(', ')
}

function sanitizeDetails(details) {
  if (!details || typeof details !== 'object' || Array.isArray(details)) return {}
  const safe = {}
  for (const key of DETAIL_KEYS) {
    const value = details[key]
    if (typeof value === 'boolean' || (typeof value === 'number' && Number.isFinite(value))) {
      safe[key] = typeof value === 'number' ? finiteNumber(value) : value
    } else if (typeof value === 'string') {
      safe[key] = safeText(value, key.startsWith('meta') ? 240 : 120)
    }
  }
  return safe
}

function sanitizeSnapshot(snapshot) {
  if (!snapshot || typeof snapshot !== 'object') return null
  const visual = snapshot.visualViewport || {}
  const view = snapshot.window || {}
  const doc = snapshot.document || {}
  const screen = snapshot.screen || {}
  const active = snapshot.activeElement
  const keyboard = snapshot.virtualKeyboard || {}
  const layoutRegions = snapshot.layoutRegions || {}
  const safeElement = element => element ? {
    rect: safeRect(element.rect),
    clientWidth: finiteNumber(element.clientWidth),
    clientHeight: finiteNumber(element.clientHeight),
    scrollWidth: finiteNumber(element.scrollWidth),
    scrollHeight: finiteNumber(element.scrollHeight)
  } : null

  return {
    window: {
      innerWidth: finiteNumber(view.innerWidth),
      innerHeight: finiteNumber(view.innerHeight),
      devicePixelRatio: finiteNumber(view.devicePixelRatio),
      scrollX: finiteNumber(view.scrollX),
      scrollY: finiteNumber(view.scrollY)
    },
    visualViewport: snapshot.visualViewport ? {
      width: finiteNumber(visual.width),
      height: finiteNumber(visual.height),
      scale: finiteNumber(visual.scale),
      offsetLeft: finiteNumber(visual.offsetLeft),
      offsetTop: finiteNumber(visual.offsetTop),
      pageLeft: finiteNumber(visual.pageLeft),
      pageTop: finiteNumber(visual.pageTop)
    } : null,
    document: {
      clientWidth: finiteNumber(doc.clientWidth),
      clientHeight: finiteNumber(doc.clientHeight),
      scrollWidth: finiteNumber(doc.scrollWidth),
      scrollHeight: finiteNumber(doc.scrollHeight)
    },
    screen: {
      width: finiteNumber(screen.width),
      height: finiteNumber(screen.height),
      availWidth: finiteNumber(screen.availWidth),
      availHeight: finiteNumber(screen.availHeight),
      orientation: safeText(screen.orientation, 60)
    },
    activeElement: active ? {
      tag: safeText(active.tag, 24),
      type: safeText(active.type, 24),
      className: typeof active.className === 'string'
        ? active.className.replace(/[^a-zA-Z0-9_ -]/g, '').slice(0, 120)
        : null,
      isContentEditable: Boolean(active.isContentEditable),
      rect: safeRect(active.rect),
      fontSize: safeText(active.fontSize, 32),
      lineHeight: safeText(active.lineHeight, 32)
    } : null,
    layoutRegions: {
      documentModal: safeElement(layoutRegions.documentModal),
      modalForm: safeElement(layoutRegions.modalForm),
      itemsSection: safeElement(layoutRegions.itemsSection),
      formActions: safeElement(layoutRegions.formActions)
    },
    virtualKeyboard: {
      supported: Boolean(keyboard.supported),
      rect: safeRect(keyboard.rect)
    },
    viewportMeta: safeViewportMeta(snapshot.viewportMeta)
  }
}

/** Removes arbitrary fields before diagnostic payloads are printed by the dev server. */
export function sanitizeKeyboardZoomPayload(payload) {
  if (!payload || typeof payload !== 'object' || !Array.isArray(payload.events)) return null
  return {
    sessionId: safeText(payload.sessionId, 64),
    browser: {
      userAgent: safeText(payload.browser?.userAgent, 240),
      platform: safeText(payload.browser?.platform, 100)
    },
    events: payload.events.slice(-MAX_EVENTS).map(event => ({
      sequence: finiteNumber(event?.sequence),
      atMs: finiteNumber(event?.atMs),
      event: typeof event?.event === 'string' && /^[a-zA-Z0-9_.:-]{1,80}$/.test(event.event)
        ? event.event
        : 'invalid-event-name',
      details: sanitizeDetails(event?.details),
      snapshot: sanitizeSnapshot(event?.snapshot)
    }))
  }
}

function jsonResponse(response, status, body) {
  response.statusCode = status
  response.setHeader('Content-Type', 'application/json; charset=utf-8')
  response.setHeader('Cache-Control', 'no-store')
  response.end(JSON.stringify(body))
}

/** Vite dev-only receiver that sanitizes same-origin reports and appends them to an ignored local JSONL file. */
export function keyboardZoomDiagnosticsPlugin({ logFile = null } = {}) {
  let lastUploadAt = 0
  let projectRoot = process.cwd()
  let resolvedLogFile = logFile ? resolve(logFile) : null

  function relativeLogFile() {
    return relative(projectRoot, resolvedLogFile).split(sep).join('/')
  }

  return {
    name: 'keyboard-zoom-diagnostics',
    apply: 'serve',
    configResolved(config) {
      projectRoot = config.root
      if (!resolvedLogFile) {
        resolvedLogFile = join(projectRoot, 'logs', 'keyboard-zoom-diagnostics.jsonl')
      }
    },
    configureServer(server) {
      server.middlewares.use('/__keyboard-zoom-logs', (request, response, next) => {
        if (request.method !== 'POST') return jsonResponse(response, 405, { error: 'POST required' })
        if (!request.headers.origin || !request.headers.host) {
          return jsonResponse(response, 403, { error: 'Same-origin diagnostics only' })
        }
        try {
          if (new URL(request.headers.origin).host !== request.headers.host) {
            return jsonResponse(response, 403, { error: 'Same-origin diagnostics only' })
          }
        } catch {
          return jsonResponse(response, 403, { error: 'Invalid origin' })
        }

        const now = Date.now()
        if (now - lastUploadAt < UPLOAD_COOLDOWN_MS) {
          return jsonResponse(response, 429, { error: 'Please wait before sending diagnostics again' })
        }

        const chunks = []
        let bodyBytes = 0
        let oversized = false
        request.on('data', chunk => {
          bodyBytes += chunk.length
          if (bodyBytes > MAX_BODY_BYTES) {
            oversized = true
            chunks.length = 0
          } else if (!oversized) {
            chunks.push(chunk)
          }
        })
        request.on('end', async () => {
          if (oversized) return jsonResponse(response, 413, { error: 'Diagnostics payload too large' })
          let payload
          try {
            payload = sanitizeKeyboardZoomPayload(JSON.parse(Buffer.concat(chunks).toString('utf8')))
          } catch {
            return jsonResponse(response, 400, { error: 'Invalid diagnostics JSON' })
          }
          if (!payload) return jsonResponse(response, 400, { error: 'Invalid diagnostics payload' })

          const logEntry = {
            receivedAt: new Date().toISOString(),
            ...payload
          }
          try {
            await mkdir(dirname(resolvedLogFile), { recursive: true })
            await appendFile(resolvedLogFile, `${JSON.stringify(logEntry)}\n`, 'utf8')
          } catch {
            return jsonResponse(response, 500, { error: 'Could not write diagnostics log' })
          }

          lastUploadAt = Date.now()
          const logPath = relativeLogFile()
          console.log(`[Keyboard zoom diagnostics] Saved ${payload.events.length} events to ${resolvedLogFile}`)
          jsonResponse(response, 200, { accepted: payload.events.length, logFile: logPath })
        })
        request.on('error', () => jsonResponse(response, 400, { error: 'Could not read diagnostics payload' }))
      })
    }
  }
}

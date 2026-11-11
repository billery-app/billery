const MAX_EVENTS = 250
const MAX_PAYLOAD_CHARACTERS = 128 * 1024
const PRIVATE_KEY_PATTERN = /value|text|note|invoice|customer|owner|email|phone|password|token|url/i

function rounded(value) {
  return Number.isFinite(value) ? Math.round(value * 10) / 10 : null
}

function rectSnapshot(rect) {
  if (!rect) return null
  return {
    x: rounded(rect.x),
    y: rounded(rect.y),
    width: rounded(rect.width),
    height: rounded(rect.height)
  }
}

function sanitizeDiagnosticValue(value, key = '', depth = 0) {
  if (PRIVATE_KEY_PATTERN.test(key) || depth > 4) return undefined
  if (value === null || typeof value === 'boolean') return value
  if (typeof value === 'number') return Number.isFinite(value) ? rounded(value) : null
  if (typeof value === 'string') return value.replace(/[\u0000-\u001f\u007f]/g, ' ').slice(0, 240)
  if (Array.isArray(value)) {
    return value.slice(-30).map(item => sanitizeDiagnosticValue(item, '', depth + 1))
  }
  if (typeof value !== 'object') return undefined

  return Object.fromEntries(
    Object.entries(value)
      .filter(([childKey]) => !PRIVATE_KEY_PATTERN.test(childKey))
      .slice(0, 40)
      .map(([childKey, childValue]) => [childKey.slice(0, 60), sanitizeDiagnosticValue(childValue, childKey, depth + 1)])
      .filter(([, childValue]) => childValue !== undefined)
  )
}

function elementSnapshot(element) {
  if (!element) return null
  const rect = element.getBoundingClientRect()
  return {
    rect: rectSnapshot(rect),
    clientWidth: element.clientWidth || null,
    clientHeight: element.clientHeight || null,
    scrollWidth: element.scrollWidth || null,
    scrollHeight: element.scrollHeight || null
  }
}

/** Captures layout and browser dimensions without reading any form-field values. */
export function captureKeyboardZoomSnapshot(virtualKeyboardApi = null) {
  const viewport = window.visualViewport
  const activeElement = document.activeElement
  const activeRect = activeElement?.getBoundingClientRect?.()
  const activeStyle = activeElement ? window.getComputedStyle(activeElement) : null
  const keyboardRect = virtualKeyboardApi?.boundingRect
  const root = document.documentElement

  return {
    window: {
      innerWidth: window.innerWidth,
      innerHeight: window.innerHeight,
      devicePixelRatio: window.devicePixelRatio || 1,
      scrollX: rounded(window.scrollX),
      scrollY: rounded(window.scrollY)
    },
    visualViewport: viewport ? {
      width: rounded(viewport.width),
      height: rounded(viewport.height),
      scale: rounded(viewport.scale),
      offsetLeft: rounded(viewport.offsetLeft),
      offsetTop: rounded(viewport.offsetTop),
      pageLeft: rounded(viewport.pageLeft),
      pageTop: rounded(viewport.pageTop)
    } : null,
    document: {
      clientWidth: root?.clientWidth || null,
      clientHeight: root?.clientHeight || null,
      scrollWidth: root?.scrollWidth || null,
      scrollHeight: root?.scrollHeight || null
    },
    screen: {
      width: window.screen?.width || null,
      height: window.screen?.height || null,
      availWidth: window.screen?.availWidth || null,
      availHeight: window.screen?.availHeight || null,
      orientation: window.screen?.orientation?.type || null
    },
    activeElement: activeElement ? {
      tag: activeElement.tagName?.toLowerCase() || null,
      type: activeElement.getAttribute?.('type') || null,
      className: typeof activeElement.className === 'string' ? activeElement.className.slice(0, 120) : null,
      isContentEditable: Boolean(activeElement.isContentEditable),
      rect: rectSnapshot(activeRect),
      fontSize: activeStyle?.fontSize || null,
      lineHeight: activeStyle?.lineHeight || null
    } : null,
    layoutRegions: {
      documentModal: elementSnapshot(document.querySelector('.document-modal > .modal-content')),
      modalForm: elementSnapshot(document.querySelector('.document-modal .modal-form')),
      itemsSection: elementSnapshot(document.querySelector('.document-modal .items-section')),
      formActions: elementSnapshot(document.querySelector('.document-modal .form-actions'))
    },
    virtualKeyboard: {
      supported: Boolean(virtualKeyboardApi),
      rect: keyboardRect ? rectSnapshot(keyboardRect) : null
    },
    viewportMeta: document.querySelector('meta[name="viewport"]')?.content?.slice(0, 240) || null
  }
}

/** Buffers an opt-in, bounded diagnostic session and sends it only on user request. */
export function createKeyboardZoomTelemetry({
  enabled = false,
  endpoint = '/__keyboard-zoom-logs',
  fetchImpl = globalThis.fetch,
  maxEvents = MAX_EVENTS
} = {}) {
  const events = []
  const startedAt = globalThis.performance?.now?.() || 0
  const sessionId = globalThis.crypto?.randomUUID?.()
    || `keyboard-zoom-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 8)}`
  let sequence = 0

  function record(event, details = {}, snapshot = null) {
    if (!enabled) return events.length

    events.push({
      sequence: ++sequence,
      atMs: rounded((globalThis.performance?.now?.() || 0) - startedAt),
      event: String(event).replace(/[^a-zA-Z0-9_.:-]/g, '_').slice(0, 80),
      details: sanitizeDiagnosticValue(details),
      snapshot: sanitizeDiagnosticValue(snapshot)
    })
    if (events.length > maxEvents) events.splice(0, events.length - maxEvents)
    return events.length
  }

  function buildPayload() {
    return {
      sessionId,
      browser: {
        userAgent: String(globalThis.navigator?.userAgent || '').slice(0, 240),
        platform: String(globalThis.navigator?.platform || '').slice(0, 100)
      },
      events: events.slice()
    }
  }

  async function send() {
    if (!enabled) throw new Error('Keyboard zoom diagnostics are disabled')
    if (typeof fetchImpl !== 'function') throw new Error('Fetch is unavailable')

    const payload = buildPayload()
    while (payload.events.length > 1 && JSON.stringify(payload).length > MAX_PAYLOAD_CHARACTERS) {
      payload.events.shift()
    }
    const response = await fetchImpl(endpoint, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(payload)
    })
    if (!response?.ok) throw new Error(`Diagnostic upload failed (${response?.status || 'network error'})`)
    const receipt = typeof response.json === 'function' ? await response.json() : {}
    return {
      sessionId,
      eventCount: payload.events.length,
      logFile: receipt?.logFile || null
    }
  }

  return {
    record,
    send,
    get eventCount() {
      return events.length
    }
  }
}

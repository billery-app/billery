import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import {
  BROWSER_MARKER,
  SESSION_MARKER,
  TEMPLATE_TOUR_STEPS,
  TOUR_STEPS,
  firstVisible,
  maybeStartTour,
  recordTourSeen,
  runTemplateTour,
  runTour,
  shouldStartTour,
  startTour
} from './tour.js'
import { session } from '../services/session.js'

/** A storage like `sessionStorage`, in memory. */
function memoryStore(initial = {}) {
  const values = { ...initial }
  return {
    getItem: key => (key in values ? values[key] : null),
    setItem: (key, value) => {
      values[key] = String(value)
    },
    values
  }
}

const blockedStore = {
  getItem: () => {
    throw new Error('blocked')
  },
  setItem: () => {
    throw new Error('blocked')
  }
}

function api(state = { seen: false, everySession: false, hasDocuments: false }) {
  return { state: vi.fn().mockResolvedValue(state), save: vi.fn().mockResolvedValue(state) }
}

/** A stand-in for driver.js that records what the tour asks of it. */
function fakeDriver() {
  const fake = { config: null, index: undefined, active: false, calls: [] }
  fake.create = config => {
    fake.config = config
    fake.instance = {
      isActive: () => fake.active,
      getActiveIndex: () => fake.index,
      drive: index => {
        fake.active = true
        fake.index = index
        fake.calls.push(['drive', index])
      },
      moveTo: index => {
        fake.index = index
        fake.calls.push(['moveTo', index])
      },
      destroy: () => {
        fake.active = false
        fake.calls.push(['destroy'])
        config.onDestroyed()
      }
    }
    return fake.instance
  }
  return fake
}

/** A router stand-in with the current route and a recorded push. */
function fakeRouter(path = '/', query = {}) {
  const router = {
    currentRoute: { value: { path, query } },
    push: vi.fn(async target => {
      router.currentRoute.value = { path: target.path, query: target.query || {} }
    })
  }
  return router
}

const flush = () => new Promise(resolve => setTimeout(resolve, 0))

beforeEach(() => {
  session.mode = 'selfhost'
  session.demo = false
  session.status = 'ready'
})

afterEach(() => {
  session.mode = 'selfhost'
  session.demo = false
  document.body.innerHTML = ''
})

describe('the stations', () => {
  it('are five, in the agreed order: catalog, invoice, offer to invoice, equipment', () => {
    expect(TOUR_STEPS).toHaveLength(5)
    expect(TOUR_STEPS.map(step => step.route && step.route.path)).toEqual([
      null,
      '/articles-services',
      '/invoices',
      '/invoices',
      '/articles-services'
    ])
    expect(TOUR_STEPS[3].route.query).toEqual({ type: 'offer' })
    expect(TOUR_STEPS[4].route.query).toEqual({ tab: 'equipment' })
    TOUR_STEPS.forEach(step => {
      expect(step.popover.title).toBeTruthy()
      // driver.js writes these as HTML: constants only, no markup
      expect(step.popover.description).not.toMatch(/[<>]/)
    })
  })

  it('point to the shown control: the tabs on a wide screen, the select on a narrow one', () => {
    document.body.innerHTML = '<div data-tour="catalog-tabs"></div><select data-tour="catalog-picker"></select>'
    const [tabs, picker] = document.body.children
    tabs.getClientRects = () => []
    picker.getClientRects = () => [{}]
    expect(firstVisible('[data-tour="catalog-tabs"]', '[data-tour="catalog-picker"]')).toBe(picker)
    tabs.getClientRects = () => [{}]
    expect(firstVisible('[data-tour="catalog-tabs"]', '[data-tour="catalog-picker"]')).toBe(tabs)
    expect(firstVisible('[data-tour="missing"]')).toBeUndefined()
  })
})

describe('startTour', () => {
  it('opens each station on its page and ends after the last one', async () => {
    const fake = fakeDriver()
    const router = fakeRouter('/customers')
    const onEnd = vi.fn()

    startTour({ router, onEnd, createDriver: fake.create })
    await flush()
    expect(fake.calls).toEqual([['drive', 0]])
    expect(router.push).not.toHaveBeenCalled()
    expect(fake.config.steps.every(step => step.waitForElement > 0)).toBe(true)

    fake.config.onNextClick()
    await flush()
    expect(router.push).toHaveBeenLastCalledWith(TOUR_STEPS[1].route)
    expect(fake.calls.at(-1)).toEqual(['moveTo', 1])

    fake.config.onNextClick() // invoices
    await flush()
    fake.config.onNextClick() // offers: same page, other query
    await flush()
    expect(router.currentRoute.value).toEqual({ path: '/invoices', query: { type: 'offer' } })
    fake.config.onPrevClick()
    await flush()
    expect(router.currentRoute.value.query).toEqual({ type: 'invoice' })
    expect(fake.calls.at(-1)).toEqual(['moveTo', 2])

    fake.config.onNextClick()
    await flush()
    fake.config.onNextClick()
    await flush()
    expect(fake.index).toBe(4)
    expect(onEnd).not.toHaveBeenCalled()

    fake.config.onNextClick() // "Fertig"
    await flush()
    expect(fake.calls.at(-1)).toEqual(['destroy'])
    expect(onEnd).toHaveBeenCalledOnce()
  })

  it('does not navigate when the station is already shown', async () => {
    const fake = fakeDriver()
    const router = fakeRouter('/articles-services', { tab: 'articles' })
    startTour({ router, createDriver: fake.create })
    await flush()
    fake.config.onNextClick()
    await flush()
    expect(router.push).not.toHaveBeenCalled()
    fake.instance.destroy()
  })

  it('stays on the page when a navigation is refused, and still explains the station', async () => {
    const fake = fakeDriver()
    const router = fakeRouter('/')
    router.push = vi.fn().mockRejectedValue(new Error('refused'))
    startTour({ router, createDriver: fake.create })
    await flush()
    fake.config.onNextClick()
    await flush()
    expect(fake.calls.at(-1)).toEqual(['moveTo', 1])
    fake.instance.destroy()
  })

  it('offers a visible "Überspringen" in every station that ends the tour', async () => {
    const fake = fakeDriver()
    const onEnd = vi.fn()
    startTour({ router: fakeRouter(), onEnd, createDriver: fake.create })
    await flush()
    const footer = document.createElement('div')
    fake.config.onPopoverRender({ footer })
    const skip = footer.querySelector('button')
    expect(skip.textContent).toBe('Überspringen')
    skip.click()
    expect(onEnd).toHaveBeenCalledOnce()
  })

  it('does not open a second tour while one runs', async () => {
    const fake = fakeDriver()
    const first = startTour({ router: fakeRouter(), createDriver: fake.create })
    await flush()
    const other = fakeDriver()
    expect(startTour({ router: fakeRouter(), createDriver: other.create })).toBe(first)
    expect(other.config).toBeNull()
    first.destroy()
  })
})

describe('shouldStartTour', () => {
  it('self-host: once, on an installation without documents', async () => {
    const sessionStore = memoryStore()
    expect(await shouldStartTour({ sessionStore, localStore: memoryStore(), api: api() })).toBe(true)
    expect(
      await shouldStartTour({ sessionStore, localStore: memoryStore(), api: api({ seen: true, everySession: false, hasDocuments: false }) })
    ).toBe(false)
  })

  it('self-host: not in an installation that already works with documents (an update)', async () => {
    const state = { seen: false, everySession: false, hasDocuments: true }
    expect(await shouldStartTour({ sessionStore: memoryStore(), localStore: memoryStore(), api: api(state) })).toBe(false)
  })

  it('self-host: in every browser session when switched on, but once per session', async () => {
    const state = { seen: true, everySession: true, hasDocuments: true }
    expect(await shouldStartTour({ sessionStore: memoryStore(), localStore: memoryStore(), api: api(state) })).toBe(true)
    const started = memoryStore({ [SESSION_MARKER]: '1' })
    expect(await shouldStartTour({ sessionStore: started, localStore: memoryStore(), api: api(state) })).toBe(false)
  })

  it('self-host: no tour when the server does not answer', async () => {
    const failing = { state: vi.fn().mockRejectedValue(new Error('500')) }
    expect(await shouldStartTour({ sessionStore: memoryStore(), localStore: memoryStore(), api: failing })).toBe(false)
  })

  it('demo: in every new browser session, without asking the server', async () => {
    session.demo = true
    const server = api({ seen: true, everySession: false, hasDocuments: true })
    expect(await shouldStartTour({ sessionStore: memoryStore(), localStore: memoryStore(), api: server })).toBe(true)
    expect(server.state).not.toHaveBeenCalled()
    expect(
      await shouldStartTour({ sessionStore: memoryStore({ [SESSION_MARKER]: '1' }), localStore: memoryStore(), api: server })
    ).toBe(false)
  })

  it('SaaS: once per browser, without asking the server', async () => {
    session.mode = 'saas'
    const server = api()
    expect(await shouldStartTour({ sessionStore: memoryStore(), localStore: memoryStore(), api: server })).toBe(true)
    expect(
      await shouldStartTour({ sessionStore: memoryStore(), localStore: memoryStore({ [BROWSER_MARKER]: '1' }), api: server })
    ).toBe(false)
    expect(server.state).not.toHaveBeenCalled()
  })

  it('not before the app is ready (sign-in, a user without a company)', async () => {
    session.status = 'no-tenant'
    expect(await shouldStartTour({ sessionStore: memoryStore(), localStore: memoryStore(), api: api() })).toBe(false)
  })

  it('treats blocked storage as unset instead of failing', async () => {
    session.demo = true
    expect(await shouldStartTour({ sessionStore: blockedStore, localStore: blockedStore, api: api() })).toBe(true)
  })
})

describe('recordTourSeen', () => {
  it('self-host stores it on the server, SaaS in the browser, the demo nowhere', async () => {
    const server = api()
    await recordTourSeen({ localStore: memoryStore(), api: server })
    expect(server.save).toHaveBeenCalledWith({ seen: true })

    session.mode = 'saas'
    const local = memoryStore()
    const saasServer = api()
    await recordTourSeen({ localStore: local, api: saasServer })
    expect(local.values[BROWSER_MARKER]).toBe('1')
    expect(saasServer.save).not.toHaveBeenCalled()

    session.mode = 'selfhost'
    session.demo = true
    const demoServer = api()
    await recordTourSeen({ localStore: memoryStore(), api: demoServer })
    expect(demoServer.save).not.toHaveBeenCalled()
  })

  it('swallows a failed save: the tour only shows once more', async () => {
    const failing = { save: vi.fn().mockRejectedValue(new Error('500')) }
    await expect(recordTourSeen({ localStore: memoryStore(), api: failing })).resolves.toBeUndefined()
  })
})

describe('runTour and maybeStartTour', () => {
  it('marks the browser session and records the end', async () => {
    const fake = fakeDriver()
    const sessionStore = memoryStore()
    const server = api()
    runTour(fakeRouter(), { sessionStore, localStore: memoryStore(), api: server, createDriver: fake.create })
    await flush()
    expect(sessionStore.values[SESSION_MARKER]).toBe('1')
    fake.instance.destroy()
    await flush()
    expect(server.save).toHaveBeenCalledWith({ seen: true })
  })

  it('starts only when the rules say so', async () => {
    const fake = fakeDriver()
    const deps = { sessionStore: memoryStore(), localStore: memoryStore(), createDriver: fake.create }
    expect(await maybeStartTour(fakeRouter(), { ...deps, api: api({ seen: true, everySession: false, hasDocuments: false }) })).toBe(false)
    expect(fake.config).toBeNull()

    expect(await maybeStartTour(fakeRouter(), { ...deps, api: api() })).toBe(true)
    await flush()
    expect(fake.calls).toEqual([['drive', 0]])
    fake.instance.destroy()
  })
})

describe('the templates tour', () => {
  it('has five stations on the templates page, the last three in the editor', () => {
    expect(TEMPLATE_TOUR_STEPS).toHaveLength(5)
    TEMPLATE_TOUR_STEPS.forEach(step => {
      expect(step.route).toEqual({ path: '/templates' })
      expect(step.popover.description).not.toMatch(/[<>]/)
    })
  })

  it('opens the first template in the editor and closes it again when going back', async () => {
    document.body.innerHTML = '<button data-tour="template-edit"></button>'
    const edit = document.querySelector('[data-tour="template-edit"]')
    const opened = vi.fn(() => {
      document.body.insertAdjacentHTML(
        'beforeend',
        '<div class="template-editor"><button data-tour="editor-cancel"></button></div>'
      )
      document.querySelector('[data-tour="editor-cancel"]').addEventListener('click', () => {
        document.querySelector('.template-editor').remove()
      })
    })
    edit.addEventListener('click', opened)

    const fake = fakeDriver()
    runTemplateTour(fakeRouter('/settings/help'), { createDriver: fake.create })
    await flush()
    expect(document.querySelector('.template-editor')).toBeNull()

    fake.config.onNextClick()
    await flush()
    fake.config.onNextClick() // third station: the editor
    await flush()
    expect(opened).toHaveBeenCalledOnce()
    expect(document.querySelector('.template-editor')).not.toBeNull()

    fake.config.onNextClick() // still the editor: not opened twice
    await flush()
    expect(opened).toHaveBeenCalledOnce()

    fake.config.onPrevClick()
    await flush()
    fake.config.onPrevClick() // back to the list: the editor closes
    await flush()
    expect(document.querySelector('.template-editor')).toBeNull()
    fake.instance.destroy()
  })
})

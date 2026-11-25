import { driver } from 'driver.js'
import 'driver.js/dist/driver.css'
import '../styles/tour.css'
import tourService from '../services/tourService.js'
import { isSaas, session } from '../services/session.js'

/**
 * The introductory tour (#14): five short stations, from the catalog to an invoice, from an offer
 * to the invoice and its PDF preview, to the equipment and its amortisation. Every station can be
 * skipped; the tour can be started again under Einstellungen → Hilfe (and in the demo banner).
 *
 * When it starts by itself:
 * - **demo** (`BILLERY_DEMO=true`): in every new browser session (`sessionStorage`), so every
 *   visitor gets their own tour, whatever the others do in the shared database;
 * - **self-host / desktop**: once per installation (`/api/tour`, `user_settings`), and only while
 *   the installation holds no documents, so an update does not start it in a business that has
 *   long been working with the app; with "Tour bei jedem Start zeigen" in every browser session;
 * - **SaaS**: once per browser (`localStorage`); the server keeps no tour state there.
 *
 * The texts are constants: driver.js writes title and description as HTML, so nothing from the
 * database or the address may ever go into them.
 */

/** Set in `sessionStorage` when the tour has started in this browser session. */
export const SESSION_MARKER = 'billery.tour.session'
/** Set in `localStorage` (SaaS) when the tour was finished or skipped in this browser. */
export const BROWSER_MARKER = 'billery.tour.seen'
/** How long a station waits for its element (the page may still be loading), in ms. */
const WAIT_FOR_ELEMENT_MS = 3000

/**
 * The first of the given elements that is shown: the desktop layout and the narrow layout have
 * different controls (tabs or a select, the top or the bottom navigation).
 * @param {...string} selectors
 * @returns {Element|undefined} undefined while none is shown (driver.js then waits, and finally
 *   shows the station in the middle of the screen)
 */
export function firstVisible(...selectors) {
  for (const selector of selectors) {
    const element = document.querySelector(selector)
    if (element && element.getClientRects().length > 0) {
      return element
    }
  }
  return undefined
}

/** The stations: where the app goes first (`route`), what is highlighted, what is said. */
export const TOUR_STEPS = [
  {
    route: null,
    element: () => firstVisible('[data-tour="nav"]', '[data-tour="nav-mobile"]'),
    popover: {
      title: 'Willkommen bei Billery',
      description:
        'In fünf kurzen Stationen sehen Sie, wie aus Stammdaten Angebote, Rechnungen und PDFs werden. ' +
        'Über diese Navigation erreichen Sie Kunden, Dokumente, Katalog und Einstellungen.'
    }
  },
  {
    route: { path: '/articles-services', query: { tab: 'articles' } },
    element: () => firstVisible('[data-tour="catalog-tabs"]', '[data-tour="catalog-picker"]'),
    popover: {
      title: 'Stammdaten und Katalog',
      description:
        'Legen Sie hier Ihre Artikel und Leistungen mit Preis und Steuersatz an, Ihre Kunden unter ' +
        '„Kunden“. Beim Schreiben einer Rechnung wählen Sie beides nur noch aus.'
    }
  },
  {
    route: { path: '/invoices', query: { type: 'invoice' } },
    element: '[data-tour="document-new"]',
    popover: {
      title: 'Eine Rechnung zusammenklicken',
      description:
        'Kunde wählen, Positionen aus dem Katalog hinzufügen, speichern: Die Rechnung ist ein Entwurf. ' +
        'Erst beim Ausstellen bekommt sie ihre fortlaufende Nummer und ist danach unveränderbar (GoBD).'
    }
  },
  {
    route: { path: '/invoices', query: { type: 'offer' } },
    element: () => firstVisible('[data-tour="doc-tab-offer"]', '[data-tour="doc-type-picker"]'),
    popover: {
      title: 'Vom Angebot zur Rechnung',
      description:
        'Ein angenommenes Angebot öffnen und mit „In Rechnung“ übernehmen, ohne etwas neu ' +
        'einzugeben. „PDF“ zeigt jedes Dokument so, wie Ihr Kunde es erhält.'
    }
  },
  {
    route: { path: '/articles-services', query: { tab: 'equipment' } },
    element: () => firstVisible('[data-tour="catalog-equipment"]', '[data-tour="catalog-picker"]'),
    popover: {
      title: 'Ausstattung und Amortisation',
      description:
        'Erfassen Sie Geräte und Werkzeuge mit Kaufpreis und verknüpfen Sie sie mit Leistungen: ' +
        'Billery zeigt, wie weit bezahlte Rechnungen sie schon eingespielt haben. Die Tour können Sie jederzeit unter ' +
        'Einstellungen → Hilfe erneut starten.'
    }
  }
]

/** The running tour, so a second start (banner and Hilfe at once) does not open two. */
let active = null

/**
 * Whether the router already shows this station's page (path and the given query values).
 * @param {import('vue-router').Router} router
 * @param {{path: string, query?: object}} target
 */
function isCurrent(router, target) {
  const current = router.currentRoute.value
  if (current.path !== target.path) return false
  return Object.entries(target.query || {}).every(([key, value]) => current.query[key] === value)
}

/**
 * Starts the tour now (by hand or because {@link maybeStartTour} decided so).
 * @param {object} options
 * @param {import('vue-router').Router} options.router - opens each station's page
 * @param {Function} [options.onEnd] - called once the tour is finished or skipped
 * @param {Function} [options.createDriver] - driver.js's factory (tests replace it)
 * @returns {object} the driver.js instance
 */
export function startTour({ router, onEnd = () => {}, createDriver = driver }) {
  if (active && active.isActive()) {
    return active
  }
  const steps = TOUR_STEPS
  const tour = createDriver({
    steps: steps.map(step => ({ element: step.element, popover: step.popover, waitForElement: WAIT_FOR_ELEMENT_MS })),
    popoverClass: 'billery-tour',
    showProgress: true,
    progressText: '{{current}} von {{total}}',
    nextBtnText: 'Weiter',
    prevBtnText: 'Zurück',
    doneBtnText: 'Fertig',
    closeBtnLabel: 'Tour schließen',
    allowClose: true,
    stageRadius: 8,
    onPopoverRender: popover => addSkipButton(popover, () => tour.destroy()),
    onNextClick: () => go(tour.getActiveIndex() + 1),
    onPrevClick: () => go(tour.getActiveIndex() - 1),
    onDestroyed: () => {
      if (active === tour) active = null
      onEnd()
    }
  })

  /** Opens the station's page if it is another one, then shows the station. */
  async function go(index) {
    if (index >= steps.length) {
      tour.destroy()
      return
    }
    if (index < 0) return
    const target = steps[index].route
    if (target && !isCurrent(router, target)) {
      try {
        await router.push(target)
      } catch {
        // a refused navigation leaves the app where it is; the station is still explained
      }
    }
    if (tour.isActive()) {
      tour.moveTo(index)
    } else {
      tour.drive(index)
    }
  }

  active = tour
  go(0)
  return tour
}

/**
 * "Tour überspringen" as a visible text button in every station (the × alone is easy to miss).
 * @param {{footer: HTMLElement}} popover - driver.js's popover elements
 * @param {Function} skip
 */
function addSkipButton(popover, skip) {
  const button = document.createElement('button')
  button.type = 'button'
  button.className = 'driver-popover-footer-btn billery-tour-skip'
  button.textContent = 'Überspringen'
  button.addEventListener('click', skip)
  popover.footer.prepend(button)
}

/**
 * The browser's storage, or null where reading it throws (storage blocked); the markers then
 * read as unset.
 * @param {'sessionStorage'|'localStorage'} name
 */
function browserStorage(name) {
  try {
    return window[name]
  } catch {
    return null
  }
}

/** Reads a marker; storage can be switched off or full, then the tour behaves as if unset. */
function readMarker(storage, key) {
  try {
    return storage.getItem(key) !== null
  } catch {
    return false
  }
}

function writeMarker(storage, key) {
  try {
    storage.setItem(key, '1')
  } catch {
    // without storage the tour may show again; it never blocks the app
  }
}

/**
 * Whether the tour starts by itself now (see the rules at the top of this file).
 * @param {object} deps
 * @param {Storage} deps.sessionStore
 * @param {Storage} deps.localStore
 * @param {{state: Function}} deps.api
 * @returns {Promise<boolean>}
 */
export async function shouldStartTour({ sessionStore, localStore, api }) {
  if (session.status !== 'ready') return false
  if (readMarker(sessionStore, SESSION_MARKER)) return false
  if (session.demo) return true
  if (isSaas()) return !readMarker(localStore, BROWSER_MARKER)
  try {
    const state = await api.state()
    return state.everySession || (!state.seen && !state.hasDocuments)
  } catch {
    return false // no answer: rather no tour than one in the way
  }
}

/**
 * Remembers that the tour was finished or skipped: per browser in SaaS, per installation in
 * self-host and desktop. The demo remembers nothing beyond the browser session.
 */
export async function recordTourSeen({ localStore, api }) {
  if (session.demo) return
  if (isSaas()) {
    writeMarker(localStore, BROWSER_MARKER)
    return
  }
  try {
    await api.save({ seen: true })
  } catch {
    // shown once more next time; nothing the user has to know about
  }
}

/**
 * Starts the tour (by hand: Einstellungen → Hilfe, the demo banner) and records the end.
 * @param {import('vue-router').Router} router
 * @param {object} [deps] - storages, API and driver factory (tests replace them)
 */
export function runTour(router, deps = {}) {
  const { sessionStore = browserStorage('sessionStorage'), localStore = browserStorage('localStorage'), api = tourService, createDriver } = deps
  writeMarker(sessionStore, SESSION_MARKER)
  return startTour({
    router,
    createDriver,
    onEnd: () => recordTourSeen({ localStore, api })
  })
}

/**
 * Called once when the app has started: starts the tour if {@link shouldStartTour} says so.
 * @param {import('vue-router').Router} router
 * @param {object} [deps]
 * @returns {Promise<boolean>} whether it started
 */
export async function maybeStartTour(router, deps = {}) {
  const { sessionStore = browserStorage('sessionStorage'), localStore = browserStorage('localStorage'), api = tourService } = deps
  if (!(await shouldStartTour({ sessionStore, localStore, api }))) return false
  runTour(router, { ...deps, sessionStore, localStore, api })
  return true
}

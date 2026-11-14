/**
 * The per-launch secret of the desktop app's window. The Tauri shell opens the window at
 * `/#desktop-token=<hex>` (a URL fragment is never sent to a server); the token is moved into
 * `sessionStorage` of this window and removed from the address at once. Only the desktop-only
 * endpoints (`/api/desktop/backups`) require it, as `X-Desktop-Token`: other programs on the
 * computer reach 127.0.0.1 too, but they do not have it.
 */
const KEY = 'billery.desktopToken'
const FRAGMENT = /^#desktop-token=([0-9a-f]{16,128})$/

/**
 * Takes the token out of the address, if it is there. Called once before the app starts.
 * @param {Location} [location]
 * @param {History} [history]
 */
export function captureDesktopToken(location = window.location, history = window.history) {
  const match = FRAGMENT.exec(location.hash || '')
  if (!match) return
  try {
    window.sessionStorage.setItem(KEY, match[1])
  } catch {
    // without storage the backup section answers 403 and says so
  }
  history.replaceState(history.state, '', `${location.pathname}${location.search}`)
}

/** @returns {string} the token of this window, '' outside the desktop app */
export function desktopToken() {
  try {
    return window.sessionStorage.getItem(KEY) || ''
  } catch {
    return ''
  }
}

/** @returns {{headers: Object}} request options carrying the token */
export function desktopTokenOptions() {
  return { headers: { 'X-Desktop-Token': desktopToken() } }
}

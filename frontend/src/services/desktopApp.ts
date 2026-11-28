/**
 * The few native functions the desktop app (Tauri) offers its pages (#22): pick a folder, show a
 * file in the file manager. The shell allows exactly these two calls to the pages of the bundled
 * backend (`app-desktop/capabilities/app-pages.json`, plugins dialog and opener, injected as
 * `window.__TAURI__` because of `app.withGlobalTauri`). In a browser (self-host, SaaS) there is no
 * `window.__TAURI__`; `isDesktopApp()` is false and the pages keep their browser behaviour.
 */

interface TauriGlobals {
  dialog?: { open(options: { directory?: boolean, multiple?: boolean, defaultPath?: string, title?: string }): Promise<string | string[] | null> }
  opener?: { revealItemInDir(path: string): Promise<void> }
}

function tauri(): TauriGlobals | undefined {
  return (window as unknown as { __TAURI__?: TauriGlobals }).__TAURI__
}

/** Whether the page runs in the desktop app with its native functions. */
export function isDesktopApp(): boolean {
  const api = tauri()
  return !!(api?.dialog && api?.opener)
}

/**
 * Lets the user choose a folder with the system's folder dialog.
 * @param defaultPath - the folder the dialog starts in
 * @param title - the dialog's title
 * @returns the absolute path, or null when cancelled or outside the desktop app
 */
export async function pickFolder(defaultPath?: string, title?: string): Promise<string | null> {
  const dialog = tauri()?.dialog
  if (!dialog) return null
  const chosen = await dialog.open({ directory: true, multiple: false, defaultPath: defaultPath || undefined, title })
  return typeof chosen === 'string' ? chosen : null
}

/**
 * Opens the folder of a file in the file manager, with the file selected.
 * @param path - the absolute path of the file (or folder)
 */
export async function showInFolder(path: string): Promise<void> {
  await tauri()?.opener?.revealItemInDir(path)
}

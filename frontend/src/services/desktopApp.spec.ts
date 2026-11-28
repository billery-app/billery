import { afterEach, describe, expect, it, vi } from 'vitest'
import { isDesktopApp, pickFolder, showInFolder } from './desktopApp.ts'

type TauriWindow = Window & { __TAURI__?: unknown }

describe('desktopApp', () => {
  afterEach(() => { delete (window as TauriWindow).__TAURI__ })

  it('is a browser without the shell: no native functions, nothing happens', async () => {
    expect(isDesktopApp()).toBe(false)
    expect(await pickFolder()).toBeNull()
    await expect(showInFolder('/tmp/x.zip')).resolves.toBeUndefined()
  })

  it('uses the folder dialog and the file manager of the desktop app', async () => {
    const open = vi.fn().mockResolvedValue('C:\\Belege')
    const revealItemInDir = vi.fn().mockResolvedValue(undefined)
    ;(window as TauriWindow).__TAURI__ = { dialog: { open }, opener: { revealItemInDir } }

    expect(isDesktopApp()).toBe(true)
    expect(await pickFolder('D:\\alt', 'Ordner')).toBe('C:\\Belege')
    expect(open).toHaveBeenCalledWith({ directory: true, multiple: false, defaultPath: 'D:\\alt', title: 'Ordner' })
    await showInFolder('C:\\Belege\\GoBD.zip')
    expect(revealItemInDir).toHaveBeenCalledWith('C:\\Belege\\GoBD.zip')
  })

  it('treats a cancelled dialog as no folder', async () => {
    ;(window as TauriWindow).__TAURI__ = { dialog: { open: vi.fn().mockResolvedValue(null) }, opener: { revealItemInDir: vi.fn() } }
    expect(await pickFolder()).toBeNull()
  })
})

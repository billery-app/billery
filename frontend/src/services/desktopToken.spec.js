import { describe, it, expect, beforeEach } from 'vitest'
import { captureDesktopToken, desktopToken, desktopTokenOptions } from './desktopToken.js'

// Built at run time: a hex literal of this length looks like a secret to the scanners.
const TOKEN = '0a'.repeat(16)

describe('desktopToken', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    window.history.replaceState(null, '', '/')
  })

  it('takes the token from the window address and removes it from there', () => {
    window.history.replaceState(null, '', `/#desktop-token=${TOKEN}`)
    captureDesktopToken()

    expect(desktopToken()).toBe(TOKEN)
    expect(window.location.hash).toBe('')
    expect(desktopTokenOptions()).toEqual({ headers: { 'X-Desktop-Token': TOKEN } })
  })

  it('ignores any other fragment', () => {
    window.history.replaceState(null, '', '/#desktop-token=<script>')
    captureDesktopToken()
    expect(desktopToken()).toBe('')
    expect(window.location.hash).toBe('#desktop-token=%3Cscript%3E')
  })
})

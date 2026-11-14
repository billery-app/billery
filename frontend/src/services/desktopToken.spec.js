import { describe, it, expect, beforeEach } from 'vitest'
import { captureDesktopToken, desktopToken, desktopTokenOptions } from './desktopToken.js'

describe('desktopToken', () => {
  beforeEach(() => {
    window.sessionStorage.clear()
    window.history.replaceState(null, '', '/')
  })

  it('takes the token from the window address and removes it from there', () => {
    window.history.replaceState(null, '', '/#desktop-token=0123456789abcdef0123456789abcdef')
    captureDesktopToken()

    expect(desktopToken()).toBe('0123456789abcdef0123456789abcdef')
    expect(window.location.hash).toBe('')
    expect(desktopTokenOptions()).toEqual({ headers: { 'X-Desktop-Token': '0123456789abcdef0123456789abcdef' } })
  })

  it('ignores any other fragment', () => {
    window.history.replaceState(null, '', '/#desktop-token=<script>')
    captureDesktopToken()
    expect(desktopToken()).toBe('')
    expect(window.location.hash).toBe('#desktop-token=%3Cscript%3E')
  })
})

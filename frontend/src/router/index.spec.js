import { describe, it, expect, afterEach } from 'vitest'
import { createAppRouter } from './index.js'

describe('createAppRouter', () => {
  afterEach(() => window.history.replaceState(null, '', '/'))

  it('starts at the address of the moment it is created, not of the import (sign-in callback)', async () => {
    window.history.replaceState(null, '', '/auth/callback?code=abc&state=def')
    // initAuth completes the sign-in and replaces the callback URL before the router is created
    window.history.replaceState(null, '', '/customers')
    const router = createAppRouter()
    await router.push(router.options.history.location)
    expect(router.currentRoute.value.path).toBe('/customers')
  })

  it('sends an unknown address to the dashboard instead of an empty page', async () => {
    const router = createAppRouter()
    await router.push('/auth/callback?code=abc')
    expect(router.currentRoute.value.path).toBe('/')
  })
})

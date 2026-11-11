import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AccountSettings from './AccountSettings.vue'
import { session } from '../services/session.js'
import { setActiveTenant } from '../services/authService.js'

vi.mock('../services/authService.js', () => ({ setActiveTenant: vi.fn() }))

beforeEach(() => {
  vi.clearAllMocks()
  session.user = { id: 'u', email: 'anna@example.com' }
  session.activeTenantId = 1
})

describe('AccountSettings', () => {
  it('shows who is signed in, the active company and the role', () => {
    session.memberships = [{ tenantId: 1, tenantName: 'Eins GmbH', role: 'ADMIN' }]

    const wrapper = mount(AccountSettings)

    expect(wrapper.get('[data-testid="account-email"]').text()).toBe('anna@example.com')
    expect(wrapper.get('[data-testid="account-company"]').text()).toBe('Eins GmbH')
    expect(wrapper.text()).toContain('Administrator')
    expect(wrapper.find('.other-companies').exists()).toBe(false)
  })

  it('lists the other companies and switches to one', async () => {
    session.memberships = [
      { tenantId: 1, tenantName: 'Eins GmbH', role: 'OWNER' },
      { tenantId: 2, tenantName: 'Zwei AG', role: 'READER' }
    ]

    const wrapper = mount(AccountSettings)
    const other = wrapper.get('.other-companies')
    expect(other.text()).toContain('Zwei AG')
    expect(other.text()).toContain('Nur lesen')
    expect(other.text()).not.toContain('Eins GmbH')
    await other.get('button').trigger('click')

    expect(setActiveTenant).toHaveBeenCalledWith(2)
  })
})

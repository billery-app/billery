import { mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import AccountMenu from './AccountMenu.vue'
import { session } from '../services/session.js'
import { openTenant, setActiveTenant, signOut } from '../services/authService.js'
import teamService from '../services/teamService.js'

vi.mock('../services/authService.js', () => ({
  openTenant: vi.fn(),
  setActiveTenant: vi.fn(),
  signOut: vi.fn()
}))
vi.mock('../services/teamService.js', () => ({
  default: { acceptInvitation: vi.fn() }
}))

const ONE = [{ tenantId: 1, tenantName: 'Eins GmbH', role: 'OWNER' }]
const TWO = [...ONE, { tenantId: 2, tenantName: 'Zwei AG', role: 'READER' }]

beforeEach(() => {
  vi.clearAllMocks()
  session.user = { id: 'u', email: 'anna@example.com' }
  session.activeTenantId = 1
  session.invitations = []
})

describe('AccountMenu', () => {
  it('shows neither the company nor the e-mail (they are in Settings) and no switcher for one company', () => {
    session.memberships = ONE

    const wrapper = mount(AccountMenu)

    expect(wrapper.find('select').exists()).toBe(false)
    expect(wrapper.text()).not.toContain('Eins GmbH')
    expect(wrapper.text()).not.toContain('anna@example.com')
    expect(wrapper.find('button.sign-out').exists()).toBe(true)
  })

  it('offers every tenant and switches on selection', async () => {
    session.memberships = TWO

    const wrapper = mount(AccountMenu)
    const options = wrapper.findAll('option')
    await wrapper.find('select').setValue('2')

    expect(options.map(o => o.text())).toEqual(['Eins GmbH', 'Zwei AG'])
    expect(setActiveTenant).toHaveBeenCalledWith(2)
  })

  it('signs out', async () => {
    session.memberships = ONE

    await mount(AccountMenu).find('button.sign-out').trigger('click')

    expect(signOut).toHaveBeenCalled()
  })

  it('shows no invitations entry without open invitations', () => {
    session.memberships = ONE

    expect(mount(AccountMenu).find('[data-testid="account-invitations"]').exists()).toBe(false)
  })

  it('lets a user who already has a company accept an invitation and opens the joined one', async () => {
    session.memberships = ONE
    session.invitations = [{ id: 'inv-1', tenantId: 7, tenantName: 'Sieben KG', role: 'ACCOUNTANT' }]
    teamService.acceptInvitation.mockResolvedValue({ tenantId: 7 })

    const wrapper = mount(AccountMenu)
    const entry = wrapper.get('[data-testid="account-invitations"]')
    expect(entry.text()).toContain('Sieben KG')
    expect(entry.text()).toContain('Buchhaltung / Steuerberatung')
    await entry.get('button').trigger('click')
    await Promise.resolve()
    await Promise.resolve()

    expect(teamService.acceptInvitation).toHaveBeenCalledWith('inv-1')
    expect(openTenant).toHaveBeenCalledWith(7)
  })

  it('shows why accepting failed', async () => {
    session.memberships = ONE
    session.invitations = [{ id: 'inv-1', tenantId: 7, tenantName: 'Sieben KG', role: 'READER' }]
    teamService.acceptInvitation.mockRejectedValue(new Error('Invitation not found'))

    const wrapper = mount(AccountMenu)
    await wrapper.get('[data-testid="account-invitations"] button').trigger('click')
    await new Promise(resolve => setTimeout(resolve))

    expect(wrapper.get('[role="alert"]').text()).toBe('Invitation not found')
    expect(openTenant).not.toHaveBeenCalled()
  })
})

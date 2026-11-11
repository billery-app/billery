import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import NoTenantNotice from './NoTenantNotice.vue'
import { session } from '../services/session.js'
import { openTenant, signOut } from '../services/authService.js'
import teamService from '../services/teamService.js'

vi.mock('../services/authService.js', () => ({ openTenant: vi.fn(), signOut: vi.fn() }))
vi.mock('../services/teamService.js', () => ({
  default: { createTenant: vi.fn(), acceptInvitation: vi.fn() }
}))

beforeEach(() => {
  vi.clearAllMocks()
  session.user = { id: 'u', email: 'anna@example.com' }
  session.invitations = []
})

describe('NoTenantNotice', () => {
  it('lists the invitations and joins the tenant of an accepted one', async () => {
    session.invitations = [{ id: 'inv-1', tenantId: 9, tenantName: 'Olga AG', role: 'READER' }]
    teamService.acceptInvitation.mockResolvedValue({ tenantId: 9 })

    const wrapper = mount(NoTenantNotice)
    expect(wrapper.get('[data-testid="invitations"]').text()).toContain('Olga AG')
    await wrapper.get('[data-testid="invitations"] button').trigger('click')
    await flushPromises()

    expect(teamService.acceptInvitation).toHaveBeenCalledWith('inv-1')
    expect(openTenant).toHaveBeenCalledWith(9)
  })

  it('creates a company and opens it', async () => {
    teamService.createTenant.mockResolvedValue({ tenantId: 12 })

    const wrapper = mount(NoTenantNotice)
    await wrapper.get('[data-testid="create-tenant"] input').setValue('  Anna GmbH ')
    await wrapper.get('[data-testid="create-tenant"]').trigger('submit')
    await flushPromises()

    expect(teamService.createTenant).toHaveBeenCalledWith('Anna GmbH')
    expect(openTenant).toHaveBeenCalledWith(12)
  })

  it('does not offer to create a company without a name and shows the backend message', async () => {
    teamService.createTenant.mockRejectedValue(new Error('Der Name ist ungültig'))

    const wrapper = mount(NoTenantNotice)
    const submit = wrapper.get('[data-testid="create-tenant"] button[type="submit"]')
    expect(submit.attributes('disabled')).toBeDefined()

    await wrapper.get('[data-testid="create-tenant"] input').setValue('X')
    await wrapper.get('[data-testid="create-tenant"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('Der Name ist ungültig')
    expect(openTenant).not.toHaveBeenCalled()
  })

  it('signs out', async () => {
    await mount(NoTenantNotice).get('button.link').trigger('click')

    expect(signOut).toHaveBeenCalled()
  })
})

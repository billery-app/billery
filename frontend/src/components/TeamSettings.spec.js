import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import TeamSettings from './TeamSettings.vue'
import { session } from '../services/session.js'
import teamService from '../services/teamService.js'

vi.mock('../services/teamService.js', () => ({
  default: {
    members: vi.fn(),
    invitations: vi.fn(),
    invite: vi.fn(),
    changeRole: vi.fn(),
    removeMember: vi.fn(),
    revokeInvitation: vi.fn(),
    auditLog: vi.fn(),
    closeTenant: vi.fn()
  }
}))

const MEMBERS = [
  { userId: 'me', email: 'owner@example.com', displayName: 'Olga', role: 'OWNER' },
  { userId: 'u2', email: 'reader@example.com', displayName: null, role: 'READER' }
]

function signedInAs(role) {
  session.mode = 'saas'
  session.user = { id: 'me', email: 'owner@example.com' }
  session.memberships = [{ tenantId: 1, tenantName: 'Eins', role }]
  session.activeTenantId = 1
}

async function mounted() {
  const wrapper = mount(TeamSettings)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  teamService.members.mockResolvedValue(MEMBERS)
  teamService.auditLog.mockResolvedValue([])
  teamService.invitations.mockResolvedValue([
    { id: 'inv', email: 'new@example.com', role: 'READER', expiresAt: '2026-12-01T00:00:00Z' }
  ])
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

describe('TeamSettings as an owner', () => {
  beforeEach(() => signedInAs('OWNER'))

  it('lists members and open invitations', async () => {
    const wrapper = await mounted()

    expect(wrapper.text()).toContain('reader@example.com')
    expect(wrapper.text()).toContain('new@example.com')
  })

  it('offers every role and invites with the chosen one', async () => {
    teamService.invite.mockResolvedValue({})
    const wrapper = await mounted()

    const options = wrapper.get('select[aria-label="Rolle der Einladung"]').findAll('option')
    expect(options.map(o => o.element.value)).toEqual(['OWNER', 'ADMIN', 'ACCOUNTANT', 'READER'])

    await wrapper.get('[data-testid="invite-form"] input').setValue('  bob@example.com ')
    await wrapper.get('select[aria-label="Rolle der Einladung"]').setValue('ACCOUNTANT')
    await wrapper.get('[data-testid="invite-form"]').trigger('submit')
    await flushPromises()

    expect(teamService.invite).toHaveBeenCalledWith('bob@example.com', 'ACCOUNTANT')
  })

  it('confirms that the invitation mail went out', async () => {
    teamService.invite.mockResolvedValue({ mailSent: true, mailProblem: null })
    const wrapper = await mounted()

    await wrapper.get('[data-testid="invite-form"] input').setValue('bob@example.com')
    await wrapper.get('[data-testid="invite-form"]').trigger('submit')
    await flushPromises()

    expect(wrapper.get('[role="status"]').text()).toBe('Einladung an bob@example.com verschickt.')
    expect(wrapper.get('[data-testid="invite-form"] input').element.value).toBe('')
  })

  it('says plainly when no mail went out and how to tell the person instead', async () => {
    teamService.invite.mockResolvedValue({
      mailSent: false,
      mailProblem: 'Der Mailversand ist nicht eingerichtet.'
    })
    const wrapper = await mounted()

    await wrapper.get('[data-testid="invite-form"] input').setValue('bob@example.com')
    await wrapper.get('[data-testid="invite-form"]').trigger('submit')
    await flushPromises()

    const text = wrapper.get('[role="status"]').text()
    expect(text).toContain('Einladung für bob@example.com angelegt, aber keine E-Mail verschickt')
    expect(text).toContain('(Der Mailversand ist nicht eingerichtet)')
    expect(text).toContain(`Anmeldung unter ${window.location.origin}`)
    expect(wrapper.find('.notice-warning').exists()).toBe(true)
  })

  it('uses the shared design-system classes for its controls', async () => {
    const wrapper = await mounted()

    expect(wrapper.get('[data-testid="team-settings"]').classes()).toContain('settings-section')
    expect(wrapper.get('[data-testid="invite-form"] input').classes()).toContain('form-control')
    expect(wrapper.get('select[aria-label="Rolle der Einladung"]').classes()).toContain('form-control')
    expect(wrapper.get('[data-testid="invite-form"] button').classes()).toEqual(
      expect.arrayContaining(['btn', 'btn-primary'])
    )
    expect(wrapper.get('.danger-button').classes()).toEqual(expect.arrayContaining(['btn', 'btn-danger']))
  })

  it('changes a role, removes a member and withdraws an invitation', async () => {
    teamService.changeRole.mockResolvedValue({})
    teamService.removeMember.mockResolvedValue({})
    teamService.revokeInvitation.mockResolvedValue({})
    const wrapper = await mounted()

    await wrapper.get('select[aria-label="Rolle von reader@example.com"]').setValue('ADMIN')
    await flushPromises()
    expect(teamService.changeRole).toHaveBeenCalledWith('u2', 'ADMIN')

    await wrapper.get('[data-testid="member-reader@example.com"] button').trigger('click')
    await flushPromises()
    expect(teamService.removeMember).toHaveBeenCalledWith('u2')

    await wrapper.get('.invitations button').trigger('click')
    await flushPromises()
    expect(teamService.revokeInvitation).toHaveBeenCalledWith('inv')
  })

  it('shows the message of the backend when something is refused', async () => {
    teamService.changeRole.mockRejectedValue(new Error('The last owner cannot be demoted'))
    const wrapper = await mounted()

    await wrapper.get('select[aria-label="Rolle von owner@example.com"]').setValue('READER')
    await flushPromises()

    expect(wrapper.get('[role="alert"]').text()).toBe('The last owner cannot be demoted')
  })

  it('lets the owner leave and close the company after a confirmation', async () => {
    teamService.closeTenant.mockResolvedValue({})
    const reload = vi.fn()
    vi.stubGlobal('location', { ...window.location, reload })
    const wrapper = await mounted()

    expect(wrapper.get('[data-testid="member-owner@example.com"] button').text()).toBe('Verlassen')
    await wrapper.get('.danger-button').trigger('click')
    await flushPromises()

    expect(window.confirm).toHaveBeenCalled()
    expect(teamService.closeTenant).toHaveBeenCalled()
    vi.unstubAllGlobals()
  })

  it('does nothing when the confirmation is declined', async () => {
    window.confirm.mockReturnValue(false)
    const wrapper = await mounted()

    await wrapper.get('.danger-button').trigger('click')
    await wrapper.get('[data-testid="member-reader@example.com"] button').trigger('click')

    expect(teamService.closeTenant).not.toHaveBeenCalled()
    expect(teamService.removeMember).not.toHaveBeenCalled()
  })
})

describe('TeamSettings as an admin', () => {
  beforeEach(() => signedInAs('ADMIN'))

  it('may only hand out and change accountant and reader and cannot close the company', async () => {
    teamService.members.mockResolvedValue([
      { userId: 'me', email: 'owner@example.com', displayName: null, role: 'ADMIN' },
      { userId: 'boss', email: 'boss@example.com', displayName: null, role: 'OWNER' },
      { userId: 'u2', email: 'reader@example.com', displayName: null, role: 'READER' }
    ])
    const wrapper = await mounted()

    const options = wrapper.get('select[aria-label="Rolle der Einladung"]').findAll('option')
    expect(options.map(o => o.element.value)).toEqual(['ACCOUNTANT', 'READER'])
    expect(wrapper.find('select[aria-label="Rolle von boss@example.com"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="member-boss@example.com"] button').exists()).toBe(false)
    expect(wrapper.find('.danger-button').exists()).toBe(false)
  })
})

describe('TeamSettings administration log', () => {
  it('shows who did what, with German labels and role names', async () => {
    signedInAs('OWNER')
    teamService.auditLog.mockResolvedValue([
      { id: 2, occurredAt: '2026-11-10T13:05:00Z', action: 'ROLE_CHANGED', details: 'tom@example.com: READER → ACCOUNTANT', actor: 'Tina' },
      { id: 1, occurredAt: '2026-11-10T13:00:00Z', action: 'SOMETHING_NEW', details: null, actor: null }
    ])
    const wrapper = await mounted()

    const entries = wrapper.findAll('[data-testid="audit-log"] li')
    expect(entries).toHaveLength(2)
    expect(entries[0].get('.audit-action').text()).toBe('Rolle geändert')
    expect(entries[0].get('.audit-details').text()).toBe('tom@example.com: Nur lesen → Buchhaltung / Steuerberatung')
    expect(entries[0].get('.audit-actor').text()).toBe('Tina')
    expect(entries[1].get('.audit-action').text()).toBe('Sonstige Änderung')
    expect(entries[1].find('.audit-details').exists()).toBe(false)
  })

  it('says when there is nothing yet', async () => {
    signedInAs('OWNER')
    const wrapper = await mounted()
    expect(wrapper.text()).toContain('Noch keine Einträge.')
  })
})

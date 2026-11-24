import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import SendMailModal from './SendMailModal.vue'
import mailService from '../services/mailService.js'
import { session } from '../services/session.js'

vi.mock('../services/mailService.js', async importOriginal => {
  const actual = await importOriginal()
  return {
    ...actual,
    default: { draft: vi.fn(), send: vi.fn() }
  }
})

const ACCOUNTS = [
  { id: 1, name: 'Allgemein', fromAddress: 'info@firma.de', fromName: '', isDefault: false },
  { id: 2, name: 'Rechnungen', fromAddress: 'rechnung@firma.de', fromName: 'Firma', isDefault: true }
]

function draft(overrides = {}) {
  return {
    to: 'max@kunde.de',
    subject: 'Rechnung RE-2026-001 von Firma',
    text: 'Guten Tag Max Müller,\n\nanbei …',
    accounts: ACCOUNTS,
    needsIssue: false,
    attachmentName: 'Rechnung_RE-2026-001.pdf',
    xrechnungPossible: true,
    xrechnungMissing: [],
    ...overrides
  }
}

async function open(overrides) {
  mailService.draft.mockResolvedValue(draft(overrides))
  const wrapper = mount(SendMailModal, {
    props: { visible: true, documentId: 7 },
    global: { stubs: { 'router-link': { template: '<a href="/settings/mail"><slot /></a>' } } }
  })
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  session.mode = 'selfhost'
  session.memberships = []
  session.activeTenantId = null
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

describe('SendMailModal', () => {
  it('starts with the customer, the template and the default account', async () => {
    const wrapper = await open()

    expect(mailService.draft).toHaveBeenCalledWith(7)
    expect(wrapper.get('[data-testid="send-to"]').element.value).toBe('max@kunde.de')
    expect(wrapper.get('[data-testid="send-subject"]').element.value).toBe('Rechnung RE-2026-001 von Firma')
    expect(wrapper.get('#send-account').element.value).toBe('2')
    expect(wrapper.text()).toContain('Rechnung_RE-2026-001.pdf')
    expect(wrapper.get('[data-testid="send-button"]').text()).toBe('Senden')
  })

  it('sends the edited mail with split recipients and reports it', async () => {
    mailService.send.mockResolvedValue({ issued: false, documentNumber: 'RE-2026-001', documentStatusId: 2 })
    const wrapper = await open()
    await wrapper.get('#send-cc').setValue('chef@kunde.de; buchhaltung@kunde.de')
    await wrapper.get('.send-form').trigger('submit')
    await flushPromises()

    expect(mailService.send).toHaveBeenCalledWith(7, expect.objectContaining({
      accountId: 2,
      to: ['max@kunde.de'],
      cc: ['chef@kunde.de', 'buchhaltung@kunde.de'],
      issue: false
    }))
    expect(window.confirm).not.toHaveBeenCalled()
    expect(wrapper.emitted('sent')[0][0]).toMatchObject({ to: ['max@kunde.de'] })
    expect(wrapper.emitted('update:visible')[0]).toEqual([false])
  })

  it('issues an invoice draft only after the confirmation', async () => {
    const wrapper = await open({ needsIssue: true })
    expect(wrapper.find('[data-testid="needs-issue"]').exists()).toBe(true)
    expect(wrapper.get('[data-testid="send-button"]').text()).toBe('Ausstellen und senden')

    window.confirm.mockReturnValue(false)
    await wrapper.get('.send-form').trigger('submit')
    await flushPromises()
    expect(mailService.send).not.toHaveBeenCalled()

    window.confirm.mockReturnValue(true)
    mailService.send.mockResolvedValue({ issued: true, documentNumber: 'RE-2026-002', documentStatusId: 2 })
    await wrapper.get('.send-form').trigger('submit')
    await flushPromises()
    expect(mailService.send).toHaveBeenCalledWith(7, expect.objectContaining({ issue: true }))
  })

  it('shows the server message and keeps the dialog open when sending fails', async () => {
    mailService.send.mockRejectedValue(new Error('Der Mailserver hat die Anmeldung abgelehnt.'))
    const wrapper = await open()
    await wrapper.get('.send-form').trigger('submit')
    await flushPromises()

    expect(wrapper.text()).toContain('Der Mailserver hat die Anmeldung abgelehnt.')
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })

  it('refuses to send without a recipient', async () => {
    const wrapper = await open({ to: '' })
    await wrapper.get('.send-form').trigger('submit')
    expect(mailService.send).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('mindestens einen Empfänger')
  })

  it('cannot attach an incomplete XRechnung and names what is missing', async () => {
    const wrapper = await open({ xrechnungMissing: ['Leitweg-ID'] })
    const checkbox = wrapper.findAll('input[type="checkbox"]')[0]
    expect(checkbox.element.disabled).toBe(true)
    expect(wrapper.text()).toContain('Leitweg-ID')
  })

  it('links to the mail settings when there is no account yet', async () => {
    const wrapper = await open({ accounts: [] })
    expect(wrapper.find('[data-testid="no-account"]').text()).toContain('Mailkonto einrichten')
    expect(wrapper.find('.send-form').exists()).toBe(false)
  })

  it('tells an accountant in SaaS to ask an administrator instead of linking', async () => {
    session.mode = 'saas'
    session.memberships = [{ tenantId: 1, tenantName: 'Eins', role: 'ACCOUNTANT' }]
    session.activeTenantId = 1
    const wrapper = await open({ accounts: [] })
    expect(wrapper.find('[data-testid="no-account"]').text()).toContain('Administrator')
    expect(wrapper.find('[data-testid="no-account"] a').exists()).toBe(false)
  })
})

import { flushPromises, mount } from '@vue/test-utils'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import MailSettings from './MailSettings.vue'
import mailService from '../services/mailService.js'
import { session } from '../services/session.js'

vi.mock('../services/mailService.js', () => ({
  default: {
    settings: vi.fn(),
    accounts: vi.fn(),
    createAccount: vi.fn(),
    updateAccount: vi.fn(),
    deleteAccount: vi.fn(),
    makeDefault: vi.fn(),
    testAccount: vi.fn(),
    saveTemplates: vi.fn()
  }
}))

const TEMPLATES = {
  invoice: { subject: 'Rechnung {{rechnungsnummer}}', text: 'Guten Tag' },
  credit_note: { subject: 'Storno', text: '' },
  offer: { subject: 'Angebot', text: '' },
  cost_calculation: { subject: 'Kostenberechnung', text: '' }
}

const ACCOUNT = {
  id: 3,
  name: 'Rechnungen',
  host: 'smtp.firma.de',
  port: 587,
  security: 'STARTTLS',
  username: 'rechnung',
  hasPassword: true,
  fromAddress: 'rechnung@firma.de',
  fromName: 'Firma',
  isDefault: true
}

async function mounted(settings = {}) {
  mailService.settings.mockResolvedValue({ passwordsStorable: true, restricted: false, templates: TEMPLATES, ...settings })
  const wrapper = mount(MailSettings)
  await flushPromises()
  return wrapper
}

beforeEach(() => {
  vi.clearAllMocks()
  mailService.accounts.mockResolvedValue([ACCOUNT])
  vi.spyOn(window, 'confirm').mockReturnValue(true)
})

describe('MailSettings', () => {
  it('lists the accounts without ever showing a password', async () => {
    const wrapper = await mounted()
    expect(wrapper.text()).toContain('Rechnungen')
    expect(wrapper.text()).toContain('Firma <rechnung@firma.de>')
    expect(wrapper.text()).toContain('Standard')
  })

  it('creates an account with the entered password', async () => {
    mailService.createAccount.mockResolvedValue({})
    const wrapper = await mounted()
    await wrapper.get('#mail-name').setValue('Neu')
    await wrapper.get('#mail-from').setValue('neu@firma.de')
    await wrapper.get('#mail-host').setValue('smtp.neu.de')
    await wrapper.get('#mail-password').setValue('Geheim')
    await wrapper.get('[data-testid="mail-account-form"]').trigger('submit')
    await flushPromises()

    expect(mailService.createAccount).toHaveBeenCalledWith(expect.objectContaining({
      name: 'Neu', host: 'smtp.neu.de', port: 587, security: 'STARTTLS', password: 'Geheim'
    }))
    expect(wrapper.text()).toContain('Mailkonto angelegt')
  })

  it('keeps the stored password when the field stays empty, and removes it only on request', async () => {
    mailService.updateAccount.mockResolvedValue({})
    const wrapper = await mounted()
    await wrapper.findAll('button').find(b => b.text() === 'Bearbeiten').trigger('click')
    expect(wrapper.get('#mail-password').attributes('placeholder')).toContain('gespeichert')
    await wrapper.get('[data-testid="mail-account-form"]').trigger('submit')
    await flushPromises()
    expect(mailService.updateAccount).toHaveBeenCalledWith(3, expect.objectContaining({ password: null }))

    await wrapper.findAll('button').find(b => b.text() === 'Bearbeiten').trigger('click')
    await wrapper.find('.inline-check input').setValue(true)
    await wrapper.get('[data-testid="mail-account-form"]').trigger('submit')
    await flushPromises()
    expect(mailService.updateAccount).toHaveBeenLastCalledWith(3, expect.objectContaining({ password: '' }))
  })

  it('moves the usual port with the security setting', async () => {
    const wrapper = await mounted()
    await wrapper.get('#mail-security').setValue('SSL')
    expect(wrapper.get('#mail-port').element.value).toBe('465')
  })

  it('offers no unencrypted connection in SaaS and warns without a key', async () => {
    const wrapper = await mounted({ restricted: true, passwordsStorable: false })
    const options = wrapper.findAll('#mail-security option').map(o => o.element.value)
    expect(options).toEqual(['STARTTLS', 'SSL'])
    expect(wrapper.find('[data-testid="no-secrets-key"]').exists()).toBe(true)
  })

  it('sends a test mail and shows where it went; shows the reason when it fails', async () => {
    mailService.testAccount.mockResolvedValueOnce({})
    const wrapper = await mounted()
    await wrapper.findAll('button').find(b => b.text() === 'Testmail').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Testmail an rechnung@firma.de gesendet')

    mailService.testAccount.mockRejectedValueOnce(new Error('Der Mailserver ist nicht erreichbar.'))
    await wrapper.findAll('button').find(b => b.text() === 'Testmail').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('Der Mailserver ist nicht erreichbar.')
  })

  it('saves the templates of every kind', async () => {
    mailService.saveTemplates.mockResolvedValue(TEMPLATES)
    const wrapper = await mounted()
    await wrapper.findAll('[role="tab"]').find(b => b.text() === 'Angebot').trigger('click')
    const subject = wrapper.get('#mail-template-subject')
    subject.element.textContent = 'Unser Angebot'
    await subject.trigger('input')
    await wrapper.get('[data-testid="mail-template-form"]').trigger('submit')
    await flushPromises()
    expect(mailService.saveTemplates).toHaveBeenCalledWith(
      expect.objectContaining({ offer: { subject: 'Unser Angebot', text: '' } })
    )
  })

  it('demo: says the mail dispatch is off and offers no account form, the templates stay', async () => {
    session.demo = true
    mailService.accounts.mockResolvedValue([])
    try {
      const wrapper = await mounted()
      expect(wrapper.find('[data-testid="mail-demo"]').exists()).toBe(true)
      expect(wrapper.find('[data-testid="mail-account-form"]').exists()).toBe(false)
      expect(wrapper.find('[data-testid="mail-template-form"]').exists()).toBe(true)
    } finally {
      session.demo = false
    }
  })

  it('outside the demo there is no demo notice', async () => {
    const wrapper = await mounted()
    expect(wrapper.find('[data-testid="mail-demo"]').exists()).toBe(false)
    expect(wrapper.find('[data-testid="mail-account-form"]').exists()).toBe(true)
  })

  it('shows the placeholders of the templates as chips, never as {{braces}}', async () => {
    const wrapper = await mounted()
    const subject = wrapper.get('#mail-template-subject')
    expect(subject.findAll('.var-chip').map(chip => chip.text())).toEqual(['Rechnungsnummer'])
    expect(subject.text()).not.toContain('{{')
    expect(wrapper.get('[data-testid="mail-template-form"]').text()).toContain('+ Platzhalter')
  })
})

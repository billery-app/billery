import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../services/backupService.js', () => ({
  default: {
    getStatus: vi.fn(),
    updateSettings: vi.fn(() => Promise.resolve({ data: {} })),
    backupNow: vi.fn(() => Promise.resolve({ data: {} })),
    restore: vi.fn(() => Promise.resolve({ data: { message: 'ok' } })),
    cancelRestore: vi.fn(() => Promise.resolve({}))
  }
}))
import backupAPI from '../services/backupService.js'
import BackupSettings from './BackupSettings.vue'

const status = (overrides = {}) => ({
  directory: '/daten/billery/backups',
  defaultDirectory: '/daten/billery/backups',
  keep: 10,
  onExit: true,
  encrypted: false,
  restorePending: false,
  backups: [],
  ...overrides
})
const backup = (name, createdAt, extra = {}) => ({ name, createdAt, reason: 'beenden', encrypted: false, size: 2048, ...extra })

async function mounted(data) {
  backupAPI.getStatus.mockResolvedValue({ data })
  const wrapper = mount(BackupSettings)
  await flushPromises()
  return wrapper
}

describe('BackupSettings', () => {
  beforeEach(() => vi.clearAllMocks())

  it('warns when there is no backup or the last one is older than 7 days', async () => {
    const none = await mounted(status())
    expect(none.find('[data-testid="backup-last"]').text()).toContain('Noch keine Sicherung')

    const old = await mounted(status({ backups: [backup('b1', '2020-01-01T10:00:00')] }))
    expect(old.find('[data-testid="backup-last"]').text()).toContain('älter als 7 Tage')

    const fresh = await mounted(status({ backups: [backup('b2', new Date().toISOString())] }))
    expect(fresh.find('[data-testid="backup-last"]').text()).not.toContain('älter')
  })

  it('backs up now and reloads the list', async () => {
    const wrapper = await mounted(status())
    await wrapper.findAll('button').find(b => b.text() === 'Jetzt sichern').trigger('click')
    await flushPromises()
    expect(backupAPI.backupNow).toHaveBeenCalled()
    expect(backupAPI.getStatus).toHaveBeenCalledTimes(2)
  })

  it('switches encryption on only with a password and keeps it on a later save', async () => {
    const wrapper = await mounted(status())
    await wrapper.find('[data-testid="backup-encrypt"]').setValue(true)
    await wrapper.find('form').trigger('submit')
    expect(backupAPI.updateSettings).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('Passwort')

    await wrapper.find('#backup-password').setValue('geheim123')
    await wrapper.find('form').trigger('submit')
    await flushPromises()
    expect(backupAPI.updateSettings).toHaveBeenLastCalledWith({ directory: '', keep: 10, onExit: true, password: 'geheim123' })

    const encrypted = await mounted(status({ encrypted: true }))
    await encrypted.find('form').trigger('submit')
    await flushPromises()
    expect(backupAPI.updateSettings).toHaveBeenLastCalledWith({ directory: '', keep: 10, onExit: true, password: null })
  })

  it('restores an encrypted backup with its password at the next start', async () => {
    const wrapper = await mounted(status({ backups: [backup('billery-x.zip.enc', new Date().toISOString(), { encrypted: true })] }))
    await wrapper.findAll('button').find(b => b.text().startsWith('Wiederherstellen')).trigger('click')
    await wrapper.find('input[type="password"][placeholder="Passwort"]').setValue('geheim123')
    await wrapper.findAll('button').find(b => b.text().startsWith('Beim nächsten Start')).trigger('click')
    await flushPromises()
    expect(backupAPI.restore).toHaveBeenCalledWith('billery-x.zip.enc', 'geheim123', false)
    expect(wrapper.find('[role="status"]').text()).toContain('nächsten Start')
  })

  it('asks for an explicit confirmation when the restore would take out later documents', async () => {
    backupAPI.restore
      .mockRejectedValueOnce({ response: { status: 409, data: { message: 'Seit dieser Sicherung wurden 2 Belege ausgestellt …' } } })
      .mockResolvedValueOnce({ data: { message: 'ok' } })
    const wrapper = await mounted(status({ backups: [backup('billery-y.zip', new Date().toISOString())] }))
    await wrapper.findAll('button').find(b => b.text().startsWith('Wiederherstellen')).trigger('click')
    await wrapper.findAll('button').find(b => b.text().startsWith('Beim nächsten Start')).trigger('click')
    await flushPromises()

    expect(wrapper.find('[data-testid="backup-loss"]').text()).toContain('2 Belege')
    await wrapper.findAll('button').find(b => b.text() === 'Trotzdem wiederherstellen').trigger('click')
    await flushPromises()
    expect(backupAPI.restore).toHaveBeenLastCalledWith('billery-y.zip', '', true)
    expect(wrapper.find('[data-testid="backup-loss"]').exists()).toBe(false)
  })
})

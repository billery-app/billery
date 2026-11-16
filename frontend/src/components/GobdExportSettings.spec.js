import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import GobdExportSettings from './GobdExportSettings.vue'

/** An empty but complete ZIP: just the end-of-central-directory record. */
const EMPTY_ZIP = new Uint8Array([0x50, 0x4b, 0x05, 0x06, ...new Array(18).fill(0)])

describe('GobdExportSettings', () => {
  it('offers a period and downloads the export for it', async () => {
    const { default: exportAPI } = await import('../services/exportService.js')
    const spy = vi.spyOn(exportAPI, 'gobdExport').mockResolvedValue({ data: new Blob([EMPTY_ZIP]) })
    window.URL.createObjectURL = vi.fn(() => 'blob:x')
    window.URL.revokeObjectURL = vi.fn()
    const wrapper = mount(GobdExportSettings)

    await wrapper.find('[data-testid="export-from"]').setValue('2026-01-01')
    await wrapper.find('[data-testid="export-to"]').setValue('2026-03-31')
    await wrapper.find('[data-testid="export-button"]').trigger('click')
    await flushPromises()

    expect(spy).toHaveBeenCalledWith('2026-01-01', '2026-03-31', true)
    expect(window.URL.createObjectURL).toHaveBeenCalled()
    expect(wrapper.find('.export-error').exists()).toBe(false)
    spy.mockRestore()
  })

  it('does not save an export that broke off on the way', async () => {
    const { default: exportAPI } = await import('../services/exportService.js')
    // local entries arrived, the central directory did not
    const spy = vi.spyOn(exportAPI, 'gobdExport').mockResolvedValue({ data: new Blob(['PK\x03\x04 belege.csv ...']) })
    window.URL.createObjectURL = vi.fn(() => 'blob:x')
    const wrapper = mount(GobdExportSettings)

    await wrapper.find('[data-testid="export-button"]').trigger('click')
    await flushPromises()

    expect(window.URL.createObjectURL).not.toHaveBeenCalled()
    expect(wrapper.find('.export-error').text()).toContain('unvollständig')
    spy.mockRestore()
  })

  it('refuses an inverted period without calling the server', async () => {
    const { default: exportAPI } = await import('../services/exportService.js')
    const spy = vi.spyOn(exportAPI, 'gobdExport')
    const wrapper = mount(GobdExportSettings)

    await wrapper.find('[data-testid="export-from"]').setValue('2026-05-01')
    await wrapper.find('[data-testid="export-to"]').setValue('2026-01-01')
    await wrapper.find('[data-testid="export-button"]').trigger('click')

    expect(spy).not.toHaveBeenCalled()
    expect(wrapper.find('.export-error').text()).toContain('Startdatum')
    spy.mockRestore()
  })
})

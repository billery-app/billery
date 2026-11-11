import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import BlockConfigPanel from './BlockConfigPanel.vue'
import { getBlockByType } from '../../utils/blockRegistry.js'
import { createBlock } from '../../utils/blockModel.js'

vi.mock('../../services/uploadService.js', () => ({
  uploadLogo: vi.fn(() => Promise.resolve('http://127.0.0.1:8080/uploads/logo.png'))
}))

function mountPanel(block, extra = {}) {
  return mount(BlockConfigPanel, {
    props: { block, blockType: getBlockByType(block.type), ...extra },
    attachTo: document.body
  })
}

const lastUpdate = (wrapper) => {
  const events = wrapper.emitted('update')
  return events[events.length - 1][0]
}

describe('BlockConfigPanel', () => {
  describe('content tab (generated from the registry fields)', () => {
    it('shows one labelled field per registry field', () => {
      const wrapper = mountPanel(createBlock('payment'))
      const labels = wrapper.findAll('.form-group > label').map(l => l.text())
      expect(labels).toEqual(['Zahlungsart', 'Bank', 'IBAN', 'BIC', 'Verwendungszweck'])
    })

    it('renders variables as chips, never as curly braces', () => {
      const wrapper = mountPanel(createBlock('address', { role: 'receiver' }))
      const name = wrapper.find('#field-' + wrapper.props('block').id + '-name')
      expect(name.text()).toBe('Kundenname')
      expect(name.find('.var-chip').exists()).toBe(true)
      expect(wrapper.text()).not.toContain('{{')
    })

    it('emits a changed copy of the block immediately (no save button)', async () => {
      const block = createBlock('header')
      const wrapper = mountPanel(block)
      const field = wrapper.find(`#field-${block.id}-companySlogan`)
      field.element.textContent = 'Qualität seit 1990'
      await field.trigger('input')

      const updated = lastUpdate(wrapper)
      expect(updated.data.companySlogan).toBe('Qualität seit 1990')
      expect(updated.id).toBe(block.id)
      expect(block.data.companySlogan).toBe('') // input block untouched
      expect(wrapper.find('.btn-save').exists()).toBe(false)
    })

    it('explains blocks without content fields', () => {
      const wrapper = mountPanel(createBlock('totals'))
      expect(wrapper.find('.hint').text()).toContain('automatisch')
    })

    it('switching an address to receiver swaps untouched defaults and the heading', async () => {
      const block = createBlock('address', { role: 'sender' })
      block.data.phone = '0800 123' // edited by the user: must be kept
      const wrapper = mountPanel(block)
      await wrapper.find(`#field-${block.id}-role`).setValue('receiver')

      const updated = lastUpdate(wrapper)
      expect(updated.data.role).toBe('receiver')
      expect(updated.data.name).toBe('{{kunde}}')
      expect(updated.data.phone).toBe('0800 123')
      expect(updated.config.label).toBe('Empfänger')
    })

    it('edits table columns (visibility, label, order)', async () => {
      const block = createBlock('line-items')
      const wrapper = mountPanel(block)
      const rows = wrapper.findAll('.column-row')
      expect(rows).toHaveLength(5)

      await rows[2].find('input[type="checkbox"]').setValue(false)
      expect(lastUpdate(wrapper).data.columns[2].show).toBe(false)

      await rows[0].find('input[type="text"]').setValue('Leistung')
      expect(lastUpdate(wrapper).data.columns[0].label).toBe('Leistung')

      await rows[0].findAll('.btn-mini')[1].trigger('click')
      expect(lastUpdate(wrapper).data.columns.map(c => c.key).slice(0, 2)).toEqual(['quantity', 'description'])
    })

    it('uploads a logo and stores the returned URL', async () => {
      const block = createBlock('header')
      const wrapper = mountPanel(block)
      const input = wrapper.find('input[type="file"]')
      const file = new File(['x'], 'logo.png', { type: 'image/png' })
      Object.defineProperty(input.element, 'files', { value: [file] })
      await input.trigger('change')
      await new Promise(r => setTimeout(r))
      expect(lastUpdate(wrapper).data.logoUrl).toContain('/uploads/logo.png')
    })

    it('shows the validation issues of the block', () => {
      const wrapper = mountPanel(createBlock('footer'), { issues: [{ message: 'Platzhalter „x“ ist unbekannt' }] })
      expect(wrapper.find('.block-issues').text()).toContain('unbekannt')
    })
  })

  describe('style tab (settings + font)', () => {
    async function openStyle(block) {
      const wrapper = mountPanel(block)
      await wrapper.findAll('.panel-tab')[1].trigger('click')
      return wrapper
    }

    it('toggles and selects write to config', async () => {
      const wrapper = await openStyle(createBlock('totals'))
      const toggle = wrapper.find('.toggle-label input')
      await toggle.setValue(false)
      expect(lastUpdate(wrapper).config.showTax).toBe(false)
    })

    it('hides dependent settings (showIf)', async () => {
      const block = createBlock('notes')
      block.config.showBox = false
      const wrapper = await openStyle(block)
      const labels = wrapper.text()
      expect(labels).not.toContain('Hintergrundfarbe')
      expect(labels).toContain('Farbige Box')
    })

    it('font settings start from the block defaults and clamp the size', async () => {
      const wrapper = await openStyle(createBlock('footer'))
      expect(wrapper.find('#fmt-size').element.value).toBe('9')
      expect(wrapper.find('.size-control').text()).not.toMatch(/pt/) // casual users do not know the unit
      await wrapper.find('#fmt-size').setValue('200')
      expect(lastUpdate(wrapper).config.format.fontSize).toBe(72)
      await wrapper.find('#fmt-family').setValue('Georgia')
      expect(lastUpdate(wrapper).config.format.fontFamily).toBe('Georgia')
    })
  })

  describe('actions', () => {
    it('emits close, duplicate and remove', async () => {
      const wrapper = mountPanel(createBlock('notes'))
      await wrapper.find('.btn-close').trigger('click')
      const [duplicate, remove] = wrapper.findAll('.btn-footer')
      await duplicate.trigger('click')
      await remove.trigger('click')
      expect(wrapper.emitted('close')).toBeTruthy()
      expect(wrapper.emitted('duplicate')).toBeTruthy()
      expect(wrapper.emitted('remove')).toBeTruthy()
    })

    it('names address blocks by their role', () => {
      const wrapper = mountPanel(createBlock('address', { role: 'receiver' }))
      expect(wrapper.find('.panel-header h3').text()).toBe('Adresse – Empfänger')
    })
  })
})

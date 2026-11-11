import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import DesignPanel from './DesignPanel.vue'
import { createDefaultBlocks } from '../../utils/blockModel.js'

vi.mock('../../services/uploadService.js', () => ({
  uploadLogo: vi.fn(() => Promise.reject(new Error('Logo-Upload fehlgeschlagen: zu groß')))
}))

const margins = { marginTopMm: 20, marginRightMm: 20, marginBottomMm: 20, marginLeftMm: 20 }

function mountPanel(blocks = createDefaultBlocks()) {
  return mount(DesignPanel, { props: { blocks, margins } })
}

const lastBlocks = (wrapper) => {
  const events = wrapper.emitted('update:blocks')
  return events[events.length - 1][0]
}

describe('DesignPanel', () => {
  it('reflects the current design', () => {
    const wrapper = mountPanel()
    expect(wrapper.find('.swatch.active').attributes('aria-label')).toBe('#2563eb')
    expect(wrapper.findAll('.segmented')[0].find('.active').text()).toBe('Normal')
    expect(wrapper.findAll('.segmented')[1].find('.active').text()).toBe('Untereinander')
    expect(wrapper.findAll('.check-row input').every(i => i.element.checked)).toBe(true)
  })

  it('accent color, font and text size emit new blocks', async () => {
    const wrapper = mountPanel()
    await wrapper.findAll('.swatch')[1].trigger('click')
    expect(lastBlocks(wrapper).find(b => b.type === 'header').config.format.fontColor).toBe('#0f766e')

    await wrapper.find('select').setValue('Georgia')
    expect(lastBlocks(wrapper).every(b => b.config.format.fontFamily === 'Georgia')).toBe(true)

    await wrapper.findAll('.segmented')[0].findAll('button')[2].trigger('click')
    expect(lastBlocks(wrapper).find(b => b.type === 'line-items').config.format.fontSize).toBe(12)
  })

  it('the arrangement control puts receiver and document data side by side', async () => {
    const wrapper = mountPanel()
    await wrapper.findAll('.segmented')[1].findAll('button')[1].trigger('click')
    const blocks = lastBlocks(wrapper)
    expect(blocks.filter(b => b.config.width === 'half').map(b => b.type)).toEqual(['address', 'invoice-meta'])
  })

  it('switching a part off removes its block', async () => {
    const wrapper = mountPanel()
    const payment = wrapper.findAll('.check-row').find(r => r.text().includes('Bankverbindung'))
    await payment.find('input').setValue(false)
    expect(lastBlocks(wrapper).some(b => b.type === 'payment')).toBe(false)
  })

  it('margin presets set all four sides; custom values are clamped', async () => {
    const wrapper = mountPanel()
    await wrapper.findAll('.segmented')[2].findAll('button')[0].trigger('click')
    expect(wrapper.emitted('update:margins')[0][0]).toEqual({ marginTopMm: 10, marginRightMm: 10, marginBottomMm: 10, marginLeftMm: 10 })
    await wrapper.find('.margin-grid input').setValue('99')
    expect(wrapper.emitted('update:margins')[1][0]).toEqual({ marginTopMm: 50 })
  })

  it('reports a failed logo upload', async () => {
    const wrapper = mountPanel()
    const input = wrapper.find('input[type="file"]')
    Object.defineProperty(input.element, 'files', { value: [new File(['x'], 'l.png', { type: 'image/png' })] })
    await input.trigger('change')
    await new Promise(r => setTimeout(r))
    expect(wrapper.emitted('notify')[0][0]).toEqual({ type: 'error', message: 'Logo-Upload fehlgeschlagen: zu groß' })
  })
})

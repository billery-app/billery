import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import BlockOutline, { MOVE_TYPE } from './BlockOutline.vue'
import { createDefaultBlocks } from '../../utils/blockModel.js'

function dataTransfer(data = {}) {
  return {
    types: Object.keys(data),
    getData: (type) => data[type] ?? '',
    setData: (type, value) => { data[type] = value },
    dropEffect: '',
    effectAllowed: ''
  }
}

describe('BlockOutline', () => {
  const blocks = createDefaultBlocks()

  it('lists the blocks in order with readable names', () => {
    const wrapper = mount(BlockOutline, { props: { blocks } })
    const names = wrapper.findAll('.item-name').map(n => n.text())
    expect(names[0]).toBe('Kopfzeile')
    expect(names[1]).toBe('Adresse – Absender')
    expect(names[2]).toBe('Adresse – Empfänger')
  })

  it('selects on click and marks the selected block', async () => {
    const wrapper = mount(BlockOutline, { props: { blocks, selectedId: blocks[1].id } })
    expect(wrapper.findAll('.outline-item')[1].classes()).toContain('selected')
    await wrapper.findAll('.outline-item')[3].trigger('click')
    expect(wrapper.emitted('select')[0]).toEqual([blocks[3].id])
  })

  it('moves with the arrow buttons and Alt+arrow keys, removes with Delete', async () => {
    const wrapper = mount(BlockOutline, { props: { blocks } })
    const item = wrapper.findAll('.outline-item')[2]
    await item.findAll('.item-actions button')[0].trigger('click')
    await item.trigger('keydown', { key: 'ArrowDown', altKey: true })
    await item.trigger('keydown', { key: 'Delete' })
    expect(wrapper.emitted('move')).toEqual([[2, 1], [2, 3]])
    expect(wrapper.emitted('remove')[0]).toEqual([blocks[2].id])
  })

  it('reorders by drag and drop (dropping before a later item accounts for the removal)', async () => {
    const wrapper = mount(BlockOutline, { props: { blocks } })
    const items = wrapper.findAll('.outline-item')
    await items[0].trigger('drop', { dataTransfer: dataTransfer({ [MOVE_TYPE]: '0' }) })
    expect(wrapper.emitted('move')).toBeFalsy()
    await items[3].trigger('drop', { dataTransfer: dataTransfer({ [MOVE_TYPE]: '0' }) })
    expect(wrapper.emitted('move')[0]).toEqual([0, 2])
  })

  it('accepts blocks dragged in from the library', async () => {
    const wrapper = mount(BlockOutline, { props: { blocks } })
    await wrapper.find('.outline-end').trigger('drop', { dataTransfer: dataTransfer({ 'block-type': '{"type":"notes"}' }) })
    expect(wrapper.emitted('add')[0]).toEqual(['notes', blocks.length])
  })

  it('shows a badge for blocks with issues', () => {
    const wrapper = mount(BlockOutline, { props: { blocks, issues: [{ blockId: blocks[0].id, message: 'x' }] } })
    expect(wrapper.findAll('.outline-item')[0].find('.issue-badge').exists()).toBe(true)
    expect(wrapper.findAll('.outline-item')[1].find('.issue-badge').exists()).toBe(false)
  })
})

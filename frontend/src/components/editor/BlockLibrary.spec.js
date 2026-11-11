import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import BlockLibrary from './BlockLibrary.vue'
import { BLOCK_TYPES } from '../../utils/blockRegistry.js'

describe('BlockLibrary - Real Rendering Tests', () => {
  const availableBlocks = BLOCK_TYPES.map(bt => ({ type: bt.type, name: bt.name, icon: bt.icon }))

  describe('DOM Rendering', () => {
    it('renders block library container in DOM', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      expect(wrapper.find('.block-library').exists()).toBe(true)
    })

    it('renders library title', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      expect(wrapper.find('.library-title').text()).toBe('Block hinzufügen')
    })

    it('renders hint text', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      expect(wrapper.find('.library-hint').text()).toContain('ziehen')
    })

    it('renders block list container', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      expect(wrapper.find('.block-list').exists()).toBe(true)
    })

    it('renders all block items in DOM', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const blockItems = wrapper.findAll('.block-item')
      expect(blockItems).toHaveLength(8)
    })
  })

  describe('Block Rendering - Each Type', () => {
    it('renders header block item', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const names = wrapper.findAll('.block-name').map(el => el.text())
      expect(names).toContain('Kopfzeile')
    })

    it('renders address block item', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const names = wrapper.findAll('.block-name').map(el => el.text())
      expect(names).toContain('Adresse')
    })

    it('renders invoice-meta block item', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const names = wrapper.findAll('.block-name').map(el => el.text())
      expect(names).toContain('Dokumentdaten')
    })

    it('renders line-items block item', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const names = wrapper.findAll('.block-name').map(el => el.text())
      expect(names).toContain('Positionen')
    })

    it('renders totals block item', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const names = wrapper.findAll('.block-name').map(el => el.text())
      expect(names).toContain('Summen')
    })

    it('renders notes block item', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const names = wrapper.findAll('.block-name').map(el => el.text())
      expect(names).toContain('Hinweise')
    })

    it('renders payment block item', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const names = wrapper.findAll('.block-name').map(el => el.text())
      expect(names).toContain('Zahlungsdaten')
    })

    it('renders footer block item', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const names = wrapper.findAll('.block-name').map(el => el.text())
      expect(names).toContain('Fußzeile')
    })
  })

  describe('Block Item Structure', () => {
    it('renders icon element for each block', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const icons = wrapper.findAll('.block-icon')
      expect(icons).toHaveLength(8)
    })

    it('renders name element for each block', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const names = wrapper.findAll('.block-name')
      expect(names).toHaveLength(8)
    })

    it('each block has icon and name', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const firstBlock = wrapper.find('.block-item')
      expect(firstBlock.find('.block-icon').exists()).toBe(true)
      expect(firstBlock.find('.block-name').exists()).toBe(true)
    })
  })

  describe('User Interaction - Click', () => {
    it('emits add-block event when first block clicked', async () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      await wrapper.find('.block-item').trigger('click')

      expect(wrapper.emitted('add-block')).toBeTruthy()
      expect(wrapper.emitted('add-block')[0][0]).toEqual(availableBlocks[0])
    })

    it('emits add-block event with correct block data', async () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const blockItems = wrapper.findAll('.block-item')
      await blockItems.at(3).trigger('click')

      expect(wrapper.emitted('add-block')[0][0].name).toBe('Positionen')
    })
  })

  describe('User Interaction - Drag', () => {
    it('block items are draggable', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      const blockItem = wrapper.find('.block-item')
      expect(blockItem.attributes('draggable')).toBe('true')
    })

    it('has drag start handler method', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      expect(typeof wrapper.vm.onDragStart).toBe('function')
    })

    it('has drag end handler method', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      expect(typeof wrapper.vm.onDragEnd).toBe('function')
    })
  })

  describe('Empty State', () => {
    it('handles empty availableBlocks', () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks: [] }
      })

      const blockItems = wrapper.findAll('.block-item')
      expect(blockItems).toHaveLength(0)
    })
  })

  describe('Block Data Structure', () => {
    it('emits complete block type object on click', async () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks }
      })

      await wrapper.find('.block-item').trigger('click')

      const emittedBlock = wrapper.emitted('add-block')[0][0]
      expect(emittedBlock.type).toBeDefined()
      expect(emittedBlock.name).toBeDefined()
      expect(emittedBlock.icon).toBeDefined()
    })
  })

  describe('Limits', () => {
    it('disables block types that reached their limit and ignores clicks on them', async () => {
      const wrapper = mount(BlockLibrary, {
        props: { availableBlocks, blocks: [{ id: 'x', type: 'totals', data: {}, config: {} }] }
      })
      const totals = wrapper.findAll('.block-item').find(el => el.text().includes('Summen'))
      expect(totals.classes()).toContain('disabled')
      expect(totals.attributes('aria-disabled')).toBe('true')
      await totals.trigger('click')
      expect(wrapper.emitted('add-block')).toBeFalsy()
    })
  })
})

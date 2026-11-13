import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../../services/documentService.js', () => ({
  default: { importDocument: vi.fn() }
}))
import documentAPI from '../../services/documentService.js'
import TemplateStartModal from './TemplateStartModal.vue'
import TemplateThumbnail from './TemplateThumbnail.vue'
import { validateBlocks } from '../../utils/blockValidation.js'

describe('TemplateStartModal', () => {
  it('offers the document types (without credit notes) and one thumbnail per preset', () => {
    const wrapper = mount(TemplateStartModal)
    const types = wrapper.findAll('.type-option').map(t => t.text())
    expect(types).toContain('Rechnung')
    expect(types).toContain('Angebot')
    expect(types.join()).not.toContain('Storno')
    expect(wrapper.findAllComponents(TemplateThumbnail)).toHaveLength(wrapper.findAll('.preset-card').length)
  })

  it('emits a ready-to-edit, valid template for the chosen type and style', async () => {
    const wrapper = mount(TemplateStartModal)
    await wrapper.findAll('.type-option').find(t => t.text() === 'Angebot').trigger('click')
    await wrapper.findAll('.preset-card')[1].trigger('click')
    await wrapper.find('.btn-primary').trigger('click')

    const template = wrapper.emitted('choose')[0][0]
    expect(template).toMatchObject({ name: 'Angebot – Klassisch', documentTypeId: 2, isDefault: false })
    expect(template.id).toBeUndefined()
    expect(validateBlocks(template.blocks).errors).toEqual([])
  })

  it('cancels on the button and on Esc', async () => {
    const wrapper = mount(TemplateStartModal)
    await wrapper.find('.btn-secondary').trigger('click')
    await wrapper.find('.start-modal').trigger('keydown', { key: 'Escape' })
    expect(wrapper.emitted('cancel')).toHaveLength(2)
  })
})

describe('TemplateStartModal import', () => {
  beforeEach(() => vi.clearAllMocks())

  const choose = async (wrapper, file) => {
    const input = wrapper.find('[data-testid="template-import-file"]')
    Object.defineProperty(input.element, 'files', { value: [file], configurable: true })
    await input.trigger('change')
    await flushPromises()
  }

  it('applies the sections of a file to the chosen style and opens it like a style', async () => {
    documentAPI.importDocument.mockResolvedValue({
      data: {
        name: 'Meine Vorlage',
        sections: [
          { heading: 'Fußzeile', paragraphs: ['Text: Danke aus Word'] },
          { heading: null, paragraphs: ['Freier Text'] }
        ]
      }
    })
    const wrapper = mount(TemplateStartModal)
    await choose(wrapper, new File(['x'], 'Meine Vorlage.docx'))

    const template = wrapper.emitted('choose')[0][0]
    expect(template.name).toBe('Meine Vorlage')
    expect(template.blocks.find(b => b.type === 'footer').data.text).toBe('Danke aus Word')
    expect(template.blocks.find(b => b.type === 'notes').data.content).toContain('Freier Text')
    expect(validateBlocks(template.blocks).errors).toEqual([])
  })

  it('shows the reason instead of opening the editor when the file is refused', async () => {
    const wrapper = mount(TemplateStartModal)
    await choose(wrapper, new File(['x'], 'rechnung.pdf'))
    expect(documentAPI.importDocument).not.toHaveBeenCalled()
    expect(wrapper.find('[role="alert"]').text()).toContain('DOCX')

    documentAPI.importDocument.mockRejectedValue({ response: { data: { message: 'Das Dokument enthält keinen Text.' } } })
    await choose(wrapper, new File(['x'], 'leer.odt'))
    expect(wrapper.find('[role="alert"]').text()).toBe('Das Dokument enthält keinen Text.')
    expect(wrapper.emitted('choose')).toBeUndefined()
  })
})

describe('TemplateThumbnail', () => {
  it('renders the template with sample data and its own scoped CSS', () => {
    const wrapper = mount(TemplateThumbnail, {
      props: {
        blocks: JSON.stringify([{ id: 'a', type: 'address', order: 0, data: { role: 'receiver', name: '{{kunde}}' }, config: { showLabels: true } }]),
        cssContent: 'strong { color: red }'
      }
    })
    expect(wrapper.html()).toContain('Anna Müller')
    const scope = wrapper.classes().find(c => c.startsWith('template-thumb-'))
    expect(wrapper.html()).toContain(`.${scope} .invoice-box strong`)
  })

  it('fits the A4 page to the available card width without changing its ratio', async () => {
    const wrapper = mount(TemplateThumbnail, { props: { maxWidth: 240 } })
    const frame = wrapper.find('.template-thumbnail').element
    const page = wrapper.find('.thumb-page')

    expect(frame.style.width).toBe('100%')
    expect(frame.style.maxWidth).toBe('240px')
    expect(frame.style.aspectRatio).toBe('210 / 297')

    Object.defineProperty(frame, 'clientWidth', { configurable: true, value: 120 })
    wrapper.vm.updateScale()
    await wrapper.vm.$nextTick()

    expect(wrapper.vm.scale).toBe(120 / 793.7)
    // The browser (and jsdom since 31) shortens the number when it serialises the style; compare
    // the value, not its spelling.
    const shown = Number(page.element.style.transform.match(/^scale\(([\d.]+)\)$/)[1])
    expect(shown).toBeCloseTo(120 / 793.7, 5)
    expect(page.element.style.zoom).toBe('')
  })
})

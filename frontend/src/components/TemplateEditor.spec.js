import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import TemplateEditor from './TemplateEditor.vue'
import ownerService from '../services/ownerService.js'
import templateAPI from '../services/templateService.js'

vi.mock('../services/ownerService.js', () => ({
  default: {
    getPrimaryOwner: vi.fn(() => Promise.resolve({ data: {} }))
  }
}))

vi.mock('../services/templateService.js', () => ({
  default: {
    previewPdf: vi.fn(() => Promise.resolve({ data: new Blob(['%PDF']) }))
  }
}))

/** A valid template: every required block, stored with `order` like the server sends it. */
const validBlocks = () => [
  { id: 'h', type: 'header', order: 0, data: { companyName: '{{firma}}' }, config: {} },
  { id: 'r', type: 'address', order: 1, data: { role: 'receiver', name: '{{kunde}}' }, config: {} },
  { id: 'm', type: 'invoice-meta', order: 2, data: { invoiceTitle: 'Rechnung' }, config: {} },
  { id: 'l', type: 'line-items', order: 3, data: {}, config: {} },
  { id: 't', type: 'totals', order: 4, data: {}, config: {} },
  { id: 'n', type: 'notes', order: 5, data: { content: '{{notizen}}' }, config: {} }
]

const baseTemplate = () => ({
  id: 1,
  name: 'Test Template',
  documentTypeId: 1,
  isDefault: false,
  marginTopMm: 10,
  marginBottomMm: 10,
  marginLeftMm: 10,
  marginRightMm: 10,
  cssContent: '',
  blocks: validBlocks()
})

async function mountEditor({ template = {}, liveData = null } = {}) {
  const wrapper = mount(TemplateEditor, {
    props: { template: { ...baseTemplate(), ...template }, liveData },
    global: {
      stubs: { BlockConfigPanel: true, DesignPanel: true, 'router-link': true }
    },
    attachTo: document.body
  })
  await flushPromises()
  return wrapper
}

const ids = (wrapper) => wrapper.vm.form.blocks.map(b => b.id)

describe('TemplateEditor — Unsaved-changes dialog', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('starts in a non-dirty state when seeded from a template prop', async () => {
    const wrapper = await mountEditor()
    expect(wrapper.vm.guard.isDirty.value).toBe(false)
    expect(wrapper.find('.unsaved-changes-dialog').exists()).toBe(false)
  })

  it('becomes dirty after the form is mutated', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.form.name = 'Renamed'
    await flushPromises()
    expect(wrapper.vm.guard.isDirty.value).toBe(true)
  })

  it('emits cancel directly when Abbrechen is clicked on a clean form', async () => {
    const wrapper = await mountEditor()
    await wrapper.find('.editor-header .btn-secondary').trigger('click')
    expect(wrapper.emitted('cancel')).toBeTruthy()
    expect(wrapper.emitted('cancel').length).toBe(1)
    expect(wrapper.find('.unsaved-changes-dialog').exists()).toBe(false)
  })

  it('shows the dialog when Abbrechen is clicked on a dirty form', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.form.name = 'Renamed'
    await flushPromises()

    await wrapper.find('.editor-header .btn-secondary').trigger('click')
    expect(wrapper.find('.unsaved-changes-dialog').exists()).toBe(true)
    expect(wrapper.emitted('cancel')).toBeFalsy()
  })

  it('Verwerfen in the dialog emits cancel and hides the dialog', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.form.name = 'Renamed'
    await flushPromises()
    await wrapper.find('.editor-header .btn-secondary').trigger('click')

    await wrapper.find('.unsaved-changes-dialog .btn-danger').trigger('click')
    expect(wrapper.emitted('cancel').length).toBe(1)
    expect(wrapper.find('.unsaved-changes-dialog').exists()).toBe(false)
  })

  it('Abbrechen in the dialog keeps the user in the editor and emits nothing', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.form.name = 'Renamed'
    await flushPromises()
    await wrapper.find('.editor-header .btn-secondary').trigger('click')

    await wrapper.find('.unsaved-changes-dialog .btn-secondary').trigger('click')
    expect(wrapper.emitted('cancel')).toBeFalsy()
    expect(wrapper.emitted('save')).toBeFalsy()
    expect(wrapper.find('.unsaved-changes-dialog').exists()).toBe(false)
  })

  it('Esc in the dialog acts like Abbrechen', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.form.name = 'Renamed'
    await flushPromises()
    await wrapper.find('.editor-header .btn-secondary').trigger('click')
    await flushPromises()

    const dialog = wrapper.find('.unsaved-changes-dialog')
    expect(document.activeElement).toBe(dialog.element)
    await dialog.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('.unsaved-changes-dialog').exists()).toBe(false)
    expect(wrapper.emitted('cancel')).toBeFalsy()
  })

  it('Speichern in the dialog emits save and hides the dialog', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.form.name = 'Renamed'
    await flushPromises()
    await wrapper.find('.editor-header .btn-secondary').trigger('click')

    await wrapper.find('.unsaved-changes-dialog .btn-primary').trigger('click')
    expect(wrapper.emitted('save').length).toBe(1)
    expect(wrapper.find('.unsaved-changes-dialog').exists()).toBe(false)
  })

  it('exposes requestClose() so parents can intercept the close path', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.form.name = 'Renamed'
    await flushPromises()

    wrapper.vm.requestClose()
    await flushPromises()
    expect(wrapper.find('.unsaved-changes-dialog').exists()).toBe(true)
    expect(wrapper.emitted('cancel')).toBeFalsy()
  })

  it('clears the dirty state after Verwerfen so a re-entered requestClose() does not re-prompt', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.form.name = 'Renamed'
    await flushPromises()
    await wrapper.find('.editor-header .btn-secondary').trigger('click')
    await wrapper.find('.unsaved-changes-dialog .btn-danger').trigger('click')
    expect(wrapper.vm.guard.isDirty.value).toBe(false)

    const secondAction = await wrapper.vm.requestClose()
    expect(secondAction).toBe('discard')
    expect(wrapper.find('.unsaved-changes-dialog').exists()).toBe(false)
  })

  it('seeds documentSpecific from the :template prop', async () => {
    const wrapper = await mountEditor({ template: { documentSpecific: true } })
    expect(wrapper.vm.form.documentSpecific).toBe(true)
  })

  it('emits documentSpecific in the save payload so the server preserves the flag', async () => {
    const wrapper = await mountEditor({ template: { documentSpecific: true } })
    wrapper.vm.form.name = 'Edited'
    await flushPromises()
    wrapper.vm.saveTemplate()
    expect(wrapper.emitted('save')[0][0].documentSpecific).toBe(true)
  })
})

describe('TemplateEditor — block model', () => {
  beforeEach(() => vi.clearAllMocks())

  it('loads blocks in stored order and saves order from the array position', async () => {
    const shuffled = validBlocks().reverse()
    const wrapper = await mountEditor({ template: { blocks: JSON.stringify(shuffled) } })
    expect(ids(wrapper)).toEqual(['h', 'r', 'm', 'l', 't', 'n'])

    wrapper.vm.moveBlockTo(0, 2)
    wrapper.vm.saveTemplate()
    const saved = JSON.parse(wrapper.emitted('save')[0][0].blocks)
    expect(saved.map(b => [b.id, b.order])).toEqual([['r', 0], ['m', 1], ['h', 2], ['l', 3], ['t', 4], ['n', 5]])
  })

  it('editing a block after moving it keeps the new position (audit bug: reorder was undone)', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.selectBlockById('n')
    wrapper.vm.moveBlockTo(5, 0)
    const edited = { ...wrapper.vm.selectedBlock, data: { content: 'Neu' } }
    wrapper.vm.updateBlock(edited)
    expect(ids(wrapper)[0]).toBe('n')
    expect(wrapper.vm.form.blocks[0].data.content).toBe('Neu')
  })

  it('adds a block at the requested position after earlier moves (audit bug: wrong drop index)', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.moveBlockTo(5, 0)
    wrapper.vm.addBlockAt('footer', 2)
    expect(wrapper.vm.form.blocks[2].type).toBe('footer')
    expect(wrapper.vm.selectedBlockId).toBe(wrapper.vm.form.blocks[2].id)
  })

  it('refuses blocks beyond their limit', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.addBlockAt('totals', 0)
    await flushPromises()
    expect(wrapper.vm.form.blocks.filter(b => b.type === 'totals')).toHaveLength(1)
    expect(wrapper.find('.toast').text()).toContain('bereits vorhanden')
  })

  it('a second address becomes the missing receiver/sender automatically', async () => {
    const wrapper = await mountEditor({ template: { blocks: validBlocks().filter(b => b.id !== 'r').concat([{ id: 's', type: 'address', data: { role: 'sender' }, config: {} }]) } })
    wrapper.vm.addBlockAt('address', 1)
    expect(wrapper.vm.form.blocks[1].data.role).toBe('receiver')
  })

  it('keeps a stored 0 mm margin (audit bug: || turned it into the default)', async () => {
    const wrapper = await mountEditor({ template: { marginTopMm: 0 } })
    expect(wrapper.vm.form.marginTopMm).toBe(0)
  })

  it('a new template starts with the default blocks and the document type title', async () => {
    const wrapper = mount(TemplateEditor, {
      props: { template: null },
      global: { stubs: { BlockConfigPanel: true, DesignPanel: true } }
    })
    await flushPromises()
    expect(wrapper.vm.validation.errors).toEqual([])
    expect(wrapper.vm.form.blocks.find(b => b.type === 'invoice-meta').data.invoiceTitle).toBe('Rechnung')
  })

  it('changing the document type renames an untouched title', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.changeDocumentType(2)
    expect(wrapper.vm.form.blocks.find(b => b.type === 'invoice-meta').data.invoiceTitle).toBe('Angebot')
  })
})

describe('TemplateEditor — validation and saving', () => {
  it('blocks saving while a required block is missing and explains why', async () => {
    const wrapper = await mountEditor({ template: { blocks: validBlocks().filter(b => b.type !== 'line-items') } })
    expect(wrapper.vm.canSave).toBe(false)
    expect(wrapper.find('.issues-bar .issue.error').text()).toContain('Positionen')
    wrapper.vm.saveTemplate()
    await flushPromises()
    expect(wrapper.emitted('save')).toBeFalsy()
    expect(wrapper.find('.toast').exists()).toBe(true)
  })

  it('warns about unknown variables without blocking the save', async () => {
    const blocks = validBlocks()
    blocks[5].data.content = 'Danke {{kundenname}}'
    const wrapper = await mountEditor({ template: { blocks } })
    expect(wrapper.find('.issues-bar .issue.warning').text()).toContain('kundenname')
    expect(wrapper.vm.canSave).toBe(true)
  })

  it('requires a name', async () => {
    const wrapper = await mountEditor({ template: { name: '   ' } })
    expect(wrapper.vm.canSave).toBe(false)
    expect(wrapper.vm.saveHint).toContain('Namen')
  })

  it('Strg+S saves', async () => {
    const wrapper = await mountEditor()
    await wrapper.find('.template-editor').trigger('keydown', { key: 's', ctrlKey: true })
    expect(wrapper.emitted('save')).toBeTruthy()
  })
})

describe('TemplateEditor — preview', () => {
  beforeEach(() => vi.clearAllMocks())

  it('starts in preview and switches between the mobile workspaces', async () => {
    const wrapper = await mountEditor()

    expect(wrapper.vm.mobileWorkspace).toBe('preview')
    expect(wrapper.find('.editor-body').classes()).toContain('mobile-preview')
    expect(wrapper.find('.mobile-workspace-tabs button[aria-selected="true"]').text()).toBe('Vorschau')

    await wrapper.find('.mobile-workspace-tabs button:nth-child(2)').trigger('click')
    expect(wrapper.vm.mobileWorkspace).toBe('design')
    expect(wrapper.find('.editor-body').classes()).toContain('mobile-design')

    await wrapper.find('.mobile-workspace-tabs button:nth-child(3)').trigger('click')
    expect(wrapper.vm.mobileWorkspace).toBe('design')

    wrapper.vm.selectBlockById('m')
    await flushPromises()
    expect(wrapper.vm.mobileWorkspace).toBe('settings')
    expect(wrapper.find('.editor-body').classes()).toContain('mobile-settings')

    wrapper.vm.deselectBlock()
    await flushPromises()
    expect(wrapper.vm.mobileWorkspace).toBe('preview')
  })

  it('uses the company data from the owner (audit bug: sample data overwrote it)', async () => {
    ownerService.getPrimaryOwner.mockResolvedValueOnce({ data: { companyName: 'ACME GmbH', street: 'Weg', houseNumber: 1, postalCode: '1', city: 'X' } })
    const wrapper = await mountEditor()
    expect(wrapper.vm.previewContext.variables.firma).toBe('ACME GmbH')
    expect(wrapper.find('.invoice-box').html()).toContain('ACME GmbH')
  })

  it('confines template CSS to the page (audit bug: it restyled the whole app)', async () => {
    const wrapper = await mountEditor({ template: { cssContent: 'table { color: red } body { margin: 0 }' } })
    const css = wrapper.vm.previewStyleTag
    expect(css).toContain('.template-editor .invoice-box table')
    // No rule may start with a bare selector (every rule follows `<style>` or `}`).
    expect(css).not.toMatch(/(<style>|\})\s*(table|body)\s*\{/)
  })

  it('shows the real document in live mode without saving its data', async () => {
    const liveData = {
      variables: { kunde: 'Bob Baumeister' },
      items: [{ description: 'Dachreparatur', quantity: 1, rate: 500, amount: 500, taxRate: 19 }],
      totals: { net: 500, gross: 595, groups: [{ ratePercent: 19, net: 500, tax: 95 }], chargesVat: true, legalNote: '' },
      invoiceId: 42
    }
    const wrapper = await mountEditor({ liveData })
    const html = wrapper.find('.invoice-box').html()
    expect(html).toContain('Bob Baumeister')
    expect(html).toContain('Dachreparatur')
    expect(html).toContain('595,00 €')

    wrapper.vm.saveTemplate()
    const saved = wrapper.emitted('save')[0][0].blocks
    expect(saved).not.toContain('Dachreparatur')
    expect(saved).not.toContain('Bob Baumeister')
  })

  it('hides document type and default flag when editing one document\'s layout', async () => {
    const wrapper = await mountEditor({ liveData: { variables: {}, items: [], totals: { groups: [], net: 0, gross: 0 } } })
    expect(wrapper.find('.type-select').exists()).toBe(false)
    expect(wrapper.find('.default-check').exists()).toBe(false)
    expect(wrapper.find('.doc-specific-badge').exists()).toBe(true)
  })

  it('PDF-Vorschau renders the draft on the server with the live document', async () => {
    URL.createObjectURL = vi.fn(() => 'blob:pdf')
    URL.revokeObjectURL = vi.fn()
    const wrapper = await mountEditor({ liveData: { variables: {}, items: [], totals: { groups: [], net: 0, gross: 0 }, invoiceId: 42 } })
    await wrapper.find('.btn-outline').trigger('click')
    await flushPromises()

    const [payload, invoiceId] = templateAPI.previewPdf.mock.calls[0]
    expect(invoiceId).toBe(42)
    expect(JSON.parse(payload.blocks)).toHaveLength(6)
    expect(wrapper.find('.pdf-frame').attributes('src')).toBe('blob:pdf#view=Fit')
  })
})

describe('TemplateEditor — editing comfort', () => {
  it('undo and redo restore earlier states', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.removeBlock('n')
    await flushPromises()
    expect(ids(wrapper)).not.toContain('n')
    wrapper.vm.undo()
    await flushPromises()
    expect(ids(wrapper)).toContain('n')
    wrapper.vm.redo()
    await flushPromises()
    expect(ids(wrapper)).not.toContain('n')
  })

  it('Delete removes the selected block, Strg+D duplicates a repeatable one', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.selectBlockById('n')
    await flushPromises()
    const root = wrapper.find('.template-editor')
    await root.trigger('keydown', { key: 'd', ctrlKey: true })
    expect(wrapper.vm.form.blocks.filter(b => b.type === 'notes')).toHaveLength(2)
    await root.trigger('keydown', { key: 'Delete' })
    expect(wrapper.vm.form.blocks.filter(b => b.type === 'notes')).toHaveLength(1)
  })

  it('clicking a block in the preview selects it and shows its toolbar', async () => {
    const wrapper = await mountEditor()
    await wrapper.findAll('.preview-block-wrapper')[2].trigger('click')
    expect(wrapper.vm.selectedBlockId).toBe('m')
    expect(wrapper.findAll('.preview-block-wrapper')[2].find('.block-actions').exists()).toBe(true)
  })

  it('renders two half-width blocks side by side in one row', async () => {
    const blocks = validBlocks()
    blocks[1].config = { width: 'half' }
    blocks[2].config = { width: 'half' }
    const wrapper = await mountEditor({ template: { blocks } })
    const halfRows = wrapper.findAll('.preview-row.half')
    expect(halfRows).toHaveLength(1)
    expect(halfRows[0].findAll('.preview-block-wrapper')).toHaveLength(2)
  })

  it('zoom stays on clean steps within bounds', async () => {
    const wrapper = await mountEditor()
    wrapper.vm.zoom = 0.7
    for (let i = 0; i < 20; i++) wrapper.vm.zoomBy(0.1)
    expect(wrapper.vm.zoom).toBe(1.5)
    wrapper.vm.zoomBy(-0.1)
    expect(wrapper.vm.zoom).toBe(1.4)
  })
})

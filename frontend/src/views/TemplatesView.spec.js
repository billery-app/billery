import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const template = {
  id: 4,
  name: 'Rechnung/Modern',
  documentTypeId: 1,
  isDefault: true,
  blocks: JSON.stringify([
    { id: 'f', type: 'footer', order: 0, data: { text: 'Danke!', website: '{{website}}' }, config: {} }
  ])
}

vi.mock('../services/templateService.js', () => ({
  default: { getTemplates: vi.fn(() => Promise.resolve({ data: [template] })) }
}))
vi.mock('../services/documentService.js', () => ({
  default: { exportDocument: vi.fn(() => Promise.resolve({ data: new Blob(['x']) })) }
}))
vi.mock('../utils/fileValidation.js', () => ({ downloadBlob: vi.fn() }))

import documentAPI from '../services/documentService.js'
import { downloadBlob } from '../utils/fileValidation.js'
import TemplatesView from './TemplatesView.vue'

describe('TemplatesView export', () => {
  it('exports a template as DOCX with one section per block and a safe file name', async () => {
    const wrapper = mount(TemplatesView, {
      global: { stubs: { TemplateThumbnail: true, TemplateEditor: true, TemplateStartModal: true } }
    })
    await flushPromises()

    await wrapper.findAll('button').find(b => b.text() === 'DOCX').trigger('click')
    await flushPromises()

    expect(documentAPI.exportDocument).toHaveBeenCalledWith({
      name: 'Rechnung/Modern',
      format: 'docx',
      sections: [{ heading: 'Fußzeile', paragraphs: ['Text: Danke!', 'Website: {{website}}'] }]
    })
    expect(downloadBlob).toHaveBeenCalledWith(expect.any(Blob), 'Rechnung Modern.docx')
  })
})

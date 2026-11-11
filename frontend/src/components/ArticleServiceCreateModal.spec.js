import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

vi.mock('../services/articleService.js', () => ({
  default: {
    createArticle: vi.fn(),
    updateArticle: vi.fn(),
    createService: vi.fn(),
    updateService: vi.fn()
  }
}))
vi.mock('../services/equipmentService.js', () => ({
  default: { getAll: vi.fn().mockResolvedValue({ data: [] }) }
}))

import articleAPI from '../services/articleService.js'
import ArticleServiceCreateModal from './ArticleServiceCreateModal.vue'

/** Mounts the modal hidden, then opens it so the visibility watcher initialises the form. */
async function open(props = {}) {
  const wrapper = mount(ArticleServiceCreateModal, { props: { visible: false, ...props } })
  await wrapper.setProps({ visible: true })
  return wrapper
}

describe('ArticleServiceCreateModal – article note', () => {
  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('sends the internal note when creating an article', async () => {
    articleAPI.createArticle.mockResolvedValue({ data: { id: 1 } })
    const wrapper = await open()

    await wrapper.find('input[required]').setValue('Kabel')
    await wrapper.find('[data-testid="article-note"]').setValue('Lieferant: Müller')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(articleAPI.createArticle).toHaveBeenCalledWith(
      expect.objectContaining({ name: 'Kabel', note: 'Lieferant: Müller' })
    )
  })

  it('pre-fills the stored note when editing and sends the edited one', async () => {
    articleAPI.updateArticle.mockResolvedValue({ data: { id: 7 } })
    const wrapper = await open({
      item: { id: 7, name: 'Kabel', salesPrice: 4.99, taxRate: 19, note: 'alt' },
      itemType: 'article'
    })

    const note = wrapper.find('[data-testid="article-note"]')
    expect(note.element.value).toBe('alt')

    await note.setValue('neu')
    await wrapper.find('form').trigger('submit')
    await flushPromises()

    expect(articleAPI.updateArticle).toHaveBeenCalledWith(7, expect.objectContaining({ note: 'neu' }))
  })

  it('starts with an empty note for a new article', async () => {
    const wrapper = await open()
    expect(wrapper.find('[data-testid="article-note"]').element.value).toBe('')
  })
})

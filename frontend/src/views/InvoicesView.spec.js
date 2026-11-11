import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import InvoicesView from './InvoicesView.vue'
import invoiceAPI from '../services/invoiceService.js'

function pageOf(items, { total = items.length, page = 0, size = 50, years = [2026] } = {}) {
  return Promise.resolve({ data: { items, page, size, total, years } })
}

vi.mock('../services/invoiceService.js', () => ({
  default: {
    getInvoicePage: vi.fn(),
    getInvoice: vi.fn()
  }
}))

beforeEach(() => {
  vi.clearAllMocks()
  invoiceAPI.getInvoicePage.mockImplementation(() => pageOf([]))
})
vi.mock('../services/customerService.js', () => ({
  default: {}
}))
vi.mock('../services/articleService.js', () => ({
  default: {
    getArticles: vi.fn(() => Promise.resolve({ data: [] })),
    getServices: vi.fn(() => Promise.resolve({ data: [] }))
  }
}))

async function mountInvoicesView(url = '/invoices') {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/invoices', component: InvoicesView }]
  })
  await router.push(url)
  await router.isReady()

  const wrapper = mount(InvoicesView, {
    global: {
      plugins: [router],
      stubs: {
        DocumentModal: true,
        DocumentViewModal: true,
        ConvertModal: true
      }
    }
  })
  await flushPromises()
  return wrapper
}

describe('InvoicesView document-type navigation', () => {
  it('offers a labeled native selector alongside the desktop button group', async () => {
    const wrapper = await mountInvoicesView()

    const selector = wrapper.find('[data-testid="mobile-document-type"]')
    expect(selector.exists()).toBe(true)
    expect(wrapper.find('label[for="mobile-document-type"]').text()).toContain('Dokumenttyp')
    expect(selector.findAll('option').map(option => option.element.value)).toEqual([
      'invoice',
      'offer',
      'costCalculation'
    ])

    const desktopGroup = wrapper.find('.doc-type-tabs')
    expect(desktopGroup.attributes('role')).toBe('group')
    expect(desktopGroup.attributes('aria-label')).toBe('Dokumenttyp')
    expect(desktopGroup.findAll('button')[0].attributes('aria-pressed')).toBe('true')

    wrapper.unmount()
  })

  it('switches document type from the selector and keeps the phone heading concise', async () => {
    const wrapper = await mountInvoicesView()
    const selector = wrapper.find('[data-testid="mobile-document-type"]')

    await selector.setValue('costCalculation')

    expect(wrapper.find('.page-heading-mobile').text()).toBe('Dokumente')
    expect(wrapper.find('h1.nav-destination-page-title').exists()).toBe(true)
    expect(wrapper.find('.page-heading-desktop').text()).toBe('Kostenvoranschlagverwaltung')
    expect(wrapper.find('.filters .btn-primary').text()).toBe('Neuer Kostenvoranschlag')
    expect(wrapper.findAll('.doc-type-tabs button')[2].attributes('aria-pressed')).toBe('true')

    wrapper.unmount()
  })

  it('opens a document from a keyboard-accessible row with its available actions', async () => {
    const draft = {
      id: 41,
      invoiceNumber: null,
      customerName: 'Beispielkunde',
      invoiceDate: '2026-04-01',
      documentTypeId: 1,
      documentStatusId: 1,
      netAmount: 100,
      grossAmount: 119
    }
    invoiceAPI.getInvoicePage.mockImplementation(() => pageOf([draft]))
    const wrapper = await mountInvoicesView()

    const row = wrapper.find('tbody tr')
    expect(row.attributes('tabindex')).toBe('0')
    expect(row.attributes('aria-label')).toContain('Aktionen öffnen')
    expect(wrapper.find('thead').text()).not.toContain('Aktionen')

    await row.trigger('keydown', { key: 'Enter' })

    expect(wrapper.vm.showInvoiceModal).toBe(true)
    expect(wrapper.vm.selectedInvoiceId).toBe(draft.id)
    expect(wrapper.vm.selectedInvoiceActions).toMatchObject({ edit: true, delete: true, archive: false })

    wrapper.unmount()
  })
})

describe('InvoicesView server-side paging', () => {
  const lastQuery = () => invoiceAPI.getInvoicePage.mock.calls.at(-1)[0]

  it('loads the first page of the invoice tab with the current year', async () => {
    const wrapper = await mountInvoicesView()

    expect(lastQuery()).toMatchObject({
      page: 0, size: 50, types: [1, 6], archived: false, year: new Date().getFullYear(), q: '', status: ''
    })
    wrapper.unmount()
  })

  it('shows the pager only when there is more than one page and fetches the next page', async () => {
    const items = Array.from({ length: 50 }, (_, i) => ({ id: i + 1, documentTypeId: 1, documentStatusId: 2 }))
    invoiceAPI.getInvoicePage.mockImplementation(({ page }) => pageOf(items, { total: 120, page }))
    const wrapper = await mountInvoicesView()

    expect(wrapper.find('[data-testid="pager-info"]').text()).toBe('Seite 1 von 3 · 120 Dokumente')
    await wrapper.findAll('[data-testid="pager"] button')[1].trigger('click')
    await flushPromises()

    expect(lastQuery().page).toBe(1)
    expect(wrapper.find('[data-testid="pager-info"]').text()).toContain('Seite 2 von 3')
    wrapper.unmount()
  })

  it('starts again at page 1 with the new filters when tab, archive or status change', async () => {
    invoiceAPI.getInvoicePage.mockImplementation(({ page }) => pageOf([{ id: 1, documentTypeId: 2 }], { total: 120, page }))
    const wrapper = await mountInvoicesView()
    await wrapper.setData({ page: 2 })

    await wrapper.find('[data-testid="mobile-document-type"]').setValue('offer')
    await flushPromises()
    expect(lastQuery()).toMatchObject({ page: 0, types: [2], status: '' })

    await wrapper.findAll('.tabs .tab-btn')[1].trigger('click')
    await flushPromises()
    expect(lastQuery()).toMatchObject({ page: 0, archived: true })

    await wrapper.find('.filter-select').setValue('expired')
    await flushPromises()
    expect(lastQuery()).toMatchObject({ page: 0, status: 'expired' })
    wrapper.unmount()
  })

  it('searches on the server after a short pause, not on every keystroke', async () => {
    vi.useFakeTimers()
    try {
      const wrapper = await mountInvoicesView()
      const before = invoiceAPI.getInvoicePage.mock.calls.length

      await wrapper.find('.search-input').setValue('Mus')
      await wrapper.find('.search-input').setValue('Muster')
      expect(invoiceAPI.getInvoicePage.mock.calls.length).toBe(before)

      vi.advanceTimersByTime(300)
      await flushPromises()
      expect(invoiceAPI.getInvoicePage.mock.calls.length).toBe(before + 1)
      expect(lastQuery()).toMatchObject({ page: 0, q: 'Muster' })
      wrapper.unmount()
    } finally {
      vi.useRealTimers()
    }
  })

  it('ignores a page that arrives after a newer request', async () => {
    let releaseOld
    const wrapper = await mountInvoicesView()
    invoiceAPI.getInvoicePage
      .mockImplementationOnce(() => new Promise(resolve => { releaseOld = resolve }))
      .mockImplementationOnce(() => pageOf([{ id: 2, invoiceNumber: 'NEU', documentTypeId: 1 }]))

    wrapper.vm.loadInvoices()
    await wrapper.vm.loadInvoices()
    releaseOld({ data: { items: [{ id: 1, invoiceNumber: 'ALT', documentTypeId: 1 }], page: 0, size: 50, total: 1, years: [] } })
    await flushPromises()

    expect(wrapper.find('tbody').text()).toContain('NEU')
    expect(wrapper.find('tbody').text()).not.toContain('ALT')
    wrapper.unmount()
  })

  it('falls back to the last page when the current one became empty', async () => {
    const wrapper = await mountInvoicesView()
    invoiceAPI.getInvoicePage.mockImplementation(({ page }) =>
      page === 2 ? pageOf([], { total: 60, page }) : pageOf([{ id: 7, documentTypeId: 1 }], { total: 60, page }))

    await wrapper.setData({ page: 2 })
    await wrapper.vm.loadInvoices()
    await flushPromises()

    expect(wrapper.vm.page).toBe(1)
    expect(lastQuery().page).toBe(1)
    expect(wrapper.findAll('tbody tr')).toHaveLength(1)
    wrapper.unmount()
  })

  it('offers the years the backend reports', async () => {
    invoiceAPI.getInvoicePage.mockImplementation(() => pageOf([], { years: [2024, 2023] }))
    const wrapper = await mountInvoicesView()

    const years = wrapper.findAll('.year-select option').map(o => o.element.value).filter(Boolean)
    expect(years).toEqual([String(new Date().getFullYear()), '2024', '2023'])
    wrapper.unmount()
  })
})

describe('InvoicesView status filter from a link', () => {
  const lastQuery = () => invoiceAPI.getInvoicePage.mock.calls.at(-1)[0]

  it('takes "open" from the dashboard link', async () => {
    const wrapper = await mountInvoicesView('/invoices?filter=open')

    expect(lastQuery().status).toBe('open')
    expect(wrapper.find('.filter-select').element.value).toBe('open')
    wrapper.unmount()
  })

  it('ignores an unknown key from an old link instead of sending it to the backend', async () => {
    const wrapper = await mountInvoicesView('/invoices?filter=unpaid')

    expect(lastQuery().status).toBe('')
    expect(invoiceAPI.getInvoicePage.mock.calls.every(([q]) => q.status !== 'unpaid')).toBe(true)
    wrapper.unmount()
  })
})

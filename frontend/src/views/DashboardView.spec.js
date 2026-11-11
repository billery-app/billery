import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import DashboardView from './DashboardView.vue'
import invoiceAPI from '../services/invoiceService.js'

vi.mock('../services/invoiceService.js', () => ({
  default: {
    getStatistics: vi.fn(),
    getInvoicePage: vi.fn()
  }
}))

const STATS = {
  paidInvoiceCount: 6, unpaidInvoiceCount: 5,
  paidGross: 950.88, unpaidGross: 1742.29, totalGross: 1938.24,
  paidTaxAmount: 151.82, unpaidTaxAmount: 278.18,
  issuedGross: 2693.17, issuedTaxAmount: 430.00
}

function invoices(n) {
  return Array.from({ length: n }, (_, i) => ({ id: i + 1, invoiceNumber: `RE-2026-00${i + 1}`, customerName: 'Max Kunde', dueDate: '2099-01-01', grossAmount: 100, documentTypeId: 1, documentStatusId: 2 }))
}

beforeEach(() => {
  vi.clearAllMocks()
  invoiceAPI.getStatistics.mockResolvedValue({ data: STATS })
  invoiceAPI.getInvoicePage.mockResolvedValue({ data: { items: invoices(5), page: 0, size: 5, total: 11, years: [] } })
})

function iso(date) {
  const pad = n => String(n).padStart(2, '0')
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}
vi.mock('../services/customerService.js', () => ({
  default: {}
}))
vi.mock('../services/articleService.js', () => ({
  default: {
    getArticles: vi.fn(() => Promise.resolve({ data: [] })),
    getServices: vi.fn(() => Promise.resolve({ data: [] }))
  }
}))

async function mountDashboard() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/', component: DashboardView }]
  })
  await router.push('/')
  await router.isReady()

  const wrapper = mount(DashboardView, {
    global: {
      plugins: [router],
      stubs: {
        ArticleServiceCreateModal: true,
        CustomerCreateModal: true,
        DocumentModal: true,
        DocumentViewModal: true
      }
    }
  })
  await flushPromises()
  return wrapper
}

describe('Dashboard revenue summary', () => {
  it('keeps revenue in period statistics without duplicating it as a top card', async () => {
    const wrapper = await mountDashboard()

    expect(wrapper.find('.stats-grid').text()).not.toContain('Gesamt Umsatz')
    expect(wrapper.findAll('.stats-grid .stat-card')).toHaveLength(3)
    expect(wrapper.find('.statistics-grid').text()).toContain('Gesamt Umsatz:')

    wrapper.unmount()
  })
})

describe('Dashboard loads figures from the server, not the full document list', () => {
  const lastPageQuery = () => invoiceAPI.getInvoicePage.mock.calls.at(-1)[0]

  it('asks for the statistics and the first five issued invoices of the current year, archive included', async () => {
    const wrapper = await mountDashboard()
    const now = new Date()

    expect(invoiceAPI.getStatistics).toHaveBeenCalledWith(`${now.getFullYear()}-01-01`, iso(now))
    expect(lastPageQuery()).toMatchObject({
      page: 0, size: 5, types: [1, 4, 5], includeArchived: true,
      from: `${now.getFullYear()}-01-01`, to: iso(now), status: 'issued'
    })
    wrapper.unmount()
  })

  it('shows the counts and gross amounts of the backend; total is paid plus open', async () => {
    const wrapper = await mountDashboard()
    const cards = wrapper.findAll('.stats-grid .stat-value').map(c => c.text())

    expect(cards).toEqual(['11', '6', '5'])
    expect(wrapper.find('.statistics-grid').text()).toContain('2.693,17')
    expect(wrapper.find('.statistics-grid').text()).toContain('430,00')
    expect(wrapper.find('.summary-amount').text()).toContain('2.693,17')
    expect(wrapper.find('[data-testid="list-total"]').text()).toBe('11')
    expect(wrapper.findAll('tbody tr')).toHaveLength(5)
    expect(wrapper.find('.widget-footer').exists()).toBe(true)
    wrapper.unmount()
  })

  it('lists the open invoices (sent or partly paid) for the unpaid card and links the list with "open"', async () => {
    const wrapper = await mountDashboard()

    await wrapper.find('.stat-card.unpaid').trigger('click')
    await flushPromises()

    expect(lastPageQuery().status).toBe('open')
    expect(wrapper.find('.summary-amount').text()).toContain('1.742,29')
    expect(wrapper.find('.widget-footer a').attributes('href')).toContain('filter=open')
    expect(invoiceAPI.getStatistics).toHaveBeenCalledTimes(1)
    wrapper.unmount()
  })

  it('shows the totals the server summed and never adds money in the browser', async () => {
    // Deliberately inconsistent: only a browser-side addition would show 3,00 €.
    invoiceAPI.getStatistics.mockResolvedValue({ data: { ...STATS, paidGross: 1, unpaidGross: 2, issuedGross: 1000, paidTaxAmount: 1, unpaidTaxAmount: 2, issuedTaxAmount: 500 } })
    const wrapper = await mountDashboard()

    const text = wrapper.find('.statistics-grid').text()
    expect(text).toContain('1.000,00')
    expect(text).toContain('500,00')
    expect(text).not.toContain('3,00')
    wrapper.unmount()
  })

  it('reloads both for another period', async () => {
    const wrapper = await mountDashboard()
    const now = new Date()

    await wrapper.findAll('.period-buttons button').find(b => b.text() === 'Letztes Jahr').trigger('click')
    await flushPromises()

    const year = now.getFullYear() - 1
    expect(invoiceAPI.getStatistics).toHaveBeenLastCalledWith(`${year}-01-01`, `${year}-12-31`)
    expect(lastPageQuery()).toMatchObject({ from: `${year}-01-01`, to: `${year}-12-31` })
    wrapper.unmount()
  })

  it('shows a readable error instead of empty figures when the statistics fail', async () => {
    invoiceAPI.getStatistics.mockRejectedValue(new Error('Zeitraum zu lang'))
    const wrapper = await mountDashboard()

    expect(wrapper.find('[data-testid="load-error"]').text()).toContain('Zeitraum zu lang')
    wrapper.unmount()
  })
})

import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import CustomersView from './CustomersView.vue'
import customerAPI from '../services/customerService.js'

vi.mock('../services/customerService.js', () => ({
  default: { getCustomerPage: vi.fn(), getCustomer: vi.fn(), deleteCustomer: vi.fn() }
}))

function customers(n, offset = 0) {
  return Array.from({ length: n }, (_, i) => ({ id: offset + i + 1, firstName: 'Kunde', lastName: String(offset + i + 1) }))
}

async function mountView() {
  const router = createRouter({ history: createMemoryHistory(), routes: [{ path: '/customers', component: CustomersView }] })
  await router.push('/customers')
  await router.isReady()
  const wrapper = mount(CustomersView, { global: { plugins: [router], stubs: { CustomerCreateModal: true } } })
  await flushPromises()
  return wrapper
}

describe('CustomersView server-side paging', () => {
  const lastQuery = () => customerAPI.getCustomerPage.mock.calls.at(-1)[0]

  beforeEach(() => {
    vi.clearAllMocks()
    customerAPI.getCustomerPage.mockImplementation(({ page }) =>
      Promise.resolve({ data: { items: customers(page === 2 ? 20 : 50, page * 50), page, size: 50, total: 120 } }))
  })
  afterEach(() => vi.useRealTimers())

  it('loads the first page instead of every customer', async () => {
    const wrapper = await mountView()

    expect(lastQuery()).toEqual({ page: 0, size: 50, q: '' })
    expect(wrapper.findAll('tbody tr')).toHaveLength(50)
    expect(wrapper.find('[data-testid="pager-info"]').text()).toBe('Seite 1 von 3 · 120 Kunden')
  })

  it('pages forward', async () => {
    const wrapper = await mountView()

    await wrapper.findAll('[data-testid="pager"] button')[1].trigger('click')
    await flushPromises()

    expect(lastQuery().page).toBe(1)
    expect(wrapper.find('tbody tr td').text()).toBe('51')
  })

  it('searches on the server after a pause and starts again at page 1', async () => {
    vi.useFakeTimers()
    const wrapper = await mountView()
    await wrapper.setData({ page: 2 })
    const before = customerAPI.getCustomerPage.mock.calls.length

    await wrapper.find('.search-input').setValue('Mus')
    await wrapper.find('.search-input').setValue('Muster')
    expect(customerAPI.getCustomerPage.mock.calls.length).toBe(before)
    vi.advanceTimersByTime(300)
    await flushPromises()

    expect(lastQuery()).toEqual({ page: 0, size: 50, q: 'Muster' })
  })
})

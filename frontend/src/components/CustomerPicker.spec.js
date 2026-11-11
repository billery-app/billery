import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import CustomerPicker from './CustomerPicker.vue'
import customerAPI from '../services/customerService.js'

vi.mock('../services/customerService.js', () => ({
  default: { getCustomerPage: vi.fn(), getCustomer: vi.fn() }
}))

const MAX = { id: 1, firstName: 'Max', lastName: 'Zimmer', postalCode: '10115', city: 'Berlin' }
const ERIKA = { id: 2, firstName: 'Erika', lastName: 'Muster', postalCode: '50667', city: 'Köln' }

function page(items, total = items.length) {
  return Promise.resolve({ data: { items, page: 0, size: 20, total } })
}

async function mountPicker(props = {}) {
  const wrapper = mount(CustomerPicker, { props: { modelValue: '', ...props }, attachTo: document.body })
  await flushPromises()
  return wrapper
}

describe('CustomerPicker', () => {
  beforeEach(() => {
    vi.useFakeTimers()
    vi.clearAllMocks()
    customerAPI.getCustomerPage.mockImplementation(() => page([ERIKA, MAX], 42))
  })
  afterEach(() => {
    vi.useRealTimers()
    document.body.innerHTML = ''
  })

  it('lists the first customers alphabetically on focus, with a hint at the rest', async () => {
    const wrapper = await mountPicker()

    await wrapper.find('input').trigger('focus')
    await flushPromises()

    expect(customerAPI.getCustomerPage).toHaveBeenCalledWith({ page: 0, size: 20, q: '', sort: 'name' })
    const options = wrapper.findAll('[role="option"]')
    expect(options.map(o => o.find('.name').text())).toEqual(['Erika Muster', 'Max Zimmer'])
    expect(wrapper.find('[role="listbox"]').text()).toContain('40 weitere')
    expect(wrapper.find('input').attributes('aria-expanded')).toBe('true')
  })

  it('searches on the server after a pause, not on every keystroke', async () => {
    const wrapper = await mountPicker()
    await wrapper.find('input').trigger('focus')
    await flushPromises()
    const before = customerAPI.getCustomerPage.mock.calls.length

    await wrapper.find('input').setValue('Mu')
    await wrapper.find('input').setValue('Mus')
    expect(customerAPI.getCustomerPage.mock.calls.length).toBe(before)

    vi.advanceTimersByTime(250)
    await flushPromises()
    expect(customerAPI.getCustomerPage).toHaveBeenLastCalledWith({ page: 0, size: 20, q: 'Mus', sort: 'name' })
  })

  it('selects with the keyboard, emits the id and the customer, and shows the name', async () => {
    const wrapper = await mountPicker()
    const input = wrapper.find('input')
    await input.trigger('focus')
    await flushPromises()

    await input.trigger('keydown', { key: 'ArrowDown' })
    expect(input.attributes('aria-activedescendant')).toBe(wrapper.findAll('[role="option"]')[1].attributes('id'))
    const enter = new KeyboardEvent('keydown', { key: 'Enter', cancelable: true, bubbles: true })
    input.element.dispatchEvent(enter)
    await flushPromises()

    expect(enter.defaultPrevented).toBe(true)
    expect(wrapper.emitted('update:modelValue')).toEqual([[1]])
    expect(wrapper.emitted('update:customer')[0][0]).toEqual(MAX)
    expect(wrapper.find('[role="listbox"]').exists()).toBe(false)
    expect(input.element.value).toBe('Max Zimmer')
  })

  it('selects with the mouse', async () => {
    const wrapper = await mountPicker()
    await wrapper.find('input').trigger('focus')
    await flushPromises()

    await wrapper.findAll('[role="option"]')[0].trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([[2]])
  })

  it('loads and shows the customer of an existing document from its id', async () => {
    customerAPI.getCustomer.mockResolvedValue({ data: ERIKA })

    const wrapper = await mountPicker({ modelValue: 2 })

    expect(customerAPI.getCustomer).toHaveBeenCalledWith(2)
    expect(wrapper.find('input').element.value).toBe('Erika Muster')
    expect(customerAPI.getCustomerPage).not.toHaveBeenCalled()
  })

  it('restores the selected name when closed with Escape or by leaving the field', async () => {
    const wrapper = await mountPicker({ modelValue: 1, customer: MAX })
    const input = wrapper.find('input')
    await input.trigger('focus')
    await input.setValue('xyz')

    await input.trigger('keydown', { key: 'Escape' })
    expect(input.element.value).toBe('Max Zimmer')
    expect(wrapper.emitted('update:modelValue')).toBeUndefined()

    await input.trigger('focus')
    await input.setValue('abc')
    await input.trigger('blur')
    expect(input.element.value).toBe('Max Zimmer')
  })

  it('clears the selection with its button', async () => {
    const wrapper = await mountPicker({ modelValue: 1, customer: MAX })

    await wrapper.find('[data-testid="customer-picker-clear"]').trigger('click')

    expect(wrapper.emitted('update:modelValue')).toEqual([['']])
    expect(wrapper.emitted('update:customer')).toEqual([[null]])
  })

  it('ignores a result that arrives after a newer search', async () => {
    let releaseOld
    customerAPI.getCustomerPage
      .mockImplementationOnce(() => new Promise(resolve => { releaseOld = resolve }))
      .mockImplementationOnce(() => page([ERIKA]))
    const wrapper = await mountPicker()
    await wrapper.find('input').trigger('focus')
    await wrapper.find('input').setValue('Eri')
    vi.advanceTimersByTime(250)
    await flushPromises()

    releaseOld({ data: { items: [MAX], page: 0, size: 20, total: 1 } })
    await flushPromises()

    expect(wrapper.findAll('[role="option"]').map(o => o.find('.name').text())).toEqual(['Erika Muster'])
  })

  it('says so when nothing matches', async () => {
    customerAPI.getCustomerPage.mockImplementation(() => page([]))
    const wrapper = await mountPicker()

    await wrapper.find('input').trigger('focus')
    await flushPromises()

    expect(wrapper.find('[role="listbox"]').text()).toContain('Kein Kunde gefunden')
  })
})

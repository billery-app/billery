import { describe, it, expect, vi } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('../services/customerService.js', () => ({
  default: { createCustomer: vi.fn(), updateCustomer: vi.fn() }
}))

import CustomerCreateModal from './CustomerCreateModal.vue'

describe('CustomerCreateModal autofill', () => {
  it('names the address fields with autocomplete tokens and splits street and number', async () => {
    const wrapper = mount(CustomerCreateModal, { props: { visible: true } })
    const token = id => wrapper.find(`#customer-${id}`).attributes('autocomplete')
    expect(token('firstName')).toBe('given-name')
    expect(token('street')).toBe('address-line1')
    expect(token('postalCode')).toBe('postal-code')
    expect(token('email')).toBe('email')
    expect(wrapper.find('#customer-phone').attributes('type')).toBe('tel')

    const street = wrapper.find('#customer-street')
    await street.setValue('Am Hang 3')
    await street.trigger('change')
    expect(street.element.value).toBe('Am Hang')
    expect(wrapper.find('#customer-houseNumber').element.value).toBe('3')
  })
})

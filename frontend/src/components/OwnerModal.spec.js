import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount } from '@vue/test-utils'

vi.mock('../services/ownerService.js', () => ({
  default: {
    createOwner: vi.fn(() => Promise.resolve({ data: {} })),
    updateOwner: vi.fn((id, owner) => Promise.resolve({ data: owner }))
  }
}))

import ownerAPI from '../services/ownerService.js'
import OwnerModal from './OwnerModal.vue'

/** The owner as GET /api/owners returns it: the flag is called smallBusiness. */
const fromApi = { id: 3, firstName: 'Max', lastName: 'Muster', companyName: 'Muster GmbH', houseNumber: 1, smallBusiness: true }

describe('OwnerModal', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows the Kleinunternehmer flag of an existing owner and sends it back under one name', async () => {
    const wrapper = mount(OwnerModal, { props: { visible: false, owner: fromApi } })
    await wrapper.setProps({ visible: true })

    expect(wrapper.find('input[type="checkbox"]').element.checked).toBe(true)
    await wrapper.find('form').trigger('submit')
    await Promise.resolve()

    const sent = ownerAPI.updateOwner.mock.calls[0][1]
    expect(sent.isSmallBusiness).toBe(true)
    expect(sent).not.toHaveProperty('smallBusiness')
  })
})

describe('OwnerModal autofill', () => {
  it('names its fields with the standard autocomplete tokens and links every label', async () => {
    const wrapper = mount(OwnerModal, { props: { visible: false, owner: null } })
    await wrapper.setProps({ visible: true })
    const token = id => wrapper.find(`#owner-${id}`).attributes('autocomplete')

    expect(token('firstName')).toBe('given-name')
    expect(token('lastName')).toBe('family-name')
    expect(token('companyName')).toBe('organization')
    expect(token('street')).toBe('address-line1')
    expect(token('postalCode')).toBe('postal-code')
    expect(token('city')).toBe('address-level2')
    expect(token('phone')).toBe('tel')
    expect(token('email')).toBe('email')
    expect(token('url')).toBe('url')
    for (const label of wrapper.findAll('label[for]')) {
      expect(wrapper.find(`#${label.attributes('for')}`).exists()).toBe(true)
    }
  })

  it('moves the house number out of an autofilled street', async () => {
    const wrapper = mount(OwnerModal, { props: { visible: false, owner: null } })
    await wrapper.setProps({ visible: true })
    const street = wrapper.find('#owner-street')
    await street.setValue('Musterstraße 12')
    await street.trigger('change')

    expect(street.element.value).toBe('Musterstraße')
    expect(wrapper.find('#owner-houseNumber').element.value).toBe('12')
  })
})

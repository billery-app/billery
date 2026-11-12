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

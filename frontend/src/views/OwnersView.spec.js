import { describe, it, expect, vi } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'

const owners = vi.hoisted(() => ({ list: [] }))
vi.mock('../services/ownerService.js', () => ({
  default: { getOwners: vi.fn(() => Promise.resolve({ data: owners.list })) }
}))

import OwnersView from './OwnersView.vue'

const newButton = wrapper => wrapper.findAll('button').filter(b => b.text() === 'Neuer Inhaber')

describe('OwnersView', () => {
  it('offers "Neuer Inhaber" only while there is no owner (one per company)', async () => {
    owners.list = []
    const empty = mount(OwnersView, { global: { stubs: { OwnerModal: true } } })
    await flushPromises()
    expect(newButton(empty)).toHaveLength(1)

    owners.list = [{ id: 1, firstName: 'Max', lastName: 'Muster', companyName: 'Muster GmbH' }]
    const withOwner = mount(OwnersView, { global: { stubs: { OwnerModal: true } } })
    await flushPromises()
    expect(newButton(withOwner)).toHaveLength(0)
  })
})

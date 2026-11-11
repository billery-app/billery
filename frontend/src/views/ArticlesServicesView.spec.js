import { flushPromises, mount } from '@vue/test-utils'
import { createMemoryHistory, createRouter } from 'vue-router'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import ArticlesServicesView from './ArticlesServicesView.vue'
import equipmentAPI from '../services/equipmentService.js'

vi.mock('../services/articleService.js', () => ({
  default: {
    getArticles: vi.fn(() => Promise.resolve({ data: [] })),
    getServices: vi.fn(() => Promise.resolve({ data: [] }))
  }
}))
vi.mock('../services/equipmentService.js', () => ({
  default: {
    getAll: vi.fn(() => Promise.resolve({ data: [] })),
    getSummary: vi.fn(() => Promise.resolve({ data: [] })),
    getUsage: vi.fn(() => Promise.resolve({ data: [] }))
  }
}))

async function mountArticlesServicesView() {
  const router = createRouter({
    history: createMemoryHistory(),
    routes: [{ path: '/articles-services', component: ArticlesServicesView }]
  })
  await router.push('/articles-services')
  await router.isReady()

  const wrapper = mount(ArticlesServicesView, {
    attachTo: document.body,
    global: {
      plugins: [router],
      stubs: { ArticleServiceCreateModal: true }
    }
  })
  await flushPromises()
  return wrapper
}

describe('ArticlesServicesView mobile section navigation', () => {
  beforeEach(() => vi.clearAllMocks())

  it('offers a labeled native selector alongside the desktop button group', async () => {
    const wrapper = await mountArticlesServicesView()
    const selector = wrapper.find('[data-testid="mobile-catalog-section"]')

    expect(selector.exists()).toBe(true)
    expect(wrapper.find('label[for="mobile-catalog-section"]').text()).toContain('Katalogbereich')
    expect(selector.findAll('option').map(option => option.element.value)).toEqual([
      'articles',
      'services',
      'equipment'
    ])

    const desktopGroup = wrapper.find('.catalog-section-tabs')
    expect(desktopGroup.attributes('role')).toBe('group')
    expect(desktopGroup.attributes('aria-label')).toBe('Katalogbereich')
    expect(desktopGroup.findAll('button')[0].attributes('aria-pressed')).toBe('true')

    wrapper.unmount()
  })

  it('switches catalog sections from the selector', async () => {
    const wrapper = await mountArticlesServicesView()
    const selector = wrapper.find('[data-testid="mobile-catalog-section"]')

    await selector.setValue('services')
    await flushPromises()

    expect(wrapper.find('.filters .btn-primary').text()).toBe('Neue Leistung')
    expect(wrapper.findAll('.catalog-section-tabs button')[1].attributes('aria-pressed')).toBe('true')

    await selector.setValue('equipment')
    await flushPromises()

    expect(wrapper.find('.filters .btn-primary').text()).toBe('Neue Ausstattung')
    expect(wrapper.find('.empty-state').text()).toContain('Keine Ausstattung vorhanden')
    expect(wrapper.findAll('.catalog-section-tabs button')[2].attributes('aria-pressed')).toBe('true')

    wrapper.unmount()
  })
})

/** Equipment as `GET /api/equipment` and `GET /api/equipment/summary` return it. */
const EQUIPMENT = [
  { id: 1, name: 'Kernbohrgerät', purchaseValue: 1000, status: 'active' },
  { id: 2, name: 'Lasermessgerät', purchaseValue: 100, status: 'active' },
  { id: 3, name: 'Gerüst', purchaseValue: null, status: 'active' },
  { id: 4, name: 'Ersatzgerät', purchaseValue: 500, status: 'active' }
]
const SUMMARY = [
  { id: 1, name: 'Kernbohrgerät', purchaseValue: 1000, totalRevenue: 560.5, usageCount: 4, paybackPercent: 56.1 },
  { id: 2, name: 'Lasermessgerät', purchaseValue: 100, totalRevenue: 250, usageCount: 1, paybackPercent: 250 },
  { id: 3, name: 'Gerüst', purchaseValue: 0, totalRevenue: 70, usageCount: 1, paybackPercent: 0 },
  { id: 4, name: 'Ersatzgerät', purchaseValue: 500, totalRevenue: 0, usageCount: 0, paybackPercent: 0 }
]
const USAGE = [
  { invoiceNumber: 'RE-2026-0002', invoiceDate: '2026-04-01', serviceName: 'Bohren', quantity: 3, unitPrice: 100, totalPrice: 300, unit: 'Std' },
  { invoiceNumber: 'RE-2026-0001', invoiceDate: '2026-03-01', serviceName: 'Kernbohrung', quantity: 1.5, unitPrice: 33.4, totalPrice: 50.1, unit: 'Stk' },
  { invoiceNumber: 'RE-2026-0001', invoiceDate: '2026-03-01', serviceName: 'Bohren', quantity: 2, unitPrice: 100.2, totalPrice: 200.4, unit: 'Std' }
]

/** Non-breaking spaces of `Intl` and runs of whitespace become one plain space. */
const plain = text => text.replace(/\s+/g, ' ').trim()

async function mountEquipmentSection() {
  equipmentAPI.getAll.mockResolvedValue({ data: EQUIPMENT })
  equipmentAPI.getSummary.mockResolvedValue({ data: SUMMARY })
  const wrapper = await mountArticlesServicesView()
  await wrapper.find('[data-testid="mobile-catalog-section"]').setValue('equipment')
  await flushPromises()
  return wrapper
}

const cellOf = (wrapper, name) =>
  wrapper.findAll('.amort-cell').find(cell => cell.attributes('aria-label').includes(name))

describe('ArticlesServicesView equipment amortisation', () => {
  beforeEach(() => vi.clearAllMocks())

  it('shows the percentage and a proportional bar below the purchase price', async () => {
    const wrapper = await mountEquipmentSection()
    const cell = cellOf(wrapper, 'Kernbohrgerät')

    expect(plain(cell.find('.amort-text').text())).toBe('56,1 %')
    expect(cell.find('.amort-fill').attributes('style')).toContain('width: 56.1%')
    expect(cell.find('.amort-fill').classes()).not.toContain('paid')
    expect(plain(cell.find('.amort-sub').text())).toBe('560,50 € · 4 Einsätze')
    expect(plain(cell.attributes('aria-label'))).toBe(
      'Einsätze für Kernbohrgerät anzeigen: 56,1 % amortisiert, 560,50 €, 4 Einsätze'
    )

    wrapper.unmount()
  })

  it('caps equipment that earned more than its price at a full bar and "amortisiert"', async () => {
    const wrapper = await mountEquipmentSection()
    const cell = cellOf(wrapper, 'Lasermessgerät')

    expect(plain(cell.find('.amort-text').text())).toBe('✓ amortisiert')
    expect(cell.find('.amort-done').exists()).toBe(true)
    expect(cell.find('.amort-fill').attributes('style')).toContain('width: 100%')
    expect(cell.find('.amort-fill').classes()).toContain('paid')
    // The revenue line below still says 250,00 €; the payback itself shows no number.
    expect(cell.find('.amort-row').text()).not.toMatch(/\d|%/)
    expect(plain(cell.find('.amort-sub').text())).toBe('250,00 € · 1 Einsatz')
    expect(plain(cell.attributes('aria-label'))).toBe(
      'Einsätze für Lasermessgerät anzeigen: amortisiert, 250,00 €, 1 Einsatz'
    )

    wrapper.unmount()
  })

  it('shows no bar and no percentage for equipment without a purchase price', async () => {
    const wrapper = await mountEquipmentSection()
    const cell = cellOf(wrapper, 'Gerüst')

    expect(cell.find('.amort-bar').exists()).toBe(false)
    expect(cell.text()).not.toContain('%')
    expect(cell.text()).toContain('ohne Kaufpreis')
    expect(plain(cell.find('.amort-sub').text())).toBe('70,00 € · 1 Einsatz')

    wrapper.unmount()
  })

  it('writes zero revenue out instead of a dash', async () => {
    const wrapper = await mountEquipmentSection()
    const cell = cellOf(wrapper, 'Ersatzgerät')

    expect(plain(cell.find('.amort-text').text())).toBe('0 %')
    expect(plain(cell.find('.amort-sub').text())).toBe('0,00 € · 0 Einsätze')

    wrapper.unmount()
  })

  it('explains what counts, linked to the column header', async () => {
    const wrapper = await mountEquipmentSection()
    const header = wrapper.findAll('th').find(th => th.text() === 'Amortisation')
    const hint = wrapper.find(`#${header.attributes('aria-describedby')}`)

    expect(hint.exists()).toBe(true)
    expect(hint.text()).toContain('vollständig bezahlten Rechnungen')
    expect(hint.text()).toContain('teilbezahlte Rechnungen zählen nicht')
    expect(hint.text()).toContain('monatliche Kosten werden nicht abgezogen')

    wrapper.unmount()
  })

  it('shows no hint on the other catalog sections', async () => {
    const wrapper = await mountEquipmentSection()
    await wrapper.find('[data-testid="mobile-catalog-section"]').setValue('articles')
    await flushPromises()

    expect(wrapper.find('#amortisation-hint').exists()).toBe(false)

    wrapper.unmount()
  })
})

describe('ArticlesServicesView usage dialog', () => {
  beforeEach(() => vi.clearAllMocks())

  async function openUsage(usage = USAGE) {
    equipmentAPI.getUsage.mockResolvedValue({ data: usage })
    const wrapper = await mountEquipmentSection()
    await cellOf(wrapper, 'Kernbohrgerät').trigger('click')
    await flushPromises()
    return wrapper
  }

  it('opens as a wide, labelled dialog without opening the edit form', async () => {
    const wrapper = await openUsage()
    const dialog = wrapper.find('[role="dialog"]')

    expect(equipmentAPI.getUsage).toHaveBeenCalledWith(1)
    expect(dialog.classes()).toContain('usage-modal')
    expect(dialog.attributes('aria-modal')).toBe('true')
    expect(wrapper.find(`#${dialog.attributes('aria-labelledby')}`).text()).toBe('Einsätze: Kernbohrgerät')
    expect(document.activeElement).toBe(dialog.element)
    expect(wrapper.text()).not.toContain('Ausstattung bearbeiten')

    wrapper.unmount()
  })

  it('lists the lines with German quantities and a sum row', async () => {
    const wrapper = await openUsage()
    const dialog = wrapper.find('[role="dialog"]')
    const rows = dialog.findAll('tbody tr').map(row => row.findAll('td').map(cell => plain(cell.text())))

    expect(rows).toEqual([
      ['RE-2026-0002', '1.4.2026', 'Bohren', '3 Std', '100,00 €', '300,00 €'],
      ['RE-2026-0001', '1.3.2026', 'Kernbohrung', '1,5 Stk', '33,40 €', '50,10 €'],
      ['RE-2026-0001', '1.3.2026', 'Bohren', '2 Std', '100,20 €', '200,40 €']
    ])
    const total = dialog.find('tfoot tr')
    expect(plain(total.find('th').text())).toBe('Summe · 3 Einsätze')
    expect(total.find('th').attributes('colspan')).toBe('5')
    // 300 + 50.1 + 200.4 is 550.4999999999999 in floating point: the sum is built in cents.
    expect(plain(total.find('td').text())).toBe('550,50 €')
    expect(plain(dialog.find('[data-testid="usage-summary"]').text())).toBe(
      '3 Einsätze · 550,50 € Netto-Umsatz aus vollständig bezahlten Rechnungen'
    )

    wrapper.unmount()
  })

  it('says what counts when there is no usage, without a sum row', async () => {
    const wrapper = await openUsage([])
    const dialog = wrapper.find('[role="dialog"]')

    expect(dialog.find('table').exists()).toBe(false)
    expect(dialog.find('.empty-state').text()).toContain('Keine Einsätze gefunden')
    expect(dialog.find('.empty-state').text()).toContain('vollständig bezahlten Rechnungen')

    wrapper.unmount()
  })

  it('shows a load error inside the dialog instead of "no usage"', async () => {
    equipmentAPI.getUsage.mockRejectedValue(new Error('Network Error'))
    const wrapper = await mountEquipmentSection()
    await cellOf(wrapper, 'Kernbohrgerät').trigger('click')
    await flushPromises()
    const dialog = wrapper.find('[role="dialog"]')

    expect(dialog.find('[role="alert"]').text()).toContain('Network Error')
    expect(dialog.text()).not.toContain('Keine Einsätze gefunden')

    wrapper.unmount()
  })

  it('closes with Escape and with the button and gives the focus back to the cell', async () => {
    const wrapper = await openUsage()
    const cell = cellOf(wrapper, 'Kernbohrgerät')

    await wrapper.find('[role="dialog"]').trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(document.activeElement).toBe(cell.element)

    await cell.trigger('click')
    await flushPromises()
    await wrapper.find('[role="dialog"] .form-actions button').trigger('click')
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
    expect(document.activeElement).toBe(cell.element)

    wrapper.unmount()
  })
})

import { describe, it, expect, vi, beforeEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DocumentViewModal from './DocumentViewModal.vue'
import invoiceAPI from '../services/invoiceService.js'
import { DRAFT, SENT, ACCEPTED, REJECTED, CONVERTED, PAID, CANCELLED, PARTIALLY_PAID } from '../utils/documentStatus.js'

const INVOICE = 1
const OFFER = 2
const COST_CALCULATION = 3
const CREDIT_NOTE = 6

function document(typeId, statusId) {
  return {
    id: 1,
    customerId: 1,
    customerName: 'Max Kunde',
    invoiceNumber: 'AN-2026-001',
    documentTypeId: typeId,
    documentStatusId: statusId,
    invoiceDate: '2026-03-01',
    dueDate: '2026-03-15',
    items: [],
    vatGroups: [],
    netAmount: 100,
    taxAmount: 19,
    grossAmount: 119,
    paidAmount: 0
  }
}

/**
 * Opens the modal on a document of the given type and status.
 * @param {number} typeId - document type
 * @param {number} statusId - document status
 * @param {object} [listActions] - what the opening list allows (edit, archive, delete, ...)
 */
async function open(typeId, statusId, listActions = {}) {
  vi.spyOn(invoiceAPI, 'getInvoice').mockResolvedValue({ data: document(typeId, statusId) })
  vi.spyOn(invoiceAPI, 'getHistory').mockResolvedValue({ data: [] })
  vi.spyOn(invoiceAPI, 'getPayments').mockResolvedValue({ data: [] })
  // The document loads when `visible` turns true, not on mount (watcher without `immediate`).
  const wrapper = mount(DocumentViewModal, {
    props: { visible: false, invoiceId: 1, listActions },
    global: { stubs: { teleport: true } },
    attachTo: window.document.body
  })
  mounted.push(wrapper)
  await wrapper.setProps({ visible: true })
  await flushPromises()
  return wrapper
}

const mounted = []

const texts = (wrapper, selector) => wrapper.findAll(selector).map(b => b.text())
/** The next steps of the lifecycle, in the strip above the document actions. */
const steps = wrapper => texts(wrapper, '.action-group-lifecycle button')
/** The document actions, in the row below the strip. */
const documentActions = wrapper => texts(wrapper, '.action-group-document button')
/** The caption that names the strip; `null` when the document has no next step and no strip. */
const stripCaption = wrapper => {
  const caption = wrapper.find('.action-group-lifecycle .lifecycle-caption')
  return caption.exists() ? caption.text() : null
}
const primaries = wrapper => texts(wrapper, '.action-bar .btn-primary')
const moreButton = wrapper => wrapper.find('.more-menu > button')

/** The entries of the "Mehr" menu (opened for it); empty when the bar has no menu. */
async function menu(wrapper) {
  if (!moreButton(wrapper).exists()) return []
  if (!wrapper.find('.more-menu-list').exists()) await moreButton(wrapper).trigger('click')
  return texts(wrapper, '.more-menu-list [role="menuitem"]')
}

async function chooseFromMenu(wrapper, label) {
  await menu(wrapper)
  await wrapper.findAll('[role="menuitem"]').find(item => item.text() === label).trigger('click')
  await flushPromises()
}

function resetMocks() {
  mounted.splice(0).forEach(wrapper => wrapper.unmount())
  vi.restoreAllMocks()
}

describe('DocumentViewModal — action bar of offers and cost calculations', () => {
  beforeEach(resetMocks)

  it('offers sending or accepting a draft, but not cancelling it', async () => {
    const wrapper = await open(OFFER, DRAFT)

    expect(steps(wrapper)).toEqual(['Gesendet', 'Angenommen'])
    expect(await menu(wrapper)).toEqual([])
  })

  it('offers accepting or rejecting a sent offer, cancelling it only in the menu', async () => {
    const wrapper = await open(OFFER, SENT)

    expect(steps(wrapper)).toEqual(['Angenommen', 'Abgelehnt'])
    expect(await menu(wrapper)).toEqual(['Stornieren'])
  })

  it('lets a rejected offer still be accepted', async () => {
    const wrapper = await open(OFFER, REJECTED)

    expect(steps(wrapper)).toEqual(['Angenommen'])
    expect(await menu(wrapper)).toEqual(['Stornieren'])
  })

  it('makes converting the next step of an accepted document when the list allows it', async () => {
    const wrapper = await open(COST_CALCULATION, ACCEPTED, { convertToOffer: true })

    expect(steps(wrapper)).toEqual(['In Angebot'])
    expect(primaries(wrapper)).toEqual(['In Angebot'])
    expect(await menu(wrapper)).toEqual(['Stornieren'])

    await wrapper.find('.action-group-lifecycle button').trigger('click')
    expect(wrapper.emitted('convert-to-offer')[0][0]).toMatchObject({ id: 1 })
  })

  it('has no next step for an accepted document opened where converting is not offered', async () => {
    const wrapper = await open(COST_CALCULATION, ACCEPTED)

    expect(steps(wrapper)).toEqual([])
    expect(primaries(wrapper)).toEqual(['PDF'])
    expect(await menu(wrapper)).toEqual(['Stornieren'])
  })

  it('offers nothing after conversion or cancellation', async () => {
    for (const status of [CONVERTED, CANCELLED]) {
      const wrapper = await open(OFFER, status)

      expect(steps(wrapper)).toEqual([])
      expect(await menu(wrapper)).toEqual([])
      expect(primaries(wrapper)).toEqual(['PDF'])
    }
  })

  it('sends the accepted status and reports the backend message when a change is refused', async () => {
    const wrapper = await open(OFFER, SENT)
    const update = vi.spyOn(invoiceAPI, 'updateInvoiceStatus')
      .mockRejectedValueOnce(new Error('Der Status „Gesendet“ kann nicht in „Angenommen“ geändert werden.'))

    await wrapper.findAll('.action-group-lifecycle button')[0].trigger('click')
    await flushPromises()

    expect(update).toHaveBeenCalledWith(1, ACCEPTED)
    expect(wrapper.find('.action-error').text()).toContain('kann nicht in „Angenommen“ geändert werden')
    expect(wrapper.find('.action-error').attributes('role')).toBe('alert')
  })

  it('cancels an offer only after the confirmation', async () => {
    const wrapper = await open(OFFER, SENT)
    const update = vi.spyOn(invoiceAPI, 'updateInvoiceStatus').mockResolvedValue({})
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false)

    await chooseFromMenu(wrapper, 'Stornieren')
    expect(confirm).toHaveBeenCalledTimes(1)
    expect(confirm.mock.calls[0][0]).toContain('endgültig')
    expect(update).not.toHaveBeenCalled()

    confirm.mockReturnValueOnce(true)
    await chooseFromMenu(wrapper, 'Stornieren')
    expect(update).toHaveBeenCalledWith(1, CANCELLED)
    expect(wrapper.emitted('status-updated')).toHaveLength(1)
    expect(steps(wrapper)).toEqual([])
  })
})

describe('DocumentViewModal — action bar of invoices', () => {
  beforeEach(resetMocks)

  it('makes issuing the one primary action of a draft and keeps deleting in the menu', async () => {
    const wrapper = await open(INVOICE, DRAFT, { edit: true, delete: true })

    expect(steps(wrapper)).toEqual(['Ausstellen'])
    expect(primaries(wrapper)).toEqual(['Ausstellen'])
    expect(documentActions(wrapper)).toEqual(['PDF', 'Duplizieren', 'Bearbeiten'])
    expect(await menu(wrapper)).toEqual(['Löschen'])
  })

  it('makes recording a payment the primary action of an open invoice', async () => {
    for (const status of [SENT, PARTIALLY_PAID]) {
      const wrapper = await open(INVOICE, status, { edit: true, archive: true })

      expect(steps(wrapper)).toEqual(['Zahlung erfassen'])
      expect(primaries(wrapper)).toEqual(['Zahlung erfassen'])
      expect(documentActions(wrapper)).toEqual(['PDF', 'E-Rechnung', 'Duplizieren', 'Bearbeiten'])
      expect(await menu(wrapper)).toEqual(['Stornieren', 'Archivieren'])
    }
  })

  it('leaves a paid invoice the PDF as primary action; it can still be cancelled', async () => {
    const wrapper = await open(INVOICE, PAID)

    expect(steps(wrapper)).toEqual([])
    expect(primaries(wrapper)).toEqual(['PDF'])
    expect(await menu(wrapper)).toEqual(['Stornieren'])
  })

  it('offers no step and no cancelling for a cancelled invoice or a credit note', async () => {
    const cancelled = await open(INVOICE, CANCELLED)
    expect(steps(cancelled)).toEqual([])
    expect(await menu(cancelled)).toEqual([])
    expect(documentActions(cancelled)).toEqual(['PDF', 'E-Rechnung', 'Duplizieren'])

    const creditNote = await open(CREDIT_NOTE, SENT)
    expect(steps(creditNote)).toEqual([])
    expect(await menu(creditNote)).toEqual([])
    expect(documentActions(creditNote)).toEqual(['PDF', 'E-Rechnung'])
    expect(primaries(creditNote)).toEqual(['PDF'])
  })

  it('offers restoring an archived document next to the document actions', async () => {
    const wrapper = await open(INVOICE, PAID, { edit: true, restore: true })

    expect(documentActions(wrapper)).toEqual(['PDF', 'E-Rechnung', 'Duplizieren', 'Bearbeiten', 'Wiederherstellen'])
    await wrapper.findAll('.action-group-document button').at(-1).trigger('click')
    expect(wrapper.emitted('restore-request')).toEqual([[1]])
  })

  it('opens the payment form in place of the bar and returns to the bar on cancel', async () => {
    const wrapper = await open(INVOICE, SENT)

    await wrapper.find('.action-group-lifecycle button').trigger('click')
    await flushPromises()

    expect(wrapper.find('.action-bar').exists()).toBe(false)
    expect(wrapper.find('.modal-footer .payment-form').exists()).toBe(true)
    expect(wrapper.find('#payment-amount').element.value).toBe('119')
    expect(window.document.activeElement.id).toBe('payment-date')

    await wrapper.findAll('.footer-form-buttons button').find(b => b.text() === 'Abbrechen').trigger('click')
    expect(wrapper.find('.payment-form').exists()).toBe(false)
    expect(steps(wrapper)).toEqual(['Zahlung erfassen'])
  })

  it('records the payment and shows the bar again', async () => {
    const wrapper = await open(INVOICE, SENT)
    const record = vi.spyOn(invoiceAPI, 'recordPayment').mockResolvedValue({})

    await wrapper.find('.action-group-lifecycle button').trigger('click')
    await wrapper.findAll('.footer-form-buttons button').find(b => b.text() === 'Speichern').trigger('click')
    await flushPromises()

    expect(record).toHaveBeenCalledWith(1, expect.objectContaining({ amount: 119, paymentMethod: 'Überweisung' }))
    expect(wrapper.find('.action-bar').exists()).toBe(true)
    expect(wrapper.emitted('status-updated')).toHaveLength(1)
  })

  it('opens the duplicate form in place of the bar and keeps it open when duplicating fails', async () => {
    const wrapper = await open(INVOICE, SENT)
    vi.spyOn(invoiceAPI, 'cloneInvoice').mockRejectedValueOnce(new Error('Server nicht erreichbar'))

    await wrapper.findAll('.action-group-document button').find(b => b.text() === 'Duplizieren').trigger('click')
    await flushPromises()
    expect(wrapper.find('.action-bar').exists()).toBe(false)
    expect(wrapper.find('.clone-form h3').text()).toBe('Rechnung duplizieren')

    await wrapper.findAll('.footer-form-buttons button').find(b => b.text() === 'Bestätigen').trigger('click')
    await flushPromises()

    // The document stays readable and the form open; the message stands above the form.
    expect(wrapper.find('.action-error').text()).toContain('Duplizieren fehlgeschlagen: Server nicht erreichbar')
    expect(wrapper.find('.clone-form').exists()).toBe(true)
    expect(wrapper.find('.invoice-content').exists()).toBe(true)
    expect(wrapper.emitted('cloned')).toBeUndefined()
  })

  it('asks before archiving and hands deleting to the list, which asks itself', async () => {
    const wrapper = await open(INVOICE, SENT, { archive: true })
    const confirm = vi.spyOn(window, 'confirm').mockReturnValueOnce(false)

    await chooseFromMenu(wrapper, 'Archivieren')
    expect(wrapper.emitted('archive-request')).toBeUndefined()

    confirm.mockReturnValueOnce(true)
    await chooseFromMenu(wrapper, 'Archivieren')
    expect(wrapper.emitted('archive-request')).toEqual([[1]])

    const draft = await open(INVOICE, DRAFT, { delete: true })
    confirm.mockClear()
    await chooseFromMenu(draft, 'Löschen')
    expect(draft.emitted('delete-request')).toEqual([[1]])
    expect(confirm).not.toHaveBeenCalled()
  })

  it('cancels an invoice from the menu after the confirmation', async () => {
    const wrapper = await open(INVOICE, SENT)
    const cancel = vi.spyOn(invoiceAPI, 'cancelInvoice').mockResolvedValue({ data: { invoiceNumber: 'ST-2026-001' } })
    // Yes to cancelling, no to the corrected copy.
    vi.spyOn(window, 'confirm').mockReturnValueOnce(true).mockReturnValueOnce(false)

    await chooseFromMenu(wrapper, 'Stornieren')

    expect(cancel).toHaveBeenCalledWith(1)
    expect(wrapper.emitted('status-updated')).toHaveLength(1)
  })
})

describe('DocumentViewModal — the lifecycle strip is told apart from the document actions', () => {
  beforeEach(resetMocks)

  it('announces status buttons as the status they set', async () => {
    for (const [type, status] of [[OFFER, DRAFT], [OFFER, SENT], [OFFER, REJECTED], [COST_CALCULATION, SENT]]) {
      const wrapper = await open(type, status)

      expect(stripCaption(wrapper)).toBe('Status setzen auf:')
    }
  })

  it('announces issuing, a payment and converting as the next step', async () => {
    expect(stripCaption(await open(INVOICE, DRAFT))).toBe('Nächster Schritt:')
    expect(stripCaption(await open(INVOICE, SENT))).toBe('Nächster Schritt:')
    expect(stripCaption(await open(OFFER, ACCEPTED, { convertToInvoice: true }))).toBe('Nächster Schritt:')
  })

  it('names the group of the strip by its caption and keeps the caption out of the document actions', async () => {
    const wrapper = await open(OFFER, SENT)
    const strip = wrapper.find('.action-group-lifecycle')
    const caption = strip.find('.lifecycle-caption')

    expect(strip.attributes('role')).toBe('group')
    expect(strip.attributes('aria-labelledby')).toBe(caption.attributes('id'))
    expect(wrapper.find('.action-group-document .lifecycle-caption').exists()).toBe(false)
    // The status buttons are in the strip only, the document actions in their own group only.
    expect(steps(wrapper)).toEqual(['Angenommen', 'Abgelehnt'])
    expect(documentActions(wrapper)).toEqual(['PDF', 'Duplizieren'])
  })

  it('shows no strip and no caption when the document has no next step', async () => {
    for (const [type, status] of [[INVOICE, PAID], [INVOICE, CANCELLED], [OFFER, CONVERTED], [CREDIT_NOTE, SENT]]) {
      const wrapper = await open(type, status)

      expect(wrapper.find('.action-group-lifecycle').exists()).toBe(false)
      expect(stripCaption(wrapper)).toBeNull()
    }
  })
})

describe('DocumentViewModal — the "Mehr" menu', () => {
  beforeEach(resetMocks)

  it('is a menu button that opens on click and moves the focus to the first entry', async () => {
    const wrapper = await open(INVOICE, SENT, { archive: true })
    const button = moreButton(wrapper)

    expect(button.attributes('aria-haspopup')).toBe('menu')
    expect(button.attributes('aria-expanded')).toBe('false')
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)

    await button.trigger('click')
    await flushPromises()

    expect(button.attributes('aria-expanded')).toBe('true')
    expect(button.attributes('aria-controls')).toBe(wrapper.find('[role="menu"]').attributes('id'))
    expect(window.document.activeElement.textContent).toBe('Stornieren')
  })

  it('moves through the entries with the arrow keys and closes on Escape, focus back on the button', async () => {
    const wrapper = await open(INVOICE, SENT, { archive: true })
    await moreButton(wrapper).trigger('click')
    await flushPromises()
    const list = wrapper.find('[role="menu"]')

    await list.trigger('keydown', { key: 'ArrowDown' })
    expect(window.document.activeElement.textContent).toBe('Archivieren')
    await list.trigger('keydown', { key: 'ArrowDown' })
    expect(window.document.activeElement.textContent).toBe('Stornieren')
    await list.trigger('keydown', { key: 'End' })
    expect(window.document.activeElement.textContent).toBe('Archivieren')

    await list.trigger('keydown', { key: 'Escape' })
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
    expect(window.document.activeElement).toBe(moreButton(wrapper).element)
    expect(wrapper.emitted('update:visible')).toBeUndefined()
  })

  it('closes on a click outside and when an entry was chosen', async () => {
    const wrapper = await open(INVOICE, SENT, { archive: true })
    vi.spyOn(window, 'confirm').mockReturnValue(false)

    await moreButton(wrapper).trigger('click')
    await wrapper.find('.invoice-content').trigger('click')
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)

    await chooseFromMenu(wrapper, 'Archivieren')
    expect(wrapper.find('[role="menu"]').exists()).toBe(false)
  })

  it('is not shown when there is nothing rare or destructive to do', async () => {
    const wrapper = await open(INVOICE, DRAFT, { edit: true })

    expect(moreButton(wrapper).exists()).toBe(false)
    expect(texts(wrapper, '.action-group-end button')).toEqual(['Schließen'])
  })
})

describe('DocumentViewModal — history', () => {
  beforeEach(resetMocks)

  async function openWithHistory(entries) {
    vi.spyOn(invoiceAPI, 'getInvoice').mockResolvedValue({ data: document(OFFER, SENT) })
    vi.spyOn(invoiceAPI, 'getHistory').mockResolvedValue({ data: entries })
    const wrapper = mount(DocumentViewModal, {
      props: { visible: false, invoiceId: 1 },
      global: { stubs: { teleport: true } }
    })
    await wrapper.setProps({ visible: true })
    await flushPromises()
    return wrapper.findAll('.history-entry')
  }

  const entry = (id, action, notes) => ({ id, action, notes, actionDate: '2026-03-01 10:15:00' })

  it('shows date, German label and details of an entry apart, in the order of the backend', async () => {
    const rows = await openWithHistory([
      entry(1, 'CREATED', 'AN-2026-001'),
      entry(2, 'STATUS_CHANGED', 'Entwurf → Gesendet')
    ])

    expect(rows.map(r => r.get('.history-action').text())).toEqual(['Erstellt', 'Status geändert'])
    expect(rows[1].get('.history-details').text()).toBe('Entwurf → Gesendet')
    expect(rows[1].get('.history-date').text()).toContain('01.03.26')
  })

  it('names the user who acted, when the backend knows them (SaaS)', async () => {
    const rows = await openWithHistory([{ ...entry(1, 'CREATED', 'AN-2026-001'), performedByName: 'Tina' }])

    expect(rows[0].get('.history-actor').text()).toBe('Tina')
  })

  it('names the status of older entries that only hold its id', async () => {
    const rows = await openWithHistory([entry(1, 'STATUS_CHANGED', 'Status 3')])

    expect(rows[0].get('.history-details').text()).toBe('Angenommen')
  })

  it('never shows the key of an unknown action', async () => {
    const rows = await openWithHistory([entry(1, 'SOMETHING_NEW', null)])

    expect(rows[0].get('.history-action').text()).toBe('Sonstige Änderung')
    expect(rows[0].get('.history-details').text()).toBe('')
    expect(rows[0].find('.history-actor').exists()).toBe(false)
  })
})

import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { mount, flushPromises } from '@vue/test-utils'
import DocumentModal from './DocumentModal.vue'

// The modal's watch on props.visible calls several services on open. The
// guards under test (onLayoutModeDefault, submit) only need a few of
// them; mock everything to keep the mount cheap and to avoid real
// network calls. Each test sets props.visible = false so the watch
// doesn't fire and the mocks stay unused.
vi.mock('../services/invoiceService.js', () => ({
  default: {
    getInvoice: vi.fn(() => Promise.resolve({ data: {} })),
    getNextInvoiceNumber: vi.fn(() => Promise.resolve({ data: 'RE-2026-001' })),
    createInvoice: vi.fn(() => Promise.resolve({ data: { id: 99 } })),
    updateInvoice: vi.fn(() => Promise.resolve({ data: { id: 7 } }))
  }
}))
vi.mock('../services/templateService.js', () => ({
  default: {
    getTemplates: vi.fn(() => Promise.resolve({ data: [] })),
    getTemplatesByDocumentType: vi.fn(() => Promise.resolve({ data: [] })),
    getDefaultTemplate: vi.fn(() => Promise.resolve({ data: { id: 5, name: 'Default' } })),
    cloneForDocument: vi.fn(() => Promise.resolve({ data: { id: 99, name: 'clone' } })),
    getTemplate: vi.fn(() => Promise.resolve({ data: {} })),
    updateTemplate: vi.fn(() => Promise.resolve({ data: {} }))
  }
}))
vi.mock('../services/customerService.js', () => ({
  default: {
    getCustomer: vi.fn(() => Promise.resolve({ data: {} }))
  }
}))
vi.mock('../services/articleService.js', () => ({
  default: { getAll: vi.fn(() => Promise.resolve({ data: [] })) }
}))
vi.mock('../services/equipmentService.js', () => ({
  default: { getAll: vi.fn(() => Promise.resolve({ data: [] })) }
}))
vi.mock('../services/ownerService.js', () => ({
  default: { getPrimaryOwner: vi.fn(() => Promise.resolve({ data: {} })) }
}))

const baseProps = () => ({
  visible: false,
  articles: [],
  services: [],
  invoice: null,
  documentType: 1
})

const invoiceWithOverride = {
  id: 7,
  customerId: 1,
  documentStatusId: 1,
  documentTypeId: 1,
  templateId: 42,
  items: [],
  invoiceNumber: 'RE-2026-001',
  paidDate: null
}

const invoiceWithoutOverride = {
  ...invoiceWithOverride,
  templateId: null
}

function mountModal(props) {
  return mount(DocumentModal, { props: { ...baseProps(), ...props } })
}

describe('DocumentModal — narrow-screen structure', () => {
  it('keeps save and cancel actions fixed outside the scrolling form fields', () => {
    const wrapper = mountModal({ visible: true })
    const form = wrapper.find('.modal-form')

    expect(wrapper.find('.document-modal').exists()).toBe(true)
    expect(form.find('.scroll-container').exists()).toBe(true)
    expect(form.find('.form-actions').exists()).toBe(true)
    expect(form.find('.scroll-container .form-actions').exists()).toBe(false)
  })

  it('restores the captured page zoom when document text fields lose focus', async () => {
    const viewportDescriptor = Object.getOwnPropertyDescriptor(window, 'visualViewport')
    const viewport = { scale: 1 }
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
    let viewportMeta = document.querySelector('meta[name="viewport"]')
    const hadViewportMeta = !!viewportMeta
    if (!viewportMeta) {
      viewportMeta = document.createElement('meta')
      viewportMeta.name = 'viewport'
      viewportMeta.content = 'width=device-width, initial-scale=1.0'
      document.head.appendChild(viewportMeta)
    }
    const originalContent = viewportMeta.content
    const wrapper = mount(DocumentModal, {
      props: { ...baseProps() },
      attachTo: document.body
    })

    try {
      await wrapper.setProps({ visible: true })
      await flushPromises()
      const fields = [
        wrapper.find('.document-notes-input').element,
        wrapper.find('.invoice-number-preview').element
      ]

      for (const field of fields) {
        viewport.scale = 1
        field.focus()
        expect(document.activeElement).toBe(field)

        viewport.scale = 1.25
        field.blur()
        expect(document.activeElement).not.toBe(field)
        expect(viewportMeta.content).toContain('maximum-scale=1')

        viewport.scale = 1
        await new Promise(resolve => window.setTimeout(resolve, 150))
        expect(viewportMeta.content).toContain('maximum-scale=1')
        await new Promise(resolve => window.setTimeout(resolve, 500))
        expect(viewportMeta.content).toBe(originalContent)
      }
    } finally {
      wrapper.unmount()
      wrapper.element.remove()
      if (hadViewportMeta) viewportMeta.content = originalContent
      else viewportMeta.remove()
      if (viewportDescriptor) Object.defineProperty(window, 'visualViewport', viewportDescriptor)
      else delete window.visualViewport
    }
  })

  it('restores the page zoom when the keyboard closes even while a field stays focused', async () => {
    const viewportDescriptor = Object.getOwnPropertyDescriptor(window, 'visualViewport')
    const viewport = new EventTarget()
    viewport.scale = 1
    viewport.height = 800
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
    let viewportMeta = document.querySelector('meta[name="viewport"]')
    const hadViewportMeta = !!viewportMeta
    if (!viewportMeta) {
      viewportMeta = document.createElement('meta')
      viewportMeta.name = 'viewport'
      viewportMeta.content = 'width=device-width, initial-scale=1.0'
      document.head.appendChild(viewportMeta)
    }
    const originalContent = viewportMeta.content
    const wrapper = mount(DocumentModal, {
      props: { ...baseProps() },
      attachTo: document.body
    })

    try {
      await wrapper.setProps({ visible: true })
      await flushPromises()
      const notes = wrapper.find('.document-notes-input').element
      notes.focus()

      // Opening the keyboard shortens the visual viewport and the browser
      // magnifies the page. Closing it expands the viewport at the new scale.
      viewport.height = 500
      viewport.scale = 1.25
      viewport.dispatchEvent(new Event('resize'))
      viewport.height = 640
      viewport.dispatchEvent(new Event('resize'))

      expect(document.activeElement).toBe(notes)
      await new Promise(resolve => window.setTimeout(resolve, 190))
      expect(viewportMeta.content).toContain('maximum-scale=1')

      viewport.scale = 1
      await new Promise(resolve => window.setTimeout(resolve, 150))
      expect(viewportMeta.content).toContain('maximum-scale=1')
      await new Promise(resolve => window.setTimeout(resolve, 500))
      expect(viewportMeta.content).toBe(originalContent)
    } finally {
      wrapper.unmount()
      wrapper.element.remove()
      if (hadViewportMeta) viewportMeta.content = originalContent
      else viewportMeta.remove()
      if (viewportDescriptor) Object.defineProperty(window, 'visualViewport', viewportDescriptor)
      else delete window.visualViewport
    }
  })

  it('uses the native virtual-keyboard close event when the viewport does not expand', async () => {
    const viewportDescriptor = Object.getOwnPropertyDescriptor(window, 'visualViewport')
    const keyboardDescriptor = Object.getOwnPropertyDescriptor(navigator, 'virtualKeyboard')
    const viewport = new EventTarget()
    viewport.scale = 1
    viewport.height = 800
    const virtualKeyboard = new EventTarget()
    virtualKeyboard.boundingRect = { height: 0 }
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
    Object.defineProperty(navigator, 'virtualKeyboard', { configurable: true, value: virtualKeyboard })
    let viewportMeta = document.querySelector('meta[name="viewport"]')
    const hadViewportMeta = !!viewportMeta
    if (!viewportMeta) {
      viewportMeta = document.createElement('meta')
      viewportMeta.name = 'viewport'
      viewportMeta.content = 'width=device-width, initial-scale=1.0'
      document.head.appendChild(viewportMeta)
    }
    const originalContent = viewportMeta.content
    const wrapper = mount(DocumentModal, {
      props: { ...baseProps() },
      attachTo: document.body
    })

    try {
      await wrapper.setProps({ visible: true })
      await flushPromises()
      const notes = wrapper.find('.document-notes-input').element
      notes.focus()

      virtualKeyboard.boundingRect = { height: 300 }
      virtualKeyboard.dispatchEvent(new Event('geometrychange'))
      viewport.scale = 1.25
      virtualKeyboard.boundingRect = { height: 0 }
      virtualKeyboard.dispatchEvent(new Event('geometrychange'))

      expect(document.activeElement).toBe(notes)
      await new Promise(resolve => window.setTimeout(resolve, 190))
      expect(viewportMeta.content).toContain('maximum-scale=1')

      viewport.scale = 1
      await new Promise(resolve => window.setTimeout(resolve, 150))
      expect(viewportMeta.content).toContain('maximum-scale=1')
      await new Promise(resolve => window.setTimeout(resolve, 500))
      expect(viewportMeta.content).toBe(originalContent)
    } finally {
      wrapper.unmount()
      wrapper.element.remove()
      if (hadViewportMeta) viewportMeta.content = originalContent
      else viewportMeta.remove()
      if (viewportDescriptor) Object.defineProperty(window, 'visualViewport', viewportDescriptor)
      else delete window.visualViewport
      if (keyboardDescriptor) Object.defineProperty(navigator, 'virtualKeyboard', keyboardDescriptor)
      else delete navigator.virtualKeyboard
    }
  })

  it('provides a development-only keyboard simulation through the same viewport handler', async () => {
    const originalUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`
    const viewportDescriptor = Object.getOwnPropertyDescriptor(window, 'visualViewport')
    const viewport = { scale: 1, height: 800 }
    Object.defineProperty(window, 'visualViewport', { configurable: true, value: viewport })
    window.history.pushState({}, '', `${window.location.pathname}?keyboardZoomDebug=1`)

    let viewportMeta = document.querySelector('meta[name="viewport"]')
    const hadViewportMeta = !!viewportMeta
    if (!viewportMeta) {
      viewportMeta = document.createElement('meta')
      viewportMeta.name = 'viewport'
      viewportMeta.content = 'width=device-width, initial-scale=1.0'
      document.head.appendChild(viewportMeta)
    }
    const originalContent = viewportMeta.content
    const wrapper = mount(DocumentModal, {
      props: { ...baseProps() },
      attachTo: document.body
    })

    try {
      await wrapper.setProps({ visible: true })
      await flushPromises()
      const debugPanel = wrapper.find('[data-testid="keyboard-zoom-debug"]')
      expect(debugPanel.exists()).toBe(true)

      await debugPanel.findAll('button').find(button => button.text() === 'Start simulation').trigger('click')
      await debugPanel.findAll('button').find(button => button.text() === 'Keyboard open').trigger('click')
      expect(wrapper.find('[data-testid="keyboard-zoom-debug-status"]').text()).toBe('Simulation: keyboard open detected')
      expect(viewportMeta.content).toBe(originalContent)

      await debugPanel.findAll('button').find(button => button.text() === 'Keyboard close').trigger('click')
      await new Promise(resolve => window.setTimeout(resolve, 200))
      expect(wrapper.find('[data-testid="keyboard-zoom-debug-status"]').text()).toContain('Reset requested: 1.00× (Simulation: keyboard close detected)')
      expect(viewportMeta.content).toContain('maximum-scale=1')
    } finally {
      wrapper.unmount()
      wrapper.element.remove()
      if (hadViewportMeta) viewportMeta.content = originalContent
      else viewportMeta.remove()
      if (viewportDescriptor) Object.defineProperty(window, 'visualViewport', viewportDescriptor)
      else delete window.visualViewport
      window.history.replaceState({}, '', originalUrl)
    }
  })

  it('uploads sanitized keyboard diagnostics only after the user taps send', async () => {
    const originalUrl = `${window.location.pathname}${window.location.search}${window.location.hash}`
    const fetchSpy = vi.fn(() => Promise.resolve({
      ok: true,
      json: () => Promise.resolve({ logFile: 'logs/keyboard-zoom-diagnostics.jsonl' })
    }))
    vi.stubGlobal('fetch', fetchSpy)
    window.history.pushState({}, '', `${window.location.pathname}?keyboardZoomDebug=1`)
    const wrapper = mount(DocumentModal, {
      props: { ...baseProps() },
      attachTo: document.body
    })

    try {
      await wrapper.setProps({ visible: true })
      await flushPromises()
      const notes = wrapper.find('.document-notes-input').element
      notes.value = 'private customer note'
      notes.focus()
      notes.dispatchEvent(new Event('focusin', { bubbles: true }))
      await wrapper.vm.$nextTick()

      expect(fetchSpy).not.toHaveBeenCalled()
      const sendButton = wrapper.findAll('[data-testid="keyboard-zoom-debug"] button')
        .find(button => button.text().startsWith('Send diagnostics'))
      await sendButton.trigger('click')
      await flushPromises()

      expect(fetchSpy).toHaveBeenCalledTimes(1)
      expect(fetchSpy.mock.calls[0][0]).toBe('/__keyboard-zoom-logs')
      const payload = JSON.parse(fetchSpy.mock.calls[0][1].body)
      expect(payload.events.some(event => event.event === 'document-field.focusin')).toBe(true)
      expect(JSON.stringify(payload)).toContain('documentModal')
      expect(JSON.stringify(payload)).not.toContain('private customer note')
      expect(wrapper.find('[data-testid="keyboard-zoom-telemetry-status"]').text()).toContain('logs/keyboard-zoom-diagnostics.jsonl')
    } finally {
      wrapper.unmount()
      wrapper.element.remove()
      window.history.replaceState({}, '', originalUrl)
      vi.unstubAllGlobals()
    }
  })
})

describe('DocumentModal — customer selection', () => {
  it('takes the customer chosen in the picker into the form', async () => {
    const { default: CustomerPicker } = await import('./CustomerPicker.vue')
    const wrapper = mountModal({ visible: true })

    wrapper.findComponent(CustomerPicker).vm.$emit('update:modelValue', 7)
    wrapper.findComponent(CustomerPicker).vm.$emit('update:customer', { id: 7, firstName: 'Max', lastName: 'Zimmer' })
    await wrapper.vm.$nextTick()

    expect(wrapper.vm.form.customerId).toBe(7)
    expect(wrapper.findComponent(CustomerPicker).props('modelValue')).toBe(7)
    wrapper.unmount()
  })
})

describe('DocumentModal — Layout override clear-confirmations', () => {
  let confirmSpy
  beforeEach(() => {
    // Reset all vi.fn() call histories between tests so that
    // not.toHaveBeenCalled() assertions in later tests are not
    // polluted by calls from earlier tests (the shared
    // `vi.mock('../services/...')` instances persist across the
    // whole file otherwise).
    vi.clearAllMocks()
    confirmSpy = vi.spyOn(window, 'confirm')
  })
  afterEach(() => {
    confirmSpy.mockRestore()
  })

  describe('Guard A: onLayoutModeDefault confirms before clearing override', () => {
    it('asks for confirmation when the document has an override', () => {
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      confirmSpy.mockReturnValue(false)
      wrapper.vm.onLayoutModeDefault()
      expect(confirmSpy).toHaveBeenCalledTimes(1)
      expect(confirmSpy.mock.calls[0][0]).toMatch(/individuelle Vorlage/i)
    })

    it('cancelled: reverts templateId to the existing override', () => {
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      confirmSpy.mockReturnValue(false)
      wrapper.vm.onLayoutModeDefault()
      expect(wrapper.vm.form.templateId).toBe(42)
    })

    it('confirmed: clears templateId', () => {
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      confirmSpy.mockReturnValue(true)
      wrapper.vm.onLayoutModeDefault()
      expect(wrapper.vm.form.templateId).toBe(null)
    })

    it('no confirmation when the document has no override', () => {
      const wrapper = mountModal({ invoice: invoiceWithoutOverride })
      confirmSpy.mockReturnValue(false)
      wrapper.vm.onLayoutModeDefault()
      expect(confirmSpy).not.toHaveBeenCalled()
      expect(wrapper.vm.form.templateId).toBe(null)
    })

    it('no confirmation when there is no invoice at all (new document)', () => {
      const wrapper = mountModal({ invoice: null })
      confirmSpy.mockReturnValue(false)
      wrapper.vm.onLayoutModeDefault()
      expect(confirmSpy).not.toHaveBeenCalled()
      expect(wrapper.vm.form.templateId).toBe(null)
    })
  })

  describe('Guard D: submit() safety net for silent templateId loss', () => {
    it('asks for confirmation when form has no override but server has one', async () => {
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      wrapper.vm.form.customerId = 1
      wrapper.vm.form.templateId = null
      confirmSpy.mockReturnValue(false)
      await wrapper.vm.submit()
      expect(confirmSpy).toHaveBeenCalled()
      const lastCall = confirmSpy.mock.calls[confirmSpy.mock.calls.length - 1]
      expect(lastCall[0]).toMatch(/individuelle Vorlage/)
    })

    it('cancelled: does not call the save API', async () => {
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      wrapper.vm.form.customerId = 1
      wrapper.vm.form.templateId = null
      confirmSpy.mockReturnValue(false)
      const { default: invoiceAPI } = await import('../services/invoiceService.js')
      await wrapper.vm.submit()
      expect(invoiceAPI.updateInvoice).not.toHaveBeenCalled()
    })

    it('confirmed: proceeds to the save API', async () => {
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      wrapper.vm.form.customerId = 1
      wrapper.vm.form.templateId = null
      confirmSpy.mockReturnValue(true)
      const { default: invoiceAPI } = await import('../services/invoiceService.js')
      await wrapper.vm.submit()
      expect(invoiceAPI.updateInvoice).toHaveBeenCalled()
    })

    it('no confirmation for new documents (props.invoice null)', async () => {
      const wrapper = mountModal({ invoice: null })
      wrapper.vm.form.customerId = 1
      wrapper.vm.form.templateId = null
      confirmSpy.mockReturnValue(false)
      await wrapper.vm.submit()
      // Guard D doesn't fire for new documents; no extra confirm beyond
      // the existing customerId check (which uses alert, not confirm).
      const guardDCalls = confirmSpy.mock.calls.filter(
        (c) => /individuelle Vorlage/.test(c[0])
      )
      expect(guardDCalls).toHaveLength(0)
    })

    it('no confirmation for offer→invoice conversions (convertFromOffer)', async () => {
      const wrapper = mountModal({
        invoice: invoiceWithOverride,
        convertFromOffer: true
      })
      wrapper.vm.form.customerId = 1
      wrapper.vm.form.templateId = null
      confirmSpy.mockReturnValue(false)
      await wrapper.vm.submit()
      const guardDCalls = confirmSpy.mock.calls.filter(
        (c) => /individuelle Vorlage/.test(c[0])
      )
      expect(guardDCalls).toHaveLength(0)
    })

    it('no confirmation for cost-calculation→offer conversions (convertFromCostCalc)', async () => {
      const wrapper = mountModal({
        invoice: invoiceWithOverride,
        convertFromCostCalc: true
      })
      wrapper.vm.form.customerId = 1
      wrapper.vm.form.templateId = null
      confirmSpy.mockReturnValue(false)
      await wrapper.vm.submit()
      const guardDCalls = confirmSpy.mock.calls.filter(
        (c) => /individuelle Vorlage/.test(c[0])
      )
      expect(guardDCalls).toHaveLength(0)
    })

    it('no confirmation when form already mirrors server (templateId is set)', async () => {
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      wrapper.vm.form.customerId = 1
      // form.templateId is null initially, simulate the user keeping the override
      wrapper.vm.form.templateId = 42
      confirmSpy.mockReturnValue(false)
      await wrapper.vm.submit()
      const guardDCalls = confirmSpy.mock.calls.filter(
        (c) => /individuelle Vorlage/.test(c[0])
      )
      expect(guardDCalls).toHaveLength(0)
    })
  })

  describe('Custom template edits are persisted (2026-06-20 bug)', () => {
    it('onCustomTemplateSaved calls updateTemplate with the clone id and the editor form', async () => {
      // The editor's save event carries the editor's local form, whose
      // `id` is undefined. The clone's id lives in form.value.templateId
      // (set by openCustomize). The handler must forward the editor's
      // form to templateAPI.updateTemplate(form.value.templateId, ...) so
      // the clone's `blocks` / `cssContent` / etc. are actually written
      // to the database. The previous version only re-fetched the
      // template, never calling updateTemplate — so the user's edits
      // (colors, new blocks) were dropped and the PDF kept rendering
      // with the global template.
      const { default: templateService } = await import('../services/templateService.js')
      const updateSpy = templateService.updateTemplate
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      // Simulate openCustomize: form.templateId is set to the clone id.
      wrapper.vm.form.templateId = 12
      // Editor's local form (no id, but carries the user's edits in
      // `blocks`, `cssContent`, etc.).
      const editorForm = {
        name: 'Custom Standardrechnung',
        documentTypeId: 1,
        isDefault: false,
        marginTopMm: 10,
        marginBottomMm: 10,
        marginLeftMm: 10,
        marginRightMm: 10,
        globalCss: '',
        cssContent: 'body { color: red; }',
        htmlContent: '<html>...</html>',
        blocks: JSON.stringify([{ id: 'h', type: 'header', data: { companyName: 'CHANGED' } }])
      }
      await wrapper.vm.onCustomTemplateSaved(editorForm)
      expect(updateSpy).toHaveBeenCalledTimes(1)
      expect(updateSpy).toHaveBeenCalledWith(12, editorForm)
    })

    it('onCustomTemplateSaved does nothing when there is no clone id', async () => {
      // Without a clone id (form.templateId is null), there's no template
      // to save to. The handler should be a no-op (not call updateTemplate
      // and not throw).
      const { default: templateService } = await import('../services/templateService.js')
      const updateSpy = templateService.updateTemplate
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      wrapper.vm.form.templateId = null
      await wrapper.vm.onCustomTemplateSaved({ name: 'X', blocks: '[]' })
      expect(updateSpy).not.toHaveBeenCalled()
    })
  })

  describe('Per-document override — deferred clone flow', () => {
    it('openCustomize does NOT create a clone when there is no override yet', async () => {
      // The previous behaviour was: openCustomize created the clone row +
      // persisted template_id on the server immediately, so cancelling
      // the editor left a dangling clone and a bound template_id. The new
      // flow defers the clone to editor save — openCustomize only fetches
      // the type default to populate the editor as a draft.
      const { default: templateService } = await import('../services/templateService.js')
      const cloneSpy = templateService.cloneForDocument
      const getDefaultSpy = templateService.getDefaultTemplate
      const wrapper = mountModal({ invoice: invoiceWithoutOverride })
      await wrapper.vm.openCustomize()
      expect(cloneSpy).not.toHaveBeenCalled()
      expect(getDefaultSpy).toHaveBeenCalledWith(1)
      // Editor is open with the type default loaded as the draft; the
      // document is still bound to no override.
      expect(wrapper.vm.showTemplateEditor).toBe(true)
      expect(wrapper.vm.editingIsNewOverride).toBe(true)
      expect(wrapper.vm.form.templateId).toBeNull()
    })

    it('openCustomize reopens the existing override (no new clone)', async () => {
      // Re-clicking "Customize…" on a document that already has an
      // override must open the existing clone in the editor, not create
      // a fresh one. The previous behaviour created a duplicate clone
      // every time the button was pressed.
      const { default: templateService } = await import('../services/templateService.js')
      const cloneSpy = templateService.cloneForDocument
      const getTemplateSpy = templateService.getTemplate
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      // The watch on props.visible doesn't fire in this test (visible=false
      // keeps the mocks unused), so seed the form directly.
      wrapper.vm.form.templateId = 42
      await wrapper.vm.openCustomize()
      expect(cloneSpy).not.toHaveBeenCalled()
      expect(getTemplateSpy).toHaveBeenCalledWith(42)
      expect(wrapper.vm.showTemplateEditor).toBe(true)
      expect(wrapper.vm.editingIsNewOverride).toBe(false)
      // form.templateId is unchanged (still the existing override's id).
      expect(wrapper.vm.form.templateId).toBe(42)
    })

    it('cancelling the editor does not bind the document to a clone', async () => {
      // The point of the deferred-clone flow: if the user opens the
      // editor and cancels, no clone is created and the document stays
      // unbound. This was a real bug in the previous flow — opening the
      // editor and cancelling still created a clone row and bound the
      // document to it.
      const { default: templateService } = await import('../services/templateService.js')
      const cloneSpy = templateService.cloneForDocument
      const updateSpy = templateService.updateTemplate
      const wrapper = mountModal({ invoice: invoiceWithoutOverride })
      await wrapper.vm.openCustomize()
      // Simulate a discard (the editor's "discard" path closes the
      // editor without calling onCustomTemplateSaved).
      wrapper.vm.closeTemplateEditor()
      expect(cloneSpy).not.toHaveBeenCalled()
      expect(updateSpy).not.toHaveBeenCalled()
      expect(wrapper.vm.form.templateId).toBeNull()
      expect(wrapper.vm.showTemplateEditor).toBe(false)
    })

    it('saving the editor with a draft creates the clone + binds template_id in one call', async () => {
      // The single-call path: editor save → cloneForDocument with the
      // editor's draft → server creates a new per-document clone row,
      // applies the editor's content, persists template_id, regenerates
      // the snapshot. The modal then writes the new id into the form.
      const { default: templateService } = await import('../services/templateService.js')
      const cloneSpy = templateService.cloneForDocument
      cloneSpy.mockResolvedValueOnce({ data: { id: 314, name: 'Mein Layout' } })
      const wrapper = mountModal({ invoice: invoiceWithoutOverride })
      await wrapper.vm.openCustomize()
      // editingCustomTemplate is the type default loaded as a draft.
      wrapper.vm.editingCustomTemplate = { id: 5, name: 'Default' }
      wrapper.vm.editingIsNewOverride = true
      const editorForm = {
        name: 'Mein Layout',
        documentTypeId: 1,
        isDefault: false,
        documentSpecific: true,
        marginTopMm: 10,
        marginBottomMm: 10,
        marginLeftMm: 10,
        marginRightMm: 10,
        globalCss: '',
        cssContent: '',
        htmlContent: '<html/>',
        blocks: '[{"id":"h","type":"header"}]'
      }
      await wrapper.vm.onCustomTemplateSaved(editorForm)
      expect(cloneSpy).toHaveBeenCalledWith(wrapper.vm.props.invoice.id, 5, editorForm)
      expect(wrapper.vm.form.templateId).toBe(314)
    })

    it('saving the editor for an existing override updates the clone in place', async () => {
      // The two-call scenario: document already has an override, user
      // edits → onCustomTemplateSaved forwards the editor's form to
      // updateTemplate(form.value.templateId, ...). The new id is the
      // existing one, not a fresh row.
      const { default: templateService } = await import('../services/templateService.js')
      const updateSpy = templateService.updateTemplate
      const cloneSpy = templateService.cloneForDocument
      const wrapper = mountModal({ invoice: invoiceWithOverride })
      wrapper.vm.form.templateId = 42 // seed the form (watch doesn't fire here)
      await wrapper.vm.openCustomize() // sets editingIsNewOverride = false
      const editorForm = { name: 'Edit', blocks: '[]' }
      await wrapper.vm.onCustomTemplateSaved(editorForm)
      expect(updateSpy).toHaveBeenCalledWith(42, editorForm)
      expect(cloneSpy).not.toHaveBeenCalled()
      // form.templateId is unchanged (still the existing override's id).
      expect(wrapper.vm.form.templateId).toBe(42)
    })
  })
})

describe('DocumentModal — VAT per line and tax treatment', () => {
  const draftWithTwoRates = {
    id: 7,
    customerId: 1,
    documentStatusId: 1,
    documentTypeId: 1,
    templateId: null,
    invoiceNumber: 'ENTWURF',
    taxTreatment: 'STANDARD',
    items: [
      { itemType: 'ARTICLE', itemId: 1, itemName: 'Beratung', quantity: 1, unitPrice: 100, unit: 'Std', taxRate: 19, children: [] },
      { itemType: 'ARTICLE', itemId: 2, itemName: 'Buch', quantity: 2, unitPrice: 25, unit: 'Stk', taxRate: 7, children: [] }
    ]
  }

  beforeEach(() => {
    vi.clearAllMocks()
  })

  it('shows net, VAT per rate and gross, and sends rates and treatment', async () => {
    const { default: invoiceAPI } = await import('../services/invoiceService.js')
    const wrapper = mountModal({ invoice: draftWithTwoRates })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    // Intl puts a non-breaking space before "€"; compare with plain spaces.
    const totals = wrapper.find('[data-testid="totals-summary"]').text().replace(/\s/g, ' ')
    expect(totals).toContain('MwSt. 7 % auf 50,00 €')
    expect(totals).toContain('3,50 €')
    expect(totals).toContain('MwSt. 19 % auf 100,00 €')
    expect(totals).toContain('172,50 €')
    expect(wrapper.findAll('[data-testid="item-tax-rate"]')).toHaveLength(2)

    await wrapper.vm.submit()

    const sent = invoiceAPI.updateInvoice.mock.calls[0][1]
    expect(sent.taxTreatment).toBe('STANDARD')
    expect(sent.items.map(i => i.taxRate)).toEqual([19, 7])
    expect(sent.items[0].unit).toBe('Std')
  })

  it('locks a Kleinunternehmer to § 19 UStG without rate selection', async () => {
    const { default: ownerAPI } = await import('../services/ownerService.js')
    const { default: invoiceAPI } = await import('../services/invoiceService.js')
    ownerAPI.getPrimaryOwner.mockResolvedValueOnce({ data: { isSmallBusiness: true } })
    const wrapper = mountModal({ invoice: draftWithTwoRates })
    await wrapper.setProps({ visible: true })
    await flushPromises()

    expect(wrapper.find('[data-testid="small-business-info"]').exists()).toBe(true)
    expect(wrapper.find('[data-testid="tax-treatment"]').exists()).toBe(false)
    expect(wrapper.findAll('[data-testid="item-tax-rate"]')).toHaveLength(0)
    expect(wrapper.find('[data-testid="totals-summary"]').text()).toContain('§ 19 UStG')

    await wrapper.vm.submit()
    expect(invoiceAPI.updateInvoice.mock.calls[0][1].taxTreatment).toBe('SMALL_BUSINESS')
  })
})

describe('DocumentModal — title', () => {
  it.each([
    [1, 'Neue Rechnung'],
    [2, 'Neues Angebot'],
    [3, 'Neuer Kostenvoranschlag'],
    [4, 'Neue Teilrechnung']
  ])('type %i is titled "%s" when created', (documentType, title) => {
    const wrapper = mountModal({ visible: true, documentType })
    expect(wrapper.find('h2').text()).toBe(title)
  })

  it('reads "Rechnung bearbeiten" when an invoice is edited', () => {
    const wrapper = mountModal({ visible: true, invoice: invoiceWithoutOverride })
    expect(wrapper.find('h2').text()).toBe('Rechnung bearbeiten')
  })
})

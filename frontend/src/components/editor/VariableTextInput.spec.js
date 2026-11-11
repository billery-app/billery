import { describe, it, expect } from 'vitest'
import { mount } from '@vue/test-utils'
import VariableTextInput from './VariableTextInput.vue'

function mountInput(props = {}) {
  return mount(VariableTextInput, { props: { modelValue: '', ...props }, attachTo: document.body })
}

const lastValue = (wrapper) => {
  const events = wrapper.emitted('update:modelValue')
  return events[events.length - 1][0]
}

describe('VariableTextInput', () => {
  it('shows variables as labelled chips and text as text', () => {
    const wrapper = mountInput({ modelValue: 'Hallo {{kunde}}, Ihre {{rechnungsnummer}}' })
    const chips = wrapper.findAll('.var-chip')
    expect(chips.map(c => c.text())).toEqual(['Kundenname', 'Rechnungsnummer'])
    expect(chips[0].attributes('contenteditable')).toBe('false')
    expect(wrapper.find('.editable').text()).not.toContain('{{')
  })

  it('marks unknown variables', () => {
    const wrapper = mountInput({ modelValue: '{{rechnungnummer}}' })
    expect(wrapper.find('.var-chip').classes()).toContain('unknown')
  })

  it('serializes text, chips and line breaks back to the stored form', async () => {
    const wrapper = mountInput({ modelValue: 'A {{firma}}', multiline: true })
    const editable = wrapper.find('.editable')
    editable.element.appendChild(document.createElement('br'))
    editable.element.appendChild(document.createTextNode('Zeile 2'))
    await editable.trigger('input')
    expect(lastValue(wrapper)).toBe('A {{firma}}\nZeile 2')
  })

  it('single-line fields never store line breaks', async () => {
    const wrapper = mountInput({ modelValue: 'A' })
    const editable = wrapper.find('.editable')
    editable.element.appendChild(document.createElement('br'))
    editable.element.appendChild(document.createTextNode('B'))
    await editable.trigger('input')
    expect(lastValue(wrapper)).toBe('A B')
  })

  it('inserts a picked variable as a chip', async () => {
    const wrapper = mountInput({ modelValue: 'Danke ' })
    await wrapper.find('.btn-insert').trigger('mousedown')
    const picker = wrapper.findComponent({ name: 'VariablePicker' })
    expect(picker.exists()).toBe(true)
    picker.vm.$emit('pick', 'kunde')
    await wrapper.vm.$nextTick()
    expect(lastValue(wrapper)).toBe('Danke {{kunde}}')
    expect(wrapper.findComponent({ name: 'VariablePicker' }).exists()).toBe(false)
  })

  it('typing @ opens the picker and picking replaces the @', async () => {
    const wrapper = mountInput({ modelValue: '' })
    const editable = wrapper.find('.editable')
    const text = document.createTextNode('Hallo @')
    editable.element.appendChild(text)
    const range = document.createRange()
    range.setStart(text, 7)
    range.collapse(true)
    window.getSelection().removeAllRanges()
    window.getSelection().addRange(range)
    editable.element.dispatchEvent(new InputEvent('input', { data: '@', bubbles: true }))
    await wrapper.vm.$nextTick()

    const picker = wrapper.findComponent({ name: 'VariablePicker' })
    expect(picker.exists()).toBe(true)
    picker.vm.$emit('pick', 'kunde')
    await wrapper.vm.$nextTick()
    expect(lastValue(wrapper)).toBe('Hallo {{kunde}}')
  })

  it('re-renders on external changes but not on its own emits', async () => {
    const wrapper = mountInput({ modelValue: 'x' })
    await wrapper.setProps({ modelValue: '{{iban}}' })
    expect(wrapper.find('.var-chip').text()).toBe('IBAN')
  })

  it('picker filters by search text', async () => {
    const wrapper = mountInput()
    await wrapper.find('.btn-insert').trigger('mousedown')
    const search = wrapper.find('.picker-search')
    await search.setValue('iban')
    const items = wrapper.findAll('.picker-item').map(i => i.text())
    expect(items).toHaveLength(1)
    expect(items[0]).toContain('IBAN')
  })
})

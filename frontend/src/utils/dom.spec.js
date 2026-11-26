import { describe, expect, it } from 'vitest'
import { inputChecked, inputValue } from './dom.js'

describe('form field of an event', () => {
  it('reads the value of a text field and the state of a checkbox', () => {
    const text = document.createElement('input')
    text.value = 'Hallo'
    const box = document.createElement('input')
    box.type = 'checkbox'
    box.checked = true
    const on = target => {
      const event = new Event('change')
      Object.defineProperty(event, 'target', { value: target })
      return event
    }
    expect(inputValue(on(text))).toBe('Hallo')
    expect(inputChecked(on(box))).toBe(true)
  })
})

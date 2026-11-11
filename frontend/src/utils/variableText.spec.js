import { describe, it, expect } from 'vitest'
import {
  parseVariableText, serializeVariableText, variablesIn, unknownVariablesIn, resolveVariableText
} from './variableText.js'

describe('variableText', () => {
  it('parses text and variables and serializes back losslessly', () => {
    const text = 'Hallo {{kunde}},\nRechnung {{ rechnungsnummer }}.'
    const segments = parseVariableText(text)
    expect(segments).toEqual([
      { type: 'text', value: 'Hallo ' },
      { type: 'var', name: 'kunde' },
      { type: 'text', value: ',\nRechnung ' },
      { type: 'var', name: 'rechnungsnummer' },
      { type: 'text', value: '.' }
    ])
    expect(serializeVariableText(segments)).toBe('Hallo {{kunde}},\nRechnung {{rechnungsnummer}}.')
  })

  it('handles empty and variable-only text', () => {
    expect(parseVariableText('')).toEqual([])
    expect(parseVariableText(null)).toEqual([])
    expect(parseVariableText('{{firma}}')).toEqual([{ type: 'var', name: 'firma' }])
  })

  it('finds unknown variables, accepting English aliases', () => {
    expect(variablesIn('{{a}} {{b}}')).toEqual(['a', 'b'])
    expect(unknownVariablesIn('{{firma}} {{client_name}} {{rechnungnummer}}')).toEqual(['rechnungnummer'])
  })

  it('resolves known variables and aliases, leaves unknown ones visible', () => {
    const values = { firma: 'ACME', kunde: 'Anna' }
    expect(resolveVariableText('{{firma}} / {{client_name}} / {{x}}', values)).toBe('ACME / Anna / {{x}}')
    expect(resolveVariableText(42, values)).toBe(42)
  })
})

import { describe, it, expect } from 'vitest'
import { MAX_IMPORT_BYTES, formatFileSize, getFileExtension, validateImportFile } from './fileValidation.js'

const file = (name, size = 1000, type = '') => ({ name, size, type })

describe('validateImportFile', () => {
  it('accepts DOCX and ODT by extension, also without a MIME type (phones)', () => {
    expect(validateImportFile(file('Vorlage.DOCX')).isValid).toBe(true)
    expect(validateImportFile(file('brief.odt')).isValid).toBe(true)
  })

  it('refuses other files, files above the server limit and no file, in German', () => {
    expect(validateImportFile(file('rechnung.pdf')).errors).toEqual(['Bitte eine DOCX- oder ODT-Datei wählen.'])
    expect(validateImportFile(file('gross.docx', MAX_IMPORT_BYTES + 1)).errors[0]).toContain('zu groß')
    expect(validateImportFile(null).isValid).toBe(false)
  })
})

describe('helpers', () => {
  it('reads extensions and formats sizes', () => {
    expect(getFileExtension('a.b.ODT')).toBe('odt')
    expect(getFileExtension('ohne')).toBe('')
    expect(formatFileSize(MAX_IMPORT_BYTES)).toBe('2 MB')
  })
})

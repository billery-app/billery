import { describe, it, expect } from 'vitest'
import {
  SUPPORTED_IMPORT_TYPES,
  SUPPORTED_EXPORT_FORMATS,
  FILE_SIZE_LIMITS,
  isSupportedImportType,
  isSupportedExportFormat,
  isFileSizeValid,
  validateImportFile,
  formatFileSize,
  getFileExtension,
  createFilename
} from './fileValidation.js'

describe('fileValidation - Real Workflows', () => {
  describe('Supported Import Types', () => {
    it('accepts DOCX files', () => {
      expect(isSupportedImportType(SUPPORTED_IMPORT_TYPES.DOCX)).toBe(true)
    })

    it('accepts ODT files', () => {
      expect(isSupportedImportType(SUPPORTED_IMPORT_TYPES.ODT)).toBe(true)
    })

    it('rejects PDF files', () => {
      expect(isSupportedImportType('application/pdf')).toBe(false)
    })

    it('rejects unknown types', () => {
      expect(isSupportedImportType('application/unknown')).toBe(false)
    })
  })

  describe('Supported Export Formats', () => {
    it('accepts docx format', () => {
      expect(isSupportedExportFormat('docx')).toBe(true)
      expect(isSupportedExportFormat('DOCX')).toBe(true)
    })

    it('accepts odt format', () => {
      expect(isSupportedExportFormat('odt')).toBe(true)
      expect(isSupportedExportFormat('ODT')).toBe(true)
    })

    it('rejects pdf format', () => {
      expect(isSupportedExportFormat('pdf')).toBe(false)
    })
  })

  describe('File Size Validation', () => {
    it('accepts file within import limit', () => {
      expect(isFileSizeValid(5 * 1024 * 1024)).toBe(true) // 5MB
    })

    it('accepts file at import limit', () => {
      expect(isFileSizeValid(10 * 1024 * 1024)).toBe(true) // 10MB exactly
    })

    it('rejects file exceeding import limit', () => {
      expect(isFileSizeValid(11 * 1024 * 1024)).toBe(false) // 11MB
    })

    it('accepts file with custom limit', () => {
      expect(isFileSizeValid(5 * 1024 * 1024, 50 * 1024 * 1024)).toBe(true)
    })
  })

  describe('Import File Validation', () => {
    it('validates valid DOCX file', () => {
      const mockFile = {
        type: SUPPORTED_IMPORT_TYPES.DOCX,
        size: 5 * 1024 * 1024
      }

      const result = validateImportFile(mockFile)
      expect(result.isValid).toBe(true)
      expect(result.errors).toHaveLength(0)
    })

    it('validates valid ODT file', () => {
      const mockFile = {
        type: SUPPORTED_IMPORT_TYPES.ODT,
        size: 5 * 1024 * 1024
      }

      const result = validateImportFile(mockFile)
      expect(result.isValid).toBe(true)
    })

    it('rejects invalid file type', () => {
      const mockFile = {
        type: 'application/pdf',
        size: 5 * 1024 * 1024
      }

      const result = validateImportFile(mockFile)
      expect(result.isValid).toBe(false)
      expect(result.errors).toContain('Bitte wählen Sie eine gültige DOCX oder ODT Datei.')
    })

    it('rejects file that is too large', () => {
      const mockFile = {
        type: SUPPORTED_IMPORT_TYPES.DOCX,
        size: 15 * 1024 * 1024
      }

      const result = validateImportFile(mockFile)
      expect(result.isValid).toBe(false)
      expect(result.errors[0]).toContain('zu groß')
    })
  })

  describe('File Size Formatting', () => {
    it('formats 0 bytes', () => {
      expect(formatFileSize(0)).toBe('0 Bytes')
    })

    it('formats bytes', () => {
      expect(formatFileSize(500)).toBe('500 Bytes')
    })

    it('formats kilobytes', () => {
      expect(formatFileSize(1024)).toBe('1 KB')
    })

    it('formats megabytes', () => {
      expect(formatFileSize(1024 * 1024)).toBe('1 MB')
    })

    it('formats gigabytes', () => {
      expect(formatFileSize(1024 * 1024 * 1024)).toBe('1 GB')
    })
  })

  describe('File Extension Extraction', () => {
    it('extracts extension from filename', () => {
      expect(getFileExtension('document.docx')).toBe('docx')
      expect(getFileExtension('file.odt')).toBe('odt')
    })

    it('handles uppercase extension', () => {
      expect(getFileExtension('file.DOCX')).toBe('docx')
    })

    it('handles multiple dots', () => {
      expect(getFileExtension('file.backup.docx')).toBe('docx')
    })
  })

  describe('Filename Creation', () => {
    it('creates filename with format extension', () => {
      expect(createFilename('My Document', 'docx')).toBe('My Document.docx')
    })

    it('removes existing extension', () => {
      expect(createFilename('Document.docx', 'pdf')).toBe('Document.pdf')
    })

    it('handles uppercase format', () => {
      expect(createFilename('Doc', 'DOCX')).toBe('Doc.docx')
    })

    it('handles empty base name', () => {
      expect(createFilename('', 'docx')).toBe('.docx')
    })
  })

  describe('Constants', () => {
    it('defines import limit', () => {
      expect(FILE_SIZE_LIMITS.IMPORT).toBe(10 * 1024 * 1024)
    })

    it('defines export limit', () => {
      expect(FILE_SIZE_LIMITS.EXPORT).toBe(50 * 1024 * 1024)
    })

    it('defines export formats', () => {
      expect(SUPPORTED_EXPORT_FORMATS).toContain('docx')
      expect(SUPPORTED_EXPORT_FORMATS).toContain('odt')
    })
  })
})
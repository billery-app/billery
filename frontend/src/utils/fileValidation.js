/**
 * File validation utilities for document import/export operations
 */

export const SUPPORTED_IMPORT_TYPES = {
  DOCX: 'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  ODT: 'application/vnd.oasis.opendocument.text'
}

export const SUPPORTED_EXPORT_FORMATS = ['docx', 'odt']

export const FILE_SIZE_LIMITS = {
  IMPORT: 10 * 1024 * 1024, // 10MB
  EXPORT: 50 * 1024 * 1024  // 50MB (for safety)
}

/**
 * Validates if a file type is supported for import
 * @param {string} mimeType - The MIME type to check
 * @returns {boolean} - True if supported
 */
export function isSupportedImportType(mimeType) {
  return Object.values(SUPPORTED_IMPORT_TYPES).includes(mimeType)
}

/**
 * Validates if an export format is supported
 * @param {string} format - The format to check (e.g., 'docx', 'odt')
 * @returns {boolean} - True if supported
 */
export function isSupportedExportFormat(format) {
  return SUPPORTED_EXPORT_FORMATS.includes(format.toLowerCase())
}

/**
 * Validates file size against import limits
 * @param {number} sizeInBytes - File size in bytes
 * @param {number} limit - Optional custom limit (defaults to IMPORT limit)
 * @returns {boolean} - True if within limits
 */
export function isFileSizeValid(sizeInBytes, limit = FILE_SIZE_LIMITS.IMPORT) {
  return sizeInBytes <= limit
}

/**
 * Validates a file for import operations
 * @param {File} file - The file object to validate
 * @returns {Object} - Validation result with isValid and error properties
 */
export function validateImportFile(file) {
  const errors = []

  // Check file type
  if (!isSupportedImportType(file.type)) {
    errors.push('Bitte wählen Sie eine gültige DOCX oder ODT Datei.')
  }

  // Check file size
  if (!isFileSizeValid(file.size)) {
    errors.push(`Die Datei ist zu groß. Maximale Größe: ${formatFileSize(FILE_SIZE_LIMITS.IMPORT)}.`)
  }

  // Check if file exists
  if (!file) {
    errors.push('Bitte wählen Sie eine Datei aus.')
  }

  return {
    isValid: errors.length === 0,
    errors
  }
}

/**
 * Formats file size in bytes to human readable format
 * @param {number} bytes - File size in bytes
 * @returns {string} - Formatted file size string
 */
export function formatFileSize(bytes) {
  if (bytes === 0) return '0 Bytes'
  
  const k = 1024
  const sizes = ['Bytes', 'KB', 'MB', 'GB']
  const i = Math.floor(Math.log(bytes) / Math.log(k))
  
  return parseFloat((bytes / Math.pow(k, i)).toFixed(2)) + ' ' + sizes[i]
}

/**
 * Gets file extension from filename
 * @param {string} filename - The filename
 * @returns {string} - The file extension (without dot)
 */
export function getFileExtension(filename) {
  return filename.split('.').pop().toLowerCase()
}

/**
 * Creates a filename with proper extension
 * @param {string} baseName - The base filename
 * @param {string} format - The format (e.g., 'docx', 'odt')
 * @returns {string} - Complete filename with extension
 */
export function createFilename(baseName, format) {
  const cleanName = baseName.replace(/\.[^/.]+$/, '') // Remove existing extension
  return `${cleanName}.${format.toLowerCase()}`
}

/**
 * Downloads a blob as a file
 * @param {Blob} blob - The blob data to download
 * @param {string} filename - The filename for the download
 */
export function downloadBlob(blob, filename) {
  const url = window.URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.setAttribute('download', filename)
  document.body.appendChild(link)
  link.click()
  link.remove()
  window.URL.revokeObjectURL(url)
}

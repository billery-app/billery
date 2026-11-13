/**
 * Checks for the template import (DOCX / ODT) and the download of exported files.
 */

/** The file types the template import reads, by extension (phones often send no MIME type). */
export const IMPORT_EXTENSIONS = ['docx', 'odt']

/** The server's upload limit (`spring.servlet.multipart.max-file-size`). */
export const MAX_IMPORT_BYTES = 2 * 1024 * 1024

/**
 * @param {string} filename
 * @returns {string} the extension in lower case, without dot ('' when there is none)
 */
export function getFileExtension(filename) {
  const name = String(filename || '')
  const dot = name.lastIndexOf('.')
  return dot > 0 ? name.slice(dot + 1).toLowerCase() : ''
}

/**
 * Checks a file before it is sent to the import.
 * @param {File|null|undefined} file
 * @returns {{isValid: boolean, errors: string[]}}
 */
export function validateImportFile(file) {
  if (!file) return { isValid: false, errors: ['Bitte eine Datei auswählen.'] }
  const errors = []
  if (!IMPORT_EXTENSIONS.includes(getFileExtension(file.name))) {
    errors.push('Bitte eine DOCX- oder ODT-Datei wählen.')
  }
  if (file.size > MAX_IMPORT_BYTES) {
    errors.push(`Die Datei ist zu groß (höchstens ${formatFileSize(MAX_IMPORT_BYTES)}).`)
  }
  return { isValid: errors.length === 0, errors }
}

/**
 * @param {number} bytes
 * @returns {string} e.g. "2 MB"
 */
export function formatFileSize(bytes) {
  if (!bytes) return '0 Bytes'
  const units = ['Bytes', 'KB', 'MB', 'GB']
  const i = Math.min(Math.floor(Math.log(bytes) / Math.log(1024)), units.length - 1)
  return `${parseFloat((bytes / Math.pow(1024, i)).toFixed(2))} ${units[i]}`
}

/**
 * Saves a blob as a download.
 * @param {Blob} blob
 * @param {string} filename
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

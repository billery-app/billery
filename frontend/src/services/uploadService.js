import { apiClient } from './baseService.js'

/**
 * Uploads a logo image (PNG, JPG, GIF) to `POST /api/upload`.
 *
 * @param {File} file the image chosen or dropped by the user
 * @returns {Promise<string>} URL of the stored file (`.../uploads/<name>`), which the
 *   PDF renderer reads from the upload directory
 * @throws {Error} with a German message when the upload fails
 */
export async function uploadLogo(file) {
  const formData = new FormData()
  formData.append('file', file)
  try {
    const response = await apiClient.post('/upload', formData)
    return response.data.url
  } catch (error) {
    throw new Error('Logo-Upload fehlgeschlagen: ' + (error.response?.data?.message || error.message))
  }
}

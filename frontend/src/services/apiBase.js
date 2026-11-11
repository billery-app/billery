/**
 * Base URL of the backend API. Empty `VITE_API_BASE_URL` means the page's own origin (the SPA is
 * served by the backend).
 * @returns {string} - e.g. `https://app.example.com/api`
 */
export function apiBaseUrl() {
  return `${import.meta.env.VITE_API_BASE_URL || window.location.origin}/api`
}

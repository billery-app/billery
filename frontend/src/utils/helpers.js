export function generateId() {
  return 'id_' + Date.now().toString(36) + '_' + Math.random().toString(36).substr(2, 9)
}

export function formatCurrency(amount, currency = 'EUR', locale = 'de-DE') {
  return new Intl.NumberFormat(locale, {
    style: 'currency',
    currency: currency
  }).format(amount)
}

export function formatDate(dateStr, format = 'DD.MM.YYYY', locale = 'de-DE') {
  if (!dateStr) return ''
  const date = new Date(dateStr)
  
  const day = String(date.getDate()).padStart(2, '0')
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const year = date.getFullYear()
  
  if (format === 'DD.MM.YYYY') {
    return `${day}.${month}.${year}`
  }
  if (format === 'YYYY-MM-DD') {
    return `${year}-${month}-${day}`
  }
  return date.toLocaleDateString(locale)
}

/**
 * Calls `fn` only after `delay` ms without a further call, with the last arguments and the `this`
 * of that call (a regular function on purpose: an arrow function would pass the module's
 * `undefined`).
 * @template {(...args: any[]) => void} F
 * @param {F} fn
 * @param {number} [delay]
 * @returns {(this: unknown, ...args: Parameters<F>) => void}
 */
export function debounce(fn, delay = 300) {
  /** @type {ReturnType<typeof setTimeout> | undefined} */
  let timeoutId
  return function (...args) {
    clearTimeout(timeoutId)
    timeoutId = setTimeout(() => fn.apply(this, args), delay)
  }
}

export function deepClone(obj) {
  return JSON.parse(JSON.stringify(obj))
}

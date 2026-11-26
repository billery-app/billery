/**
 * Reading the router's address parameters with a type: vue-router types a query value as
 * `string | null | (string | null)[]` (`?a=1&a=2` repeats a key), which every reader of a single
 * value has to narrow (#39).
 */

/**
 * The single value of a query or path parameter: the first one if the key repeats.
 * @param {import('vue-router').LocationQueryValue | import('vue-router').LocationQueryValue[] | undefined} value
 * @returns {string} the value, or '' when it is missing
 */
export function param(value) {
  const first = Array.isArray(value) ? value[0] : value
  return first ?? ''
}

/**
 * A numeric parameter (an id, a year).
 * @param {import('vue-router').LocationQueryValue | import('vue-router').LocationQueryValue[] | undefined} value
 * @returns {number|null} the number, or null when it is missing or not a number
 */
export function numberParam(value) {
  const number = parseInt(param(value), 10)
  return Number.isNaN(number) ? null : number
}

/**
 * Typed access to the form field an event came from, for templates: `$event.target` is only an
 * `EventTarget` to the type checker, which has no `value` or `checked` (#39).
 */

/**
 * @param {Event} event - an `input` / `change` event of an input, select or textarea
 * @returns {string} the field's value
 */
export function inputValue(event) {
  return /** @type {HTMLInputElement} */ (event.target).value
}

/**
 * @param {Event} event - a `change` event of a checkbox
 * @returns {boolean} whether it is checked
 */
export function inputChecked(event) {
  return /** @type {HTMLInputElement} */ (event.target).checked
}

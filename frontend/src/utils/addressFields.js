/**
 * Browser autofill knows the street and the house number only together ("address-line1"), the
 * forms keep them apart (the house number is a whole number in the database). When autofill or
 * the user puts "Musterstraße 12" into the street field and the house number is still empty, the
 * trailing number moves into the house number field.
 *
 * @param {{street?: string, houseNumber?: number|string|null}} form - changed in place
 * @returns {boolean} whether the street was split
 */
export function splitStreetAndNumber(form) {
  if (form.houseNumber !== null && form.houseNumber !== undefined && form.houseNumber !== '') {
    return false
  }
  const match = /^(.*\S)\s+(\d{1,6})$/.exec((form.street || '').trim())
  if (!match) return false
  form.street = match[1]
  form.houseNumber = Number(match[2])
  return true
}

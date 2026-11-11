/** German labels of the tenant roles (see `Role` in the backend). */
const LABELS = {
  OWNER: 'Inhaber',
  ADMIN: 'Administrator',
  ACCOUNTANT: 'Buchhaltung / Steuerberatung',
  READER: 'Nur lesen'
}

/**
 * @param {string} role - OWNER, ADMIN, ACCOUNTANT or READER
 * @returns {string} - The label shown to users
 */
export function roleLabel(role) {
  return LABELS[role] || role
}

/**
 * The roles the acting role may hand out or change: an owner every role, an admin only
 * accountants and readers (the backend enforces the same rule).
 * @param {string|null} actingRole
 * @returns {string[]}
 */
export function manageableRoles(actingRole) {
  if (actingRole === 'OWNER') return ['OWNER', 'ADMIN', 'ACCOUNTANT', 'READER']
  if (actingRole === 'ADMIN') return ['ACCOUNTANT', 'READER']
  return []
}

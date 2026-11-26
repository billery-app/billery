import { t } from '../i18n/index.js'

/** The tenant roles (see `Role` in the backend); their names are in the message files. */
const ROLES = ['OWNER', 'ADMIN', 'ACCOUNTANT', 'READER']

/**
 * @param {string} role - OWNER, ADMIN, ACCOUNTANT or READER
 * @returns {string} - The label shown to users, in the interface language
 */
export function roleLabel(role) {
  return ROLES.includes(role) ? t(`roles.${role}`) : role
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

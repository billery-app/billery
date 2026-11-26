import { activeRole, isSaas, session } from '../services/session.js'

/**
 * The areas of the settings page, in display order, grouped as on the overview.
 *
 * Every area is a card. `to` is where the card leads: a view of its own (Inhaber, Vorlagen) or a
 * sub-page under `/settings/<id>` that shows the area's component (`SettingsAreaView`).
 * `available()` decides whether the card is shown and whether its sub-page may be opened; the
 * overview and the router guard both ask it, so a hidden area cannot be reached by its address
 * either. Areas with `upcoming: true` are shown as disabled "Demnächst verfügbar" cards.
 *
 * The titles are the names the documentation uses ("Einstellungen → Team & Zugriff").
 */
export const SETTINGS_GROUPS = [
  {
    id: 'general',
    title: 'Allgemein',
    areas: [
      {
        id: 'owners',
        to: '/owners',
        icon: '👤',
        title: 'Inhaber',
        description: 'Firmendaten und Stammdaten des Rechnungsstellers'
      },
      {
        id: 'templates',
        to: '/templates',
        icon: '📄',
        title: 'Dokumentvorlagen',
        description: 'Vorlagen für Rechnungen, Angebote und Kalkulationen'
      },
      {
        id: 'account',
        to: '/settings/account',
        icon: '🔑',
        title: 'Konto',
        description: 'Angemeldetes Konto, aktives Unternehmen und Ihre Rolle',
        // SaaS: every role sees who is signed in where
        available: () => isSaas()
      },
      {
        id: 'team',
        to: '/settings/team',
        icon: '👥',
        title: 'Team & Zugriff',
        description: 'Mitglieder, Einladungen, Rollen und Änderungsprotokoll',
        // SaaS: the team is managed by the owners and admins of the company
        available: () => isSaas() && ['OWNER', 'ADMIN'].includes(activeRole())
      }
    ]
  },
  {
    id: 'data',
    title: 'Daten',
    areas: [
      {
        id: 'export',
        to: '/settings/export',
        icon: '📦',
        title: 'Daten & Export',
        description: 'GoBD-Export für Betriebsprüfung und Steuerberatung'
      },
      {
        id: 'backup',
        to: '/settings/backup',
        icon: '💾',
        title: 'Datensicherung',
        description: 'Sicherungen der Datenbank und Dateien, Wiederherstellung',
        // the H2 file database of the desktop app; a server is backed up by its operator
        available: () => session.desktop
      }
    ]
  },
  {
    id: 'more',
    title: 'Weitere Einstellungen',
    areas: [
      {
        id: 'mail',
        to: '/settings/mail',
        icon: '✉️',
        title: 'Mail-Einstellungen',
        description: 'Mailkonten und Vorlagen für den Versand an Kunden',
        // SaaS: the company's mail accounts are managed by its owners and admins
        available: () => !isSaas() || ['OWNER', 'ADMIN'].includes(activeRole())
      },
      {
        id: 'profile',
        upcoming: true,
        icon: '🪪',
        title: 'Benutzer & Profil',
        description: 'Persönliche Einstellungen und Profil'
      },
      {
        id: 'help',
        to: '/settings/help',
        icon: '❓',
        title: 'Hilfe',
        description: 'Einführungstour starten und wiederholen'
      }
    ]
  }
]

/**
 * Whether an area is shown (and, for a sub-page, may be opened) in the current mode and role.
 *
 * @param {object} area an entry of {@link SETTINGS_GROUPS}
 * @returns {boolean}
 */
export function isAvailable(area) {
  return !area.available || Boolean(area.available())
}

/**
 * The groups with only the areas that apply now; a group left without areas is dropped.
 *
 * @returns {Array<{id: string, title: string, areas: object[]}>}
 */
export function visibleGroups() {
  return SETTINGS_GROUPS.map(group => ({ ...group, areas: group.areas.filter(isAvailable) })).filter(
    group => group.areas.length > 0
  )
}

/**
 * The area with this id, or null.
 *
 * @param {string} id e.g. 'team'
 * @returns {object|null}
 */
export function findArea(id) {
  for (const group of SETTINGS_GROUPS) {
    const area = group.areas.find(entry => entry.id === id)
    if (area) {
      return area
    }
  }
  return null
}

/**
 * Router guard of the settings sub-pages: an area that does not apply (another mode, a role
 * without access) or does not exist leads back to the overview instead of showing a page that
 * would only fail.
 *
 * @param {import('vue-router').RouteLocationNormalized} to the sub-page being opened
 * @returns {true|string} true to open it, otherwise the overview's path
 */
export function guardSettingsArea(to) {
  const area = findArea(/** @type {string} */ (to.meta.area))
  return area && !area.upcoming && isAvailable(area) ? true : '/settings'
}

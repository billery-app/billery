import { activeRole, isSaas, session } from '../services/session.js'
import { t } from '../i18n/index.js'

/**
 * The areas of the settings page, in display order, grouped as on the overview.
 *
 * Every area is a card. `to` is where the card leads: a view of its own (Inhaber, Vorlagen) or a
 * sub-page under `/settings/<id>` that shows the area's component (`SettingsAreaView`).
 * `available()` decides whether the card is shown and whether its sub-page may be opened; the
 * overview and the router guard both ask it, so a hidden area cannot be reached by its address
 * either. Areas with `upcoming: true` are shown as disabled "Demnächst verfügbar" cards.
 *
 * The titles are the names the documentation uses ("Einstellungen → Team & Zugriff"); they come from
 * the message files (`settings.areas.<id>.title`), so they follow the interface language.
 */
export const SETTINGS_GROUPS = [
  {
    id: 'general',
    get title() {
      return t('settings.groups.general')
    },
    areas: [
      {
        id: 'owners',
        to: '/owners',
        icon: '👤',
        get title() {
          return t('settings.areas.owners.title')
        },
        get description() {
          return t('settings.areas.owners.description')
        }
      },
      {
        id: 'templates',
        to: '/templates',
        icon: '📄',
        get title() {
          return t('settings.areas.templates.title')
        },
        get description() {
          return t('settings.areas.templates.description')
        }
      },
      {
        id: 'account',
        to: '/settings/account',
        icon: '🔑',
        get title() {
          return t('settings.areas.account.title')
        },
        get description() {
          return t('settings.areas.account.description')
        },
        // SaaS: every role sees who is signed in where
        available: () => isSaas()
      },
      {
        id: 'team',
        to: '/settings/team',
        icon: '👥',
        get title() {
          return t('settings.areas.team.title')
        },
        get description() {
          return t('settings.areas.team.description')
        },
        // SaaS: the team is managed by the owners and admins of the company
        available: () => isSaas() && ['OWNER', 'ADMIN'].includes(activeRole())
      }
    ]
  },
  {
    id: 'data',
    get title() {
      return t('settings.groups.data')
    },
    areas: [
      {
        id: 'export',
        to: '/settings/export',
        icon: '📦',
        get title() {
          return t('settings.areas.export.title')
        },
        get description() {
          return t('settings.areas.export.description')
        }
      },
      {
        id: 'backup',
        to: '/settings/backup',
        icon: '💾',
        get title() {
          return t('settings.areas.backup.title')
        },
        get description() {
          return t('settings.areas.backup.description')
        },
        // the H2 file database of the desktop app; a server is backed up by its operator
        available: () => session.desktop
      }
    ]
  },
  {
    id: 'more',
    get title() {
      return t('settings.groups.more')
    },
    areas: [
      {
        id: 'mail',
        to: '/settings/mail',
        icon: '✉️',
        get title() {
          return t('settings.areas.mail.title')
        },
        get description() {
          return t('settings.areas.mail.description')
        },
        // SaaS: the company's mail accounts are managed by its owners and admins
        available: () => !isSaas() || ['OWNER', 'ADMIN'].includes(activeRole())
      },
      {
        id: 'language',
        to: '/settings/language',
        icon: '🌐',
        get title() {
          return t('settings.areas.language.title')
        },
        get description() {
          return t('settings.areas.language.description')
        }
      },
      {
        id: 'profile',
        upcoming: true,
        icon: '🪪',
        get title() {
          return t('settings.areas.profile.title')
        },
        get description() {
          return t('settings.areas.profile.description')
        }
      },
      {
        id: 'help',
        to: '/settings/help',
        icon: '❓',
        get title() {
          return t('settings.areas.help.title')
        },
        get description() {
          return t('settings.areas.help.description')
        }
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

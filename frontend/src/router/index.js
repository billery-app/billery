import { createRouter, createWebHistory } from 'vue-router'
import DashboardView from '../views/DashboardView.vue'
import CustomersView from '../views/CustomersView.vue'
import CustomerDetailView from '../views/CustomerDetailView.vue'
import InvoicesView from '../views/InvoicesView.vue'
import ArticlesServicesView from '../views/ArticlesServicesView.vue'
import OwnersView from '../views/OwnersView.vue'
import TemplatesView from '../views/TemplatesView.vue'
import SearchView from '../views/SearchView.vue'
import SettingsView from '../views/SettingsView.vue'
import SettingsAreaView from '../views/SettingsAreaView.vue'
import { guardSettingsArea } from '../utils/settingsAreas.js'

/** @type {import('vue-router').RouteRecordRaw[]} */
const routes = [
  { path: '/', component: DashboardView },
  { path: '/customers', component: CustomersView },
  { path: '/customers/:id', component: CustomerDetailView },
  { path: '/invoices', component: InvoicesView },
  { path: '/articles-services', component: ArticlesServicesView },
  { path: '/owners', component: OwnersView },
  { path: '/templates', component: TemplatesView },
  { path: '/search', component: SearchView },
  // The settings overview and its sub-pages. The parent has no component of its own, so the
  // navigation's "Einstellungen" stays highlighted on every sub-page; an area that does not apply
  // (mode, role) leads back to the overview (utils/settingsAreas.js).
  {
    path: '/settings',
    children: [
      { path: '', component: SettingsView },
      ...['account', 'team', 'export', 'backup', 'mail', 'language', 'help'].map(area => ({
        path: area,
        component: SettingsAreaView,
        meta: { area },
        beforeEnter: guardSettingsArea
      }))
    ]
  },
  // An unknown address (an old bookmark, a stale sign-in callback) shows the dashboard, not an
  // empty page.
  { path: '/:pathMatch(.*)*', redirect: '/' }
]

/**
 * Creates the router. The browser history reads the current address when it is created, so this
 * is called only after {@code initAuth} has finished: the sign-in callback replaces
 * {@code /auth/callback?code=…} with the address the user came from, and a router created before
 * that (at import time) navigated back to the callback URL, which has no route — an empty page
 * after every sign-in.
 * @returns {import('vue-router').Router}
 */
export function createAppRouter() {
  return createRouter({
    history: createWebHistory(),
    routes
  })
}

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

const routes = [
  { path: '/', component: DashboardView },
  { path: '/customers', component: CustomersView },
  { path: '/customers/:id', component: CustomerDetailView },
  { path: '/invoices', component: InvoicesView },
  { path: '/articles-services', component: ArticlesServicesView },
  { path: '/owners', component: OwnersView },
  { path: '/templates', component: TemplatesView },
  { path: '/search', component: SearchView },
  { path: '/settings', component: SettingsView }
]

const router = createRouter({
  history: createWebHistory(),
  routes
})

export default router

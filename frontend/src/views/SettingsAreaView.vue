<template>
  <div class="settings-view settings-area-view">
    <header class="view-header">
      <router-link to="/settings" class="back-link" data-testid="settings-back">‹ Einstellungen</router-link>
      <!-- visible: a sub-page is not a navigation destination of its own (unlike the overview) -->
      <h1>{{ area ? area.title : 'Einstellungen' }}</h1>
    </header>
    <component :is="areaComponent" v-if="areaComponent" />
  </div>
</template>

<script>
import { findArea } from '../utils/settingsAreas.js'
import AccountSettings from '../components/AccountSettings.vue'
import BackupSettings from '../components/BackupSettings.vue'
import GobdExportSettings from '../components/GobdExportSettings.vue'
import HelpSettings from '../components/HelpSettings.vue'
import MailSettings from '../components/MailSettings.vue'
import TeamSettings from '../components/TeamSettings.vue'

/** Which component an area's sub-page shows (`/settings/<id>`). */
const COMPONENTS = {
  account: AccountSettings,
  team: TeamSettings,
  export: GobdExportSettings,
  backup: BackupSettings,
  mail: MailSettings,
  help: HelpSettings
}

/**
 * A sub-page of the settings: the area named by the route's `meta.area`, under its title, with a
 * way back to the overview. Whether the area may be opened at all is decided before, by the
 * router guard (`guardSettingsArea`).
 */
export default {
  name: 'SettingsAreaView',
  computed: {
    /** The area of the current route (title, description). */
    area() {
      return findArea(this.$route.meta.area)
    },
    /** The component that shows the area's settings. */
    areaComponent() {
      return COMPONENTS[this.$route.meta.area] || null
    }
  }
}
</script>

<style scoped>
.settings-view {
  padding: 20px;
  max-width: 1400px;
  margin: 0 auto;
}

.view-header {
  margin-bottom: 24px;
}

.view-header h1 {
  margin: 0;
  font-size: 1.75rem;
  color: var(--color-text);
}

.back-link {
  display: inline-block;
  margin-bottom: 6px;
  color: var(--color-text-muted);
  text-decoration: none;
  font-size: 0.9rem;
}

.back-link:hover {
  color: var(--color-primary);
  text-decoration: underline;
}

@media (max-width: 600px) {
  .settings-view { padding: 12px; }
}
</style>

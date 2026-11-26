<template>
  <div class="settings-view">
    <header class="view-header">
      <div>
        <h1 class="nav-destination-page-title">{{ $t('settings.title') }}</h1>
      </div>
    </header>

    <section
      v-for="group in groups"
      :key="group.id"
      class="settings-section"
      :data-testid="`settings-group-${group.id}`"
    >
      <h2 class="section-title">{{ group.title }}</h2>
      <div class="settings-grid">
        <template v-for="area in group.areas" :key="area.id">
          <div
            v-if="area.upcoming"
            class="settings-card is-disabled"
            :aria-disabled="true"
            :data-testid="`settings-card-${area.id}`"
          >
            <div class="card-icon">{{ area.icon }}</div>
            <div class="card-body">
              <div class="card-title">
                {{ area.title }}
                <span class="coming-soon-badge">{{ $t('settings.upcoming') }}</span>
              </div>
              <div class="card-description">{{ area.description }}</div>
            </div>
          </div>
          <router-link
            v-else
            :to="area.to"
            class="settings-card"
            :data-testid="`settings-card-${area.id}`"
          >
            <div class="card-icon">{{ area.icon }}</div>
            <div class="card-body">
              <div class="card-title">{{ area.title }}</div>
              <div class="card-description">{{ area.description }}</div>
            </div>
            <div class="card-chevron">&rsaquo;</div>
          </router-link>
        </template>
      </div>
    </section>
    <p v-if="version" class="app-version" data-testid="app-version">{{ $t('settings.version', { version }) }}</p>
  </div>
</template>

<script>
import { visibleGroups } from '../utils/settingsAreas.js'
import { session } from '../services/session.js'

/**
 * The settings overview: one card per area, grouped. A card opens its own view (Inhaber,
 * Vorlagen) or a sub-page under `/settings/<id>` (`SettingsAreaView`); areas that do not apply
 * to the current mode or role are not shown (`utils/settingsAreas.js`), areas still to come are
 * disabled cards.
 */
export default {
  name: 'SettingsView',
  computed: {
    /** The groups and areas that apply now. */
    groups() {
      return visibleGroups()
    },
    /** The backend's version (`/api/config`), the reference for support and later updates. */
    version() {
      return session.version
    }
  }
}
</script>

<style scoped>
.app-version {
  margin: var(--space-6) 0 0;
  color: var(--color-text-subtle);
  font-size: var(--font-size-xs);
  text-align: center;
}

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

.settings-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 280px), 1fr));
  gap: 16px;
}

.settings-card {
  background: var(--color-surface);
  border-radius: 8px;
  box-shadow: var(--shadow-sm);
  padding: 16px;
  display: flex;
  align-items: center;
  gap: 14px;
  text-decoration: none;
  color: inherit;
  transition: box-shadow 0.2s, transform 0.2s, border-color 0.2s;
  border: 2px solid transparent;
  min-height: 80px;
}

.settings-card:hover {
  box-shadow: var(--shadow-lg);
  transform: translateY(-2px);
  border-color: var(--color-primary);
}

.settings-card.is-disabled {
  cursor: not-allowed;
  opacity: 0.65;
  background: var(--color-surface-subtle);
}

.settings-card.is-disabled:hover {
  box-shadow: var(--shadow-sm);
  transform: none;
  border-color: transparent;
}

.card-icon {
  font-size: 1.8em;
  width: 44px;
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--color-surface-subtle);
  border-radius: 10px;
  flex-shrink: 0;
}

.settings-card.is-disabled .card-icon {
  background: var(--color-border);
}

.card-body {
  flex: 1;
  min-width: 0;
}

.card-title {
  font-weight: 600;
  color: var(--color-text-strong);
  font-size: 1rem;
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
}

.card-description {
  margin-top: 2px;
  color: var(--color-text-muted);
  font-size: 0.85rem;
  line-height: 1.35;
}

.card-chevron {
  font-size: 1.5em;
  color: var(--color-text-subtle);
  font-weight: 700;
  flex-shrink: 0;
}

.coming-soon-badge {
  display: inline-block;
  background: var(--color-border);
  color: var(--color-text-muted);
  font-size: 0.7rem;
  font-weight: 600;
  padding: 2px 8px;
  border-radius: 10px;
  text-transform: uppercase;
  letter-spacing: 0.3px;
  white-space: nowrap;
}

@media (max-width: 600px) {
  .settings-view { padding: 12px; }
  .settings-grid { grid-template-columns: minmax(0, 1fr); }
}
</style>

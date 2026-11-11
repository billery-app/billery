<template>
  <div class="app">
    <nav class="navbar" aria-label="Hauptnavigation">
      <router-link class="nav-title" to="/" aria-label="Billery – Dashboard">Billery</router-link>
      <div class="search-wrapper">
        <button class="search-btn" type="button" aria-label="Suche öffnen (Strg+K oder ⌘K)" @click="showSearch = true">
          <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" aria-hidden="true">
            <circle cx="11" cy="11" r="8"/>
            <path d="M21 21l-4.35-4.35"/>
          </svg>
          <span>Suche</span>
          <kbd>⌘K</kbd>
        </button>
      </div>
      <div class="nav-links">
        <router-link to="/" class="nav-dashboard-link">Dashboard</router-link>
        <router-link to="/customers">Kunden</router-link>
        <router-link to="/invoices">Dokumente</router-link>
        <router-link to="/articles-services">Katalog</router-link>
        <router-link to="/settings">Einstellungen</router-link>
      </div>
      <AccountMenu v-if="session.mode === 'saas' && session.status === 'ready'" />
    </nav>
    <nav class="mobile-bottom-nav" aria-label="Hauptnavigation mobil">
      <router-link to="/" exact-active-class="router-link-exact-active" class="mobile-nav-link" aria-label="Dashboard">
        <svg viewBox="0 0 24 24" aria-hidden="true"><rect x="3" y="3" width="7" height="7" rx="1"/><rect x="14" y="3" width="7" height="7" rx="1"/><rect x="3" y="14" width="7" height="7" rx="1"/><rect x="14" y="14" width="7" height="7" rx="1"/></svg>
        <span>Start</span>
      </router-link>
      <router-link to="/customers" class="mobile-nav-link" aria-label="Kunden">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="9" cy="8" r="3.5"/><path d="M2.5 20v-1.5a6.5 6.5 0 0 1 13 0V20zM16 5a3.5 3.5 0 0 1 0 6.8M18 14a5.5 5.5 0 0 1 3.5 5.1V20h-4"/></svg>
        <span>Kunden</span>
      </router-link>
      <router-link to="/invoices" class="mobile-nav-link" aria-label="Dokumente">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M6 2.75h8l4 4V21H6zM14 3v5h5M9 12h6M9 16h6"/></svg>
        <span>Dokumente</span>
      </router-link>
      <router-link to="/articles-services" class="mobile-nav-link" aria-label="Katalog">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="m3 7 9-4 9 4-9 4zM3 7v10l9 4 9-4V7M12 11v10"/></svg>
        <span>Katalog</span>
      </router-link>
      <router-link to="/settings" class="mobile-nav-link" aria-label="Einstellungen">
        <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="3"/><path d="m19.4 15 .1.1 1.4 1.1-1.4 2.4-1.7-.6a8 8 0 0 1-1.7 1l-.3 1.8h-2.8l-.3-1.8a8 8 0 0 1-1.7-1l-1.7.6-1.4-2.4 1.4-1.1a7 7 0 0 1 0-2l-1.4-1.1 1.4-2.4 1.7.6a8 8 0 0 1 1.7-1l.3-1.8h2.8l.3 1.8a8 8 0 0 1 1.7 1l1.7-.6 1.4 2.4-1.4 1.1a7 7 0 0 1 0 2Z"/></svg>
        <span>Einstell.</span>
      </router-link>
    </nav>
    <main>
      <NoTenantNotice v-if="session.status === 'no-tenant'" />
      <router-view v-else />
    </main>
    <SpotlightSearch :isOpen="showSearch" @close="showSearch = false" />
  </div>
</template>

<script>
import SpotlightSearch from './components/SpotlightSearch.vue'
import AccountMenu from './components/AccountMenu.vue'
import NoTenantNotice from './components/NoTenantNotice.vue'
import { session } from './services/session.js'

export default {
  name: 'App',
  components: { SpotlightSearch, AccountMenu, NoTenantNotice },
  data() {
    return {
      showSearch: false,
      session
    }
  },
  mounted() {
    window.addEventListener('keydown', this.handleKeydown)
  },
  beforeUnmount() {
    window.removeEventListener('keydown', this.handleKeydown)
  },
  methods: {
    handleKeydown(e) {
      if ((e.metaKey || e.ctrlKey) && e.key === 'k') {
        e.preventDefault()
        this.showSearch = true
      }
    }
  }
}
</script>

<style>
* { margin: 0; padding: 0; box-sizing: border-box; }
body { font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif; }
.app { min-height: 100vh; min-height: 100dvh; }
.navbar { background: var(--color-nav); color: var(--color-on-nav); padding: 15px clamp(16px, 2vw, 30px); display: flex; align-items: center; gap: clamp(12px, 1.5vw, 24px); }
.nav-title { flex: 1 1 auto; min-width: 0; color: var(--color-on-nav); text-decoration: none; font-size: 1.5em; font-weight: 700; white-space: nowrap; transition: opacity 0.2s; }
.nav-title:hover { opacity: 0.8; }
.search-wrapper { flex: 0; display: flex; justify-content: center; }
.nav-links { display: flex; gap: clamp(4px, 0.8vw, 16px); align-items: center; flex: 1 1 auto; justify-content: flex-end; min-width: 0; }
.nav-links a { color: var(--color-on-nav); text-decoration: none; padding: 8px clamp(8px, 1vw, 16px); border-radius: 4px; white-space: nowrap; transition: background 0.3s; }
.nav-links a:hover { background: var(--color-nav-hover); }
.nav-links a.router-link-active { background: var(--color-primary); }
.nav-dashboard-link { display: none; }
.mobile-bottom-nav { display: none; }

.search-btn {
  display: flex;
  align-items: center;
  gap: 8px;
  background: var(--color-nav-hover);
  border: 1px solid var(--color-nav-hover-strong);
  color: var(--color-on-nav);
  padding: 6px 12px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 14px;
  transition: background 0.2s;
}

.search-btn:hover {
  background: var(--color-nav-hover-strong);
}

.search-btn svg {
  width: 16px;
  height: 16px;
}

.search-btn kbd {
  font-size: 11px;
  padding: 2px 6px;
  background: var(--color-nav-kbd);
  border-radius: 4px;
  font-family: inherit;
}

.app > main { width: 100%; max-width: 1600px; min-width: 0; margin: 0 auto; }
.app > main .nav-destination-page-title {
  position: absolute;
  width: 1px;
  height: 1px;
  padding: 0;
  margin: -1px;
  overflow: hidden;
  clip: rect(0, 0, 0, 0);
  white-space: nowrap;
  border: 0;
}
.table-scroll { width: 100%; max-width: 100%; overflow-x: auto; overscroll-behavior-inline: contain; -webkit-overflow-scrolling: touch; }
.table-scroll:focus-visible { outline: 2px solid var(--color-primary); outline-offset: 2px; }
.data-table tbody tr.clickable-row { cursor: pointer; }
.data-table tbody tr.clickable-row:hover { background-color: var(--color-primary-soft); }
.data-table tbody tr.clickable-row:focus-visible { outline: 2px solid var(--color-primary); outline-offset: -2px; }

/* The full navbar keeps its current layout until its links need more room. */
@media (max-width: 1100px) {
  .navbar { padding: 12px 18px; gap: 12px; }
  .search-wrapper { display: none; }
  .nav-links { gap: 4px; }
  .nav-links a { padding: 8px 10px; }
}

/* If the brand no longer fits, expose Dashboard as a regular route link. */
@media (max-width: 850px) and (min-width: 761px) {
  .nav-title { display: none; }
  .nav-dashboard-link { display: inline-flex; }
  .nav-links { justify-content: center; }
}

/* Constrained screens use a five-destination bottom bar instead of shrinking text. */
@media (max-width: 760px) {
  .navbar {
    display: flex;
    min-height: 52px;
    padding: 10px 16px;
    justify-content: center;
    border-bottom: 1px solid var(--color-nav-hover);
  }
  .nav-title { flex: 0 1 auto; font-size: 1.25rem; }
  .search-wrapper,
  .nav-links { display: none; }
  .app > main { padding-bottom: calc(76px + env(safe-area-inset-bottom, 0px)); }
  .table-scroll:not(.table-scroll--compact) > .data-table { min-width: 680px; }
  .table-scroll--compact > .data-table { min-width: 520px; }
  .table-scroll > .items-table { min-width: 600px; }
  .mobile-bottom-nav {
    position: fixed;
    z-index: 100;
    inset: auto 0 0;
    display: grid;
    grid-template-columns: repeat(5, minmax(0, 1fr));
    align-items: stretch;
    padding: 6px 4px calc(6px + env(safe-area-inset-bottom, 0px));
    border-top: 1px solid var(--color-border-strong);
    background: var(--color-surface);
    box-shadow: var(--shadow-up);
  }
  .mobile-nav-link {
    display: flex;
    min-width: 0;
    min-height: 52px;
    flex-direction: column;
    align-items: center;
    justify-content: center;
    gap: 3px;
    border-radius: 8px;
    color: var(--color-text-muted);
    font-size: 10px;
    font-weight: 600;
    line-height: 1.1;
    text-decoration: none;
    white-space: nowrap;
    -webkit-tap-highlight-color: transparent;
  }
  .mobile-nav-link svg {
    width: 19px;
    height: 19px;
    flex: 0 0 auto;
    fill: none;
    stroke: currentColor;
    stroke-width: 1.7;
    stroke-linecap: round;
    stroke-linejoin: round;
  }
  .mobile-nav-link:first-child svg rect { fill: currentColor; stroke: none; }
  .mobile-nav-link:not(:first-child).router-link-active,
  .mobile-nav-link.router-link-exact-active {
    color: var(--color-primary-hover);
    background: var(--color-primary-soft);
  }
  .mobile-nav-link:focus-visible,
  .nav-title:focus-visible,
  .nav-links a:focus-visible,
  .search-btn:focus-visible {
    outline: 3px solid var(--color-primary-border);
    outline-offset: 2px;
  }
}
</style>

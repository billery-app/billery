import { createApp } from 'vue'
import './styles/tokens.css'
import './styles/base.css'
import './styles/components.css'
import App from './App.vue'
import { createAppRouter } from './router'
import { initAuth } from './services/authService.js'
import { captureDesktopToken } from './services/desktopToken.js'

// Self-hosted installs mount at once. In SaaS mode the app first completes or starts the sign-in
// (a redirect to the identity provider) and mounts once the user and their tenants are known.
async function start() {
  captureDesktopToken() // the desktop window's secret, before anything reads the address
  try {
    await initAuth()
  } catch (error) {
    console.error('Sign-in could not be started', error)
    showStartError()
    return
  }
  const app = createApp(App)
  // Created after initAuth: see createAppRouter.
  app.use(createAppRouter())
  app.mount('#app')
}

/**
 * The app could not start (the sign-in failed). Shown with the design system's card and button,
 * built from DOM nodes (no HTML string), with a way to try again.
 */
function showStartError() {
  const card = document.createElement('div')
  card.className = 'card start-error'
  card.setAttribute('role', 'alert')
  const title = document.createElement('h1')
  title.textContent = 'Anmeldung nicht möglich'
  const text = document.createElement('p')
  text.textContent = 'Bitte später erneut versuchen. Besteht das Problem weiter, wenden Sie sich an den Betreiber.'
  const retry = document.createElement('button')
  retry.type = 'button'
  retry.className = 'btn btn-primary'
  retry.textContent = 'Erneut versuchen'
  retry.addEventListener('click', () => window.location.assign('/'))
  card.append(title, text, retry)
  document.getElementById('app').replaceChildren(card)
}

start()

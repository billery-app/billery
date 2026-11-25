import { request } from '@playwright/test'

/**
 * Before every run: marks the introductory tour as seen (`PUT /api/tour`), so it does not start
 * by itself on the empty CI database and lie over the screens the specs click through.
 * `tour.spec.js` starts it by hand. A SaaS backend has no such endpoint (404): nothing to do there.
 * @param {import('@playwright/test').FullConfig} config
 */
export default async function globalSetup(config) {
  const baseURL = config.projects[0].use.baseURL
  const context = await request.newContext({ baseURL })
  try {
    const response = await context.put('/api/tour', {
      headers: { Origin: new URL(baseURL).origin },
      data: { seen: true }
    })
    if (!response.ok() && response.status() !== 404) {
      throw new Error(`marking the tour as seen failed: ${response.status()}`)
    }
  } finally {
    await context.dispose()
  }
}

# e2e smoke tests (Playwright)

A small end-to-end suite built on [Playwright](https://playwright.dev). It
uses the **built product** in a real browser and fails on what unit tests
cannot see: response headers (a Content-Security-Policy that blocks a frame),
filters, routing of the built SPA, console errors, uncaught exceptions and
`/api` responses >= 400. Vitest remains the primary test surface.

## In CI

`.github/workflows/e2e.yml` (check `e2e-smoke`) runs on every pull request and
on pushes to `main`. It packages the app the way `backend/Dockerfile` does
(`npm run build`, `frontend/dist` copied into the backend's `static/`,
`mvn -Pdesktop package`), starts the JAR with an empty in-memory H2 on port
8080 and runs `npm run test:e2e` with `E2E_BASE_URL=http://127.0.0.1:8080`.
On failure the traces (`test-results/`) and the backend log are uploaded as
the artifact `e2e-results`.

## Running locally

All commands run from `frontend/`.

| Command | Purpose |
|---------|---------|
| `npm run test:e2e:install` | One-time: installs Chromium (and its system packages). |
| `npm run test:e2e` | Headless run of all specs. |
| `npx playwright test --ui` | UI mode (trace viewer, locator picker) for a failing test. |

The base URL defaults to `http://localhost:5173` (Vite dev server, which
proxies `/api` to the backend on 8080). Closest to CI is the packaged app:

```bash
npm run build && mkdir -p ../backend/target/classes/static && cp -R dist/. ../backend/target/classes/static/
# in backend/: SPRING_PROFILES_ACTIVE=desktop DB_URL='jdbc:h2:mem:e2e;MODE=MariaDB;DB_CLOSE_DELAY=-1' \
#   SERVER_PORT=18181 mvn -Pdesktop spring-boot:run
E2E_BASE_URL=http://127.0.0.1:18181 npm run test:e2e
```

Only the packaged app (or any instance behind the real backend) sends the
backend's security headers; the Vite dev server does not, so CSP problems only
show up against the backend.

## Layout

```
frontend/
├── playwright.config.js     # no webServer; baseURL from E2E_BASE_URL
└── e2e/
    ├── README.md            # this file
    ├── smoke.spec.js        # main screens, template editor PDF preview, document view (action bar, lifecycle strip, "Mehr" menu) + customer search
    ├── dashboard.spec.js    # / renders, period selector visible
    └── invoices.spec.js     # /invoices renders + create/delete a draft via the API
```

## Test data

The suite works on an empty database (that is what CI has):

- `smoke.spec.js` creates the company data if none exist, then a customer and
  a draft tagged `E2E-<timestamp>`, and deletes the customer and the draft in
  `afterAll`. Company data it created itself are deleted too; existing ones
  are never touched.
- `invoices.spec.js` uses the first customer, or creates and deletes a
  temporary one, and deletes its draft in a `finally`.

Write requests send the app's own `Origin` header; the backend rejects writes
without it (CSRF protection). An interrupted run against a dev database may
leave rows whose customer name starts with `E2E-`; delete them in the app.

## Adding a test

1. Prefer read-only flows; a mutating flow tags its rows with the marker and
   cleans up in `afterAll` / `finally`.
2. Use `getByRole` / `getByText` / `getByLabel` / `getByTestId` locators.
3. Reuse `watch(page)` from `smoke.spec.js` so the test fails on console
   errors and failed API calls, not only on the asserted element.
4. Do not depend on pre-existing data: create what the test needs.

## Reference

- [Playwright docs — writing tests](https://playwright.dev/docs/writing-tests)
- Internal: [`docs/guides/testing.md`](../../docs/guides/testing.md)

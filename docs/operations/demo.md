# Running a public demo

A demo instance is an ordinary self-host installation with `BILLERY_DEMO=true`, loaded with the
demo data set and reset regularly. It is meant for visitors who want to try Billery without
installing it. **It is never a real installation**: everything a visitor enters is visible to
every other visitor until the next reset.

## What the demo mode changes

`BILLERY_DEMO=true` (`config/DemoMode`, property `billery.demo`; meant for the self-host image, never set it in
SaaS) does three things:

- `GET /api/config` answers `demo: true`. The app shows a banner ("Demo – die Daten werden
  regelmäßig zurückgesetzt") with **"Tour erneut starten"**, and the introductory tour starts in
  every new browser session (the marker is in `sessionStorage`, so every visitor gets their own
  tour, whatever the others do in the shared database). The server stores no tour state for the
  demo.
- **No mail leaves the demo.** Creating or changing a mail account, the test mail and sending a
  document to a customer are refused with 409 ("… ist in der Demo abgeschaltet."); the app hides
  the "Per E-Mail" action and the account form and says why. Mail templates can still be edited.
  Without this guard a public demo would be an open relay for anyone's SMTP account.
- Einstellungen → Hilfe offers "Tour starten", without the "Tour bei jedem Start zeigen" switch
  (the demo shows the tour in every session anyway).

Everything else works as in a real installation, uploads (logos, 2 MB each) included.

## Setting it up

Use the root `docker-compose.yml` on a host of its own, never next to a real installation (the
compose file uses fixed container names, and the reset below drops the volumes). Pass the two variables through an override file next to it
(`docker-compose.override.yml`, which `docker compose` reads automatically):

```yaml
services:
  backend:
    environment:
      BILLERY_DEMO: "true"
      LOAD_DEMO_DATA: "true"   # demo data (100 customers, 1 420 documents) into the empty database
```

`LOAD_DEMO_DATA` fills only a database without customers and also seeds the number-series
counters; that is why it is never set for a real installation. Put the instance behind a reverse
proxy with HTTPS as described in [`deployment.md`](./deployment.md#behind-a-reverse-proxy), but
**without** `basic_auth`: the demo is meant to be public.

Check after the start:

```bash
curl -s https://demo.example.com/api/config   # … "demo":true …
```

## Resetting it

Visitors change and delete data, upload files and leave texts behind that the next visitor sees.
Reset the instance at least nightly; drop the volumes, so the database and the uploads start
empty and the demo data are loaded again:

```bash
# crontab of the demo host, 03:17 every night
17 3 * * * cd /srv/billery-demo && docker compose down -v && docker compose up -d
```

The demo data's documents lie in 2023–2026; refresh the image with each release (`docker compose
pull` or `--build`) so the demo shows the current version.

## Limits and risks

The self-host build has **no login and no request limit** of its own (the per-tenant request
limit is SaaS only). A public demo is therefore exposed to anyone:

- **Request rate:** limit it in the proxy. nginx: `limit_req_zone $binary_remote_addr
  zone=demo:10m rate=10r/s;` and `limit_req zone=demo burst=40 nodelay;` in the `location`.
  Caddy has no rate limit built in; it needs the third-party module `caddy-ratelimit` (a custom
  build with `xcaddy`), or put the limit in front of Caddy.
- **Expensive requests:** PDFs, the template preview and the GoBD export cost CPU. The export is
  limited by `EXPORT_MAX_CONCURRENT` (default 2) and the other `EXPORT_*` limits
  ([`env-vars.md`](../reference/env-vars.md)); keep the container's `BACKEND_CPUS` /
  `BACKEND_MEMORY_LIMIT` small, so a burst slows the demo down instead of the host.
- **Content:** visitors can write anything into customers, texts and templates and upload
  images; other visitors see it until the reset. The banner says so; reset more often if that
  becomes a problem.
- **No real data:** the banner asks visitors not to enter real data. Do not collect any either:
  the demo needs no analytics, and its logs hold only what the self-host backend logs anyway.

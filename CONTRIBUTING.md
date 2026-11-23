# Contributing to Billery

Ideas, bug reports and pull requests are welcome. Billery handles invoices, so it has to follow
GoBD and German tax rules, and in the SaaS mode it keeps the companies' data apart. Because of
that, every change goes through review and a set of required checks. This page says what a pull
request needs before it can be merged.

**Security problems are never reported in an issue or a pull request.** Report them privately as
described in [`SECURITY.md`](./SECURITY.md).

## Before you start

- For anything bigger than a small fix, open an issue first and describe what you plan. A change
  that does not fit the GoBD rules or the architecture is cheaper to discuss before it is written.
- Read [`AGENTS.md`](./AGENTS.md), even if you are not an agent. It lists the parts of the
  architecture that are easy to get wrong: transactions, money as `BigDecimal`, the two migration
  systems, the invoice lifecycle and the parity between the editor and the PDF renderers. The
  [`docs/`](./docs/README.md) tree describes the rest.

## Making the change

- Branch from `main` and open the pull request against `main`.
- Run the checks locally first. CI should confirm your work, not find the problems:

  ```bash
  cd backend && mvn spotless:apply && mvn verify    # tests, formatting, PMD
  cd frontend && npm run test:run && npm run build
  ```

  For changes to the SaaS code, the schema or the database layer, also run the suite against a
  throwaway PostgreSQL (`POSTGRES_TEST_URL`, `mvn -Psaas verify`). See
  [`docs/guides/testing.md`](./docs/guides/testing.md).
- Add tests. A check that refuses something also needs a test showing that valid input still
  passes.
- Update the documentation in the same pull request when your change alters a documented fact
  (commands, environment variables, architecture). Stale docs are treated as a bug.
- Keep pull requests focused, and push your fixes for one review round together. Each push
  restarts every workflow, and the project's Actions minutes are limited.

## What a pull request needs before merging

- **Required checks:** build and tests (H2, PostgreSQL, SaaS), XRechnung validation, frontend
  tests, the end-to-end smoke test, dependency scan, SAST and secret scan. A job that does not
  apply to your change skips itself and counts as passed.
- **Workflows of pull requests from forks run only after a maintainer approves them.** This keeps
  untrusted code away from the runners. Until a maintainer starts them, the checks show as
  pending.
- **An approving review by a code owner** ([`.github/CODEOWNERS`](./.github/CODEOWNERS)). A new
  push dismisses the approval, so the reviewed state is the state that gets merged. Review threads
  must be resolved.
- Changes to the paths listed separately in `CODEOWNERS` get a closer review and may take
  longer. These are tenant isolation and authentication, migrations, security headers, uploads,
  PDF rendering, imports, backups, workflows, images and deployment.

## License

Billery is licensed under the [AGPL-3.0](./LICENSE). By opening a pull request, you agree that your
contribution is licensed under the same terms. There is no separate contributor agreement.

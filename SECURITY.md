# Security policy

## Reporting a vulnerability

Please do **not** open a public issue for a security problem. Report it privately through
GitHub's [private vulnerability reporting](https://github.com/billery-app/billery/security/advisories/new)
(the "Report a vulnerability" button on the repository's *Security* tab).

Include what you found, how to reproduce it, the affected version or commit, and the mode
(self-host, desktop or SaaS). You will get an answer within a few working days; a fix and an
advisory follow once the problem is confirmed.

## Supported versions

Only the latest release receives security fixes.

## Scope

The threat model is in [`docs/architecture/threat-model.md`](./docs/architecture/threat-model.md).
Note that the self-host mode has **no authentication of its own** by design: it must only be
reachable through a reverse proxy that protects it (see
[`docs/operations/deployment.md`](./docs/operations/deployment.md)). An unprotected self-host
instance on a public interface is a deployment error, not a vulnerability.

# Supply chain: dependency and image scanning, SBOMs, releases

> **Audience:** maintainers and release managers.

What protects against a vulnerable library or base image reaching a release, how findings are
handled, and what a release contains. SAST (`pr-sast.yml`, Semgrep) and secret scanning
(`gitleaks.yml`) are separate checks; none of the three replaces another.

## What runs where

| Check | Workflow / job | When | Effect |
|-------|----------------|------|--------|
| Dependencies of the source tree | `security.yml` / `dependency-scan` | weekly, manual; on a pull request when a manifest or lockfile (`pom.xml`, `package*.json`, `Cargo.*`), `.trivyignore` or the workflow changed (code brings no new dependency) | **fails** on HIGH or CRITICAL with a fix |
| The container images | `security.yml` / `image-scan (backend)`, `image-scan (frontend)` | weekly, manual, release; on a pull request only when a Dockerfile, the dependencies that go into an image (`backend/pom.xml`, `frontend/package*.json`), `.trivyignore` or the workflow changed | **fails** on HIGH or CRITICAL with a fix |
| The release image | `release.yml` | tag `v*.*.*` | **blocks** the release before anything is published |
| Update proposals | Dependabot (`.github/dependabot.yml`) | monthly, grouped by minor/patch | pull requests; security updates are switched on in the repository settings |

Trivy reads `backend/pom.xml`, `frontend/package-lock.json`, `app-desktop/package-lock.json` and
`app-desktop/Cargo.lock` for the tree, and the image layers (OS packages, the JAR's libraries, the
SPA's node modules) for the images. The KoSIT validator of the test job is not in the repository
(`scripts/fetch-kosit-validator.sh` downloads it with fixed checksums) and is not shipped. The Trivy action is pinned to a commit; Dependabot
(`github-actions`) proposes its updates.

## Severity policy

| Severity | Has a fix | No fix yet |
|----------|-----------|------------|
| CRITICAL | blocks the pull request or release; fix within **7 days** | listed in the weekly report; review weekly; mitigate or accept (below) |
| HIGH | blocks; fix within **30 days** (usually the same pull request) | as above |
| MEDIUM | weekly report; fix with the next dependency update, within **90 days** | weekly report |
| LOW / UNKNOWN | weekly report only | weekly report only |

"Has a fix" is Trivy's `ignore-unfixed`: the scan only blocks what an update would resolve. The
weekly run writes the full report (all severities, fixed or not) into the job summary of
`security.yml`.

## Remediation workflow

1. **Update.** Merge the Dependabot pull request, or raise the version yourself (`npm install
   vue@^x.y.z` with npm 11, which keeps the lockfile format; Maven: the version in `pom.xml`, or the
   Spring Boot BOM). Run the tests; the scan turns green when the finding is gone.
2. **Cannot update** (no compatible release, breaking change): upgrade a transitive dependency
   explicitly, or replace the library. Say in the pull request why the simple update was not possible.
   Current cases:
   - `frontend/package.json` `overrides`: `glob` `^14.0.2` and `minimatch` `^11.0.1`
     (GHSA-3g3m-2fjr-6pw4, GHSA-g3hv-75wg-m249). Both come only through `@vue/test-utils` ->
     `js-beautify` (its command-line tool, which the tests never load); `js-beautify` 2.0.3 still
     asks for `glob ^13`. Remove the overrides when `js-beautify` allows the fixed versions.
   - `app-desktop/Cargo.lock`: `glib` 0.18 (GHSA-wrw7-89jp-8q8g, unsoundness of
     `VariantStrIter`, which the shell does not use) comes with Tauri's GTK 3 bindings; the fix is
     in `glib` 0.20, the GTK 4 bindings. It goes away when Tauri moves; until then the Dependabot
     alert stays open (Trivy only fails on HIGH / CRITICAL).
3. **Does not apply** (the vulnerable code is not reachable, for example a feature we do not use):
   add the advisory id to `.trivyignore` with **a reason and an expiry date** (format in the file).
   `scripts/check-trivyignore.sh` enforces it in the scan and the release: a `# reason:` line before
   every entry, an expiry date in the future and at most 90 days away. The entry expires and the
   finding returns for review; it is never open-ended. The scans run with the list of the pull
   request, so a change to `.trivyignore` is reported as a warning and in the job summary of the
   pull request: review every entry there. Ask for a code owner review of the file in the branch
   protection if more than one person merges.
4. **Base image:** the images pin a patch version (`eclipse-temurin:25.0.3_9-jre-alpine`). A finding in
   the OS or JRE is fixed by raising that pin (Dependabot `docker`).
5. **A release is blocked:** fix as above on `main`, then move the tag (delete and re-push it) or
   cut the next patch version. Do not weaken the policy to get a release out.

## What a release contains

`release.yml` runs on a tag `v<major>.<minor>.<patch>` and, in this order: `mvn verify` (tests, JaCoCo,
Spotless, PMD), builds the **two images** of `backend/Dockerfile` (below), checks that each is the
variant it claims to be, scans both (HIGH and CRITICAL with a fix stop the release), writes the
reports, **pushes both images to the GitHub container registry** and creates a **GitHub release** with:

| File | Content |
|------|---------|
| `billery-<tag>.jar`, `billery-saas-<tag>.jar` | the JARs that run in the images (copied out of them) |
| `billery-<tag>-image.cdx.json`, `billery-saas-<tag>-image.cdx.json` | SBOM (CycloneDX) of each image: OS packages and the JAR's libraries |
| `billery-<tag>-source.cdx.json` | SBOM (CycloneDX) of the manifests and lockfiles: backend, frontend, desktop shell |
| `billery-<tag>-image-scan.json`, `billery-saas-<tag>-image-scan.json` | the scan report of each image, all severities |
| `billery-<tag>-image-digest.txt`, `billery-saas-<tag>-image-digest.txt` | the digest of each pushed image (`ghcr.io/…@sha256:…`) |
| `SHA256SUMS` | checksums of every file above (the build job's list, verified before the push, extended by the digest files) |

The job `build` (everything that runs third-party code: Maven plugins, the Docker build, Trivy) has a
read-only token; the job `publish` is the only one with `contents: write` and `packages: write`, runs
no build and only publishes what `build` made.

Everything `release.yml` uses is pinned to a commit. Between the jobs the artifacts are checked against
values that travel as job outputs, not inside the artifact: the hash of `SHA256SUMS` (and then every
file against it), and the image ids of the two scanned images (compared after `docker load`, a swapped
`image.tar` is not pushed).

## Who can release: protect the tags

A release runs the workflow **of the tagged commit** with a write token (`contents`, `packages`). So
whoever can push a `v*` tag can publish an image: the tag format check only stops accidents. Before the
first release, add a ruleset in the repository settings (Settings > Rules > Rulesets, target: tags,
pattern `v*`) that restricts **creating, updating and deleting** to the maintainers, and keep
`.github/workflows/` and `.trivyignore` under a required review of a code owner if more than one person
can merge. The `publish` job holds `packages: write` for all its steps; they are shell commands and
pinned actions only, so splitting it further would not change who can run it, which is what the ruleset
decides.

## The container images

Two images, both from `backend/Dockerfile` (GitHub container registry, lower case):

| Image | What | Build |
|-------|------|-------|
| `ghcr.io/billery-app/billery` | the **self-host** application: one organisation, no login of its own | default |
| `ghcr.io/billery-app/billery-saas` | the **multi-tenant** application: sign-in with an OpenID provider, tenant isolation | `--build-arg MAVEN_PROFILES=saas --build-arg SPRING_PROFILE=saas` |

They are different JARs, and the boundary is kept on purpose: the self-host one contains no SaaS code.
The SaaS image has the Spring profile `saas` **built in** (`SPRING_PROFILES_ACTIVE=saas`), because a SaaS JAR
started without it would run like the self-host application, that is without authentication. That default
can be replaced by an environment setting, so the SaaS build also **refuses to start** without the profile
(`AppProfiles.requireProfileForBuild`, called first in `MainApp.main`): a container started with
`SPRING_PROFILES_ACTIVE=prod` stops with a message instead of serving without a sign-in.
`scripts/check-image-variant.sh` checks both properties for each image, in the release and in the
`image-scan` job of `security.yml`. The images that are pushed are the ones that were scanned: `build`
saves them as an artifact and `publish` loads and pushes them, there is no second build.

| Release tag | Image tags |
|-------------|------------|
| `v1.2.3`, the highest stable release | `1.2.3`, `1.2`, `latest` (for both images) |
| `v1.1.10`, a hotfix for an older line | `1.1.10`, `1.1` (not `latest`) |
| `v1.2.2`, published after `v1.2.3` | `1.2.2` only |
| `v1.2.3-rc1` (any tag with a hyphen) | `1.2.3-rc1` only, and the GitHub release is marked pre-release |

A release tag is `v<major>.<minor>.<patch>` with an optional pre-release part (`v1.2.3-rc1`); anything else
(`v1.2.3.4`, `v1.2.x`) stops the workflow before it builds. A version is **immutable**: if the release or
the image tag `1.2.3` exists already, the push is refused instead of overwriting it. Only a clear "not found" counts as free; if the lookup fails
for another reason (network, authentication) the release stops, because a check that fails open would allow
the overwrite it is there to prevent. To repeat a release
that failed half-way, remove the half-published parts on purpose (the GitHub release, the package
version) or cut the next patch version.

`1.2` and `latest` only move to the highest stable release of their line (resp. of all), compared with
version sorting over the existing GitHub releases. Releases run one at a time (`concurrency: release`),
so two tags pushed together cannot set the aliases in a random order. Pin the full version (or the
digest from the release) in production; `1.2` and `latest` move. The digest is read from the push
output and checked to be the same for every tag.

- **Package visibility.** The package is created by the first release and linked to this repository
  through the `org.opencontainers.image.source` label. Its visibility is set in the package settings
  (the image holds nothing that is not in the AGPL-licensed repository). While a package is private,
  pulling needs a token with `read:packages`: `echo $TOKEN | docker login ghcr.io -u <user>
  --password-stdin`.
- **No signature or provenance attestation yet.** Sigstore signing or GitHub's build attestations
  are the next step for the release images.
- **Docker Hub** is not used: it would add a second place to keep in step, a second secret, and the
  anonymous pull limits, without a public project to find there. A mirror is a few lines in `publish`
  (login, `docker tag`, `docker push`) if it becomes useful.

## Dependabot rules

`.github/dependabot.yml` groups minor and patch updates per ecosystem. Three deliberate exceptions:

- the three `org.docx4j` artifacts are one group (they must have the same version: a single one broke the
  DOCX export);
- the Docker images do not get major updates (`eclipse-temurin`, `node`), so Dependabot does not propose
  Java 26/27 (not LTS) or a Node line that CI does not run; the Maven build image is not updated at
  all, its Java line has to match the pom;
- `simple-java-mail` is not raised to a new major version until the mail feature is built (the provider is
  not wired in and version 9 has another API).

A **green CI is not a verdict** for a major update: check what the tests actually cover (a green pull
request for one docx4j artifact passed because no test uses it) and which files use the dependency.

## Java and Node versions

One version each, everywhere: **Java 25** (pom `<release>`, all workflows, the Docker images, the
desktop runtime built by `scripts/build-desktop.*`) and **Node 24** (CI and the images). Raise them in
all places together; `scripts/build-desktop.sh` refuses a JDK that does not match.

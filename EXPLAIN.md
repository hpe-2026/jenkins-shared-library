# NITTE Merchandise Shop — Jenkins Shared Library
## Architecture, Design, and Operations Guide

---

## Table of Contents

1. [Repository Tree](#repository-tree)
2. [Architecture Overview](#architecture-overview)
3. [How Everything Connects](#how-everything-connects)
4. [Branch Strategy](#branch-strategy)
5. [Tagging Strategy](#tagging-strategy)
6. [CI/CD Lifecycle — Sequence Diagrams](#cicd-lifecycle)
7. [Stage-by-Stage Pipeline Guide](#stage-by-stage)
8. [File Reference](#file-reference)
9. [Running Tests Locally](#running-tests-locally)
10. [Debugging Guide](#debugging-guide)
11. [Extending the Library](#extending-the-library)
12. [Security Notes](#security-notes)
13. [Change Log](#change-log)

---

## Repository Tree

```
jenkins-shared-library/
│
├── vars/                          ← Global pipeline steps (Jenkins DSL)
│   ├── merchPipeline.groovy       ← Main orchestrator (the entry point)
│   ├── kanikoBuild.groovy         ← OCI image build using Kaniko
│   ├── updateGitOps.groovy        ← Patches the GitOps config repo
│   ├── trivyScan.groovy           ← Trivy vulnerability scanner
│   ├── runTests.groovy            ← Unit test runner (Node.js + Python)
│   ├── waitForArgoCD.groovy       ← Waits for ArgoCD app to be Healthy
│   ├── smokeTest.groovy           ← HTTP health check post-deploy
│   ├── sonarScan.groovy           ← SonarQube analysis + Quality Gate
│   ├── semver.groovy              ← Semantic version bump from commits
│   └── notify.groovy              ← Build notifications (console/email/Slack)
│
├── src/com/nitte/merch/           ← Compiled Groovy classes
│   ├── Constants.groovy           ← All credential IDs, namespaces, config constants
│   ├── ServiceDetector.groovy     ← Monorepo changed-service detection
│   └── TagUtils.groovy            ← Semver tag parser and Git tag helper
│
├── resources/
│   └── pod-templates/
│       └── build-pod.yaml         ← Kubernetes agent pod spec (loaded by Jenkins)
│
├── test/                          ← Unit tests for the shared library itself
│   ├── JenkinsPipelineTests.groovy ← JenkinsPipelineUnit tests for vars/
│   └── src/
│       ├── TagUtilsTest.groovy    ← Pure Groovy tests for TagUtils
│       └── ServiceDetectorTest.groovy ← Tests for ServiceDetector
│
├── .github/
│   └── workflows/
│       └── test-library.yml       ← GitHub Actions CI (runs on every PR)
│
├── build.gradle                   ← Gradle build for running tests
├── gradle/                        ← Gradle wrapper + compiler config
└── EXPLAIN.md                     ← This file
```

---

## Architecture Overview

```
┌──────────────────────────────────────────────────────────────────┐
│                      On-Premise Infrastructure                    │
│                                                                  │
│  ┌─────────────────────────────────────────────────────────┐    │
│  │                    Admin VM (admin cluster)              │    │
│  │  ┌──────────┐  ┌───────────┐  ┌───────────┐            │    │
│  │  │ Jenkins  │  │  Nexus    │  │ ArgoCD    │            │    │
│  │  │ (CI/CD)  │  │ (Docker   │  │ (GitOps   │            │    │
│  │  │          │  │  registry)│  │  control) │            │    │
│  │  └────┬─────┘  └───────────┘  └───────────┘            │    │
│  │       │ spawns Kubernetes pods (build agents)           │    │
│  │       │                                                  │    │
│  │  ┌────▼─────────────────────────────────────────────┐  │    │
│  │  │  Jenkins Agent Pod (per build)                    │  │    │
│  │  │  ┌──────────┐  ┌──────────┐  ┌────────────────┐ │  │    │
│  │  │  │  devops  │  │  kaniko  │  │   security     │ │  │    │
│  │  │  │node:20-  │  │ (image   │  │ (trivy scanner)│ │  │    │
│  │  │  │  alpine  │  │  builder)│  │                │ │  │    │
│  │  │  └──────────┘  └──────────┘  └────────────────┘ │  │    │
│  │  └──────────────────────────────────────────────────┘  │    │
│  └─────────────────────────────────────────────────────────┘    │
│                                                                  │
│  ┌─────────────────┐         ┌──────────────────────────────┐  │
│  │  Dev VM         │         │  Prod VM                     │  │
│  │  (dev cluster)  │         │  (prod cluster)              │  │
│  │  ArgoCD watches │         │  ArgoCD watches              │  │
│  │  dev overlay    │         │  prod overlay                │  │
│  └─────────────────┘         └──────────────────────────────┘  │
└──────────────────────────────────────────────────────────────────┘

GitHub:
  ┌──────────────────────┐   ┌──────────────────────────────────┐
  │  merch-source-code   │   │  hpe-merch-config  (GitOps repo) │
  │  (application code)  │   │  downstream-clusters/            │
  │                      │   │    overlays/dev/kustomization.yaml│
  │  Jenkinsfile uses    │   │    overlays/prod/kustomization.yaml│
  │  @Library(...)       │   └──────────────────────────────────┘
  └──────────────────────┘
         ↑ push triggers Jenkins
```

---

## How Everything Connects

| Component | Role |
|-----------|------|
| **Jenkins** | Orchestrates the CI/CD pipeline. Runs in the admin cluster. Spawns Kubernetes pod agents for each build. |
| **Shared Library** | Contains all reusable pipeline logic. Loaded from `jenkins-shared-library` repo. Keeps `Jenkinsfile` small and projects consistent. |
| **Kaniko** | Builds OCI images inside the Kubernetes pod **without** a Docker daemon (required since Docker is disallowed in RKE2 clusters). |
| **Nexus** | Stores built Docker images as an OCI registry. Kaniko pushes to it; RKE2 nodes pull from it. Also caches Kaniko build layers. |
| **Trivy** | Scans images for CVEs after they are pushed to Nexus. Fails the build if CRITICAL vulnerabilities are found. |
| **SonarQube** | Performs static analysis and enforces Quality Gates. Scans changed services in parallel. |
| **GitOps repo** | The source of truth for Kubernetes manifests. Jenkins patches `kustomization.yaml` with the new image tag. Neither Jenkins nor developers apply `kubectl` directly. |
| **ArgoCD** | Watches the GitOps repo and syncs it to the dev or prod cluster automatically. Jenkins waits for ArgoCD to reach Healthy+Synced before proceeding. |

---

## Branch Strategy

```
main ────────────────────────────────────────────── production
  │
  ├── feature/foo          ← PR flow (test only, no build)
  ├── fix/bug-123          ← PR flow
  └── chore/update-deps    ← PR flow

Git Tags: v1.2.3           ← production release trigger
          v1.3.0-rc.1      ← pre-release (no prod deploy)
```

| Branch Type | What runs |
|-------------|-----------|
| `feature/*`, `fix/*` (PR) | Install → Lint → Test → SonarQube (PR analysis) |
| `main` | Install → Lint → Test → Sonar → **Semver Bump → Tag → Build → Scan → GitOps Dev → Verify Dev** |
| `vX.Y.Z` tag | Build → Scan → **Manual Approval → GitOps Prod → Verify Prod → GitHub Release** |
| `vX.Y.Z-rc.N` pre-release tag | Build → Scan (stops here — no prod deploy) |

---

## Tagging Strategy

Tags are created **automatically** by the pipeline on every successful `main` build using **Conventional Commits**:

```
feat!: drop support for Node 16     → MAJOR bump  v1.0.0 → v2.0.0
feat: add supplier API              → MINOR bump  v1.0.0 → v1.1.0
fix: correct price calculation      → PATCH bump  v1.0.0 → v1.0.1
chore: update dependencies          → PATCH bump
```

To trigger a **production deployment**, you do NOT push a tag manually. The pipeline auto-creates the semver tag after tests pass on `main`. The tag push then triggers a second pipeline run that does the production flow.

To create a **pre-release** tag for testing:
```bash
git tag -a v1.1.0-rc.1 -m "Release candidate 1"
git push origin v1.1.0-rc.1
```
This builds and scans the images but does **not** deploy to production.

---

## CI/CD Lifecycle

### PR Flow

```
Developer pushes feature branch
         │
         ▼
  GitHub PR created
         │
         ▼
  Jenkins detects (GitHub webhook)
         │
         ▼
  ┌──────────────────────────────────────────────┐
  │  Pod spun up in admin cluster                │
  │                                              │
  │  1. Setup & Checkout                         │
  │  2. Detect Changed Services                  │
  │  3. Install Dependencies (parallel)          │
  │  4. ┌─────────────────────────────────────┐  │
  │     │ Lint + Test + SonarQube (parallel)  │  │
  │     │ per changed service                 │  │
  │     └─────────────────────────────────────┘  │
  │                                              │
  │  ✅ PASS: GitHub PR check green              │
  │  ❌ FAIL: PR check red — developer fixes     │
  └──────────────────────────────────────────────┘
```

### Main Branch Flow

```
PR merged to main
      │
      ▼
Jenkins detects push to main
      │
      ▼
┌─────────────────────────────────────────────────────────────────┐
│  1. Setup & Checkout                                            │
│  2. Detect Changed Services                                     │
│  3. Install Dependencies (parallel per service)                │
│  4. Lint + Test + SonarQube (parallel per service)             │
│  5. Semver Bump → create & push Git tag (e.g. v1.4.0)          │
│  6. Build images with Kaniko → push to Nexus (sequential)      │
│  7. Trivy security scan (parallel per service)                  │
│  8. Update GitOps dev overlay (kustomization.yaml)             │
│  9. Wait for ArgoCD sync on dev cluster                         │
│ 10. Smoke test dev endpoints                                    │
└─────────────────────────────────────────────────────────────────┘
      │
      │ (git tag v1.4.0 push triggers a new pipeline run)
      ▼
┌─────────────────────────────────────────────────────────────────┐
│  Tag pipeline (vX.Y.Z — isRelease=true)                        │
│                                                                 │
│  1. Build images (re-uses Kaniko cache → fast)                 │
│  2. Trivy scan                                                  │
│  3. Manual approval (Jenkins input — requires admin/devops)    │
│  4. Update GitOps prod overlay                                  │
│  5. Wait for ArgoCD sync on prod cluster                        │
│  6. Smoke test prod endpoints                                   │
│  7. Create GitHub Release with changelog                        │
└─────────────────────────────────────────────────────────────────┘
```

### Rollback Flow

```
Production deployment is broken
         │
         ▼
Developer/ops runs:
  git tag -a v1.3.1 -m "rollback: revert v1.4.0" HEAD~N
  git push origin v1.3.1
         │
         ▼
Tag pipeline runs → builds + scans old code → prod approval → deploy
  (ArgoCD updates kustomization.yaml to v1.3.1 → reverts pods)
```

---

## Stage-by-Stage Pipeline Guide

### Stage 1: Setup & Checkout
- Installs `git`, `curl`, `jq`, `python3`, `yq`, and optionally `sonar-scanner` once.
- Runs `checkout scm` to get the source code.
- Determines build context: PR, main, or tag.
- Sets `IMAGE_TAG`:  
  - PR: `pr-<branch>-<sha>` (never pushed to Nexus)
  - Main: `dev-<build>-<sha>` (updated to semver tag in Stage 5)
  - Tag: the tag itself (`v1.4.0`)

### Stage 2: Detect Changed Services
Uses `ServiceDetector` to diff the current HEAD against the base ref. Only services with actual file changes are built — if `Jenkinsfile` changes, all services rebuild (global trigger).

### Stage 3: Install Dependencies
Runs in **parallel per service**. Each service gets its own parallel branch. Uses `npm ci --prefer-offline` for Node.js (respects cache) or `pip3 install` for Python.

### Stage 4: Lint, Test & Analyse
Runs in **parallel per service**. Each branch:
1. Lints (ESLint for JS, flake8 for Python)
2. Runs unit tests via `runTests.groovy`
3. Optionally runs `sonarScan.groovy` (skips gracefully if not configured)

JUnit results are published via `junit` step even on failure.

### Stage 5: Version & Tag *(main only)*
Reads conventional commits since the last tag using `semver.groovy`, bumps the version, creates an annotated tag, and pushes it. The `IMAGE_TAG` env var is updated to the new tag.

### Stage 6: Build & Push Images *(main + tags)*
Runs Kaniko **sequentially** (Kaniko has container state that prevents true parallel runs). Uses `--cache=true` with a Nexus-based cache repo to dramatically speed up repeat builds.

### Stage 7: Security Scan *(main + tags)*
Runs Trivy **in parallel** across all built services. Uses the `security` container. Results are parsed — build is marked `UNSTABLE` on HIGH, fails on CRITICAL.

### Stage 8: Update GitOps — Dev *(main only)*
Clones `hpe-merch-config`, patches `downstream-clusters/overlays/dev/kustomization.yaml` with the new image tag using `yq`, commits, and pushes. Uses git-askpass for credential security.

### Stage 9: Verify Dev Deployment *(main only)*
Runs `waitForArgoCD` and `smokeTest` in **parallel per service**. Dev smoke failures are warnings (not hard failures) to avoid blocking on infra issues.

### Stage 10: Production Approval *(release tags only)*
Shows a Jenkins `input` prompt with commit author, commit message, and version. Requires approval from users in the `approvalUsers` list.

### Stage 11–12: GitOps Prod + Verify *(release tags only)*
Same as Stages 8–9 but for prod. Smoke test failures are **hard failures** in prod.

### Stage 13: GitHub Release *(release tags only)*
Creates a GitHub Release via the REST API with an auto-generated changelog listing the services that were updated.

---

## File Reference

### `vars/merchPipeline.groovy`
The entry point. Called from `Jenkinsfile` via `merchPipeline(config)`. Defines all pipeline stages, conditions, and post actions. Delegates all real work to the other `vars/` scripts and `src/` classes.

### `vars/kanikoBuild.groovy`
Wraps the Kaniko executor. Must run inside the `kaniko` container. Key parameters: `serviceName`, `contextPath`, `registry`, `repo`, `tag`. Returns the pushed image reference. Includes retry logic and layer cache support.

### `vars/updateGitOps.groovy`
Clones the GitOps repo, patches `kustomization.yaml` images entries using `yq`, commits, and pushes. Uses git-askpass to avoid embedding credentials in remote URLs (security improvement). Retries on push failures from concurrent pipelines.

### `vars/trivyScan.groovy`
Runs `trivy image` or `trivy fs`. Returns a result map `[critical: N, high: N, medium: N, passed: Boolean]`. The caller decides whether to fail or unstable the build. Archives the JSON report.

### `vars/runTests.groovy`
Auto-detects `package.json` (Node.js) or `requirements.txt` (Python) and runs the appropriate test command. Produces JUnit XML. Archives coverage reports. Returns `[passed: Boolean, skipped: Boolean]`.

### `vars/waitForArgoCD.groovy`
Polls an ArgoCD Application CR using either the `argocd` CLI (preferred) or `kubectl` (fallback). Implements exponential backoff. Fails on `Degraded`, times out cleanly.

### `vars/smokeTest.groovy`
Makes HTTP requests to verify a deployed service is responding. Configurable HTTP codes, timeout, interval. Can be set to `failOnError: false` for dev (warning) or `true` for prod (hard fail).

### `vars/sonarScan.groovy`
Runs `sonar-scanner` with the correct flags for branch or PR analysis. Calls `waitForQualityGate` and fails the build if the gate fails (configurable). Skips gracefully if the token credential doesn't exist.

### `vars/semver.groovy`
Reads `git log` since the last tag, detects `feat!:`, `BREAKING CHANGE`, `feat:`, `fix:` prefixes, bumps `major`, `minor`, or `patch` accordingly, creates an annotated Git tag, and pushes it.

### `vars/notify.groovy`
Prints a formatted ASCII banner to console always. Optionally sends email (if `NOTIFY_EMAIL_TO` is set) and Slack (if `slackSend` plugin is installed). All external calls are wrapped in try/catch — notifications can never break the pipeline.

### `src/com/nitte/merch/Constants.groovy`
Single source of truth for all Jenkins credential IDs, Kubernetes namespace names, ArgoCD naming conventions, timeout values, and file paths. Change here to affect the entire library.

### `src/com/nitte/merch/ServiceDetector.groovy`
Detects which services changed by diffing `HEAD` against a base reference. For PRs, uses the target branch. For `main`, uses `HEAD~1`. Returns all services if global files (Jenkinsfile, etc.) changed. Returns empty list if nothing relevant changed.

### `src/com/nitte/merch/TagUtils.groovy`
Parses Git tag strings matching `vX.Y.Z[-prerelease]`. Returns a metadata map with `major`, `minor`, `patch`, `prerelease`, `version`, `isRelease`, `valid`. Used by `semver.groovy` and `merchPipeline.groovy`.

### `resources/pod-templates/build-pod.yaml`
The Kubernetes pod spec used for every build. Three containers: `devops` (Node.js build, Python, tools), `kaniko` (image builder, no Docker daemon needed), `security` (Trivy). Shared workspace volume. Nexus docker credentials mounted as a secret.

---

## Running Tests Locally

### Prerequisites
- Java 17+
- Internet access for Gradle to download dependencies (first run only)

### Run all tests
```bash
cd jenkins-shared-library
./gradlew test
```

### Run a specific test class
```bash
./gradlew test --tests "com.nitte.merch.TagUtilsTest"
./gradlew test --tests "JenkinsPipelineTests"
./gradlew test --tests "JenkinsPipelineTests\$NotifyTests"
```

### View HTML test report
```bash
open build/reports/tests/test/index.html
```

### Run with verbose output
```bash
./gradlew test --info 2>&1 | less
```

### What each test validates

| Test class | What it tests |
|------------|---------------|
| `TagUtilsTest` | Semver parsing: valid tags, pre-releases, invalid inputs, null safety |
| `ServiceDetectorTest` | Change detection: PR vs main, global triggers, first-commit fallback, sorting |
| `JenkinsPipelineTests.NotifyTests` | notify.groovy: success/failure/warning, empty inputs, missing plugins |
| `JenkinsPipelineTests.TrivyScanTests` | trivyScan.groovy: validation, result map, missing report |
| `JenkinsPipelineTests.SmokeTestTests` | smokeTest.groovy: HTTP 200 pass, timeout with failOnError=false |
| `JenkinsPipelineTests.RunTestsTests` | runTests.groovy: Node detection, Python detection, no framework skip |
| `JenkinsPipelineTests.KanikoBuildTests` | kanikoBuild.groovy: missing params, executor called with correct args |
| `JenkinsPipelineTests.WaitForArgoCDTests` | waitForArgoCD.groovy: Healthy+Synced pass, Degraded fail, missing appName |

---

## Debugging Guide

### "Could not find any definition of libraries [merch-shared-lib]"

**Cause**: Jenkins doesn't know about the shared library.

**Fix**: The library must be registered in Jenkins' JCasC config (`jenkins-casc-config.yaml`). Check that the `globalLibraries` block exists with the correct name `merch-shared-lib` and points to `jenkins-shared-library` repo on GitHub. After updating the ConfigMap, go to **Manage Jenkins → Configuration as Code → Reload existing configuration**.

### "No such DSL method 'slackSend' found"

**Cause**: The Slack Notification plugin is not installed. The library catches this, but older versions of Jenkins sandbox block the `try/catch` before it runs.

**Fix**: `notify.groovy` wraps `slackSend` in a try/catch. If you see this error it means you're using an older version of `notify.groovy`. Update to the latest version — the error is silenced.

### "python3: not found"

**Cause**: The `devops` container is `node:20-alpine` which doesn't include Python by default.

**Fix**: The Setup stage now installs `python3` and `py3-pip` via `apk add` once at the start of every build.

### Kaniko "context must be a tar, directory, or a URL"

**Cause**: The `contextPath` passed to `kanikoBuild` doesn't exist or is wrong.

**Fix**: Verify the `config.services` map matches your actual directory layout. The `kanikoBuild.groovy` now validates the Dockerfile exists before calling Kaniko.

### ArgoCD "timeout waiting for app"

**Cause**: ArgoCD hasn't synced within the timeout window. Common reasons: image pull failure (wrong Nexus credentials), OOM kill during startup, or ArgoCD auto-sync is disabled.

**Fix**:
1. Check ArgoCD UI for the specific error.
2. Verify `nexus-docker-config` secret is correct in the target cluster.
3. Increase `timeoutSeconds` in `waitForArgoCD`.

### GitOps push fails with "rejected (non-fast-forward)"

**Cause**: Two pipelines ran simultaneously and both tried to push to the same branch.

**Fix**: `updateGitOps.groovy` has retry logic with `git pull --rebase` between attempts. The pipeline uses `disableConcurrentBuilds(abortPrevious: true)` to avoid this on the same job. For different jobs (dev + prod), the rebase retry handles it.

### SonarQube Quality Gate times out

**Cause**: The SonarQube server is slow to compute the gate result.

**Fix**: Increase the timeout in `sonarScan.groovy` (default 5 minutes). Check SonarQube server health.

### Debugging shared library code

1. **Add `echo` statements** — they appear in the Jenkins console log immediately.
2. **Print variables**: `echo "DEBUG: services = ${servicesToBuild}"`
3. **Use `@Library('merch-shared-lib@your-branch') _`** in the Jenkinsfile to test a branch before merging.
4. **Replay a build**: In Jenkins, click a failed build → Replay → edit the library script inline → run.
5. **Run unit tests locally**: `./gradlew test --info` — much faster than running a Jenkins build.
6. **Check the pipeline sandbox**: Some Jenkins sandbox restrictions silently block code. Check `ScriptApproval` in Manage Jenkins if a step isn't executing.

---

## Extending the Library

### Adding a new service

1. Add the service to `SERVICES` in `Jenkinsfile`:
   ```groovy
   def SERVICES = [
       ...
       'my-new-service': 'services/my-new-service',
   ]
   ```
2. Add a health endpoint:
   ```groovy
   healthEndpoints: [
       ...
       'my-new-service': '/api/health',
   ]
   ```
3. Create the ArgoCD Application manifests in the GitOps repo.

### Adding a new pipeline step

1. Create `vars/myNewStep.groovy` with a `def call(Map params)` function.
2. Write a test in `test/JenkinsPipelineTests.groovy`.
3. Run `./gradlew test` locally to verify.
4. Call it from `merchPipeline.groovy` in the appropriate stage.

### Adding a new notification channel

1. Add a new `_sendX()` private method in `notify.groovy`.
2. Call it from `success()` and `failure()`.
3. Wrap in try/catch so it never breaks the pipeline.

### Changing the Quality Gate behavior

In `Jenkinsfile`:
```groovy
merchPipeline(
    sonarEnabled: true,
    // enforceGate: true  ← default; set false to report-only
)
```

Or per-service in `sonarScan.groovy` call via `enforceGate: false`.

---

## Security Notes

| Risk | Mitigation |
|------|------------|
| Credentials in Git URLs | `updateGitOps.groovy` uses `GIT_ASKPASS` script instead of embedding credentials in the remote URL |
| Credentials in logs | All `withCredentials` blocks use environment variable masking |
| Critical vulnerabilities | `trivyScan.groovy` marks builds UNSTABLE on HIGH, fails on CRITICAL by default |
| Kaniko registry auth | Nexus docker config mounted as a Kubernetes secret, not hardcoded |
| Jenkins agents | Pod agents run in the `system` namespace with the `jenkins` service account (limited RBAC) |

---

## Change Log

| Version | Change |
|---------|--------|
| 1.0.0 | Initial implementation |
| 1.1.0 | Added SonarQube integration (`sonarScan.groovy`) |
| 1.2.0 | Added semantic versioning (`semver.groovy`) |
| 1.3.0 | Parallel install and test stages for performance |
| 1.3.1 | Fixed `slackSend` crash when plugin not installed |
| 1.3.2 | Fixed `python3: not found` on node:20-alpine agent |
| 1.4.0 | Security fix: git-askpass for GitOps credential handling |
| 1.4.1 | Added Kaniko layer caching via `--cache-repo` in Nexus |
| 1.5.0 | Added unit test infrastructure (JenkinsPipelineUnit + GitHub Actions) |

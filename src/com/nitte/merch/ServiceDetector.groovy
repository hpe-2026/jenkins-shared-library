package com.nitte.merch

/**
 * ServiceDetector — change detection for a monorepo.
 *
 * Compares the current HEAD against a base reference to find which service
 * directories were modified.  Returns all services when the diff is empty
 * (e.g. first commit, force-push) so the build is never silently skipped.
 *
 * Expected monorepo layout:
 *   services/
 *     frontend/          ← service name = "frontend"
 *     node-backend/      ← service name = "node-backend"
 *     python-service/    ← service name = "python-service"
 *     ...
 *
 * Shared files (root-level changes like Jenkinsfile, library files, etc.)
 * trigger a full rebuild of every service.  You can refine this list via
 * the `globalTriggerPaths` config key.
 *
 * Usage:
 *   def detector = new ServiceDetector(config.services, this)
 *   def toBuild  = detector.detect(env.BRANCH_NAME, env.CHANGE_ID, env.CHANGE_TARGET)
 */
class ServiceDetector implements Serializable {

    private final Map<String, String> services   // name → relative path
    private final def                 steps       // Jenkins pipeline steps

    // Paths that, when changed, trigger rebuilding ALL services
    private static final List<String> GLOBAL_TRIGGERS = [
        'Jenkinsfile',
        'jenkins-shared-library/',
        '.github/',
        'docker-compose',
        'Makefile',
    ]

    ServiceDetector(Map<String, String> services, def steps) {
        this.services = services
        this.steps    = steps
    }

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Detect which services have changed.
     *
     * @param branchName    Current branch (e.g. "main", "feature/foo")
     * @param changeId      PR number from Jenkins (null / empty on non-PR builds)
     * @param changeTarget  Target branch of the PR (e.g. "main")
     * @return              Sorted list of service names to build
     */
    List<String> detect(String branchName, String changeId, String changeTarget) {
        def isPr    = changeId != null && changeId != '' && changeId != 'null'
        def baseRef = resolveBaseRef(isPr, changeTarget)

        if (!baseRef) {
            steps.echo "⚠  Cannot resolve base ref — building ALL services (safe default)"
            return allServices()
        }

        def changedFiles = diffFiles(baseRef)
        steps.echo "Changed files vs ${baseRef}:\n  ${changedFiles.join('\n  ')}"

        // Global trigger: rebuild everything if shared infra changed
        if (hasGlobalTrigger(changedFiles)) {
            steps.echo "🔁 Global trigger detected — building ALL services"
            return allServices()
        }

        // Map changed files to service names
        def changedServices = changedFiles
            .findAll  { it.startsWith('services/') }
            .collect  { it.tokenize('/').size() >= 2 ? it.tokenize('/')[1] : null }
            .findAll  { it != null && services.containsKey(it) }
            .unique()
            .sort()

        if (changedServices.isEmpty()) {
            steps.echo "ℹ  No service directories changed — skipping build"
            return []
        }

        steps.echo "🎯 Services to build: ${changedServices.join(', ')}"
        return changedServices
    }

    // ── Private helpers ────────────────────────────────────────────────────────

    private String resolveBaseRef(boolean isPr, String changeTarget) {
        if (isPr) {
            def target = changeTarget ?: 'main'
            steps.sh(script: "git fetch origin ${target} --depth=50 2>&1 || true", label: "Fetch ${target}")
            return "origin/${target}"
        }

        // Non-PR build: compare HEAD against previous commit
        steps.sh(script: "git fetch origin main --depth=50 2>&1 || true", label: 'Fetch main')
        def hasPrev = steps.sh(
            script: 'git rev-parse HEAD~1 > /dev/null 2>&1',
            returnStatus: true
        ) == 0

        return hasPrev ? 'HEAD~1' : null
    }

    private List<String> diffFiles(String baseRef) {
        def out = steps.sh(
            script: "git diff --name-only '${baseRef}...HEAD' 2>/dev/null || true",
            returnStdout: true,
            label: "git diff ${baseRef}..HEAD"
        ).trim()

        return out ? out.split('\n').findAll { it }.toList() : []
    }

    private boolean hasGlobalTrigger(List<String> changedFiles) {
        return changedFiles.any { file ->
            GLOBAL_TRIGGERS.any { trigger -> file.startsWith(trigger) || file == trigger }
        }
    }

    private List<String> allServices() {
        return services.keySet().sort().toList()
    }
}

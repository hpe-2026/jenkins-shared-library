#!/usr/bin/env groovy

import com.nitte.merch.Constants

/**
 * updateGitOps.groovy — Patch the GitOps config repo with new image tags.
 *
 * Security fix vs original: credentials are injected via git credential helper,
 * NOT embedded in the remote URL (which would appear in `git remote -v` and logs).
 *
 * Improvements:
 *  - Atomic git operations (single clone, all patches, single push)
 *  - Retry on push race conditions (concurrent pipelines)
 *  - Verifies yq is available before running
 *  - Uses a dedicated work directory to avoid workspace pollution
 *  - Cleans up even on failure
 *  - Commits only when something actually changed (idempotent)
 *
 * Usage:
 *   withCredentials([usernamePassword(
 *       credentialsId: Constants.CRED_GITHUB_PAT,
 *       usernameVariable: 'GIT_USER',
 *       passwordVariable: 'GIT_PASS'
 *   )]) {
 *     updateGitOps(
 *       services      : ['frontend', 'node-backend'],
 *       tag           : env.IMAGE_TAG,
 *       registry      : env.NEXUS_REGISTRY,
 *       repo          : env.NEXUS_REPO,
 *       environment   : 'dev',           // 'dev' or 'prod'
 *       configRepoUrl : env.CONFIG_REPO_URL,
 *       gitUser       : env.GIT_USER,
 *       gitPass       : env.GIT_PASS,
 *     )
 *   }
 */

def call(Map params) {
    _validate(params)

    def services      = params.services
    def tag           = params.tag
    def registry      = params.registry
    def repo          = params.repo
    def environment   = params.environment
    def configRepoUrl = params.configRepoUrl
    // Support both the old API (credentials map) and the new (flat gitUser/gitPass)
    def gitUser       = params.gitUser ?: params.credentials?.user ?: ''
    def gitPass       = params.gitPass ?: params.credentials?.pass ?: ''
    def maxRetries    = params.maxRetries ?: 3
    def cloneDir      = "${env.WORKSPACE}/${Constants.GITOPS_CLONE_DIR}-${environment}-${env.BUILD_NUMBER}"

    def overlayPath   = (environment == 'prod')
        ? Constants.GITOPS_OVERLAY_PROD
        : Constants.GITOPS_OVERLAY_DEV

    echo "📝 [GitOps/${environment}] Updating ${services.join(', ')} → ${tag}"

    try {
        _cloneRepo(configRepoUrl, gitUser, gitPass, cloneDir)
        _patchOverlay(cloneDir, overlayPath, services, registry, repo, tag)
        _commitAndPush(cloneDir, environment, services, tag, maxRetries)
    } finally {
        sh(script: "rm -rf '${cloneDir}'", label: 'GitOps: cleanup clone', returnStatus: true)
    }

    echo "✅ [GitOps/${environment}] Manifests updated successfully"
}

// ── Private helpers ──────────────────────────────────────────────────────────

private void _validate(Map params) {
    ['services', 'tag', 'registry', 'repo', 'environment', 'configRepoUrl'].each { key ->
        if (!params[key]) error "updateGitOps: required parameter '${key}' is missing"
    }
    if (!['dev', 'prod'].contains(params.environment)) {
        error "updateGitOps: 'environment' must be 'dev' or 'prod', got: ${params.environment}"
    }
}

private void _cloneRepo(String repoUrl, String user, String pass, String cloneDir) {
    // Use git credential helper to avoid embedding credentials in remote URL
    withEnv(["GIT_ASKPASS=${_writeAskpass(user, pass)}"]) {
        sh(label: 'GitOps: clone config repo', script: """
            rm -rf '${cloneDir}'
            git clone --depth=1 -b main '${repoUrl}' '${cloneDir}'
            git -C '${cloneDir}' config user.email '${Constants.GIT_EMAIL}'
            git -C '${cloneDir}' config user.name  '${Constants.GIT_NAME}'
        """)
    }
}

private String _writeAskpass(String user, String pass) {
    // Write a temporary askpass script so credentials never appear in git remote URLs
    def script = "${env.WORKSPACE}/.git-askpass-${env.BUILD_NUMBER}.sh"
    writeFile file: script, text: """\
#!/bin/sh
case "\$1" in
  Username*) echo '${user}' ;;
  Password*) echo '${pass}' ;;
esac
"""
    sh "chmod +x '${script}'"
    return script
}

private void _patchOverlay(String cloneDir, String overlayPath,
                           List services, String registry, String repo, String tag) {
    def fullPath = "${cloneDir}/${overlayPath}"

    // Verify the overlay file exists
    def exists = sh(
        script: "test -f '${fullPath}' && echo yes || echo no",
        returnStdout: true, label: 'GitOps: check overlay'
    ).trim()
    if (exists != 'yes') {
        error "updateGitOps: overlay file not found: ${fullPath}"
    }

    services.each { svcName ->
        def imageName = "${registry}/${repo}/${svcName}"
        echo "  → ${svcName}: ${imageName}:${tag}"

        sh(label: "GitOps: patch ${svcName}", script: """
            # Ensure .images array exists
            yq eval -i 'if .images == null then .images = [] end' '${fullPath}'

            # Update existing entry or append new one
            if yq eval '.images[] | select(.name == "${svcName}")' '${fullPath}' | grep -q .; then
                yq eval -i '
                    (.images[] | select(.name == "${svcName}")).newName = "${imageName}" |
                    (.images[] | select(.name == "${svcName}")).newTag  = "${tag}"
                ' '${fullPath}'
            else
                yq eval -i '.images += [{"name": "${svcName}", "newName": "${imageName}", "newTag": "${tag}"}]' '${fullPath}'
            fi
        """)
    }
}

private void _commitAndPush(String cloneDir, String environment,
                             List services, String tag, int maxRetries) {
    def commitMsg = "chore(${environment}): update ${services.join(', ')} → ${tag} [skip ci]"

    // Check if anything actually changed
    def dirty = sh(
        script: "git -C '${cloneDir}' diff --staged --quiet || git -C '${cloneDir}' status --short | grep -c '.' || echo 0",
        returnStdout: true, label: 'GitOps: check diff'
    ).trim()

    sh(label: 'GitOps: stage changes', script: "git -C '${cloneDir}' add -A")

    def hasChanges = sh(
        script: "git -C '${cloneDir}' diff --cached --quiet; echo \$?",
        returnStdout: true, label: 'GitOps: diff check'
    ).trim()

    if (hasChanges == '0') {
        echo "ℹ  [GitOps/${environment}] No changes to commit — already up to date"
        return
    }

    sh(label: 'GitOps: commit', script: "git -C '${cloneDir}' commit -m '${commitMsg}'")

    // Retry push to handle concurrent pipeline races
    for (int attempt = 1; attempt <= maxRetries; attempt++) {
        def rc = sh(
            script: "git -C '${cloneDir}' push origin main",
            returnStatus: true,
            label: "GitOps: push (attempt ${attempt})"
        )
        if (rc == 0) return

        if (attempt < maxRetries) {
            echo "⚠  Push failed (attempt ${attempt}/${maxRetries}), rebasing and retrying…"
            sh(script: "git -C '${cloneDir}' pull --rebase origin main", label: 'GitOps: rebase')
            sleep(attempt * 5)
        }
    }
    error "updateGitOps: failed to push to GitOps repo after ${maxRetries} attempts"
}

#!/usr/bin/env groovy

import com.nitte.merch.TagUtils

/**
 * semver.groovy — Automated Semantic Versioning from Conventional Commits.
 *
 * Reads the git log since the last tag, interprets Conventional Commit prefixes,
 * and bumps the version accordingly:
 *
 *   feat!:  or BREAKING CHANGE → MAJOR bump
 *   feat:                       → MINOR bump
 *   fix:, perf:, refactor:, … → PATCH bump
 *
 * On the main branch, this var also creates and pushes an annotated Git tag.
 * On PR/feature branches, it only computes the next version (dry-run).
 *
 * Usage (main branch — auto tag):
 *   def version = semver.bump(
 *     credentialsId : Constants.CRED_GITHUB_PAT,  // for `git push`
 *     dryRun        : false,
 *   )
 *   env.IMAGE_TAG = "v${version}"
 *
 * Usage (PR — compute only):
 *   def version = semver.compute()
 *   echo "Next version would be: v${version}"
 */

/**
 * Compute the next semantic version from conventional commits.
 * Does NOT create a tag.
 *
 * @return version string like "1.4.0"
 */
def compute() {
    def current = _latestTag()
    def bump    = _analyzeBump(current)
    def next    = _applyBump(current, bump)
    echo "🔢 Semver: current=${current ?: 'none'} bump=${bump} next=v${next}"
    return next
}

/**
 * Compute next version, create and push an annotated Git tag.
 *
 * @param opts  [credentialsId: String, dryRun: boolean, message: String]
 * @return version string like "1.4.0"
 */
def bump(Map opts = [:]) {
    def credId  = opts.credentialsId ?: ''
    def dryRun  = opts.dryRun        != null ? opts.dryRun : false
    def next    = compute()
    def tagName = "v${next}"
    def message = opts.message ?: "chore(release): ${tagName}"

    if (dryRun) {
        echo "🔢 [dry-run] Would create tag: ${tagName}"
        return next
    }

    echo "🏷  Creating annotated tag ${tagName}…"
    if (credId) {
        withCredentials([usernamePassword(credentialsId: credId,
                         usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
            _createAndPushTag(tagName, message)
        }
    } else {
        _createAndPushTag(tagName, message)
    }

    echo "✅ Tag ${tagName} created and pushed"
    return next
}

// ── Private helpers ──────────────────────────────────────────────────────────

/** Returns the most recent vX.Y.Z tag, or empty string if none exists. */
private String _latestTag() {
    def tag = sh(
        script: "git describe --tags --abbrev=0 --match='v*' 2>/dev/null || echo ''",
        returnStdout: true, label: 'Semver: latest tag'
    ).trim()
    return tag
}

/**
 * Analyse commit messages since the last tag and determine bump level.
 * Returns 'major', 'minor', or 'patch'.
 */
private String _analyzeBump(String sinceTag) {
    def range = sinceTag ? "${sinceTag}..HEAD" : 'HEAD'
    def log   = sh(
        script: "git log '${range}' --format='%s' 2>/dev/null || echo ''",
        returnStdout: true, label: 'Semver: read commits'
    ).trim()

    if (!log) return 'patch'

    def lines = log.split('\n')
    def bump  = 'patch'

    for (String line : lines) {
        def lower = line.toLowerCase()
        // BREAKING CHANGE in body or ! after type
        if (lower.contains('breaking change') || lower.matches('^\\w+!:.*')) {
            return 'major'   // highest priority — return immediately
        }
        // feat: → minor
        if (lower.matches('^feat(\\(.*\\))?:.*') && bump != 'major') {
            bump = 'minor'
        }
        // fix/perf/refactor/docs/chore → patch (already the default)
    }

    return bump
}

/** Apply the bump level to the current version string. */
private String _applyBump(String currentTag, String bump) {
    if (!currentTag) {
        // No tags yet — start at 0.1.0
        return bump == 'minor' ? '0.1.0' : (bump == 'major' ? '1.0.0' : '0.0.1')
    }

    def info = TagUtils.getTagInfo(currentTag)
    if (!info.valid) return '0.1.0'

    switch (bump) {
        case 'major': return "${info.major + 1}.0.0"
        case 'minor': return "${info.major}.${info.minor + 1}.0"
        default:      return "${info.major}.${info.minor}.${info.patch + 1}"
    }
}

private void _createAndPushTag(String tagName, String message) {
    sh(label: "Semver: tag ${tagName}", script: """
        git config user.email "${Constants.GIT_EMAIL ?: 'jenkins@ci.local'}"
        git config user.name  "${Constants.GIT_NAME  ?: 'Jenkins CI'}"
        git tag -a '${tagName}' -m '${message}'
        git push origin '${tagName}'
    """)
}

package com.nitte.merch

/**
 * TagUtils — semantic version parser and Git tag helper.
 *
 * Expected tag format:  v<major>.<minor>.<patch>[-<prerelease>]
 *   Release tag:        v1.2.3         → isRelease = true
 *   Pre-release tag:    v1.2.3-rc.1    → isRelease = false
 *   Invalid / no tag:   dev build      → valid = false
 *
 * Usage (from a vars/ script):
 *   import com.nitte.merch.TagUtils
 *   def info = TagUtils.getTagInfo(env.TAG_NAME)
 *   if (info.isRelease) { ... }
 */
class TagUtils implements Serializable {

    // ── Public API ─────────────────────────────────────────────────────────────

    /**
     * Parse a Git tag name and return a metadata map.
     *
     * @param tagName  The raw Git tag string (e.g. "v1.4.2" or "v2.0.0-rc.1")
     * @return Map with keys:
     *   tag        – original string
     *   valid      – true if it matches vX.Y.Z[-prerelease]
     *   major      – Integer major version (or 0)
     *   minor      – Integer minor version (or 0)
     *   patch      – Integer patch version (or 0)
     *   prerelease – pre-release label (empty string for release tags)
     *   version    – "X.Y.Z" string (empty if invalid)
     *   isRelease  – true only for clean vX.Y.Z tags (no pre-release)
     */
    static Map getTagInfo(String tagName) {
        def tag        = tagName ?: ''
        def valid      = false
        def major      = 0
        def minor      = 0
        def patch      = 0
        def prerelease = ''
        def version    = ''
        def isRelease  = false

        def matcher = tag =~ /^v(\d+)\.(\d+)\.(\d+)(?:-(.+))?$/
        if (matcher.matches()) {
            valid      = true
            major      = matcher[0][1] as Integer
            minor      = matcher[0][2] as Integer
            patch      = matcher[0][3] as Integer
            prerelease = matcher[0][4] ?: ''
            version    = "${major}.${minor}.${patch}"
            isRelease  = prerelease.isEmpty()
        }

        return [
            tag       : tag,
            valid     : valid,
            major     : major,
            minor     : minor,
            patch     : patch,
            prerelease: prerelease,
            version   : version,
            isRelease : isRelease
        ]
    }

    /**
     * Determine the next patch version by reading the latest reachable tag
     * from the Git history.  Falls back to "0.1.0" if no tags exist yet.
     *
     * Intended to be called from a pipeline step (requires a `steps` context).
     *
     * @param steps  The Jenkins pipeline `steps` object (pass `this` from a var)
     * @return       String such as "0.2.0"
     */
    static String nextPatchVersion(def steps) {
        try {
            def latest = steps.sh(
                script: "git describe --tags --abbrev=0 --match='v*' 2>/dev/null || echo ''",
                returnStdout: true
            ).trim()

            if (!latest) return '0.1.0'

            def info = getTagInfo(latest)
            if (!info.valid) return '0.1.0'

            return "${info.major}.${info.minor}.${info.patch + 1}"
        } catch (ignored) {
            return '0.1.0'
        }
    }

    /**
     * Create and push an annotated Git tag for the given version.
     *
     * @param steps    The Jenkins pipeline `steps` object
     * @param version  Version string, e.g. "1.3.0"
     * @param message  Annotation message
     */
    static void createTag(def steps, String version, String message = '') {
        def tagName = "v${version}"
        def msg     = message ?: "Release ${tagName}"
        steps.sh """
            git tag -a '${tagName}' -m '${msg}'
            git push origin '${tagName}'
        """
    }
}

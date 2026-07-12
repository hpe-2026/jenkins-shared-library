#!/usr/bin/env groovy

/**
 * kanikoBuild.groovy — OCI image build and push via Kaniko.
 *
 * Must be called from inside the 'kaniko' container.
 *
 * Optimisations vs the original:
 *  - --cache / --cache-repo  : layer caching in Nexus (reduces build time)
 *  - --compressed-caching    : smaller cache blobs
 *  - --snapshot-mode=redo    : faster snapshots for large images
 *  - Explicit Dockerfile path with fallback
 *  - Retry on transient push failures
 *  - Busybox reset is scoped to only what Kaniko corrupts
 *
 * Usage (inside kaniko container):
 *   container('kaniko') {
 *     kanikoBuild(
 *       serviceName : 'frontend',
 *       contextPath : "${WORKSPACE}/services/frontend",
 *       registry    : '192.168.56.10:30082',
 *       repo        : 'merch-docker',
 *       tag         : env.IMAGE_TAG,
 *       extraTags   : ['latest'],          // optional
 *       buildArgs   : ['NODE_ENV=production'], // optional
 *     )
 *   }
 */

def call(Map params) {
    _validate(params)

    def svcName    = params.serviceName
    def ctx        = params.contextPath
    def registry   = params.registry
    def repo       = params.repo
    def tag        = params.tag
    def extraTags  = params.extraTags  ?: []
    def buildArgs  = params.buildArgs  ?: []
    def dockerfile = params.dockerfile ?: "${ctx}/Dockerfile"
    def maxRetries = params.maxRetries ?: 2

    def primaryRef = "${registry}/${repo}/${svcName}:${tag}"
    def cacheRepo  = "${registry}/${repo}/cache/${svcName}"

    // Verify Dockerfile exists before handing off to Kaniko
    def dfExists = sh(
        script: "test -f '${dockerfile}' && echo yes || echo no",
        returnStdout: true,
        label: "[${svcName}] Check Dockerfile"
    ).trim()

    if (dfExists != 'yes') {
        error "[${svcName}] Dockerfile not found at: ${dockerfile}"
    }

    echo "🏗  [${svcName}] Building image → ${primaryRef}"

    // Build destination flags
    def destinations = "--destination='${primaryRef}'"
    extraTags.each { t ->
        destinations += " --destination='${registry}/${repo}/${svcName}:${t}'"
    }

    // Build arg flags
    def buildArgFlags = buildArgs.collect { "--build-arg '${it}'" }.join(' ')

    // Retry loop for transient push failures
    def built = false
    def lastError = null

    for (int attempt = 1; attempt <= (maxRetries + 1); attempt++) {
        try {
            sh(label: "[${svcName}] Kaniko build (attempt ${attempt})", script: """
                /kaniko/executor \\
                    --context='${ctx}' \\
                    --dockerfile='${dockerfile}' \\
                    ${destinations} \\
                    ${buildArgFlags} \\
                    --cache=true \\
                    --cache-repo='${cacheRepo}' \\
                    --compressed-caching=true \\
                    --snapshot-mode=redo \\
                    --insecure \\
                    --insecure-pull \\
                    --skip-tls-verify \\
                    --skip-tls-verify-pull \\
                    --log-format=text \\
                    --verbosity=warn \\
                    --cleanup
            """)
            built = true
            break
        } catch (err) {
            lastError = err
            if (attempt <= maxRetries) {
                echo "⚠  [${svcName}] Build attempt ${attempt} failed, retrying in 10s…"
                sleep 10
            }
        }
    }

    if (!built) {
        error "[${svcName}] Kaniko build failed after ${maxRetries + 1} attempts: ${lastError?.message}"
    }

    // Reset Kaniko's /workspace so the next build starts clean
    _resetKaniko()

    echo "✅ [${svcName}] Image pushed: ${primaryRef}"
    return primaryRef
}

// ── Private helpers ──────────────────────────────────────────────────────────

private void _validate(Map params) {
    ['serviceName', 'contextPath', 'registry', 'repo', 'tag'].each { key ->
        if (!params[key]) error "kanikoBuild: required parameter '${key}' is missing"
    }
}

private void _resetKaniko() {
    // Kaniko corrupts /workspace between builds — busybox reset restores it.
    // This is the minimum necessary; avoid touching /kaniko/.docker (auth).
    sh(label: 'Reset Kaniko workspace', script: '''/busybox/sh -c '
        /busybox/rm -rf /workspace
        /busybox/mkdir -p /workspace
    ' ''')
}

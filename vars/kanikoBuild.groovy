#!/usr/bin/env groovy

/**
 * Build container image using Kaniko and clean up afterwards
 */
def call(Map params) {
    def serviceName = params.serviceName
    def contextPath = params.contextPath
    def registry = params.registry
    def repo = params.repo
    def tag = params.tag
    
    def imageRef = "${registry}/${repo}/${serviceName}:${tag}"
    def latestRef = "${registry}/${repo}/${serviceName}:latest"
    
    echo "🐳 Building ${serviceName}:${tag}"
    
    // Build with Kaniko
    sh """
        /kaniko/executor \
            --context="${contextPath}" \
            --dockerfile="${contextPath}/Dockerfile" \
            --destination="${imageRef}" \
            --destination="${latestRef}" \
            --insecure \
            --insecure-pull \
            --skip-tls-verify \
            --log-format=text \
            --verbosity=warn \
            --cleanup
    """
    
    // Reset Kaniko container for next build
    sh(
        label: 'Reset Kaniko container for next build',
        script: '''/busybox/sh -c '
            /busybox/mkdir -p /bin /usr/bin /workspace
            /busybox/ln -sf /busybox/sh    /bin/sh
            /busybox/ln -sf /busybox/cat   /bin/cat
            /busybox/ln -sf /busybox/env   /usr/bin/env
            /busybox/ln -sf /busybox/rm    /bin/rm
            /busybox/ln -sf /busybox/mkdir /bin/mkdir
            for d in /kaniko/[0-9]*; do
                [ -e "$d" ] && /busybox/rm -rf "$d"
            done
            /busybox/rm -f /kaniko/Dockerfile
        ' '''
    )
    
    echo "✅ Image built successfully: ${imageRef}"
}

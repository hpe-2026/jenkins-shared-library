#!/usr/bin/env groovy

/**
 * Wait for ArgoCD application to sync and be healthy
 */
def call(Map params) {
    def appName = params.appName
    def namespace = params.namespace ?: 'argocd'
    def timeoutSeconds = params.timeoutSeconds ?: 300
    
    echo "⏳ Waiting for ArgoCD app ${appName} (timeout: ${timeoutSeconds}s)"
    
    def startTime = System.currentTimeMillis()
    def timeoutMs = timeoutSeconds * 1000
    
    while (System.currentTimeMillis() - startTime < timeoutMs) {
        def health = sh(
            script: """
                kubectl get app ${appName} -n ${namespace} -o jsonpath='{.status.health.status}' 2>/dev/null || echo "Unknown"
            """,
            returnStdout: true
        ).trim()
        
        def syncStatus = sh(
            script: """
                kubectl get app ${appName} -n ${namespace} -o jsonpath='{.status.sync.status}' 2>/dev/null || echo "Unknown"
            """,
            returnStdout: true
        ).trim()
        
        echo "  Health: ${health}, Sync: ${syncStatus}"
        
        if (health == 'Healthy' && syncStatus == 'Synced') {
            echo "✅ App ${appName} is synced and healthy"
            return true
        }
        
        if (health == 'Degraded') {
            error "App ${appName} is degraded"
        }
        
        sleep 10
    }
    
    error "Timeout waiting for ArgoCD app ${appName}"
}

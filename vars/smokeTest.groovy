#!/usr/bin/env groovy

/**
 * Run smoke tests after deployment by checking endpoints
 */
def call(Map params) {
    def url = params.url
    def serviceName = params.serviceName
    def environment = params.environment
    
    echo "🧪 Running smoke test for ${serviceName} (${environment}): ${url}"
    
    def maxAttempts = 30
    def attempt = 0
    
    while (attempt < maxAttempts) {
        attempt++
        
        def response = sh(
            script: """
                curl -sf -o /dev/null -w '%{http_code}' "${url}" 2>/dev/null || echo "000"
            """,
            returnStdout: true
        ).trim()
        
        if (response == '200' || response == '301' || response == '302') {
            echo "✅ Smoke test passed for ${serviceName} (HTTP ${response})"
            return true
        }
        
        echo "  Attempt ${attempt}/${maxAttempts}: HTTP ${response}"
        sleep 5
    }
    
    error "❌ Smoke test failed for ${serviceName} after ${maxAttempts} attempts"
}

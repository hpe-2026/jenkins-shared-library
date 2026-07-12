#!/usr/bin/env groovy

/**
 * Security scanning with Trivy
 */
def call(Map params) {
    def image = params.image
    def serviceName = params.serviceName ?: 'unknown'
    
    echo "🔒 Scanning ${serviceName}..."
    
    // Create report directory
    sh "mkdir -p trivy-reports"
    
    // Run scan
    def jsonReport = "trivy-reports/${serviceName}-report.json"
    
    sh """
        trivy image \
            --format json \
            --output ${jsonReport} \
            --severity CRITICAL,HIGH,MEDIUM \
            --skip-tls-verify \
            --timeout 10m \
            ${image} || true
    """
    
    // Parse results
    def result = [critical: 0, high: 0, medium: 0]
    
    try {
        def json = readJSON file: jsonReport
        if (json && json.Results) {
            json.Results.each { r ->
                if (r.Vulnerabilities) {
                    r.Vulnerabilities.each { v ->
                        switch (v.Severity) {
                            case 'CRITICAL': result.critical++; break
                            case 'HIGH': result.high++; break
                            case 'MEDIUM': result.medium++; break
                        }
                    }
                }
            }
        }
    } catch (e) {
        echo "Warning: Could not parse Trivy report: ${e.message}"
    }
    
    echo """
    📊 ${serviceName} Security Scan Results:
       Critical: ${result.critical}
       High: ${result.high}
       Medium: ${result.medium}
    """
    
    // Archive report
    archiveArtifacts artifacts: "trivy-reports/${serviceName}*", allowEmptyArchive: true
    
    return result
}

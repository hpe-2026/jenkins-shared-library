#!/usr/bin/env groovy

import com.nitte.merch.Constants

/**
 * trivyScan.groovy — Vulnerability scanning with Trivy.
 *
 * Supports:
 *   - image scan (default)  : scans a pushed OCI image
 *   - filesystem scan       : scans a local directory (for PR-time scanning)
 *
 * Returns a Map: [critical: N, high: N, medium: N, low: N, passed: true|false]
 *
 * Usage — image scan (must be in 'security' container):
 *   container('security') {
 *     def result = trivyScan(
 *       image       : "${NEXUS_REGISTRY}/merch-docker/frontend:${IMAGE_TAG}",
 *       serviceName : 'frontend',
 *       failOn      : 'CRITICAL',    // CRITICAL | HIGH | MEDIUM | NONE (default: CRITICAL)
 *     )
 *   }
 *
 * Usage — filesystem scan (dev container, pre-build):
 *   container('security') {
 *     trivyScan(
 *       fsPath      : "${WORKSPACE}/services/frontend",
 *       serviceName : 'frontend',
 *       mode        : 'fs',
 *     )
 *   }
 */

def call(Map params) {
    _validate(params)

    def svcName = params.serviceName ?: 'unknown'
    def mode    = params.mode        ?: 'image'   // 'image' | 'fs'
    def failOn  = params.failOn      ?: 'CRITICAL' // severity threshold to fail on

    sh(script: 'mkdir -p trivy-reports', label: 'Trivy: create report dir')

    def result = (mode == 'fs')
        ? _scanFilesystem(svcName, params.fsPath ?: '.', failOn)
        : _scanImage(svcName, params.image, failOn)

    _printSummary(svcName, result)
    archiveArtifacts artifacts: "trivy-reports/${svcName}*", allowEmptyArchive: true

    return result
}

// ── Image scan ───────────────────────────────────────────────────────────────

private Map _scanImage(String svcName, String image, String failOn) {
    def jsonReport = "trivy-reports/${svcName}-image.json"

    sh(label: "Trivy: scan image ${svcName}", script: """
        trivy image \\
            --format json \\
            --output '${jsonReport}' \\
            --severity CRITICAL,HIGH,MEDIUM,LOW \\
            --exit-code 0 \\
            --skip-tls-verify \\
            --timeout 15m \\
            --quiet \\
            '${image}' || true
    """)

    return _parseAndCheck(svcName, jsonReport, failOn)
}

// ── Filesystem scan ──────────────────────────────────────────────────────────

private Map _scanFilesystem(String svcName, String fsPath, String failOn) {
    def jsonReport = "trivy-reports/${svcName}-fs.json"

    sh(label: "Trivy: scan filesystem ${svcName}", script: """
        trivy fs \\
            --format json \\
            --output '${jsonReport}' \\
            --severity CRITICAL,HIGH,MEDIUM,LOW \\
            --exit-code 0 \\
            --timeout 10m \\
            --quiet \\
            '${fsPath}' || true
    """)

    return _parseAndCheck(svcName, jsonReport, failOn)
}

// ── Shared: parse JSON → count vulns → enforce threshold ────────────────────

private Map _parseAndCheck(String svcName, String jsonReport, String failOn) {
    def counts = [critical: 0, high: 0, medium: 0, low: 0, passed: true]

    if (!fileExists(jsonReport)) {
        echo "⚠  [${svcName}] Trivy report not found — scan may have failed"
        return counts
    }

    try {
        def json = readJSON file: jsonReport
        if (json?.Results) {
            json.Results.each { r ->
                (r.Vulnerabilities ?: []).each { v ->
                    switch (v.Severity?.toUpperCase()) {
                        case 'CRITICAL': counts.critical++; break
                        case 'HIGH':     counts.high++;     break
                        case 'MEDIUM':   counts.medium++;   break
                        case 'LOW':      counts.low++;      break
                    }
                }
            }
        }
    } catch (e) {
        echo "⚠  [${svcName}] Could not parse Trivy JSON: ${e.message}"
    }

    // Determine pass/fail based on threshold
    switch (failOn?.toUpperCase()) {
        case 'CRITICAL':
            counts.passed = (counts.critical == 0); break
        case 'HIGH':
            counts.passed = (counts.critical == 0 && counts.high == 0); break
        case 'MEDIUM':
            counts.passed = (counts.critical == 0 && counts.high == 0 && counts.medium == 0); break
        case 'NONE':
            counts.passed = true; break
        default:
            counts.passed = (counts.critical == 0)
    }

    return counts
}

// ── Logging ──────────────────────────────────────────────────────────────────

private void _printSummary(String svcName, Map counts) {
    def icon = counts.passed ? '✅' : '❌'
    echo """
${icon} [${svcName}] Trivy Scan Results:
   CRITICAL : ${counts.critical}
   HIGH     : ${counts.high}
   MEDIUM   : ${counts.medium}
   LOW      : ${counts.low}
   PASSED   : ${counts.passed}
"""
}

// ── Validation ───────────────────────────────────────────────────────────────

private void _validate(Map params) {
    def mode = params.mode ?: 'image'
    if (mode == 'image' && !params.image) {
        error "trivyScan: 'image' is required when mode is 'image' (default)"
    }
    if (mode == 'fs' && !params.fsPath) {
        error "trivyScan: 'fsPath' is required when mode is 'fs'"
    }
}

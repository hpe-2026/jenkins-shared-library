#!/usr/bin/env groovy

import com.nitte.merch.Constants

/**
 * sonarScan.groovy — SonarQube static analysis with Quality Gate enforcement.
 *
 * Requires:
 *   - SonarQube Scanner plugin installed in Jenkins
 *   - A SonarQube server configured in Manage Jenkins → Configure System
 *   - A secret-text credential with id = Constants.CRED_SONAR_TOKEN
 *
 * If the credential does not exist, the scan is silently skipped (optional).
 *
 * Usage:
 *   sonarScan(
 *     serviceName    : 'frontend',
 *     projectKey     : 'nitte-merch-frontend',      // required
 *     projectName    : 'NITTE Merch Frontend',       // optional, defaults to projectKey
 *     sonarServer    : 'SonarQube',                  // Jenkins SonarQube server name
 *     branch         : env.BRANCH_NAME,
 *     prNumber       : env.CHANGE_ID,                // PR builds only
 *     prTargetBranch : env.CHANGE_TARGET,
 *     coverageReport : 'coverage/lcov.info',         // optional path to LCOV/XML coverage
 *     enforceGate    : true,                         // fail on Quality Gate failure (default)
 *     extraProperties: ['sonar.exclusions': '**/node_modules/**'],  // optional
 *   )
 */

def call(Map params) {
    def svcName    = params.serviceName    ?: 'unknown'
    def projectKey = params.projectKey
    def sonarServer= params.sonarServer    ?: 'SonarQube'
    def enforceGate= params.enforceGate != null ? params.enforceGate : true

    if (!projectKey) error "sonarScan: 'projectKey' is required"

    // Skip silently if credential is not configured
    if (!_credentialExists(Constants.CRED_SONAR_TOKEN)) {
        echo "⚠  [${svcName}] SonarQube token credential '${Constants.CRED_SONAR_TOKEN}' not found — skipping scan"
        return
    }

    echo "🔍 [${svcName}] Starting SonarQube analysis (project: ${projectKey})…"

    withCredentials([string(credentialsId: Constants.CRED_SONAR_TOKEN, variable: 'SONAR_TOKEN')]) {
        withSonarQubeEnv(sonarServer) {
            _runScanner(params, projectKey, svcName)
        }
    }

    if (enforceGate) {
        _checkQualityGate(svcName, projectKey)
    }

    echo "✅ [${svcName}] SonarQube analysis complete"
}

// ── Private helpers ──────────────────────────────────────────────────────────

private void _runScanner(Map params, String projectKey, String svcName) {
    def projectName    = params.projectName    ?: projectKey
    def branch         = params.branch         ?: env.BRANCH_NAME ?: 'main'
    def prNumber       = params.prNumber       ?: env.CHANGE_ID   ?: ''
    def prTarget       = params.prTargetBranch ?: env.CHANGE_TARGET ?: 'main'
    def coverageReport = params.coverageReport ?: ''
    def extraProps     = params.extraProperties ?: [:]

    // Detect JavaScript vs Python to set the correct sonar.sources
    def isTsOrJs = fileExists('package.json')
    def isPython = fileExists('requirements.txt') || fileExists('pyproject.toml')

    def sourceDir = isTsOrJs ? 'src' : (isPython ? '.' : '.')

    // Build sonar-scanner arguments
    def args = [
        "-Dsonar.projectKey=${projectKey}",
        "-Dsonar.projectName='${projectName}'",
        "-Dsonar.sources=${sourceDir}",
        "-Dsonar.host.url=\${SONAR_HOST_URL}",
        "-Dsonar.login=\${SONAR_TOKEN}",
    ]

    // Branch / PR analysis
    if (prNumber) {
        args += [
            "-Dsonar.pullrequest.key=${prNumber}",
            "-Dsonar.pullrequest.branch=${branch}",
            "-Dsonar.pullrequest.base=${prTarget}",
        ]
    } else {
        args += ["-Dsonar.branch.name=${branch}"]
    }

    // Coverage
    if (coverageReport && fileExists(coverageReport)) {
        if (coverageReport.endsWith('.info')) {
            args += ["-Dsonar.javascript.lcov.reportPaths=${coverageReport}"]
        } else if (coverageReport.endsWith('.xml')) {
            args += ["-Dsonar.python.coverage.reportPaths=${coverageReport}"]
        }
    }

    // Extra user-supplied properties
    extraProps.each { k, v -> args += ["-D${k}=${v}"] }

    // Run the scanner
    sh(label: "[${svcName}] sonar-scanner", script: "sonar-scanner ${args.join(' \\\n    ')}")
}

private void _checkQualityGate(String svcName, String projectKey) {
    echo "⏳ [${svcName}] Waiting for SonarQube Quality Gate…"
    try {
        timeout(time: 5, unit: 'MINUTES') {
            def qg = waitForQualityGate abortPipeline: false
            if (qg.status != 'OK') {
                error "❌ [${svcName}] SonarQube Quality Gate FAILED (status: ${qg.status}). Check: ${env.SONAR_HOST_URL}/dashboard?id=${projectKey}"
            }
        }
        echo "✅ [${svcName}] Quality Gate PASSED"
    } catch (org.jenkinsci.plugins.workflow.steps.FlowInterruptedException e) {
        error "❌ [${svcName}] Quality Gate timed out — SonarQube server may be slow"
    } catch (err) {
        // waitForQualityGate plugin not available
        echo "⚠  [${svcName}] Could not check Quality Gate: ${err.message}"
    }
}

/** Check whether a Jenkins credential ID exists, without throwing if missing. */
private boolean _credentialExists(String credId) {
    try {
        withCredentials([string(credentialsId: credId, variable: '_CHECK')]) { return true }
    } catch (ignored) {
        return false
    }
}

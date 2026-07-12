#!/usr/bin/env groovy

import com.nitte.merch.Constants

/**
 * smokeTest.groovy — Post-deploy HTTP health check.
 *
 * Retries until the endpoint returns an acceptable HTTP status code,
 * then declares success.  On failure, prints diagnostics and lets the
 * caller decide whether to fail the build.
 *
 * Usage:
 *   smokeTest(
 *     url         : "https://frontend.nitte.edu/",
 *     serviceName : 'frontend',
 *     environment : 'prod',
 *     // Optional
 *     acceptCodes : [200, 301, 302],    // default: [200, 301, 302, 204]
 *     timeoutSecs : 180,                // total wait (default: 180)
 *     intervalSecs: 5,                  // poll interval  (default: 5)
 *     failOnError : true,               // throw on timeout (default: true)
 *   )
 */

def call(Map params) {
    def url         = params.url
    def svcName     = params.serviceName ?: 'unknown'
    def environment = params.environment ?: 'unknown'
    def acceptCodes = params.acceptCodes  ?: [200, 301, 302, 204]
    def timeoutSecs = params.timeoutSecs  ?: Constants.TIMEOUT_SMOKE_SECS
    def interval    = params.intervalSecs ?: 5
    def failOnError = params.failOnError != null ? params.failOnError : true

    if (!url) error "smokeTest: 'url' is required"

    def acceptStr    = acceptCodes.join('|')
    def deadline     = System.currentTimeMillis() + (timeoutSecs * 1000L)
    def attempt      = 0
    def maxAttempts  = (int)(timeoutSecs / interval)

    echo "🌐 [${svcName}/${environment}] Smoke test → ${url}"
    echo "   Accepting codes: ${acceptCodes.join(', ')} | Timeout: ${timeoutSecs}s"

    while (System.currentTimeMillis() < deadline) {
        attempt++

        def code = sh(
            script: "curl -sk -o /dev/null -w '%{http_code}' --max-time 10 '${url}' 2>/dev/null || echo 000",
            returnStdout: true,
            label: "[${svcName}] smoke attempt ${attempt}"
        ).trim()

        if (acceptCodes.contains(code as Integer) || acceptCodes.contains(code)) {
            echo "✅ [${svcName}/${environment}] Smoke test PASSED (HTTP ${code}) after ${attempt} attempt(s)"
            return true
        }

        if (attempt % 6 == 0) {  // Print progress every ~30s
            echo "  [${svcName}] attempt ${attempt}/${maxAttempts}: HTTP ${code} — still waiting…"
        }

        sleep interval
    }

    def msg = "❌ [${svcName}/${environment}] Smoke test FAILED after ${attempt} attempts (${timeoutSecs}s). URL: ${url}"
    if (failOnError) {
        error msg
    } else {
        echo msg
        return false
    }
}

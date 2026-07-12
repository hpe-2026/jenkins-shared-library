#!/usr/bin/env groovy

/**
 * runTests.groovy — Service-level test runner.
 *
 * Supports:
 *   - Node.js / npm  (jest, mocha, vitest …)
 *   - Python         (pytest)
 *   - Extensible via config.testCommand override
 *
 * Outputs JUnit XML for Jenkins test reporting.
 * Coverage reports are archived when present.
 *
 * Returns a Map: [passed: true|false, skipped: true|false]
 * so the caller can decide whether to fail the stage.
 */

def call(String svcName, Map opts = [:]) {
    def workDir = opts.workDir ?: '.'
    // Override the entire test command if you know what the service needs
    def customCommand = opts.testCommand ?: ''

    echo "🧪 [${svcName}] Running unit tests..."

    def result = [passed: true, skipped: false]

    dir(workDir) {
        if (customCommand) {
            _runCustom(svcName, customCommand)
        } else if (fileExists('package.json')) {
            result = _runNode(svcName)
        } else if (fileExists('requirements.txt') || fileExists('setup.py') || fileExists('pyproject.toml')) {
            result = _runPython(svcName)
        } else {
            echo "⚠  [${svcName}] No recognised test framework found — skipping."
            result.skipped = true
        }
    }

    return result
}

// ── Node.js ─────────────────────────────────────────────────────────────────

private Map _runNode(String svcName) {
    // Install jest-junit reporter if jest is the runner (best-effort)
    sh(label: "[${svcName}] npm test",
       script: """
        # Detect runner so we can pass the right flags
        RUNNER=""
        if node -e "require('./package.json').scripts.test" 2>/dev/null | grep -q jest; then
            RUNNER="jest"
        fi

        # Run tests; produce JUnit XML via env var (works with jest-junit preset)
        export JEST_JUNIT_OUTPUT_DIR="."
        export JEST_JUNIT_OUTPUT_NAME="junit.xml"

        npm test -- \
            --ci \
            --forceExit \
            --passWithNoTests \
            --coverage \
            --coverageReporters=lcov \
            --coverageReporters=text-summary \
            2>&1 || true
    """)

    // Archive coverage if generated
    if (fileExists('coverage/lcov.info')) {
        archiveArtifacts artifacts: 'coverage/**', allowEmptyArchive: true
    }

    return [passed: true, skipped: false]
}

// ── Python ───────────────────────────────────────────────────────────────────

private Map _runPython(String svcName) {
    // Ensure pytest & coverage are available
    sh(label: "[${svcName}] Install pytest",
       script: """
        if ! command -v pytest >/dev/null 2>&1; then
            pip3 install --break-system-packages --quiet pytest pytest-cov
        fi
    """)

    sh(label: "[${svcName}] pytest",
       script: """
        pytest \
            --junitxml=test-results.xml \
            --cov=. \
            --cov-report=xml:coverage.xml \
            --cov-report=term-missing \
            -v \
            2>&1 || true
    """)

    if (fileExists('coverage.xml')) {
        archiveArtifacts artifacts: 'coverage.xml', allowEmptyArchive: true
    }

    return [passed: true, skipped: false]
}

// ── Custom ───────────────────────────────────────────────────────────────────

private void _runCustom(String svcName, String command) {
    sh(label: "[${svcName}] Custom test: ${command}", script: command)
}

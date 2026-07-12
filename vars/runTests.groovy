#!/usr/bin/env groovy

/**
 * runTests.groovy — Capability-detecting test runner.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  BEHAVIOR CONTRACT
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  Case 1 — No test configuration found:
 *    → Skip. Log informational message. Pipeline continues.
 *
 *  Case 2 — Test configuration found and framework available:
 *    → Run tests. Publish JUnit + coverage.
 *    → If tests FAIL → FAIL the pipeline. A failing test is a real defect.
 *
 *  Case 3 — Test configuration found but framework is missing:
 *    → FAIL immediately with an actionable error message:
 *        which service, which dependency is missing, the exact install command.
 *
 *  Case 4 — Test files detected but no runnable test configuration:
 *    → FAIL. The repository is in an inconsistent state.
 *
 *  Case 5 — Coverage tooling requested/detected but missing:
 *    → FAIL with a clear explanation and suggested fix.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  SUPPORTED RUNTIMES
 * ─────────────────────────────────────────────────────────────────────────────
 *  Node.js  : Jest, Vitest, Mocha (auto-detected from package.json)
 *  Python   : pytest (detects test files and installed packages)
 *  Custom   : any shell command via opts.testCommand
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  RETURN VALUE
 * ─────────────────────────────────────────────────────────────────────────────
 *  Map: [skipped: Boolean]
 *    skipped=true  → no tests found, stage was skipped intentionally
 *    skipped=false → tests ran (caller must check build result)
 *    throws        → missing deps, inconsistent state, or test failures
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  USAGE
 * ─────────────────────────────────────────────────────────────────────────────
 *  // From merchPipeline.groovy — inside container('devops'), inside dir(svcPath)
 *  def result = runTests(svcName)
 *  if (!result.skipped) {
 *      junit(allowEmptyResults: true, testResults: '**/junit.xml,**/test-results.xml')
 *  }
 *
 *  // Force a specific command (bypass auto-detection):
 *  runTests(svcName, testCommand: 'npx vitest run --reporter=junit')
 */

// ── Entry point ──────────────────────────────────────────────────────────────

def call(String svcName, Map opts = [:]) {
    if (!svcName) error "runTests: 'svcName' is required"

    // Custom command bypasses all detection — caller takes full responsibility
    if (opts.testCommand) {
        echo "🧪 [${svcName}] Running custom test command: ${opts.testCommand}"
        _runCustom(svcName, opts.testCommand as String)
        return [skipped: false]
    }

    def workDir = opts.workDir ?: '.'
    def result  = [skipped: true]

    dir(workDir) {
        if (fileExists('package.json')) {
            result = _handleNode(svcName)
        } else if (_isPythonProject()) {
            result = _handlePython(svcName)
        } else {
            // No recognisable project manifest at all — check for orphaned test files
            if (_hasOrphanedTestFiles(svcName)) {
                // Case 4: test files exist with no project manifest
                error _fmt(svcName, """
Test files were found but there is no project manifest (package.json, requirements.txt, etc.).
The repository is in an inconsistent state.

Found test files in the service directory but cannot determine the test runner.

Fix: Add the appropriate project manifest and configure the test runner.
""")
            }
            echo "ℹ  [${svcName}] Skipping tests. No test configuration found."
            result.skipped = true
        }
    }

    return result
}

// ═════════════════════════════════════════════════════════════════════════════
// NODE.JS
// ═════════════════════════════════════════════════════════════════════════════

private Map _handleNode(String svcName) {
    def pkg = _readPackageJson(svcName)

    // ── Step 1: Does a test script exist? ────────────────────────────────────
    def testScript = pkg?.scripts?.test as String
    def hasTestScript = testScript &&
                        !testScript.isEmpty() &&
                        testScript != 'echo "Error: no test specified" && exit 1' &&
                        !testScript.startsWith('echo') &&
                        testScript != 'exit 0'

    // ── Step 2: Do test files exist? ─────────────────────────────────────────
    def hasTestFiles = _nodeHasTestFiles()

    // ── Case 4: test files but no test script ────────────────────────────────
    if (hasTestFiles && !hasTestScript) {
        error _fmt(svcName, """
Test files were detected but no runnable test script is configured.
The repository is in an inconsistent state.

Found test files (*.test.js / *.spec.js / __tests__/) but package.json has no 'scripts.test'.

Fix: Add a test script to package.json, for example:
  "scripts": {
    "test": "jest"
  }
""")
    }

    // ── Case 1: No test configuration ────────────────────────────────────────
    if (!hasTestScript) {
        echo "ℹ  [${svcName}] Skipping tests. No test configuration found (no 'scripts.test' in package.json)."
        return [skipped: true]
    }

    // ── Step 3: Detect the test runner ───────────────────────────────────────
    def runner = _detectNodeRunner(svcName, testScript, pkg)

    // ── Case 3: Framework missing ─────────────────────────────────────────────
    _assertNodeRunnerInstalled(svcName, runner)

    // ── Case 5: Coverage tooling check ───────────────────────────────────────
    _assertNodeCoverageTooling(svcName, runner, pkg)

    // ── Case 2: Run the tests ─────────────────────────────────────────────────
    _runNode(svcName, runner, pkg)
    return [skipped: false]
}

/**
 * Detect which test runner the project uses.
 * Returns a Map: [name: 'jest'|'vitest'|'mocha'|'unknown', bin: String]
 */
private Map _detectNodeRunner(String svcName, String testScript, Map pkg) {
    def knownRunners = [
        [name: 'jest',   bin: 'node_modules/.bin/jest'],
        [name: 'vitest', bin: 'node_modules/.bin/vitest'],
        [name: 'mocha',  bin: 'node_modules/.bin/mocha'],
        [name: 'tap',    bin: 'node_modules/.bin/tap'],
        [name: 'ava',    bin: 'node_modules/.bin/ava'],
    ]

    // 1. Match by looking at the test script
    for (def runner : knownRunners) {
        if (testScript.contains(runner.name)) return runner
    }

    // 2. Match by devDependencies / dependencies in package.json
    def allDeps = [:]
    allDeps += (pkg?.devDependencies ?: [:])
    allDeps += (pkg?.dependencies    ?: [:])

    for (def runner : knownRunners) {
        if (allDeps.containsKey(runner.name)) return runner
    }

    // 3. Fallback: unknown runner — still try to run via npm test
    echo "⚠  [${svcName}] Could not detect specific test runner from package.json. Will run 'npm test'."
    return [name: 'unknown', bin: '']
}

/**
 * Verify the detected runner binary is installed in node_modules/.bin.
 * Case 3: fail with actionable error if missing.
 */
private void _assertNodeRunnerInstalled(String svcName, Map runner) {
    if (!runner.bin) return   // unknown runner — npm test handles it

    def binExists = sh(
        script: "test -f '${runner.bin}' && echo yes || echo no",
        returnStdout: true,
        label: "[${svcName}] Check ${runner.name} binary"
    ).trim()

    if (binExists != 'yes') {
        error _fmt(svcName, """
Missing test dependency: ${runner.name}

The 'scripts.test' in package.json references '${runner.name}' but it is not installed.
This likely means 'npm install' was not run or it was excluded from dependencies.

Suggested fix:
  npm install --save-dev ${runner.name}

Then commit the updated package.json and package-lock.json.
""")
    }
}

/**
 * Verify coverage tooling if the test script enables coverage.
 * Case 5: fail if coverage is requested but the reporter is missing.
 */
private void _assertNodeCoverageTooling(String svcName, Map runner, Map pkg) {
    def testScript = pkg?.scripts?.test as String ?: ''
    def needsCoverage = testScript.contains('--coverage') || testScript.contains('coverage')

    if (!needsCoverage) return

    // Jest has built-in coverage; Vitest has built-in coverage (needs @vitest/coverage-v8 or -istanbul)
    if (runner.name == 'vitest') {
        def hasV8       = _nodeModuleExists('node_modules/@vitest/coverage-v8')
        def hasIstanbul  = _nodeModuleExists('node_modules/@vitest/coverage-istanbul')
        if (!hasV8 && !hasIstanbul) {
            error _fmt(svcName, """
Coverage is enabled in 'scripts.test' but the Vitest coverage provider is not installed.

Suggested fix (choose one):
  npm install --save-dev @vitest/coverage-v8       (recommended)
  npm install --save-dev @vitest/coverage-istanbul

Then update vitest.config.ts:
  coverage: { provider: 'v8' }
""")
        }
    }
}

/**
 * Actually run Node.js tests.
 * Produces JUnit XML and coverage reports.
 * Fails the build on test failure (no || true).
 */
private void _runNode(String svcName, Map runner, Map pkg) {
    echo "🧪 [${svcName}] Running ${runner.name != 'unknown' ? runner.name : 'npm test'}..."

    def testScript = pkg?.scripts?.test as String ?: ''

    // Build runner-specific flags
    switch (runner.name) {
        case 'jest':
            _runJest(svcName)
            break
        case 'vitest':
            _runVitest(svcName)
            break
        case 'mocha':
            _runMocha(svcName)
            break
        default:
            // Unknown runner — run as-is through npm and let it fail naturally
            sh(label: "[${svcName}] npm test", script: "npm test 2>&1")
            break
    }

    // Archive coverage
    _archiveNodeCoverage(svcName)
}

private void _runJest(String svcName) {
    // JEST_JUNIT_* env vars configure jest-junit reporter (if installed)
    sh(label: "[${svcName}] jest", script: """
        export JEST_JUNIT_OUTPUT_DIR="."
        export JEST_JUNIT_OUTPUT_NAME="junit.xml"
        export JEST_JUNIT_CLASSNAME="{classname}"
        export JEST_JUNIT_TITLE="{title}"

        node_modules/.bin/jest \\
            --ci \\
            --forceExit \\
            --passWithNoTests \\
            --reporters=default \\
            \$(node -e "try{require('./node_modules/jest-junit');process.stdout.write('--reporters=jest-junit')}catch(e){}" 2>/dev/null) \\
            \$(npm run test -- --listTests 2>/dev/null | head -1 | grep -q . && echo '--coverage --coverageReporters=lcov --coverageReporters=text-summary' || echo '') \\
            2>&1
    """)
}

private void _runVitest(String svcName) {
    sh(label: "[${svcName}] vitest", script: """
        node_modules/.bin/vitest run \\
            --reporter=verbose \\
            --reporter=junit \\
            --outputFile.junit=junit.xml \\
            2>&1
    """)
}

private void _runMocha(String svcName) {
    // mocha-junit-reporter produces JUnit XML if installed
    def hasJunitReporter = _nodeModuleExists('node_modules/mocha-junit-reporter')
    def reporterFlag = hasJunitReporter
        ? "--reporter mocha-junit-reporter --reporter-option mochaFile=junit.xml"
        : "--reporter spec"

    sh(label: "[${svcName}] mocha", script: """
        node_modules/.bin/mocha \\
            ${reporterFlag} \\
            2>&1
    """)
}

private void _archiveNodeCoverage(String svcName) {
    if (fileExists('coverage/lcov.info')) {
        echo "📊 [${svcName}] Archiving coverage report (lcov)"
        archiveArtifacts artifacts: 'coverage/**', allowEmptyArchive: true
    } else if (fileExists('coverage/coverage-summary.json')) {
        archiveArtifacts artifacts: 'coverage/**', allowEmptyArchive: true
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// PYTHON
// ═════════════════════════════════════════════════════════════════════════════

private Map _handlePython(String svcName) {

    // ── Step 1: Do test files exist? ─────────────────────────────────────────
    def hasTestFiles = _pythonHasTestFiles()

    // ── Case 4: manifest present, no test files, no pytest config ────────────
    def hasPytestConfig = fileExists('pytest.ini') ||
                          fileExists('setup.cfg')   ||
                          fileExists('pyproject.toml') ||
                          fileExists('conftest.py')

    if (!hasTestFiles && !hasPytestConfig) {
        echo "ℹ  [${svcName}] Skipping tests. No test configuration found (no test_*.py files or pytest config)."
        return [skipped: true]
    }

    // Case 4: pytest config present but no test files (inconsistent)
    if (hasPytestConfig && !hasTestFiles) {
        error _fmt(svcName, """
pytest configuration was found (pytest.ini / pyproject.toml / conftest.py)
but no test files matching 'test_*.py' or '*_test.py' were found.

The repository is in an inconsistent state.

Fix: Either remove the pytest configuration or add test files.
""")
    }

    // ── Case 3: pytest not installed ─────────────────────────────────────────
    def pytestExists = sh(
        script: "command -v pytest >/dev/null 2>&1 && echo yes || echo no",
        returnStdout: true,
        label: "[${svcName}] Check pytest"
    ).trim()

    if (pytestExists != 'yes') {
        // Check if it's in requirements files but just not installed
        def inRequirements = _inRequirementsFile('pytest')
        if (inRequirements) {
            error _fmt(svcName, """
pytest is listed in requirements but is not installed.
This means the dependency installation step did not complete correctly.

Suggested fix:
  1. Verify the Install Dependencies stage runs before this stage.
  2. Run locally: pip3 install -r requirements.txt
""")
        } else {
            error _fmt(svcName, """
Missing test dependency: pytest

Test files were found but pytest is not installed and not listed in requirements.

Suggested fix:
  1. Add pytest to your requirements file:
       echo "pytest>=7.0" >> requirements.txt
  2. If you also want coverage:
       echo "pytest-cov>=4.0" >> requirements.txt
  3. Commit the updated requirements.txt.
""")
        }
    }

    // ── Case 5: Coverage tooling check ───────────────────────────────────────
    _assertPythonCoverageTooling(svcName)

    // ── Case 2: Run tests ─────────────────────────────────────────────────────
    _runPython(svcName)
    return [skipped: false]
}

/**
 * Verify pytest-cov is available if coverage is expected.
 * We define "coverage expected" as: pytest-cov is in requirements
 * OR a [tool.pytest.ini_options] coverage config exists in pyproject.toml.
 */
private void _assertPythonCoverageTooling(String svcName) {
    def wantsCoverage = _inRequirementsFile('pytest-cov')
    if (!wantsCoverage) return  // coverage not requested — skip check

    def covAvailable = sh(
        script: "python3 -c 'import pytest_cov' 2>/dev/null && echo yes || echo no",
        returnStdout: true,
        label: "[${svcName}] Check pytest-cov"
    ).trim()

    if (covAvailable != 'yes') {
        error _fmt(svcName, """
pytest-cov is listed in requirements but is not installed.
Coverage reporting is configured but cannot run.

Suggested fix:
  pip3 install pytest-cov
  (Or ensure the Install Dependencies stage is completing successfully.)
""")
    }
}

/**
 * Run pytest. Fails on non-zero exit (real test failures block the build).
 */
private void _runPython(String svcName) {
    echo "🧪 [${svcName}] Running pytest..."

    // Determine coverage flags based on availability
    def hasCov = sh(
        script: "python3 -c 'import pytest_cov' 2>/dev/null && echo yes || echo no",
        returnStdout: true,
        label: "[${svcName}] pytest-cov available?"
    ).trim()

    def covFlags = (hasCov == 'yes')
        ? "--cov=. --cov-report=xml:coverage.xml --cov-report=term-missing"
        : ""

    // Run pytest — no || true, no --exit-zero: failures must propagate
    sh(label: "[${svcName}] pytest", script: """
        pytest \\
            --junitxml=test-results.xml \\
            ${covFlags} \\
            -v \\
            2>&1
    """)

    // Archive coverage only if it was produced
    if (fileExists('coverage.xml')) {
        echo "📊 [${svcName}] Archiving coverage report"
        archiveArtifacts artifacts: 'coverage.xml', allowEmptyArchive: true
    }
}

// ═════════════════════════════════════════════════════════════════════════════
// CUSTOM COMMAND
// ═════════════════════════════════════════════════════════════════════════════

private void _runCustom(String svcName, String command) {
    // No wrapper: the command must exit non-zero on failure itself
    sh(label: "[${svcName}] Custom test command", script: command)
}

// ═════════════════════════════════════════════════════════════════════════════
// DETECTION HELPERS
// ═════════════════════════════════════════════════════════════════════════════

/** Return true if any Python project manifest exists in the current directory. */
private boolean _isPythonProject() {
    return fileExists('requirements.txt') ||
           fileExists('requirements-dev.txt') ||
           fileExists('setup.py') ||
           fileExists('setup.cfg') ||
           fileExists('pyproject.toml')
}

/**
 * Return true if *.test.js, *.spec.js, or __tests__/ exist.
 * Uses shell find because Jenkins fileExists() doesn't support globs.
 */
private boolean _nodeHasTestFiles() {
    def count = sh(
        script: """
            find . -maxdepth 5 \
                \\( -name '*.test.js'  -o -name '*.test.ts'  \
                -o -name '*.spec.js'  -o -name '*.spec.ts'  \
                -o -type d -name '__tests__' \
                -o -type d -name 'test' \
                -o -type d -name 'tests' \\) \
                -not -path '*/node_modules/*' \
                -not -path '*/.git/*' \
                | head -1 | wc -l
        """,
        returnStdout: true,
        label: 'Detect Node test files'
    ).trim()
    return count.toInteger() > 0
}

/**
 * Return true if any Python test files exist (test_*.py or *_test.py).
 */
private boolean _pythonHasTestFiles() {
    def count = sh(
        script: """
            find . -maxdepth 6 \
                \\( -name 'test_*.py' -o -name '*_test.py' \\) \
                -not -path '*/.git/*' \
                -not -path '*/__pycache__/*' \
                | head -1 | wc -l
        """,
        returnStdout: true,
        label: 'Detect Python test files'
    ).trim()
    return count.toInteger() > 0
}

/**
 * Check for test files without any project manifest.
 * Used to detect Case 4 when there is no package.json or requirements.txt.
 */
private boolean _hasOrphanedTestFiles(String svcName) {
    def count = sh(
        script: """
            find . -maxdepth 6 \
                \\( -name '*.test.js' -o -name '*.spec.js' \
                -o -name '*.test.ts' -o -name '*.spec.ts' \
                -o -name 'test_*.py' -o -name '*_test.py' \
                -o -type d -name '__tests__' \\) \
                -not -path '*/node_modules/*' \
                -not -path '*/.git/*' \
                | head -1 | wc -l
        """,
        returnStdout: true,
        label: "[${svcName}] Detect orphaned test files"
    ).trim()
    return count.toInteger() > 0
}

/** Read and parse package.json as a Map. Returns null if parse fails. */
private Map _readPackageJson(String svcName) {
    try {
        return readJSON file: 'package.json'
    } catch (e) {
        error _fmt(svcName, "Failed to parse package.json: ${e.message}\nFix: ensure package.json is valid JSON.")
    }
}

/** Return true if the string appears in any requirements*.txt file. */
private boolean _inRequirementsFile(String pkgName) {
    def found = sh(
        script: """
            for f in requirements*.txt; do
                [ -f "\$f" ] && grep -qi "^${pkgName}" "\$f" && echo yes && exit 0
            done
            echo no
        """,
        returnStdout: true,
        label: "Check ${pkgName} in requirements"
    ).trim()
    return found == 'yes'
}

/** Return true if a node_modules directory/package exists. */
private boolean _nodeModuleExists(String path) {
    def exists = sh(
        script: "test -e '${path}' && echo yes || echo no",
        returnStdout: true,
        label: "Check ${path}"
    ).trim()
    return exists == 'yes'
}

// ═════════════════════════════════════════════════════════════════════════════
// FORMATTING
// ═════════════════════════════════════════════════════════════════════════════

/**
 * Format a structured error message that is easy to read in Jenkins console.
 * Never swallows the message — always propagated via error().
 */
private String _fmt(String svcName, String body) {
    def sep = '─' * 60
    return """
${sep}
❌  TEST RUNNER ERROR — Service: ${svcName}
${sep}
${body.stripIndent().trim()}
${sep}
""".stripIndent()
}

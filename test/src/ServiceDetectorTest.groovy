package com.nitte.merch

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import static org.assertj.core.api.Assertions.*

/**
 * Unit tests for ServiceDetector.
 *
 * Uses a mock `steps` object to intercept sh() calls instead of needing
 * a full Jenkins context.
 */
class ServiceDetectorTest {

    private static final Map SERVICES = [
        'frontend'      : 'services/frontend',
        'node-backend'  : 'services/node-backend',
        'python-service': 'services/python-service',
    ]

    // ── Mock steps ────────────────────────────────────────────────────────────

    /** Simple mock that records sh() calls and returns preconfigured output. */
    static class MockSteps {
        List<String> echoLog    = []
        List<String> shCommands = []
        Map<String, String> shOutputs = [:]   // script → stdout
        Map<String, Integer> shStatuses = [:] // script → exit code

        def echo(String msg) { echoLog << msg }

        def sh(Map args) {
            def script = args.script ?: ''
            shCommands << script
            if (args.returnStdout) return shOutputs.find { k, _ -> script.contains(k) }?.value ?: ''
            if (args.returnStatus) return shStatuses.find { k, _ -> script.contains(k) }?.value ?: 0
            return null
        }

        def sh(String script) { shCommands << script }
    }

    // ── Tests ─────────────────────────────────────────────────────────────────

    MockSteps steps

    @BeforeEach
    void setup() { steps = new MockSteps() }

    @Nested
    class MainBranchBuilds {

        @Test
        void 'returns changed services when diff is non-empty'() {
            steps.shOutputs['git diff'] = 'services/frontend/src/App.tsx\nservices/node-backend/index.js'
            steps.shStatuses['rev-parse HEAD~1'] = 0

            def detector = new ServiceDetector(SERVICES, steps)
            def result   = detector.detect('main', null, null)

            assertThat(result).containsExactlyInAnyOrder('frontend', 'node-backend')
        }

        @Test
        void 'returns all services when diff touches a global trigger file'() {
            steps.shOutputs['git diff'] = 'Jenkinsfile'
            steps.shStatuses['rev-parse HEAD~1'] = 0

            def detector = new ServiceDetector(SERVICES, steps)
            def result   = detector.detect('main', null, null)

            assertThat(result).containsExactlyInAnyOrder('frontend', 'node-backend', 'python-service')
        }

        @Test
        void 'returns empty list when no service dirs changed'() {
            steps.shOutputs['git diff'] = 'README.md\ndocs/architecture.md'
            steps.shStatuses['rev-parse HEAD~1'] = 0

            def detector = new ServiceDetector(SERVICES, steps)
            def result   = detector.detect('main', null, null)

            assertThat(result).isEmpty()
        }

        @Test
        void 'falls back to all services when HEAD~1 does not exist (first commit)'() {
            steps.shOutputs['git diff'] = ''
            steps.shStatuses['rev-parse HEAD~1'] = 1  // non-zero = no previous commit

            def detector = new ServiceDetector(SERVICES, steps)
            def result   = detector.detect('main', null, null)

            // No base ref → safe default: all services
            assertThat(result).containsExactlyInAnyOrder('frontend', 'node-backend', 'python-service')
        }
    }

    @Nested
    class PullRequestBuilds {

        @Test
        void 'uses changeTarget as base ref for PR builds'() {
            steps.shOutputs['git diff'] = 'services/python-service/main.py'

            def detector = new ServiceDetector(SERVICES, steps)
            def result   = detector.detect('feature/my-feature', '42', 'main')

            assertThat(result).containsExactly('python-service')
            // Verify it fetched the target branch
            assertThat(steps.shCommands.join('\n')).contains('fetch origin main')
        }

        @Test
        void 'handles multiple changed services in a PR'() {
            steps.shOutputs['git diff'] = [
                'services/frontend/src/index.tsx',
                'services/node-backend/routes/api.js',
                'services/unknown-service/foo.ts',  // not in SERVICES map → ignored
            ].join('\n')

            def detector = new ServiceDetector(SERVICES, steps)
            def result   = detector.detect('feature/foo', '10', 'main')

            assertThat(result).containsExactlyInAnyOrder('frontend', 'node-backend')
            assertThat(result).doesNotContain('unknown-service')
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void 'handles empty SERVICES map gracefully'() {
            steps.shStatuses['rev-parse HEAD~1'] = 0
            steps.shOutputs['git diff'] = ''

            def detector = new ServiceDetector([:], steps)
            def result   = detector.detect('main', null, null)

            assertThat(result).isEmpty()
        }

        @Test
        void 'returns sorted list for deterministic output'() {
            steps.shOutputs['git diff'] = 'services/python-service/x.py\nservices/frontend/x.ts\nservices/node-backend/x.js'
            steps.shStatuses['rev-parse HEAD~1'] = 0

            def detector = new ServiceDetector(SERVICES, steps)
            def result   = detector.detect('main', null, null)

            assertThat(result).isSortedAccordingTo(Comparator.naturalOrder())
        }
    }
}

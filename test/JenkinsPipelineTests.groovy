import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Nested
import static org.assertj.core.api.Assertions.*

/**
 * JenkinsPipelineUnit tests for vars/ scripts.
 *
 * These tests mock the Jenkins DSL environment and verify the
 * behaviour of individual vars/ scripts without running real Jenkins.
 *
 * For full setup docs: https://github.com/jenkinsci/JenkinsPipelineUnit
 */
class JenkinsPipelineTests extends BasePipelineTest {

    @BeforeEach
    @Override
    void setUp() throws Exception {
        // Point PipelineUnit at the vars/ and src/ directories
        scriptRoots = ['vars', 'src'] as String[]
        super.setUp()

        // Register mock helper methods that scripts depend on
        helper.registerAllowedMethod('echo',            [String.class], { s -> println "[echo] ${s}" })
        helper.registerAllowedMethod('sh',              [Map.class],    { return '' })
        helper.registerAllowedMethod('sh',              [String.class], { return '' })
        helper.registerAllowedMethod('error',           [String.class], { msg -> throw new RuntimeException(msg) })
        helper.registerAllowedMethod('sleep',           [Integer.class],{ /* no-op */ })
        helper.registerAllowedMethod('fileExists',      [String.class], { false })
        helper.registerAllowedMethod('readJSON',        [Map.class],    { [:] })
        helper.registerAllowedMethod('archiveArtifacts',[Map.class],    { /* no-op */ })
        helper.registerAllowedMethod('junit',           [Map.class],    { /* no-op */ })
        helper.registerAllowedMethod('mail',            [Map.class],    { /* no-op */ })
        helper.registerAllowedMethod('withCredentials', [List.class, Closure.class], { List creds, Closure c -> c() })
        helper.registerAllowedMethod('string',          [Map.class],    { it })
        helper.registerAllowedMethod('usernamePassword',[Map.class],    { it })
        helper.registerAllowedMethod('writeFile',       [Map.class],    { /* no-op */ })
        helper.registerAllowedMethod('dir',             [String.class, Closure.class], { String d, Closure c -> c() })
        helper.registerAllowedMethod('timeout',         [Map.class, Closure.class],   { Map m, Closure c -> c() })
        helper.registerAllowedMethod('withEnv',         [List.class, Closure.class],  { List e, Closure c -> c() })
        helper.registerAllowedMethod('withSonarQubeEnv',[String.class, Closure.class],{ String s, Closure c -> c() })
        helper.registerAllowedMethod('waitForQualityGate',[Map.class], { [status: 'OK'] })
        helper.registerAllowedMethod('container',       [String.class, Closure.class],{ String n, Closure c -> c() })

        // Set up environment
        binding.setVariable('env', [
            BUILD_URL       : 'http://jenkins.test/job/test/1/',
            BUILD_NUMBER    : '1',
            BRANCH_NAME     : 'main',
            IMAGE_TAG       : 'v1.0.0',
            NEXUS_REGISTRY  : '192.168.56.10:30082',
            NEXUS_REPO      : 'merch-docker',
            CONFIG_REPO_URL : 'https://github.com/test/config.git',
            WORKSPACE       : '/workspace',
        ])
    }

    // ── notify.groovy ─────────────────────────────────────────────────────────

    @Nested
    class NotifyTests {

        @Test
        void 'success() prints banner without throwing'() {
            def script = loadScript('notify.groovy')
            assertThatCode {
                script.success(services: ['frontend'], version: 'v1.0.0', environment: 'DEV')
            }.doesNotThrowAnyException()
        }

        @Test
        void 'failure() prints banner without throwing'() {
            def script = loadScript('notify.groovy')
            assertThatCode {
                script.failure(services: ['frontend'], version: 'v1.0.0', stage: 'Build')
            }.doesNotThrowAnyException()
        }

        @Test
        void 'warning() prints message without throwing'() {
            def script = loadScript('notify.groovy')
            assertThatCode {
                script.warning('This is a test warning')
            }.doesNotThrowAnyException()
        }

        @Test
        void 'success() works with empty services list'() {
            def script = loadScript('notify.groovy')
            assertThatCode {
                script.success(services: [], version: 'v1.0.0', environment: 'PROD')
            }.doesNotThrowAnyException()
        }

        @Test
        void 'notify does not fail when email is not configured'() {
            // mail() is mocked to succeed
            def script = loadScript('notify.groovy')
            assertThatCode {
                script.success(services: ['frontend'])
            }.doesNotThrowAnyException()
        }
    }

    // ── trivyScan.groovy ──────────────────────────────────────────────────────

    @Nested
    class TrivyScanTests {

        @Test
        void 'image scan mode requires image parameter'() {
            def script = loadScript('trivyScan.groovy')
            assertThatThrownBy {
                script.call(serviceName: 'frontend')   // no image → should error
            }.hasMessageContaining("'image' is required")
        }

        @Test
        void 'fs scan mode requires fsPath parameter'() {
            def script = loadScript('trivyScan.groovy')
            assertThatThrownBy {
                script.call(mode: 'fs', serviceName: 'frontend')   // no fsPath → error
            }.hasMessageContaining("'fsPath' is required")
        }

        @Test
        void 'scan returns result map with expected keys'() {
            // Mock readJSON to return empty Results
            helper.registerAllowedMethod('readJSON', [Map.class], { [Results: []] })
            helper.registerAllowedMethod('fileExists', [String.class], { true })
            def script = loadScript('trivyScan.groovy')

            def result = script.call(
                image      : '192.168.56.10:30082/merch-docker/frontend:v1.0.0',
                serviceName: 'frontend',
            )

            assertThat(result).containsKey('critical')
            assertThat(result).containsKey('high')
            assertThat(result).containsKey('passed')
        }

        @Test
        void 'scan passes when no critical vulnerabilities found'() {
            helper.registerAllowedMethod('readJSON', [Map.class], { [Results: []] })
            helper.registerAllowedMethod('fileExists', [String.class], { true })
            def script = loadScript('trivyScan.groovy')

            def result = script.call(
                image      : '192.168.56.10:30082/merch-docker/frontend:v1.0.0',
                serviceName: 'frontend',
                failOn     : 'CRITICAL',
            )

            assertThat(result.passed).isTrue()
            assertThat(result.critical).isEqualTo(0)
        }

        @Test
        void 'scan handles missing report file gracefully'() {
            helper.registerAllowedMethod('fileExists', [String.class], { false })
            def script = loadScript('trivyScan.groovy')

            assertThatCode {
                script.call(
                    image      : 'registry/repo/svc:tag',
                    serviceName: 'frontend',
                )
            }.doesNotThrowAnyException()
        }
    }

    // ── smokeTest.groovy ──────────────────────────────────────────────────────

    @Nested
    class SmokeTestTests {

        @Test
        void 'throws when url is missing'() {
            def script = loadScript('smokeTest.groovy')
            assertThatThrownBy {
                script.call(serviceName: 'frontend', environment: 'dev')
            }.hasMessageContaining("'url' is required")
        }

        @Test
        void 'passes when curl returns 200'() {
            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                if (args.returnStdout) return '200'
                return ''
            })
            def script = loadScript('smokeTest.groovy')

            def result = script.call(
                url        : 'http://frontend.dev.nitte.edu/',
                serviceName: 'frontend',
                environment: 'dev',
            )

            assertThat(result).isTrue()
        }

        @Test
        void 'returns false and does not throw when failOnError=false and timeout reached'() {
            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                if (args.returnStdout) return '503'
                return ''
            })
            def script = loadScript('smokeTest.groovy')

            def result = script.call(
                url        : 'http://service.dev.nitte.edu/',
                serviceName: 'broken-service',
                environment: 'dev',
                timeoutSecs: 1,
                intervalSecs: 1,
                failOnError: false,
            )

            assertThat(result).isFalse()
        }
    }

    // ── runTests.groovy ───────────────────────────────────────────────────────

    @Nested
    class RunTestsTests {

        @Test
        void 'skips gracefully when no test framework found'() {
            helper.registerAllowedMethod('fileExists', [String.class], { false })
            def script = loadScript('runTests.groovy')

            def result = script.call('my-service')

            assertThat(result.skipped).isTrue()
        }

        @Test
        void 'detects package.json and runs npm test'() {
            def shCalls = []
            helper.registerAllowedMethod('fileExists', [String.class], { p -> p == 'package.json' })
            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                shCalls << (args.label ?: args.script ?: '')
                return args.returnStdout ? '' : null
            })
            def script = loadScript('runTests.groovy')

            script.call('frontend')

            assertThat(shCalls.join('\n')).containsIgnoringCase('npm test')
        }

        @Test
        void 'detects requirements.txt and runs pytest'() {
            def shCalls = []
            helper.registerAllowedMethod('fileExists', [String.class], { p -> p == 'requirements.txt' })
            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                shCalls << (args.label ?: args.script ?: '')
                return null
            })
            def script = loadScript('runTests.groovy')

            script.call('python-service')

            assertThat(shCalls.join('\n')).contains('pytest')
        }
    }

    // ── kanikoBuild.groovy ────────────────────────────────────────────────────

    @Nested
    class KanikoBuildTests {

        @Test
        void 'throws when required parameters are missing'() {
            def script = loadScript('kanikoBuild.groovy')

            assertThatThrownBy {
                script.call(serviceName: 'frontend')  // missing registry, repo, tag, contextPath
            }.isInstanceOf(RuntimeException.class)
        }

        @Test
        void 'calls kaniko executor with correct destination'() {
            def shCalls = []
            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                shCalls << (args.script ?: '')
                return args.returnStdout ? 'yes' : null
            })
            def script = loadScript('kanikoBuild.groovy')

            script.call(
                serviceName : 'frontend',
                contextPath : '/workspace/services/frontend',
                registry    : '192.168.56.10:30082',
                repo        : 'merch-docker',
                tag         : 'v1.0.0',
            )

            def allCalls = shCalls.join('\n')
            assertThat(allCalls).contains('192.168.56.10:30082/merch-docker/frontend:v1.0.0')
            assertThat(allCalls).contains('/kaniko/executor')
        }
    }

    // ── waitForArgoCD.groovy ──────────────────────────────────────────────────

    @Nested
    class WaitForArgoCDTests {

        @Test
        void 'throws when appName is missing'() {
            def script = loadScript('waitForArgoCD.groovy')
            assertThatThrownBy {
                script.call([:])
            }.hasMessageContaining('appName')
        }

        @Test
        void 'returns true when app is Healthy and Synced'() {
            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                if (args.returnStdout) {
                    if ((args.script ?: '').contains('health')) return 'Healthy'
                    if ((args.script ?: '').contains('sync'))   return 'Synced'
                }
                return ''
            })
            def script = loadScript('waitForArgoCD.groovy')

            def result = script.call(appName: 'frontend-dev', timeoutSeconds: 30)

            assertThat(result).isTrue()
        }

        @Test
        void 'throws when app is Degraded'() {
            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                if (args.returnStdout) {
                    if ((args.script ?: '').contains('health')) return 'Degraded'
                    return 'Unknown'
                }
                return ''
            })
            def script = loadScript('waitForArgoCD.groovy')

            assertThatThrownBy {
                script.call(appName: 'frontend-dev', timeoutSeconds: 30)
            }.hasMessageContaining('DEGRADED')
        }
    }
}

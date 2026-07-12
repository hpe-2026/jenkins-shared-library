import com.lesfurets.jenkins.unit.BasePipelineTest
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import static org.assertj.core.api.Assertions.*

/**
 * Unit tests for runTests.groovy capability-detection logic.
 *
 * Each nested class maps exactly to one of the 5 documented cases.
 */
class RunTestsTest extends BasePipelineTest {

    // ── Shared mock state ─────────────────────────────────────────────────────
    Map<String, Boolean> fileExistsMap = [:]
    Map<String, String>  shOutputMap   = [:]   // label/script substring → stdout
    List<String>         shCalled      = []
    List<String>         echoed        = []

    @BeforeEach
    @Override
    void setUp() throws Exception {
        scriptRoots = ['vars'] as String[]
        super.setUp()

        helper.registerAllowedMethod('echo', [String.class], { s ->
            echoed << s; null
        })

        helper.registerAllowedMethod('error', [String.class], { msg ->
            throw new RuntimeException(msg)
        })

        helper.registerAllowedMethod('dir', [String.class, Closure.class], { String d, Closure c ->
            c()
        })

        helper.registerAllowedMethod('archiveArtifacts', [Map.class], { /* no-op */ })
        helper.registerAllowedMethod('readJSON',          [Map.class], { [:] })

        helper.registerAllowedMethod('fileExists', [String.class], { String path ->
            fileExistsMap.getOrDefault(path, false)
        })

        helper.registerAllowedMethod('sh', [Map.class], { Map args ->
            def script = (args.script ?: '') as String
            def label  = (args.label  ?: '') as String
            shCalled << (label ?: script)

            if (args.returnStdout) {
                // Return configured output for matching label/script
                def match = shOutputMap.find { k, _ ->
                    script.contains(k) || label.contains(k)
                }
                return match?.value ?: ''
            }
            if (args.returnStatus) return 0
            return null
        })

        helper.registerAllowedMethod('sh', [String.class], { String script ->
            shCalled << script
        })

        binding.setVariable('env', [:])
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CASE 1 — No test configuration → skip gracefully
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Case1_NoTestConfiguration {

        @Test
        void 'Node - no package.json → skip'() {
            // No project files exist
            def script = loadScript('runTests.groovy')
            def result = script.call('my-service')

            assertThat(result.skipped).isTrue()
            assertThat(echoed.join('\n')).containsIgnoringCase('skipping tests')
        }

        @Test
        void 'Node - package.json exists but scripts.test is the npm default placeholder → skip'() {
            fileExistsMap['package.json'] = true
            helper.registerAllowedMethod('readJSON', [Map.class], {[
                scripts: [test: 'echo "Error: no test specified" && exit 1']
            ]})
            // No test files either
            shOutputMap['Detect Node test files'] = '0'

            def script = loadScript('runTests.groovy')
            def result = script.call('frontend')

            assertThat(result.skipped).isTrue()
            assertThat(echoed.join('\n')).containsIgnoringCase('skipping tests')
        }

        @Test
        void 'Node - package.json with scripts.test = echo only → skip'() {
            fileExistsMap['package.json'] = true
            helper.registerAllowedMethod('readJSON', [Map.class], {[
                scripts: [test: 'echo "No tests"']
            ]})
            shOutputMap['Detect Node test files'] = '0'

            def script = loadScript('runTests.groovy')
            def result = script.call('admin-dashboard')

            assertThat(result.skipped).isTrue()
        }

        @Test
        void 'Python - requirements.txt exists but no test files → skip'() {
            fileExistsMap['requirements.txt'] = true
            fileExistsMap['pytest.ini']       = false
            fileExistsMap['setup.cfg']        = false
            fileExistsMap['pyproject.toml']   = false
            fileExistsMap['conftest.py']      = false
            shOutputMap['Detect Python test files'] = '0'

            def script = loadScript('runTests.groovy')
            def result = script.call('python-service')

            assertThat(result.skipped).isTrue()
            assertThat(echoed.join('\n')).containsIgnoringCase('skipping tests')
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CASE 2 — Tests configured and pass
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Case2_TestsRunAndPass {

        @Test
        void 'Node Jest - tests run and produce junit.xml'() {
            fileExistsMap['package.json']              = true
            fileExistsMap['node_modules/.bin/jest']    = true
            fileExistsMap['coverage/lcov.info']        = true
            shOutputMap['Detect Node test files']      = '1'
            shOutputMap['Check jest binary']           = 'yes'
            shOutputMap['jest-junit']                  = ''   // not installed
            shOutputMap['listTests']                   = ''

            helper.registerAllowedMethod('readJSON', [Map.class], {[
                scripts: [test: 'jest'],
                devDependencies: [jest: '^29.0.0']
            ]})

            def script = loadScript('runTests.groovy')
            def result = script.call('frontend')

            assertThat(result.skipped).isFalse()
            // Verify jest was invoked
            assertThat(shCalled.join('\n')).containsIgnoringCase('jest')
        }

        @Test
        void 'Python pytest - tests run and produce JUnit XML'() {
            fileExistsMap['requirements.txt'] = true
            shOutputMap['Detect Python test files'] = '1'
            shOutputMap['Check pytest']              = 'yes'
            shOutputMap['pytest-cov available?']     = 'no'
            shOutputMap['Check pytest-cov']          = 'no'
            shOutputMap['pytest in requirements']    = 'no'

            def script = loadScript('runTests.groovy')
            def result = script.call('python-service')

            assertThat(result.skipped).isFalse()
            assertThat(shCalled.join('\n')).contains('pytest')
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CASE 3 — Test script configured but framework missing → fail with message
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Case3_MissingFramework {

        @Test
        void 'Node Jest - jest listed in devDependencies but not installed → fail'() {
            fileExistsMap['package.json']           = true
            fileExistsMap['node_modules/.bin/jest'] = false   // NOT installed
            shOutputMap['Detect Node test files']   = '1'
            shOutputMap['Check jest binary']        = 'no'

            helper.registerAllowedMethod('readJSON', [Map.class], {[
                scripts: [test: 'jest'],
                devDependencies: [jest: '^29.0.0']
            ]})

            def script = loadScript('runTests.groovy')

            assertThatThrownBy { script.call('frontend') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('jest')
                .hasMessageContaining('npm install --save-dev jest')
        }

        @Test
        void 'Node Vitest - vitest detected but not installed → fail'() {
            fileExistsMap['package.json']              = true
            fileExistsMap['node_modules/.bin/vitest']  = false
            shOutputMap['Detect Node test files']      = '1'
            shOutputMap['Check vitest binary']         = 'no'

            helper.registerAllowedMethod('readJSON', [Map.class], {[
                scripts: [test: 'vitest run'],
                devDependencies: [vitest: '^1.0.0']
            ]})

            def script = loadScript('runTests.groovy')

            assertThatThrownBy { script.call('merchant-portal') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('vitest')
                .hasMessageContaining('npm install --save-dev vitest')
        }

        @Test
        void 'Node Mocha - mocha not installed → fail'() {
            fileExistsMap['package.json']              = true
            fileExistsMap['node_modules/.bin/mocha']   = false
            shOutputMap['Detect Node test files']      = '1'
            shOutputMap['Check mocha binary']          = 'no'

            helper.registerAllowedMethod('readJSON', [Map.class], {[
                scripts: [test: 'mocha'],
                devDependencies: [mocha: '^10.0.0']
            ]})

            def script = loadScript('runTests.groovy')

            assertThatThrownBy { script.call('node-backend') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('mocha')
        }

        @Test
        void 'Python - pytest in requirements but not installed → fail with install hint'() {
            fileExistsMap['requirements.txt'] = true
            shOutputMap['Detect Python test files'] = '1'
            shOutputMap['Check pytest']              = 'no'
            // pytest is in requirements but not installed
            shOutputMap['Check pytest in requirements'] = 'yes'

            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                def label = (args.label ?: '') as String
                def script = (args.script ?: '') as String
                if (args.returnStdout) {
                    if (label.contains('Check pytest') && !label.contains('cov')) return 'no'
                    if (script.contains('requirements') && script.contains('pytest')) return 'yes'
                    if (label.contains('Detect Python test files')) return '1'
                }
                return null
            })

            def groovyScript = loadScript('runTests.groovy')

            assertThatThrownBy { groovyScript.call('python-service') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('pytest')
                .hasMessageContaining('requirements')
        }

        @Test
        void 'Python - pytest not in requirements and not installed → fail with add-to-requirements hint'() {
            fileExistsMap['requirements.txt'] = true
            shOutputMap['Detect Python test files'] = '1'

            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                def label = (args.label ?: '') as String
                if (args.returnStdout) {
                    if (label.contains('Check pytest') && !label.contains('cov')) return 'no'
                    if (label.contains('requirements'))                            return 'no'
                    if (label.contains('Detect Python test files'))               return '1'
                }
                return null
            })

            def groovyScript = loadScript('runTests.groovy')

            assertThatThrownBy { groovyScript.call('notification-service') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('requirements.txt')
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CASE 4 — Test files exist but no runner configuration → fail
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Case4_InconsistentState {

        @Test
        void 'Node - test files found but no scripts.test in package.json → fail'() {
            fileExistsMap['package.json']         = true
            shOutputMap['Detect Node test files'] = '1'   // test files exist

            helper.registerAllowedMethod('readJSON', [Map.class], {[
                // scripts.test is absent
                name: 'my-service',
                version: '1.0.0'
            ]})

            def script = loadScript('runTests.groovy')

            assertThatThrownBy { script.call('my-service') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('inconsistent state')
                .hasMessageContaining('scripts.test')
        }

        @Test
        void 'Python - pytest config present but no test files → fail'() {
            fileExistsMap['requirements.txt'] = true
            fileExistsMap['pytest.ini']       = true
            shOutputMap['Detect Python test files'] = '0'  // no test files

            def script = loadScript('runTests.groovy')

            assertThatThrownBy { script.call('broken-service') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('inconsistent state')
        }

        @Test
        void 'No manifest but test files found → fail with orphaned files message'() {
            // No package.json, no requirements.txt
            // But there are test files present
            shOutputMap['Detect orphaned test files']  = '1'
            shOutputMap['Detect Node test files']      = '1'

            def script = loadScript('runTests.groovy')

            assertThatThrownBy { script.call('ghost-service') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('inconsistent state')
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CASE 5 — Coverage tooling missing
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class Case5_MissingCoverage {

        @Test
        void 'Vitest - coverage enabled in test script but coverage provider missing → fail'() {
            fileExistsMap['package.json']              = true
            fileExistsMap['node_modules/.bin/vitest']  = true
            shOutputMap['Detect Node test files']      = '1'
            shOutputMap['Check vitest binary']         = 'yes'
            // Neither coverage provider is installed
            shOutputMap['node_modules/@vitest/coverage-v8']       = 'no'
            shOutputMap['node_modules/@vitest/coverage-istanbul']  = 'no'

            helper.registerAllowedMethod('readJSON', [Map.class], {[
                scripts: [test: 'vitest run --coverage'],
                devDependencies: [vitest: '^1.0.0']
            ]})

            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                def label  = (args.label  ?: '') as String
                def script = (args.script ?: '') as String
                if (args.returnStdout) {
                    if (label.contains('Check vitest binary'))              return 'yes'
                    if (label.contains('Detect Node test files'))           return '1'
                    if (script.contains('coverage-v8'))                     return 'no'
                    if (script.contains('coverage-istanbul'))               return 'no'
                }
                return null
            })

            def groovyScript = loadScript('runTests.groovy')

            assertThatThrownBy { groovyScript.call('merchant-portal') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('@vitest/coverage-v8')
                .hasMessageContaining('npm install --save-dev')
        }

        @Test
        void 'Python - pytest-cov in requirements but not installed → fail'() {
            fileExistsMap['requirements.txt'] = true
            shOutputMap['Detect Python test files'] = '1'

            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                def label  = (args.label  ?: '') as String
                def script = (args.script ?: '') as String
                if (args.returnStdout) {
                    if (label.contains('Check pytest') && !label.contains('cov')) return 'yes'
                    if (label.contains('Detect Python test files'))               return '1'
                    // pytest-cov is in requirements
                    if (script.contains('pytest-cov')) return 'yes'
                    // but not importable
                    if (label.contains('pytest-cov available'))  return 'no'
                    if (script.contains('import pytest_cov'))    return 'no'
                }
                return null
            })

            def groovyScript = loadScript('runTests.groovy')

            assertThatThrownBy { groovyScript.call('python-service') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('pytest-cov')
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CUSTOM COMMAND
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class CustomCommand {

        @Test
        void 'custom testCommand bypasses all detection and runs directly'() {
            def script = loadScript('runTests.groovy')
            def result = script.call('my-service', [testCommand: 'npx vitest run'])

            assertThat(result.skipped).isFalse()
            assertThat(shCalled.join('\n')).contains('npx vitest run')
        }

        @Test
        void 'custom testCommand failure propagates (no error suppression)'() {
            helper.registerAllowedMethod('sh', [Map.class], { Map args ->
                def script = (args.script ?: '') as String
                if (script.contains('failing-command')) {
                    throw new RuntimeException("exit code 1")
                }
                return null
            })

            def groovyScript = loadScript('runTests.groovy')

            assertThatThrownBy {
                groovyScript.call('failing-service', [testCommand: 'failing-command'])
            }.isInstanceOf(RuntimeException)
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ERROR MESSAGE QUALITY
    // ─────────────────────────────────────────────────────────────────────────

    @Nested
    class ErrorMessageQuality {

        @Test
        void 'error messages include service name'() {
            fileExistsMap['package.json']           = true
            fileExistsMap['node_modules/.bin/jest'] = false
            shOutputMap['Detect Node test files']   = '1'

            helper.registerAllowedMethod('readJSON', [Map.class], {[
                scripts: [test: 'jest'],
                devDependencies: [jest: '^29.0.0']
            ]})

            def script = loadScript('runTests.groovy')

            assertThatThrownBy { script.call('notification-service') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('notification-service')
        }

        @Test
        void 'error messages include actionable install command'() {
            fileExistsMap['package.json']           = true
            fileExistsMap['node_modules/.bin/jest'] = false
            shOutputMap['Detect Node test files']   = '1'

            helper.registerAllowedMethod('readJSON', [Map.class], {[
                scripts: [test: 'jest'],
                devDependencies: [jest: '^29.0.0']
            ]})

            def script = loadScript('runTests.groovy')

            assertThatThrownBy { script.call('frontend') }
                .isInstanceOf(RuntimeException)
                .hasMessageContaining('npm install')
        }
    }
}

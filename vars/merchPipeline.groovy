#!/usr/bin/env groovy

import com.nitte.merch.Constants
import com.nitte.merch.TagUtils
import com.nitte.merch.ServiceDetector

/**
 * merchPipeline.groovy — NITTE Merch Standard CI/CD Orchestrator.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  BRANCH BEHAVIOUR
 * ─────────────────────────────────────────────────────────────────────────────
 *  PR / feature branch:
 *    checkout → detect changes → install deps → [lint + test + sonar] in parallel
 *    → NO image build, NO GitOps update, NO deployment
 *
 *  main branch:
 *    checkout → detect changes → install deps → [lint + test + sonar] in parallel
 *    → build images (Kaniko) → security scan (Trivy) → semver bump + auto-tag
 *    → update GitOps (dev) → wait ArgoCD → smoke test
 *    → manual approval → update GitOps (prod) → wait ArgoCD → smoke test
 *    → create GitHub Release
 *
 *  Git tag (vX.Y.Z):
 *    checkout → detect all → build images → security scan
 *    → manual approval → update GitOps (prod) → wait ArgoCD → smoke test
 *    → create GitHub Release
 *
 * ─────────────────────────────────────────────────────────────────────────────
 *  USAGE
 * ─────────────────────────────────────────────────────────────────────────────
 *  @Library('merch-shared-lib') _
 *
 *  def SERVICES = [
 *    'frontend'   : 'services/frontend',
 *    'node-backend': 'services/node-backend',
 *    'python-service': 'services/python-service',
 *  ]
 *
 *  merchPipeline(
 *    services      : SERVICES,
 *    nexusRegistry : '192.168.56.10:30082',
 *    nexusRepo     : 'merch-docker',
 *    configRepoUrl : 'https://github.com/hpe-2026/hpe-merch-config.git',
 *    healthEndpoints: [
 *      'frontend'    : '/',
 *      'node-backend': '/api/health',
 *    ],
 *    // Optional
 *    sonarEnabled  : true,
 *    sonarServer   : 'SonarQube',
 *    approvalUsers : 'admin,devops',
 *    githubRepo    : 'hpe-2026/merch-source-code',
 *    trivyFailOn   : 'CRITICAL',
 *    deployDomains : [dev: 'dev.nitte.edu', prod: 'nitte.edu'],
 *  )
 * ─────────────────────────────────────────────────────────────────────────────
 */
def call(Map config) {

    // ── Pipeline-scoped state (closures must read from env or these vars) ────
    def tagInfo         = [valid: false, isRelease: false, version: '']
    def servicesToBuild = []
    def isPr            = false
    def isMain          = false
    def isTagBuild      = false

    // ── Config defaults ──────────────────────────────────────────────────────
    def sonarEnabled    = config.sonarEnabled  != null  ? config.sonarEnabled  : false
    def sonarServer     = config.sonarServer            ?: 'SonarQube'
    def approvalUsers   = config.approvalUsers          ?: 'admin,devops'
    def trivyFailOn     = config.trivyFailOn            ?: 'CRITICAL'
    def githubRepo      = config.githubRepo             ?: ''
    def deployDomains   = config.deployDomains          ?: [dev: 'dev.nitte.edu', prod: 'nitte.edu']
    def healthEndpoints = config.healthEndpoints        ?: [:]

    // ── Pipeline definition ──────────────────────────────────────────────────
    pipeline {

        agent {
            kubernetes {
                defaultContainer 'devops'
                yaml libraryResource('pod-templates/build-pod.yaml')
                retries 2   // retry pod scheduling on transient failures
            }
        }

        options {
            skipDefaultCheckout()                           // we do our own checkout in setup
            timestamps()
            ansiColor('xterm')
            disableConcurrentBuilds(abortPrevious: true)    // abort stale builds on new push
            buildDiscarder(logRotator(numToKeepStr: '30'))
            timeout(time: Constants.TIMEOUT_PIPELINE_MINS, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY  = "${config.nexusRegistry}"
            NEXUS_REPO      = "${config.nexusRepo}"
            CONFIG_REPO_URL = "${config.configRepoUrl}"
        }

        // ════════════════════════════════════════════════════════════════════
        stages {

            // ── 1. SETUP & CHECKOUT ─────────────────────────────────────────
            stage('Setup & Checkout') {
                steps {
                    container('devops') {
                        script {
                            // Install tools once, for all subsequent stages
                            sh(label: 'Install CI tools', script: '''
                                apk add --no-cache \
                                    git curl jq python3 py3-pip \
                                    2>/dev/null
                                # Install yq (YAML processor for GitOps patches)
                                if ! command -v yq >/dev/null 2>&1; then
                                    YQ_VERSION=v4.44.2
                                    wget -qO /usr/local/bin/yq \
                                        "https://github.com/mikefarah/yq/releases/download/${YQ_VERSION}/yq_linux_amd64"
                                    chmod +x /usr/local/bin/yq
                                fi
                                # Install sonar-scanner if SonarQube is enabled
                                # (best-effort; sonarScan.groovy skips if not present)
                                if ! command -v sonar-scanner >/dev/null 2>&1; then
                                    wget -qO- https://binaries.sonarsource.com/Distribution/sonar-scanner-cli/sonar-scanner-cli-5.0.1.3006-linux.zip \
                                        | unzip -q -o - -d /opt 2>/dev/null || true
                                    ln -sf /opt/sonar-scanner-*/bin/sonar-scanner /usr/local/bin/sonar-scanner 2>/dev/null || true
                                fi
                            ''')

                            sh 'git config --global --add safe.directory "${WORKSPACE}"'
                            checkout scm

                            // ── Determine build context ──────────────────────
                            isPr       = env.CHANGE_ID  != null && env.CHANGE_ID  != ''
                            isTagBuild = env.TAG_NAME   != null && env.TAG_NAME   != ''
                            isMain     = env.BRANCH_NAME == Constants.MAIN_BRANCH && !isPr && !isTagBuild

                            env.IS_PR        = isPr.toString()
                            env.IS_MAIN      = isMain.toString()
                            env.IS_TAG_BUILD = isTagBuild.toString()

                            // ── Determine version ────────────────────────────
                            env.GIT_SHORT_SHA = sh(
                                script: 'git rev-parse --short HEAD',
                                returnStdout: true, label: 'git short SHA'
                            ).trim()

                            if (isTagBuild) {
                                tagInfo = TagUtils.getTagInfo(env.TAG_NAME)
                                if (!tagInfo.valid) {
                                    error "Invalid tag format: ${env.TAG_NAME}. Expected vX.Y.Z or vX.Y.Z-prerelease"
                                }
                                env.IMAGE_TAG = env.TAG_NAME
                                env.SEMVER    = tagInfo.version
                            } else if (isMain) {
                                // Semver is computed in the "Version & Tag" stage after tests pass.
                                // Placeholder tag used for the build (replaced later by the actual tag).
                                env.IMAGE_TAG = "dev-${env.BUILD_NUMBER}-${env.GIT_SHORT_SHA}"
                                env.SEMVER    = ''
                            } else {
                                // PR / feature branch — ephemeral tag, no push to registry
                                env.IMAGE_TAG = "pr-${env.CHANGE_ID ?: env.BRANCH_NAME.replaceAll('[^a-zA-Z0-9]', '-')}-${env.GIT_SHORT_SHA}"
                                env.SEMVER    = ''
                            }

                            currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.IMAGE_TAG}"
                            echo """
╔══════════════════════════════════════════════════╗
║  Branch    : ${env.BRANCH_NAME ?: 'unknown'}
║  Tag       : ${env.TAG_NAME ?: 'none'}
║  Commit    : ${env.GIT_SHORT_SHA}
║  Image tag : ${env.IMAGE_TAG}
║  PR        : ${isPr}  Main: ${isMain}  Tag: ${isTagBuild}
╚══════════════════════════════════════════════════╝"""
                        }
                    }
                }
            }

            // ── 2. DETECT CHANGED SERVICES ──────────────────────────────────
            stage('Detect Changed Services') {
                steps {
                    script {
                        container('devops') {
                            def detector = new ServiceDetector(config.services, this)
                            servicesToBuild = detector.detect(env.BRANCH_NAME, env.CHANGE_ID, env.CHANGE_TARGET)
                            env.SERVICES_TO_BUILD = servicesToBuild.join(',')

                            if (servicesToBuild.isEmpty()) {
                                currentBuild.result = 'NOT_BUILT'
                                echo "ℹ  No services changed — pipeline skipped"
                                return
                            }
                        }
                    }
                }
            }

            // ── 3. INSTALL DEPENDENCIES (parallel per service) ──────────────
            stage('Install Dependencies') {
                when { expression { servicesToBuild.size() > 0 } }
                steps {
                    script {
                        def installTasks = [:]

                        servicesToBuild.each { svcName ->
                            installTasks["install-${svcName}"] = {
                                container('devops') {
                                    dir(config.services[svcName]) {
                                        sh(label: "[${svcName}] install deps", script: """
                                            if [ -f package.json ]; then
                                                npm ci --prefer-offline --legacy-peer-deps 2>&1
                                            elif [ -f requirements.txt ]; then
                                                pip3 install --break-system-packages --quiet -r requirements.txt
                                            elif [ -f pyproject.toml ]; then
                                                pip3 install --break-system-packages --quiet .
                                            else
                                                echo "No dependency manifest found — skipping"
                                            fi
                                        """)
                                    }
                                }
                            }
                        }

                        parallel installTasks
                    }
                }
            }

            // ── 4. LINT + TEST + SONAR (parallel per service) ───────────────
            stage('Lint, Test & Analyse') {
                when { expression { servicesToBuild.size() > 0 } }
                steps {
                    script {
                        def qualityTasks = [:]

                        servicesToBuild.each { svcName ->
                            qualityTasks["quality-${svcName}"] = {
                                container('devops') {
                                    dir(config.services[svcName]) {

                                        // Lint
                                        sh(label: "[${svcName}] lint", script: """
                                            if [ -f package.json ]; then
                                                npm run lint --if-present 2>&1 || true
                                            elif command -v flake8 >/dev/null 2>&1; then
                                                flake8 . --max-line-length=120 || true
                                            fi
                                        """)

                                        // Unit tests
                                        runTests(svcName)

                                        // SonarQube (optional)
                                        if (sonarEnabled) {
                                            def projectKey = "nitte-merch-${svcName}"
                                            sonarScan(
                                                serviceName    : svcName,
                                                projectKey     : projectKey,
                                                projectName    : "NITTE Merch — ${svcName}",
                                                sonarServer    : sonarServer,
                                                branch         : env.BRANCH_NAME,
                                                prNumber       : env.CHANGE_ID,
                                                prTargetBranch : env.CHANGE_TARGET,
                                                coverageReport : 'coverage/lcov.info',
                                                enforceGate    : !isPr,  // gate enforced on main only
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        parallel qualityTasks
                    }
                }
                post {
                    always {
                        junit allowEmptyResults: true,
                              testResults: '**/junit.xml,**/test-results.xml'
                    }
                }
            }

            // ── 5. SEMVER BUMP + AUTO-TAG (main branch only) ─────────────────
            stage('Version & Tag') {
                when { expression { isMain } }
                steps {
                    container('devops') {
                        script {
                            withCredentials([usernamePassword(
                                credentialsId: Constants.CRED_GITHUB_PAT,
                                usernameVariable: 'GIT_USER',
                                passwordVariable: 'GIT_PASS'
                            )]) {
                                // Configure git remote with credentials for tag push
                                sh """
                                    git remote set-url origin "https://\${GIT_USER}:\${GIT_PASS}@${config.configRepoUrl.replace('https://', '').replace(config.configRepoUrl.split('/')[2], '')}"
                                    git remote set-url origin "\$(git config --get remote.origin.url | sed 's|https://|https://'\${GIT_USER}':\${GIT_PASS}'@|')"
                                """

                                def nextVer = semver.bump(dryRun: false)
                                env.IMAGE_TAG   = "v${nextVer}"
                                env.SEMVER      = nextVer
                                currentBuild.displayName = "#${env.BUILD_NUMBER} ${env.IMAGE_TAG}"
                                echo "🏷  Version resolved: ${env.IMAGE_TAG}"
                            }
                        }
                    }
                }
            }

            // ── 6. BUILD & PUSH IMAGES (main + tag builds) ──────────────────
            stage('Build & Push Images') {
                when {
                    anyOf {
                        expression { isMain }
                        expression { isTagBuild }
                    }
                }
                steps {
                    script {
                        // Kaniko cannot safely run in parallel (shared container state)
                        // Build sequentially but fast — Kaniko cache reduces repeat time
                        servicesToBuild.each { svcName ->
                            container('kaniko') {
                                kanikoBuild(
                                    serviceName : svcName,
                                    contextPath : "${WORKSPACE}/${config.services[svcName]}",
                                    registry    : env.NEXUS_REGISTRY,
                                    repo        : env.NEXUS_REPO,
                                    tag         : env.IMAGE_TAG,
                                    extraTags   : isMain ? ['latest'] : [],
                                )
                            }
                        }
                    }
                }
            }

            // ── 7. SECURITY SCAN (parallel per service, post-build) ──────────
            stage('Security Scan') {
                when {
                    anyOf {
                        expression { isMain }
                        expression { isTagBuild }
                    }
                }
                steps {
                    script {
                        def scanTasks = [:]

                        servicesToBuild.each { svcName ->
                            scanTasks["trivy-${svcName}"] = {
                                container('security') {
                                    def result = trivyScan(
                                        image      : "${env.NEXUS_REGISTRY}/${env.NEXUS_REPO}/${svcName}:${env.IMAGE_TAG}",
                                        serviceName: svcName,
                                        failOn     : trivyFailOn,
                                    )
                                    if (!result.passed) {
                                        unstable("Security scan failed for ${svcName}: ${result.critical} CRITICAL, ${result.high} HIGH")
                                    }
                                }
                            }
                        }

                        parallel scanTasks
                    }
                }
            }

            // ── 8. UPDATE GITOPS – DEV (main branch only) ────────────────────
            stage('Update GitOps — Dev') {
                when { expression { isMain } }
                steps {
                    container('devops') {
                        script {
                            withCredentials([usernamePassword(
                                credentialsId: Constants.CRED_GITHUB_PAT,
                                usernameVariable: 'GIT_USER',
                                passwordVariable: 'GIT_PASS'
                            )]) {
                                updateGitOps(
                                    services     : servicesToBuild,
                                    tag          : env.IMAGE_TAG,
                                    registry     : env.NEXUS_REGISTRY,
                                    repo         : env.NEXUS_REPO,
                                    environment  : 'dev',
                                    configRepoUrl: env.CONFIG_REPO_URL,
                                    gitUser      : env.GIT_USER,
                                    gitPass      : env.GIT_PASS,
                                )
                            }
                        }
                    }
                }
            }

            // ── 9. VERIFY DEV DEPLOYMENT ─────────────────────────────────────
            stage('Verify Dev Deployment') {
                when { expression { isMain } }
                steps {
                    container('devops') {
                        script {
                            def verifyTasks = [:]

                            servicesToBuild.each { svcName ->
                                verifyTasks["verify-dev-${svcName}"] = {
                                    waitForArgoCD(
                                        appName       : "${svcName}-${Constants.ARGOCD_APP_SUFFIX_DEV}",
                                        namespace     : Constants.NS_ARGOCD,
                                        timeoutSeconds: Constants.TIMEOUT_ARGOCD_DEV,
                                    )
                                    def healthPath = healthEndpoints[svcName] ?: '/health'
                                    smokeTest(
                                        url        : "http://${svcName}.${deployDomains.dev}${healthPath}",
                                        serviceName: svcName,
                                        environment: 'dev',
                                        failOnError: false,   // dev smoke failure = warning, not error
                                    )
                                }
                            }

                            parallel verifyTasks
                        }
                    }
                }
            }

            // ── 10. PRODUCTION APPROVAL (tag builds only) ────────────────────
            stage('Production Approval') {
                when {
                    allOf {
                        expression { isTagBuild }
                        expression { tagInfo.isRelease }
                    }
                }
                steps {
                    script {
                        def author  = sh(script: 'git log -1 --format="%an <%ae>"', returnStdout: true).trim()
                        def message = sh(script: 'git log -1 --format="%s"',        returnStdout: true).trim()

                        input(
                            message: """
🚀 PRODUCTION DEPLOYMENT APPROVAL
══════════════════════════════════
  Version  : ${env.IMAGE_TAG}
  Services : ${env.SERVICES_TO_BUILD}
  Author   : ${author}
  Commit   : ${message}
══════════════════════════════════
Approve to deploy to PRODUCTION?""",
                            submitter: approvalUsers,
                            submitterParameter: 'APPROVER'
                        )
                    }
                }
            }

            // ── 11. UPDATE GITOPS – PROD (tag release only) ──────────────────
            stage('Update GitOps — Production') {
                when {
                    allOf {
                        expression { isTagBuild }
                        expression { tagInfo.isRelease }
                    }
                }
                steps {
                    container('devops') {
                        script {
                            withCredentials([usernamePassword(
                                credentialsId: Constants.CRED_GITHUB_PAT,
                                usernameVariable: 'GIT_USER',
                                passwordVariable: 'GIT_PASS'
                            )]) {
                                updateGitOps(
                                    services     : servicesToBuild,
                                    tag          : env.IMAGE_TAG,
                                    registry     : env.NEXUS_REGISTRY,
                                    repo         : env.NEXUS_REPO,
                                    environment  : 'prod',
                                    configRepoUrl: env.CONFIG_REPO_URL,
                                    gitUser      : env.GIT_USER,
                                    gitPass      : env.GIT_PASS,
                                )
                            }
                        }
                    }
                }
            }

            // ── 12. VERIFY PROD DEPLOYMENT ───────────────────────────────────
            stage('Verify Production Deployment') {
                when {
                    allOf {
                        expression { isTagBuild }
                        expression { tagInfo.isRelease }
                    }
                }
                steps {
                    container('devops') {
                        script {
                            def verifyTasks = [:]

                            servicesToBuild.each { svcName ->
                                verifyTasks["verify-prod-${svcName}"] = {
                                    waitForArgoCD(
                                        appName       : "${svcName}-${Constants.ARGOCD_APP_SUFFIX_PROD}",
                                        namespace     : Constants.NS_ARGOCD,
                                        timeoutSeconds: Constants.TIMEOUT_ARGOCD_PROD,
                                    )
                                    def healthPath = healthEndpoints[svcName] ?: '/health'
                                    smokeTest(
                                        url        : "https://${svcName}.${deployDomains.prod}${healthPath}",
                                        serviceName: svcName,
                                        environment: 'prod',
                                        failOnError: true,    // prod smoke failure = hard fail
                                    )
                                }
                            }

                            parallel verifyTasks
                        }
                    }
                }
            }

            // ── 13. CREATE GITHUB RELEASE (tag release only) ─────────────────
            stage('Create GitHub Release') {
                when {
                    allOf {
                        expression { isTagBuild }
                        expression { tagInfo.isRelease }
                        expression { githubRepo != '' }
                    }
                }
                steps {
                    container('devops') {
                        script {
                            withCredentials([usernamePassword(
                                credentialsId: Constants.CRED_GITHUB_PAT,
                                usernameVariable: 'GIT_USER',
                                passwordVariable: 'GIT_PASS'
                            )]) {
                                def serviceList = servicesToBuild.collect { "- ${it}" }.join('\\n')
                                def body = "## Release ${env.IMAGE_TAG}\\n\\n### Services updated\\n${serviceList}\\n\\n_Deployed automatically by Jenkins CI_"

                                sh(label: 'GitHub Release', script: """
                                    curl -sf -X POST \\
                                        -H "Authorization: token \${GIT_PASS}" \\
                                        -H "Accept: application/vnd.github.v3+json" \\
                                        -d '{"tag_name":"${env.IMAGE_TAG}","name":"Release ${env.IMAGE_TAG}","body":"${body}","draft":false,"prerelease":false}' \\
                                        "https://api.github.com/repos/${githubRepo}/releases" \\
                                    | jq -r '.html_url // "Release created (no URL)"'
                                """)
                            }
                        }
                    }
                }
            }

        } // end stages

        // ════════════════════════════════════════════════════════════════════
        post {
            success {
                script {
                    def envName = isTagBuild && tagInfo.isRelease ? 'PRODUCTION' : (isMain ? 'DEV' : 'PR')
                    notify.success(
                        services   : servicesToBuild,
                        version    : env.IMAGE_TAG ?: 'unknown',
                        environment: envName,
                    )
                }
            }
            failure {
                script {
                    notify.failure(
                        services: servicesToBuild,
                        version : env.IMAGE_TAG ?: 'unknown',
                        stage   : env.STAGE_NAME ?: 'unknown',
                    )
                }
            }
            unstable {
                script {
                    notify.warning("Build UNSTABLE — check security scan or test results for ${env.IMAGE_TAG ?: 'unknown'}")
                }
            }
            always {
                // Collect JUnit results even on failure
                junit allowEmptyResults: true,
                      testResults: '**/junit.xml,**/test-results.xml'
                // Clean workspace to free agent disk space
                cleanWs(deleteDirs: true, disableDeferredWipeout: true)
            }
        }

    } // end pipeline
}

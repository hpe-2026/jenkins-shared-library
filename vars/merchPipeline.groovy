#!/usr/bin/env groovy

import com.nitte.merch.TagUtils
import com.nitte.merch.ServiceDetector

/**
 * NITTE Merchandise Shop - Standard Pipeline
 * Supports both branch-based (dev) and tag-based (production) flows
 */
def call(Map config) {
    
    def tagInfo = [valid: false, isRelease: false, version: '']
    def servicesToBuild = []
    
    pipeline {
        agent {
            kubernetes {
                defaultContainer 'devops'
                yaml libraryResource('pod-templates/build-pod.yaml')
            }
        }

        options {
            skipDefaultCheckout()
            timestamps()
            ansiColor('xterm')
            disableConcurrentBuilds()
            buildDiscarder(logRotator(numToKeepStr: '30'))
            timeout(time: 120, unit: 'MINUTES')
        }

        environment {
            NEXUS_REGISTRY    = "${config.nexusRegistry}"
            NEXUS_REPO        = "${config.nexusRepo}"
            CONFIG_REPO_URL   = "${config.configRepoUrl}"
            CONFIG_REPO_DIR   = 'hpe-merch-config'
        }

        stages {
            
            // ==================== STAGE 1: SETUP ====================
            stage('Setup & Checkout') {
                steps {
                    container('devops') {
                        sh '''
                            apk add --no-cache git curl jq yq
                        '''
                        sh 'git config --global --add safe.directory "${WORKSPACE}"'
                        checkout scm
                        
                        script {
                            env.GIT_SHORT_SHA = sh(script: 'git rev-parse --short HEAD', returnStdout: true).trim()
                            env.IS_TAG_BUILD = (env.TAG_NAME != null && env.TAG_NAME != '') ? 'true' : 'false'
                            env.IS_MAIN = (env.BRANCH_NAME == 'main') ? 'true' : 'false'
                            
                            // Determine version
                            if (env.IS_TAG_BUILD == 'true') {
                                tagInfo = TagUtils.getTagInfo(env.TAG_NAME)
                                env.IMAGE_TAG = env.TAG_NAME
                                env.SEMVER = tagInfo.version
                            } else {
                                env.IMAGE_TAG = "dev-${env.BUILD_NUMBER}-${env.GIT_SHORT_SHA}"
                                env.SEMVER = "0.0.0-dev.${env.BUILD_NUMBER}"
                            }
                            
                            currentBuild.displayName = "Build #${env.BUILD_NUMBER} - ${env.IMAGE_TAG}"
                        }
                    }
                }
            }
            
            // ==================== STAGE 2: DETECT CHANGES ====================
            stage('Detect Changed Services') {
                steps {
                    script {
                        def detector = new ServiceDetector(config.services, this)
                        servicesToBuild = detector.detect(env.BRANCH_NAME, env.CHANGE_ID, env.CHANGE_TARGET)
                        env.SERVICES_TO_BUILD = servicesToBuild.join(',')
                        echo "Services to build: ${env.SERVICES_TO_BUILD}"
                    }
                }
            }
            
            // ==================== STAGE 3: INSTALL ====================
            stage('Install Dependencies') {
                when { expression { env.SERVICES_TO_BUILD } }
                steps {
                    script {
                        servicesToBuild.each { svcName ->
                            dir(config.services[svcName]) {
                                container('devops') {
                                    sh '''
                                        if [ -f package.json ]; then
                                            npm ci --legacy-peer-deps
                                        elif [ -f requirements.txt ]; then
                                            python3 -m venv .venv
                                            . .venv/bin/activate
                                            pip install -r requirements.txt
                                        fi
                                    '''
                                }
                            }
                        }
                    }
                }
            }
            
            // ==================== STAGE 4: LINT ====================
            stage('Lint & Type Check') {
                when { expression { env.SERVICES_TO_BUILD } }
                steps {
                    script {
                        servicesToBuild.each { svcName ->
                            dir(config.services[svcName]) {
                                container('devops') {
                                    sh 'npm run lint --if-present || true'
                                }
                            }
                        }
                    }
                }
            }
            
            // ==================== STAGE 5: TEST ====================
            stage('Unit Tests') {
                when { expression { env.SERVICES_TO_BUILD } }
                steps {
                    script {
                        servicesToBuild.each { svcName ->
                            dir(config.services[svcName]) {
                                container('devops') {
                                    runTests(svcName)
                                }
                            }
                        }
                    }
                }
                post {
                    always {
                        junit allowEmptyResults: true, testResults: '**/test-results.xml,**/junit.xml'
                    }
                }
            }
            
            // ==================== STAGE 6: BUILD IMAGES ====================
            stage('Build & Push Images') {
                when { 
                    anyOf {
                        expression { env.IS_MAIN == 'true' }
                        expression { env.IS_TAG_BUILD == 'true' }
                    }
                }
                steps {
                    script {
                        // Build sequentially to prevent Kaniko container state conflicts
                        servicesToBuild.each { svcName ->
                            container('kaniko') {
                                kanikoBuild(
                                    serviceName: svcName,
                                    contextPath: "${WORKSPACE}/${config.services[svcName]}",
                                    registry: env.NEXUS_REGISTRY,
                                    repo: env.NEXUS_REPO,
                                    tag: env.IMAGE_TAG
                                )
                            }
                        }
                    }
                }
            }
            
            // ==================== STAGE 7: SECURITY SCAN ====================
            stage('Security Scan') {
                when { 
                    anyOf {
                        expression { env.IS_MAIN == 'true' }
                        expression { env.IS_TAG_BUILD == 'true' }
                    }
                }
                steps {
                    script {
                        servicesToBuild.each { svcName ->
                            container('security') {
                                def result = trivyScan(
                                    image: "${env.NEXUS_REGISTRY}/${env.NEXUS_REPO}/${svcName}:${env.IMAGE_TAG}",
                                    serviceName: svcName
                                )
                                
                                // Fail on critical vulnerabilities
                                if (result.critical > 0) {
                                    error "Critical vulnerabilities found in ${svcName}"
                                }
                            }
                        }
                    }
                }
            }
            
            // ==================== STAGE 8: UPDATE GITOPS (DEV) ====================
            stage('Update GitOps - Dev') {
                when { expression { env.IS_MAIN == 'true' } }
                steps {
                    script {
                        withCredentials([usernamePassword(credentialsId: 'github-pat', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
                            updateGitOps(
                                services: servicesToBuild,
                                tag: env.IMAGE_TAG,
                                registry: env.NEXUS_REGISTRY,
                                repo: env.NEXUS_REPO,
                                environment: 'dev',
                                configRepoUrl: env.CONFIG_REPO_URL,
                                configRepoDir: env.CONFIG_REPO_DIR,
                                credentials: [user: GIT_USER, pass: GIT_PASS]
                            )
                        }
                    }
                }
            }
            
            // ==================== STAGE 9: WAIT FOR DEV DEPLOYMENT ====================
            stage('Verify Dev Deployment') {
                when { expression { env.IS_MAIN == 'true' } }
                steps {
                    script {
                        servicesToBuild.each { svcName ->
                            def healthPath = config.healthEndpoints[svcName] ?: '/health'
                            waitForArgoCD(
                                appName: "${svcName}-dev",
                                namespace: 'argocd',
                                timeoutSeconds: 300
                            )
                            smokeTest(
                                url: "http://${svcName}.dev.nitte.edu${healthPath}",
                                serviceName: svcName,
                                environment: 'dev'
                            )
                        }
                    }
                }
            }
            
            // ==================== STAGE 10: PRODUCTION APPROVAL ====================
            stage('Production Approval') {
                when { 
                    expression { 
                        env.IS_TAG_BUILD == 'true' && 
                        tagInfo.isRelease == true 
                    }
                }
                steps {
                    script {
                        def message = """
                        🚀 PRODUCTION DEPLOYMENT APPROVAL
                        
                        Version: ${env.IMAGE_TAG}
                        Services: ${env.SERVICES_TO_BUILD}
                        Dev Status: ✅ Deployed & Verified
                        
                        Author: ${sh(script: 'git log -1 --format="%an"', returnStdout: true).trim()}
                        Message: ${sh(script: 'git log -1 --format="%s"', returnStdout: true).trim()}
                        
                        Approve for PRODUCTION?
                        """
                        
                        input message: message,
                              submitter: 'admin,devops',
                              submitterParameter: 'APPROVAL'
                    }
                }
            }
            
            // ==================== STAGE 11: UPDATE GITOPS (PROD) ====================
            stage('Update GitOps - Production') {
                when { 
                    expression { 
                        env.IS_TAG_BUILD == 'true' && 
                        tagInfo.isRelease == true 
                    }
                }
                steps {
                    script {
                        withCredentials([usernamePassword(credentialsId: 'github-pat', usernameVariable: 'GIT_USER', passwordVariable: 'GIT_PASS')]) {
                            updateGitOps(
                                services: servicesToBuild,
                                tag: env.IMAGE_TAG,
                                registry: env.NEXUS_REGISTRY,
                                repo: env.NEXUS_REPO,
                                environment: 'prod',
                                configRepoUrl: env.CONFIG_REPO_URL,
                                configRepoDir: env.CONFIG_REPO_DIR,
                                credentials: [user: GIT_USER, pass: GIT_PASS]
                            )
                        }
                    }
                }
            }
            
            // ==================== STAGE 12: VERIFY PROD DEPLOYMENT ====================
            stage('Verify Production Deployment') {
                when { 
                    expression { 
                        env.IS_TAG_BUILD == 'true' && 
                        tagInfo.isRelease == true 
                    }
                }
                steps {
                    script {
                        servicesToBuild.each { svcName ->
                            def healthPath = config.healthEndpoints[svcName] ?: '/health'
                            waitForArgoCD(
                                appName: "${svcName}-prod",
                                namespace: 'argocd',
                                timeoutSeconds: 600
                            )
                            smokeTest(
                                url: "https://${svcName}.nitte.edu${healthPath}",
                                serviceName: svcName,
                                environment: 'prod'
                            )
                        }
                    }
                }
            }
            
            // ==================== STAGE 13: CREATE RELEASE ====================
            stage('Create GitHub Release') {
                when { 
                    expression { 
                        env.IS_TAG_BUILD == 'true' && 
                        tagInfo.isRelease == true 
                    }
                }
                steps {
                    container('devops') {
                        script {
                            withCredentials([string(credentialsId: 'github-pat', variable: 'TOKEN')]) {
                                sh """
                                    curl -s -X POST \
                                        -H "Authorization: token ${TOKEN}" \
                                        -H "Accept: application/vnd.github.v3+json" \
                                        https://api.github.com/repos/hpe-2026/merch-source-code/releases \
                                        -d '{
                                            "tag_name": "${env.IMAGE_TAG}",
                                            "name": "Release ${env.IMAGE_TAG}",
                                            "body": "Automated release for version ${env.IMAGE_TAG}\\n\\nServices updated:\\n${servicesToBuild.collect { '- ' + it }.join('\\n')}",
                                            "draft": false,
                                            "prerelease": false
                                        }' || true
                                """
                            }
                        }
                    }
                }
            }
        }
        
        post {
            success {
                script {
                    def envName = (env.IS_TAG_BUILD == 'true' && tagInfo.isRelease) ? 'PRODUCTION' : 'DEV'
                    notify.success(
                        services: servicesToBuild,
                        version: env.IMAGE_TAG,
                        environment: envName
                    )
                }
            }
            failure {
                script {
                    notify.failure(
                        services: servicesToBuild,
                        version: env.IMAGE_TAG ?: "unknown",
                        buildUrl: env.BUILD_URL
                    )
                }
            }
            always {
                cleanWs()
            }
        }
    }
}

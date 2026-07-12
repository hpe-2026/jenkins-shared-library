#!/usr/bin/env groovy

import com.nitte.merch.Constants

/**
 * waitForArgoCD.groovy — Poll an ArgoCD application until it is Healthy + Synced.
 *
 * Strategy (in order of preference):
 *   1. argocd CLI    — most reliable; requires ArgoCD server URL + auth token credential
 *   2. kubectl       — fallback; reads ArgoCD Application CRDs directly
 *
 * Returns true on success, throws on timeout or degraded.
 *
 * Usage:
 *   waitForArgoCD(
 *     appName        : 'frontend-dev',
 *     namespace      : 'argocd',         // default: 'argocd'
 *     timeoutSeconds : 300,              // default: 300
 *     // Optional — enables argocd CLI path
 *     argocdServer   : 'https://argocd.admin.nitte.edu',
 *     argocdCredId   : 'argocd-token',   // secret-text credential in Jenkins
 *   )
 */

def call(Map params) {
    def appName    = params.appName
    def namespace  = params.namespace  ?: Constants.NS_ARGOCD
    def timeoutSec = params.timeoutSeconds ?: Constants.TIMEOUT_ARGOCD_DEV
    def server     = params.argocdServer ?: ''
    def credId     = params.argocdCredId  ?: ''

    if (!appName) error "waitForArgoCD: 'appName' is required"

    echo "⏳ [ArgoCD] Waiting for '${appName}' to be Healthy+Synced (timeout: ${timeoutSec}s)…"

    def deadline = System.currentTimeMillis() + (timeoutSec * 1000L)
    def pollInterval = 10   // seconds between polls
    def attempt = 0

    while (System.currentTimeMillis() < deadline) {
        attempt++
        def status = _getStatus(appName, namespace, server, credId)

        echo "  [${appName}] attempt=${attempt} health=${status.health} sync=${status.sync}"

        if (status.health == 'Healthy' && status.sync == 'Synced') {
            echo "✅ [ArgoCD] '${appName}' is Healthy and Synced"
            return true
        }

        if (status.health == 'Degraded') {
            error "❌ [ArgoCD] '${appName}' is DEGRADED — check ArgoCD UI for details"
        }

        // Exponential backoff up to 30s between polls
        def wait = Math.min(pollInterval * attempt, 30)
        sleep wait
    }

    error "❌ [ArgoCD] Timeout after ${timeoutSec}s waiting for '${appName}'"
}

// ── Private helpers ──────────────────────────────────────────────────────────

/**
 * Returns [health: String, sync: String]
 * Prefers argocd CLI if server URL is configured, falls back to kubectl.
 */
private Map _getStatus(String appName, String namespace, String server, String credId) {
    if (server) {
        return _getStatusViaArgocdCli(appName, server, credId)
    }
    return _getStatusViaKubectl(appName, namespace)
}

private Map _getStatusViaArgocdCli(String appName, String server, String credId) {
    try {
        def output = ''
        if (credId) {
            withCredentials([string(credentialsId: credId, variable: 'ARGOCD_AUTH_TOKEN')]) {
                output = sh(
                    script: "argocd app get '${appName}' --server '${server}' --auth-token \"\$ARGOCD_AUTH_TOKEN\" --insecure -o json 2>/dev/null || echo '{}'",
                    returnStdout: true, label: 'ArgoCD CLI status'
                ).trim()
            }
        } else {
            output = sh(
                script: "argocd app get '${appName}' --server '${server}' --insecure -o json 2>/dev/null || echo '{}'",
                returnStdout: true, label: 'ArgoCD CLI status'
            ).trim()
        }
        def json = readJSON text: (output ?: '{}')
        return [
            health: json?.status?.health?.status ?: 'Unknown',
            sync  : json?.status?.sync?.status   ?: 'Unknown'
        ]
    } catch (e) {
        echo "⚠  ArgoCD CLI failed (${e.message}), falling back to kubectl"
        return _getStatusViaKubectl(appName, 'argocd')
    }
}

private Map _getStatusViaKubectl(String appName, String namespace) {
    def health = sh(
        script: "kubectl get application '${appName}' -n '${namespace}' -o jsonpath='{.status.health.status}' 2>/dev/null || echo Unknown",
        returnStdout: true, label: 'kubectl: health'
    ).trim()

    def sync = sh(
        script: "kubectl get application '${appName}' -n '${namespace}' -o jsonpath='{.status.sync.status}' 2>/dev/null || echo Unknown",
        returnStdout: true, label: 'kubectl: sync'
    ).trim()

    return [health: health ?: 'Unknown', sync: sync ?: 'Unknown']
}

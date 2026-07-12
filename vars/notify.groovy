#!/usr/bin/env groovy

/**
 * Notification helper — console output only.
 * Slack plugin is not installed; slackSend calls are removed.
 * To re-enable Slack later, install the "Slack Notification" plugin
 * and uncomment the slackSend block below.
 */

def success(Map params) {
    def message = """
╔══════════════════════════════════════╗
║   ✅  DEPLOYMENT SUCCESSFUL          ║
╠══════════════════════════════════════╣
║  Services  : ${(params.services ?: []).join(', ')}
║  Version   : ${params.version ?: 'N/A'}
║  Env       : ${params.environment ?: 'N/A'}
║  Build URL : ${env.BUILD_URL ?: 'N/A'}
╚══════════════════════════════════════╝"""
    echo message
    _sendEmail('SUCCESS', message, params)
}

def failure(Map params) {
    def message = """
╔══════════════════════════════════════╗
║   ❌  DEPLOYMENT FAILED              ║
╠══════════════════════════════════════╣
║  Services  : ${(params.services ?: []).join(', ')}
║  Version   : ${params.version ?: 'N/A'}
║  Build URL : ${params.buildUrl ?: env.BUILD_URL ?: 'N/A'}
╚══════════════════════════════════════╝"""
    echo message
    _sendEmail('FAILURE', message, params)
}

// ── private helper ────────────────────────────────────────────────────────────
def _sendEmail(String status, String body, Map params) {
    // Email notification (requires the Mailer plugin — fails silently if absent)
    try {
        mail(
            to:      'devops@nitte.edu',
            subject: "[Jenkins] ${status} — ${params.version ?: 'unknown'} (${params.environment ?: 'N/A'})",
            body:    body
        )
    } catch (err) {
        echo "Email notification skipped: ${err.message}"
    }
}

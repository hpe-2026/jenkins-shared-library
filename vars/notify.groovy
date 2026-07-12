#!/usr/bin/env groovy

/**
 * notify.groovy — Build notification helper.
 *
 * Design principle: notifications must NEVER cause the pipeline to fail.
 * Every external call (email) is wrapped in try/catch.
 *
 * Priority order:
 *   1. Console output (always works, zero dependencies)
 *   2. Email via the Mailer plugin (if installed and configured)
 *
 * To enable email:
 *   1. Configure SMTP in Manage Jenkins → Configure System → E-mail Notification.
 *   2. Set NOTIFY_EMAIL_TO or pass emailTo: 'team@example.com'.
 */

// ── Public API ─────────────────────────────────────────────────────────────

/** Call on pipeline success */
def success(Map args = [:]) {
    def services = (args.services ?: []).join(', ') ?: 'n/a'
    def version  = args.version  ?: env.IMAGE_TAG ?: 'unknown'
    def envName  = args.environment ?: 'UNKNOWN'
    def url      = env.BUILD_URL ?: ''

    def banner = _banner('SUCCESS', [
        "Services : ${services}",
        "Version  : ${version}",
        "Env      : ${envName}",
        "URL      : ${url}",
    ])
    echo banner
    _sendEmail("✅ SUCCESS [${envName}] ${version}", banner, args)
}

/** Call on pipeline failure */
def failure(Map args = [:]) {
    def services = (args.services ?: []).join(', ') ?: 'n/a'
    def version  = args.version  ?: env.IMAGE_TAG ?: 'unknown'
    def stage    = args.stage    ?: 'unknown stage'
    def url      = args.buildUrl ?: env.BUILD_URL ?: ''

    def banner = _banner('FAILURE', [
        "Failed at : ${stage}",
        "Services  : ${services}",
        "Version   : ${version}",
        "URL       : ${url}",
    ])
    echo banner
    _sendEmail("❌ FAILURE [${stage}] ${version}", banner, args)
}

/** Call for non-blocking warnings */
def warning(String message) {
    echo "⚠  WARNING: ${message}"
}

// ── Private helpers ─────────────────────────────────────────────────────────

private String _banner(String status, List<String> lines) {
    def icon  = (status == 'SUCCESS') ? '✅' : '❌'
    def width = 52
    def sep   = '═' * width
    def body  = lines.collect { "  ${it}" }.join('\n')
    return """
╔${sep}╗
║   ${icon}  BUILD ${status}${' ' * (width - 12 - status.length())}║
╠${sep}╣
${body}
╚${sep}╝"""
}

private void _sendEmail(String subject, String body, Map args) {
    try {
        def to = args.emailTo ?: env.NOTIFY_EMAIL_TO ?: ''
        if (!to) return
        mail(to: to, subject: "[Jenkins] ${subject}", body: body)
    } catch (err) {
        echo "Email notification skipped: ${err.message}"
    }
}

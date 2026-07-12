#!/usr/bin/env groovy

/**
 * Notification helper for slack and console
 */
def success(Map params) {
    def message = """
    ✅ **Deployment Successful**
    
    📦 **Services**: ${params.services.join(', ')}
    🏷️ **Version**: ${params.version}
    🌍 **Environment**: ${params.environment}
    🔗 **Build**: ${env.BUILD_URL}
    """
    
    sendNotification(message, 'good')
}

def failure(Map params) {
    def message = """
    ❌ **Deployment Failed**
    
    📦 **Services**: ${params.services?.join(', ') ?: 'Unknown'}
    🏷️ **Version**: ${params.version}
    🔗 **Build**: ${params.buildUrl ?: env.BUILD_URL}
    """
    
    sendNotification(message, 'danger')
}

def sendNotification(String message, String color) {
    // Slack notification (if configured)
    try {
        slackSend(
            channel: '#deployments',
            color: color,
            message: message
        )
    } catch (e) {
        echo "Slack not configured: ${e.message}"
    }
    
    // Console output always works
    echo message
}

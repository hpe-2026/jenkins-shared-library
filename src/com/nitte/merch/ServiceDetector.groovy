package com.nitte.merch

class ServiceDetector implements Serializable {
    private final Map services
    private final def steps

    ServiceDetector(Map services, def steps) {
        this.services = services
        this.steps = steps
    }

    List<String> detect(String branchName, String changeId, String changeTarget) {
        def isPr = (changeId != null && changeId != '' && changeId != 'null')
        def baseRef = null
        
        if (isPr) {
            steps.sh "git fetch origin ${changeTarget} --depth=100"
            baseRef = "origin/${changeTarget}"
        } else {
            steps.sh "git fetch origin main --depth=100"
            def hasPrev = steps.sh(script: 'git rev-parse HEAD~1 >/dev/null 2>&1', returnStatus: true) == 0
            baseRef = hasPrev ? 'HEAD~1' : null
        }
        
        def changedSet = [] as Set
        if (baseRef) {
            def diffOut = steps.sh(script: "git diff --name-only ${baseRef}...HEAD 2>/dev/null || true", returnStdout: true).trim()
            diffOut.split('\n').each { filePath ->
                if (!filePath) return
                def parts = filePath.tokenize('/')
                if (parts.size() >= 2 && parts[0] == 'services') {
                    def candidate = parts[1]
                    if (services.containsKey(candidate)) {
                        changedSet << candidate
                    }
                }
            }
        }
        
        if (changedSet.isEmpty()) {
            changedSet = services.keySet() as Set
        }
        
        return changedSet.toList()
    }
}

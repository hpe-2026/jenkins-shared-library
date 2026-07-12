#!/usr/bin/env groovy

/**
 * Update GitOps manifest repository with new image tags
 * Handles both dev and prod overlays correctly
 */
def call(Map params) {
    def services = params.services
    def tag = params.tag
    def registry = params.registry
    def repo = params.repo
    def environment = params.environment
    def configRepoUrl = params.configRepoUrl
    def configRepoDir = params.configRepoDir
    def credentials = params.credentials
    
    echo "📝 Updating ${environment} manifests for: ${services.join(', ')}"
    
    def authUrl = configRepoUrl.replace("https://", "https://${credentials.user}:${credentials.pass}@")
    
    // Clone the config repo
    sh """
        rm -rf ${configRepoDir}
        git clone -b main ${authUrl} ${configRepoDir}
        cd ${configRepoDir}
        git config user.email "jenkins@nitte.edu"
        git config user.name "Jenkins Automation"
    """
    
    // Update each service's kustomization in the correct overlay
    services.each { svcName ->
        def kustomizationPath = "${configRepoDir}/downstream-clusters/overlays/${environment}/kustomization.yaml"
        
        echo "Updating image ${svcName} to tag ${tag} in ${kustomizationPath}"
        
        sh """
            cd ${configRepoDir}
            
            # Ensure .images field is initialized as an array if it doesn't exist
            if [ "\$(yq eval '.images' ${kustomizationPath})" = "null" ]; then
                yq eval -i '.images = []' ${kustomizationPath}
            fi
            
            # If the image entry already exists, update it. Otherwise append it.
            if yq eval '.images[] | select(.name == "${svcName}")' ${kustomizationPath} >/dev/null 2>&1; then
                yq eval -i '(.images[] | select(.name == "${svcName}")).newTag = "${tag}"' ${kustomizationPath}
                yq eval -i '(.images[] | select(.name == "${svcName}")).newName = "${registry}/${repo}/${svcName}"' ${kustomizationPath}
            else
                yq eval -i '.images += [{"name": "${svcName}", "newName": "${registry}/${repo}/${svcName}", "newTag": "${tag}"}]' ${kustomizationPath}
            fi
        """
    }
    
    // Commit and push
    def commitMsg = "chore(${environment}): update ${services.join(', ')} to ${tag} [skip ci]"
    
    sh """
        cd ${configRepoDir}
        git add -A
        git diff --staged --quiet || git commit -m "${commitMsg}"
        git push origin main
    """
    
    // Cleanup
    sh "rm -rf ${configRepoDir}"
    
    echo "✅ ${environment} manifests updated successfully"
}

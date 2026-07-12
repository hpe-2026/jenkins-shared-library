package com.nitte.merch

/**
 * Central registry of constants used across the Jenkins shared library.
 *
 * CREDENTIAL IDs must match what is configured in Jenkins → Manage Credentials.
 * NAMESPACES must match what is deployed in each RKE2 cluster.
 *
 * Update this file when you add a new cluster, namespace, or credential.
 */
class Constants implements Serializable {

    // ── Credential IDs (Jenkins Credentials Store) ─────────────────────────────
    // GitHub PAT with repo + read:org scope.  Used for checkout, GitOps push,
    // and GitHub Releases.
    static final String CRED_GITHUB_PAT       = 'github-pat'

    // Nexus username/password with write access to the Docker registry.
    static final String CRED_NEXUS            = 'nexus-creds'

    // SonarQube token (secret-text credential).  If absent the scan is skipped.
    static final String CRED_SONAR_TOKEN      = 'sonarqube-token'

    // ── Kubernetes Namespaces ──────────────────────────────────────────────────
    // The namespace where application workloads run in each cluster.
    static final String NS_DEV                = 'nitte-dev'
    static final String NS_PROD               = 'nitte-prod'

    // The namespace where ArgoCD is installed in every cluster.
    static final String NS_ARGOCD             = 'argocd'

    // ── ArgoCD naming convention ───────────────────────────────────────────────
    // ArgoCD Application names follow the pattern: <service>-<env>
    // e.g. frontend-dev, node-backend-prod
    static final String ARGOCD_APP_SUFFIX_DEV  = 'dev'
    static final String ARGOCD_APP_SUFFIX_PROD = 'prod'

    // ── Versioning ─────────────────────────────────────────────────────────────
    // Regex for valid semantic version Git tags: v1.2.3 or v1.2.3-rc.1
    static final String SEMVER_PATTERN        = /^v(\d+)\.(\d+)\.(\d+)(?:-(.+))?$/

    // Image tag used for dev/branch builds:  dev-<buildNumber>-<gitShortSha>
    static final String DEV_TAG_PATTERN       = 'dev-%s-%s'

    // ── Git & GitOps ───────────────────────────────────────────────────────────
    // Kustomization overlay paths inside the GitOps repo.
    // These must match the actual directory structure in your config repo.
    static final String GITOPS_OVERLAY_DEV    = 'downstream-clusters/overlays/dev/kustomization.yaml'
    static final String GITOPS_OVERLAY_PROD   = 'downstream-clusters/overlays/prod/kustomization.yaml'

    // Git identity used for automated commits.
    static final String GIT_EMAIL             = 'jenkins@nitte.edu'
    static final String GIT_NAME              = 'Jenkins CI'

    // ── Trivy ──────────────────────────────────────────────────────────────────
    // Fail the build if CRITICAL vulns are found; flag but continue for HIGH.
    static final int    TRIVY_FAIL_THRESHOLD  = 0   // max allowed CRITICAL vulns

    // ── Timeouts (seconds) ────────────────────────────────────────────────────
    static final int    TIMEOUT_ARGOCD_DEV    = 300
    static final int    TIMEOUT_ARGOCD_PROD   = 600
    static final int    TIMEOUT_SMOKE_SECS    = 180   // 3 min per service
    static final int    TIMEOUT_PIPELINE_MINS = 90

    // ── Miscellaneous ─────────────────────────────────────────────────────────
    // Directory name for cloning the GitOps repo during the update stage.
    static final String GITOPS_CLONE_DIR      = '.gitops-config'

    // Branches that trigger a full CI+build+deploy flow.
    static final String MAIN_BRANCH           = 'main'
}

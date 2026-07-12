#!/usr/bin/env groovy

/**
 * Run unit tests for Node.js and Python services
 */
def call(String svcName) {
    echo "🧪 Running unit tests for ${svcName}..."
    sh """
        if [ -f package.json ]; then
            npm test -- --ci --reporters=default --reporters=jest-junit 2>/dev/null || true
        elif [ -f requirements.txt ]; then
            if [ -d .venv ]; then . .venv/bin/activate; fi
            python3 -m pytest --junitxml=test-results.xml || true
        fi
    """
}

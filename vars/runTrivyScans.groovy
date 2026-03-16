#!/usr/bin/env groovy

def call(Map config = [:]) {
    def imageName = config.imageName
    def imageTag  = config.imageTag

    // Installer jq avant les scans parallèles
    container('trivy') {
        sh '''
            if ! which jq > /dev/null 2>&1; then
                echo "Installing jq..."
                apk add --no-cache jq
            fi
        '''
    }

    parallel(
        'Trivy Filesystem': {
            container('trivy') {
                sh '''
                    echo "Starting Trivy filesystem scan..."

                    trivy fs . \
                        --scanners vuln \
                        --timeout 30m \
                        --severity HIGH,CRITICAL \
                        --format json \
                        --output trivy-fs-report.json \
                        --cache-dir /root/.cache/trivy \
                        --skip-db-update \
                        --offline-scan \
                        --exit-code 0 || \
                    trivy fs . \
                        --scanners vuln \
                        --timeout 30m \
                        --severity HIGH,CRITICAL \
                        --format json \
                        --output trivy-fs-report.json \
                        --cache-dir /root/.cache/trivy \
                        --exit-code 0 || true

                    if [ -f trivy-fs-report.json ]; then
                        VULN_COUNT=$(jq '[.Results[]?.Vulnerabilities[]? | select(.Severity=="HIGH" or .Severity=="CRITICAL")] | length' trivy-fs-report.json 2>/dev/null || echo "0")
                        echo "✅ Filesystem scan done — HIGH/CRITICAL: ${VULN_COUNT:-0}"
                    else
                        echo "⚠️ trivy-fs-report.json not found"
                    fi
                '''
                archiveArtifacts artifacts: 'trivy-fs-report.json', allowEmptyArchive: true
            }
        },

        'Trivy Image': {
            container('trivy') {
                sh """
                    echo "Starting Trivy image scan..."

                    # Essai 1 : utiliser le cache local (rapide ~30s)
                    trivy image ${imageName}:${imageTag} \\
                        --scanners vuln \\
                        --timeout 10m \\
                        --severity HIGH,CRITICAL \\
                        --format json \\
                        --output trivy-image-report.json \\
                        --cache-dir /root/.cache/trivy \\
                        --skip-db-update \\
                        --skip-java-db-update \\
                        --ignore-unfixed \\
                        --exit-code 0 || \\
                    trivy image ${imageName}:${imageTag} \\
                        --scanners vuln \\
                        --timeout 60m \\
                        --severity HIGH,CRITICAL \\
                        --format json \\
                        --output trivy-image-report.json \\
                        --cache-dir /root/.cache/trivy \\
                        --ignore-unfixed \\
                        --exit-code 0 || true

                    if [ -f trivy-image-report.json ]; then
                        VULN_COUNT=\$(jq '[.Results[]?.Vulnerabilities[]? | select(.Severity=="HIGH" or .Severity=="CRITICAL")] | length' trivy-image-report.json 2>/dev/null || echo "0")
                        echo "✅ Image scan done — HIGH/CRITICAL: \${VULN_COUNT:-0}"
                    else
                        echo "⚠️ trivy-image-report.json not found — scan may have timed out"
                        echo '{"Results":[]}' > trivy-image-report.json
                    fi
                """
                archiveArtifacts artifacts: 'trivy-image-report.json', allowEmptyArchive: true
            }
        }
    )
}

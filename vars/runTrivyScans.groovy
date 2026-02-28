#!/usr/bin/env groovy

def call(Map config = [:]) {
    def imageName = config.imageName
    def imageTag = config.imageTag

    stage('🔍 Security: Vulnerability Scan (Trivy)') {
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
                          --cache-dir /tmp/trivy-cache \
                          --exit-code 0 || true

                        echo "Trivy filesystem scan completed"

                        VULN_COUNT=$(jq '[.Results[].Vulnerabilities[]? | select(.Severity=="HIGH" or .Severity=="CRITICAL")] | length' trivy-fs-report.json)
                        echo "Filesystem HIGH/CRITICAL vulnerabilities: $VULN_COUNT"
                        if [ "$VULN_COUNT" -gt 0 ]; then
                            echo "❌ Filesystem scan found HIGH/CRITICAL vulnerabilities!"
                            exit 1
                        fi
                    '''
                }
                archiveArtifacts artifacts: 'trivy-fs-report.json', allowEmptyArchive: true
            },

            'Trivy Image': {
                container('trivy') {
                    sh """
                        echo "Starting Trivy image scan..."
                        trivy image ${imageName}:${imageTag} \
                          --scanners vuln \
                          --timeout 30m \
                          --severity HIGH,CRITICAL \
                          --format json \
                          --output trivy-image-report.json \
                          --cache-dir /tmp/trivy-cache \
                          --exit-code 0 || true

                        echo "Trivy image scan completed"

                        VULN_COUNT=\$(jq '[.Results[].Vulnerabilities[]? | select(.Severity=="HIGH" or .Severity=="CRITICAL")] | length' trivy-image-report.json)
                        echo "Image HIGH/CRITICAL vulnerabilities: \$VULN_COUNT"
                        if [ "\$VULN_COUNT" -gt 0 ]; then
                            echo "❌ Image scan found HIGH/CRITICAL vulnerabilities!"
                            exit 1
                        fi
                    """
                }
                archiveArtifacts artifacts: 'trivy-image-report.json', allowEmptyArchive: true
            }
        )
    }
}

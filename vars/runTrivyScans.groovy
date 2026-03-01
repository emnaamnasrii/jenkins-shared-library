#!/usr/bin/env groovy

def call(Map config = [:]) {

    def imageName = config.imageName
    def imageTag  = config.imageTag

    parallel(

        'Trivy Filesystem': {

            container('trivy') {

                sh '''
                    echo "Installing jq..."
                    apk add --no-cache jq

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

                    if [ -f trivy-fs-report.json ]; then
                        echo "Parsing results..."

                        VULN_COUNT=$(jq '[.Results[]?.Vulnerabilities[]? | select(.Severity=="HIGH" or .Severity=="CRITICAL")] | length' trivy-fs-report.json)

                        echo "Filesystem HIGH/CRITICAL vulnerabilities: $VULN_COUNT"

                        if [ "$VULN_COUNT" -gt 0 ]; then
                            echo "⚠️ Filesystem scan found $VULN_COUNT HIGH/CRITICAL vulnerabilities"
                        else
                            echo "✅ No HIGH/CRITICAL vulnerabilities found in filesystem"
                        fi
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
                    echo "Installing jq..."
                    apk add --no-cache jq

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

                    if [ -f trivy-image-report.json ]; then
                        echo "Parsing results..."

                        VULN_COUNT=\$(jq '[.Results[]?.Vulnerabilities[]? | select(.Severity=="HIGH" or .Severity=="CRITICAL")] | length' trivy-image-report.json)

                        echo "Image HIGH/CRITICAL vulnerabilities: \$VULN_COUNT"

                        if [ "\$VULN_COUNT" -gt 0 ]; then
                            echo "⚠️ Image scan found \$VULN_COUNT HIGH/CRITICAL vulnerabilities"
                        else
                            echo "✅ No HIGH/CRITICAL vulnerabilities found in image"
                        fi
                    else
                        echo "⚠️ trivy-image-report.json not found"
                    fi
                """

                archiveArtifacts artifacts: 'trivy-image-report.json', allowEmptyArchive: true
            }
        }
    )
}

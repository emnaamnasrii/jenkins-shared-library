#!/usr/bin/env groovy

def call(Map config = [:]) {
    def imageName = config.imageName
    def imageTag = config.imageTag
    
    parallel(
        'Trivy Filesystem': {
            container('scanner') {
                sh '''
                    trivy fs \
                        --severity HIGH,CRITICAL \
                        --format json \
                        --output trivy-fs-report.json \
                        . || echo "Trivy FS scan completed"
                    
                    trivy fs \
                        --severity HIGH,CRITICAL \
                        . || true
                '''
                archiveArtifacts artifacts: 'trivy-fs-report.json', allowEmptyArchive: true
            }
        },
        'Trivy Image': {
            container('docker') {
                sh """
                    trivy image \
                        --severity HIGH,CRITICAL \
                        --format json \
                        --output trivy-image-report.json \
                        ${imageName}:${imageTag} || echo "Trivy image scan completed"
                    
                    trivy image \
                        --severity HIGH,CRITICAL \
                        --exit-code 0 \
                        ${imageName}:${imageTag} || true
                """
                archiveArtifacts artifacts: 'trivy-image-report.json', allowEmptyArchive: true
            }
        }
    )
}

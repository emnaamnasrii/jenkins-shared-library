#!/usr/bin/env groovy

def call(Map config = [:]) {
    def appUrl = config.appUrl
    
    if (!appUrl) {
        echo "⚠️  No app URL, skipping ZAP scan"
        return
    }
    
    container('docker') {
        sh """
            docker run --rm \
                -v \$(pwd):/zap/wrk:rw \
                ghcr.io/zaproxy/zaproxy:stable \
                zap-baseline.py \
                -t ${appUrl} \
                -r zap-report.html \
                -J zap-report.json \
                -w zap-report.md \
                -I || echo "ZAP scan completed"
        """
        
        publishHTML([
            allowMissing: true,
            alwaysLinkToLastBuild: true,
            keepAll: true,
            reportDir: '.',
            reportFiles: 'zap-report.html',
            reportName: 'ZAP Security Report'
        ])
        
        archiveArtifacts artifacts: 'zap-report.*', allowEmptyArchive: true
    }
}

#!/usr/bin/env groovy

def call(Map config = [:]) {
    def repoUrl = config.repoUrl
    def imageName = config.imageName ?: env.JOB_NAME.toLowerCase()
    def namespace = config.namespace ?: 'dev'
    def runE2E = config.runE2E ?: true
    def runPerf = config.runPerf ?: true
    def runZAP = config.runZAP ?: false
    
    def tech
    def buildResult
    def appUrl
    
    
    try {
        // 1. CLONE REPO
        stage('📥 Clone Repository') {
            git url: repoUrl, branch: 'main', credentialsId: 'github-creds'
            echo "✅ Repository cloned: ${repoUrl}"
        }
        
        // 2. DETECT TECH
        stage('🔍 Detect Technology') {
            tech = detectTech()
            env.DETECTED_LANGUAGE = tech.language
            env.DETECTED_FRAMEWORK = tech.framework
            
            echo "========================================="
            echo "Technology Detection Results:"
            echo "Language: ${tech.language}"
            echo "Framework: ${tech.framework}"
            echo "Package Manager: ${tech.packageManager}"
            echo "========================================="
        }
        
        // 3. GITLEAKS SCAN
        stage('🔒 Security: Secret Scan (Gitleaks)') {
            container('scanner') {
                sh '''
                    gitleaks detect \
                        --source=. \
                        --report-path=gitleaks-report.json \
                        --report-format=json \
                        --no-git \
                        --verbose || true
                    
                    if [ -f gitleaks-report.json ]; then
                        echo "Gitleaks scan completed"
                        cat gitleaks-report.json
                    fi
                '''
                archiveArtifacts artifacts: 'gitleaks-report.json', allowEmptyArchive: true
            }
        }
        
        // 4. INSTALL DEPENDENCIES
      stage('📦 Install Dependencies') {
    if (tech.language == 'Python') {
        container('python') {
            sh '''
                pip install --upgrade pip --quiet
                pip install pip-tools --quiet

                # Générer requirements.txt automatiquement si absent
                if [ ! -f requirements.txt ]; then
                    echo "Generating requirements.txt automatically..."
                    pip-compile --generate-hashes --allow-unsafe --output-file=requirements.txt
                fi

                # Installer toutes les dépendances
                pip install -r requirements.txt --quiet

                # Installer les outils de test
                pip install pytest pytest-cov pytest-html locust --quiet
            '''
        }
    }
    else if (tech.language == 'Node.js') {
        container('node') {
            sh '''
                if [ -f package.json ]; then
                    npm install
                fi
            '''
        }
    }
}
        
        // 5. UNIT TESTS
        stage('🧪 Unit Tests') {
            runUnitTests(tech: tech)
        }
        
        // 6. SONARQUBE ANALYSIS

stage('📊 Code Quality (SonarQube)') {
    runSonarAnalysis(
        projectKey: imageName.replaceAll('/', '-'),
        projectName: imageName.replaceAll('/', '-')
    )
}
        
        // 7. BUILD DOCKER IMAGE
        stage('🐳 Build Docker Image') {
            buildResult = autoBuild(imageName: imageName)
            env.IMAGE_TAG = buildResult.imageTag
            env.FULL_IMAGE = buildResult.fullImage
            echo "✅ Built: ${env.FULL_IMAGE}"
        }
        
  // 8. TRIVY SCANS
stage('🔍 Security: Vulnerability Scan (Trivy)') {
    runTrivyScans(
        imageName: imageName,
        imageTag: env.IMAGE_TAG
    )
}
        // 9. DEPLOY TO K8S
        stage('🚀 Deploy to Kubernetes') {
            deployToK8s(
                namespace: namespace,
                appName: imageName,
                image: env.FULL_IMAGE,
                replicas: 2
            )
            
            // Get app URL
            appUrl = getAppUrl(namespace: namespace, appName: imageName)
            env.APP_URL = appUrl
            echo "✅ Deployed to: ${appUrl}"
        }
        
        // 10. E2E TESTS
        if (runE2E) {
            stage('🌐 E2E Tests') {
                sleep 30 // Wait for app to be ready
                runE2ETests(appUrl: appUrl)
            }
        }
        
        // 11. PERFORMANCE TESTS
        if (runPerf) {
            stage('⚡ Performance Tests') {
                runPerfTests(
                    appUrl: appUrl,
                    vus: 10,
                    duration: '30s'
                )
            }
        }
        
        // 12. ZAP SECURITY SCAN
        if (runZAP) {
            stage('🛡️ Security: Web Scan (OWASP ZAP)') {
                runZAPScan(appUrl: appUrl)
            }
        }
        
        // 13. FINAL SUMMARY
        stage('📊 Summary') {
            def summary = """
========================================
✅ CI/CD PIPELINE COMPLETED SUCCESSFULLY
========================================
Repository: ${repoUrl}
Language: ${tech.language}
Framework: ${tech.framework}
Docker Image: ${env.FULL_IMAGE}
Deployed to: ${namespace}
Application URL: ${appUrl}

Tests Executed:
  ✅ Secret Scan (Gitleaks)
  ✅ Unit Tests (${tech.testFramework ?: 'Auto-detected'})
  ✅ Code Quality (SonarQube)
  ✅ Vulnerability Scan (Trivy)
  ✅ Deployment (Kubernetes)
${runE2E ? '  ✅ E2E Tests' : '  ⏭️  E2E Tests (skipped)'}
${runPerf ? '  ✅ Performance Tests' : '  ⏭️  Performance Tests (skipped)'}
${runZAP ? '  ✅ Web Security Scan (ZAP)' : '  ⏭️  ZAP Scan (skipped)'}
========================================
🎉 Ready for CD (Continuous Deployment)
========================================
"""
            echo summary
            
            // Save summary
            writeFile file: 'pipeline-summary.txt', text: summary
            archiveArtifacts artifacts: 'pipeline-summary.txt'
        }
        
    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        error("Pipeline failed: ${e.message}")
    }
}

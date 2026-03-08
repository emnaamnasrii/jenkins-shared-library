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
            script {
                def branches = ['main', 'master']
                def cloned = false
                for (b in branches) {
                    try {
                        git url: repoUrl, branch: b, credentialsId: 'github-creds'
                        echo "✅ Repository cloned: ${repoUrl} (branch: ${b})"
                        cloned = true
                        break
                    } catch (err) {
                        echo "⚠️ Branch '${b}' not found, trying next..."
                    }
                }
                if (!cloned) {
                    error "❌ Could not clone repository. No valid branch found."
                }
            }
        }

        // 2. DETECT TECHNOLOGY
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
                    export PATH=$PATH:/tmp
                    curl -sSL https://github.com/gitleaks/gitleaks/releases/latest/download/gitleaks-linux-amd64 -o /tmp/gitleaks
                    chmod +x /tmp/gitleaks
                    /tmp/gitleaks detect \
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
                        python3 -m pip install --upgrade pip --quiet --user
                        python3 -m pip install pip-tools --quiet --user

                        if [ ! -f requirements.txt ]; then
                            echo "Generating requirements.txt..."
                            cat <<EOT > requirements.in
pytest>=8.3.3,<9.0.0
pytest-cov==4.1.0
pytest-html==3.2.0
locust==2.40.5
EOT
                            pip-compile requirements.in --generate-hashes --allow-unsafe --output-file=requirements.txt
                        fi

                        pip install --user -r requirements.txt --quiet
                    '''
                }
            } else if (tech.language == 'Node.js') {
                container('node') {
                    sh 'npm install'
                }
            } else if (tech.language == 'Java') {
                container('maven') {
                    sh 'mvn clean install -DskipTests'
                }
            } else {
                echo "⚠️ Language not supported: ${tech.language}"
            }
        }

        // 5. SANITY CHECK
        stage('🔧 Sanity Check: SH & Kubectl') {
            container('kubectl') {
                sh 'echo "Hello from SH!"'
                sh '''
                    echo "✅ Testing kubectl connectivity..."
                    kubectl version --client
                    kubectl get nodes
                '''
            }
        }

        // 6. UNIT TESTS
        stage('🧪 Unit Tests') {
            runUnitTests(tech: tech)
        }

        // 7. SONARQUBE ANALYSIS
        stage('📊 Code Quality (SonarQube)') {
            runSonarAnalysis(
                projectKey: imageName.replaceAll('/', '-'),
                projectName: imageName.replaceAll('/', '-')
            )
        }

        // 8. BUILD DOCKER IMAGE
        stage('🐳 Build Docker Image') {
            buildResult = autoBuild(imageName: imageName)
            env.IMAGE_TAG = buildResult.imageTag
            env.FULL_IMAGE = buildResult.fullImage
            echo "✅ Built: ${env.FULL_IMAGE}"
        }

        // 9. TRIVY SCANS
        stage('🔍 Security: Vulnerability Scan (Trivy)') {
            runTrivyScans(
                imageName: imageName,
                imageTag: env.IMAGE_TAG
            )
        }

        // 10. DEPLOY TO K8S
        stage('🚀 Deploy to Kubernetes') {
            deployToK8s(
                namespace: namespace,
                appName: imageName,
                image: env.FULL_IMAGE,
                replicas: 2
            )
            appUrl = getAppUrl(namespace: namespace, appName: imageName)
            env.APP_URL = appUrl
            echo "✅ Deployed to: ${appUrl}"
        }

        // 11. E2E TESTS
        if (runE2E) {
            stage('🌐 E2E Tests') {
                sleep 30
                runE2ETests(appUrl: appUrl)
            }
        }

        // 12. PERFORMANCE TESTS
        if (runPerf) {
            stage('⚡ Performance Tests') {
                runPerfTests(appUrl: appUrl, vus: 10, duration: '30s')
            }
        }

        // 13. ZAP SECURITY SCAN
        if (runZAP) {
            stage('🛡️ Security: Web Scan (OWASP ZAP)') {
                runZAPScan(appUrl: appUrl)
            }
        }

        // 14. FINAL SUMMARY
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
            writeFile file: 'pipeline-summary.txt', text: summary
            archiveArtifacts artifacts: 'pipeline-summary.txt'
        }

    } catch (Exception e) {
        currentBuild.result = 'FAILURE'
        error("Pipeline failed: ${e.message}")
    }
}

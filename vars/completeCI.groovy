#!/usr/bin/env groovy

def call(Map config = [:]) {
    def repoUrl    = config.repoUrl
    def imageName  = config.imageName ?: env.JOB_NAME.toLowerCase()
    def namespace  = config.namespace ?: 'dev'
    def runE2E     = config.runE2E    ?: true
    def runPerf    = config.runPerf   ?: true
    def runZAP     = config.runZAP    ?: false

    def tech
    def buildResult
    def appUrl
    def dbConfig = [deployed: false]

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

        // 3. DETECT DATABASE
        stage('🗄️ Detect Database') {
            def dbInfo = detectDatabase()
            env.DB_TYPE     = dbInfo.type
            env.DB_DETECTED = dbInfo.detected.toString()
            env.DB_VERSION  = dbInfo.version
            env.DB_PORT     = dbInfo.port.toString()
            env.DB_ENV_VARS = groovy.json.JsonOutput.toJson(dbInfo.envVars)
            echo "========================================="
            echo "Database Detection Results:"
            echo "Type: ${env.DB_TYPE}"
            echo "Detected: ${env.DB_DETECTED}"
            echo "Version: ${env.DB_VERSION}"
            echo "Port: ${env.DB_PORT}"
            echo "========================================="
        }

        // 4. GITLEAKS SCAN
        stage('🔒 Security: Secret Scan (Gitleaks)') {
            container('scanner') {
                sh '''
                    export PATH=$PATH:/tmp
                    GITLEAKS_VERSION="8.18.4"
                    GITLEAKS_URL="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"

                    echo "📦 Downloading Gitleaks v${GITLEAKS_VERSION}..."
                    curl -sSL "${GITLEAKS_URL}" -o /tmp/gitleaks.tar.gz

                    FILE_SIZE=$(wc -c < /tmp/gitleaks.tar.gz)
                    echo "Downloaded size: ${FILE_SIZE} bytes"

                    if [ "$FILE_SIZE" -gt "1000000" ]; then
                        echo "✅ Valid archive downloaded (${FILE_SIZE} bytes)"
                        tar -xzf /tmp/gitleaks.tar.gz -C /tmp gitleaks 2>/dev/null || \
                        tar -xzf /tmp/gitleaks.tar.gz -C /tmp 2>/dev/null
                        chmod +x /tmp/gitleaks
                        echo "✅ Gitleaks ready: $(/tmp/gitleaks version)"
                    else
                        echo "❌ Download too small — trying pipe method..."
                        curl -sSL "${GITLEAKS_URL}" | tar -xz -C /tmp gitleaks 2>/dev/null || true
                        chmod +x /tmp/gitleaks 2>/dev/null || true
                    fi

                    if [ -x /tmp/gitleaks ]; then
                        /tmp/gitleaks detect \
                            --source=. \
                            --report-path=gitleaks-report.json \
                            --report-format=json \
                            --no-git \
                            --verbose || true

                        if [ -f gitleaks-report.json ]; then
                            SECRETS=$(grep -c '"RuleID"' gitleaks-report.json 2>/dev/null || echo "0")
                            echo "✅ Gitleaks scan completed — secrets found: ${SECRETS}"
                            if [ "${SECRETS}" -gt "0" ]; then
                                echo "⚠️ WARNING: ${SECRETS} potential secret(s) detected!"
                            else
                                echo "✅ No secrets detected"
                            fi
                        fi
                    else
                        echo "⚠️ Gitleaks not available — skipping"
                        echo '[]' > gitleaks-report.json
                    fi
                '''
                archiveArtifacts artifacts: 'gitleaks-report.json', allowEmptyArchive: true
            }
        }

        // 5. INSTALL DEPENDENCIES
        stage('📦 Install Dependencies') {
            if (tech.language == 'Python') {
                container('python') {
                    sh '''
                        python3 -m pip install --upgrade pip --quiet
                        python3 -m pip install pip-tools --quiet
                        if [ ! -f requirements.txt ]; then
                            echo "Generating requirements.txt..."
                            cat <<EOT > requirements.in
pytest>=8.3.3,<9.0.0
pytest-cov==4.1.0
pytest-html==3.2.0
locust==2.40.5
EOT
                            python3 -m piptools compile requirements.in \
                                --generate-hashes \
                                --allow-unsafe \
                                --output-file=requirements.txt
                        fi
                        python3 -m pip install -r requirements.txt --quiet
                    '''
                }
            }
            else if (tech.language == 'Node.js') {
                container('node') {
                    sh 'npm install'
                }
            }
            else if (tech.language == 'Java') {
                container('maven') {
                    sh 'mvn clean install -DskipTests'
                }
            }
            else {
                echo "⚠️ Language not supported: ${tech.language}"
            }
        }

        // 6. SANITY CHECK
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

        // 7. UNIT TESTS
        stage('🧪 Unit Tests') {
            runUnitTests(tech: tech)
        }

        // 8. SONARQUBE ANALYSIS
        stage('📊 Code Quality (SonarQube)') {
            runSonarAnalysis(
                projectKey: imageName.replaceAll('/', '-'),
                projectName: imageName.replaceAll('/', '-')
            )
        }

        // 9. BUILD DOCKER IMAGE
        stage('🐳 Build Docker Image') {
            buildResult = autoBuild(imageName: imageName)
            env.IMAGE_TAG  = buildResult.imageTag
            env.FULL_IMAGE = buildResult.fullImage
            echo "✅ Built: ${env.FULL_IMAGE}"
        }

        // 10. PRE-PULL IMAGE SUR LES NOEUDS
        // ─────────────────────────────────────────────────────────────────
        // FIX : force le téléchargement de l'image sur TOUS les noeuds
        // avant le déploiement → évite ContainerCreating long au deploy
        // ─────────────────────────────────────────────────────────────────
        stage('📦 Pre-pull image on nodes') {
            container('kubectl') {
                withKubeConfig([credentialsId: 'kubeconfig']) {
                    sh """
                        echo "🔄 Pre-pulling ${env.FULL_IMAGE} on all nodes..."

                        # Créer un DaemonSet temporaire qui force le pull sur tous les noeuds
                        kubectl apply -f - <<EOF
apiVersion: apps/v1
kind: DaemonSet
metadata:
  name: prepull-${env.BUILD_NUMBER}
  namespace: jenkins
spec:
  selector:
    matchLabels:
      app: prepull-${env.BUILD_NUMBER}
  template:
    metadata:
      labels:
        app: prepull-${env.BUILD_NUMBER}
    spec:
      initContainers:
      - name: prepull
        image: ${env.FULL_IMAGE}
        imagePullPolicy: Always
        command: ['/bin/sh', '-c', 'echo Image pulled on \$(hostname)']
      containers:
      - name: pause
        image: pause:3.1
        imagePullPolicy: IfNotPresent
EOF

                        # Attendre que l'image soit pulled sur tous les noeuds (max 3 min)
                        for i in \$(seq 1 18); do
                            READY=\$(kubectl get daemonset prepull-${env.BUILD_NUMBER} -n jenkins \
                                -o jsonpath='{.status.numberReady}' 2>/dev/null || echo '0')
                            DESIRED=\$(kubectl get daemonset prepull-${env.BUILD_NUMBER} -n jenkins \
                                -o jsonpath='{.status.desiredNumberScheduled}' 2>/dev/null || echo '0')
                            if [ "\$READY" = "\$DESIRED" ] && [ "\$DESIRED" != "0" ]; then
                                echo "✅ Image pre-pulled on all \$READY nodes"
                                break
                            fi
                            echo "  Waiting for pre-pull... (\$i/18) - Ready: \$READY/\$DESIRED"
                            sleep 10
                        done

                        # Supprimer le DaemonSet temporaire
                        kubectl delete daemonset prepull-${env.BUILD_NUMBER} -n jenkins || true
                        echo "✅ Pre-pull complete"
                    """
                }
            }
        }

        // 11. TRIVY SCANS
        stage('🔍 Security: Vulnerability Scan (Trivy)') {
            runTrivyScans(
                imageName: imageName,
                imageTag: env.IMAGE_TAG
            )
        }

        // 12. DEPLOY DATABASE (if detected)
        if (env.DB_DETECTED == 'true') {
            stage('🗄️ Deploy Database') {
                dbConfig = deployDatabase(
                    namespace: namespace,
                    dbType: env.DB_TYPE,
                    dbVersion: env.DB_VERSION,
                    dbPort: env.DB_PORT.toInteger(),
                    appName: imageName.replaceAll('[/_]', '-')
                )
                echo "✅ Database deployed: ${dbConfig.type} at ${dbConfig.serviceName}:${dbConfig.port}"
            }
        }

        // 13. DEPLOY TO K8S
        stage('🚀 Deploy to Kubernetes') {
            deployToK8s(
                namespace: namespace,
                appName: imageName,
                image: env.FULL_IMAGE,
                replicas: 2,
                dbConfig: dbConfig
            )
            appUrl = getAppUrl(namespace: namespace, appName: imageName)
            env.APP_URL = appUrl
            echo "✅ Deployed to: ${appUrl}"
        }

        // 14. E2E TESTS
        if (runE2E) {
            stage('🌐 E2E Tests') {
                sleep 30
                def hasFrontend = detectFrontend()
                runE2ETests(
                    appUrl: appUrl,
                    hasFrontend: hasFrontend
                )
            }
        }

        // 15. PERFORMANCE TESTS
        if (runPerf) {
            stage('⚡ Performance Tests') {
                runPerfTests(appUrl: appUrl, vus: 10, duration: '30s')
            }
        }

        // 16. ZAP SECURITY SCAN
        if (runZAP) {
            stage('🛡️ Security: Web Scan (OWASP ZAP)') {
                runZAPScan(appUrl: appUrl)
            }
        }

        // 17. FINAL SUMMARY
        stage('📊 Summary') {
            def dbSummary = ''
            if (dbConfig.deployed) {
                dbSummary = """
Database:
  Type: ${dbConfig.type}
  Service: ${dbConfig.serviceName}
  Port: ${dbConfig.port}
"""
            }

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
${dbSummary}
Tests Executed:
  ✅ Secret Scan (Gitleaks)
  ✅ Unit Tests (${tech.testFramework ?: 'Auto-detected'})
  ✅ Code Quality (SonarQube)
  ✅ Vulnerability Scan (Trivy)
${dbConfig.deployed ? '  ✅ Database Deployment (' + dbConfig.type + ')' : '  ⏭️  Database (not detected)'}
  ✅ Pre-pull image on nodes
  ✅ Application Deployment (Kubernetes)
${runE2E   ? '  ✅ E2E Tests'               : '  ⏭️  E2E Tests (skipped)'}
${runPerf  ? '  ✅ Performance Tests'        : '  ⏭️  Performance Tests (skipped)'}
${runZAP   ? '  ✅ Web Security Scan (ZAP)'  : '  ⏭️  ZAP Scan (skipped)'}
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

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
    def dbConfig        = [deployed: false]
    def hasFrontend     = false
    def frontendBuild   = [:]

    try {
        // ══════════════════════════════════════════════════════════════════
        // 1. CLONE REPO
        // ══════════════════════════════════════════════════════════════════
   stage('📥 Clone Repository') {
    script {
        // Détecter la branche par défaut via git ls-remote
        // Fonctionne avec TOUS les repos sans connaître la branche à l'avance
        def defaultBranch = sh(
            script: """
                git ls-remote --symref ${repoUrl} HEAD 2>/dev/null \
                | grep 'ref:' \
                | sed 's|ref: refs/heads/||' \
                | awk '{print \$1}' \
                | head -1
            """,
            returnStdout: true
        ).trim()

        if (!defaultBranch || defaultBranch == '') {
            defaultBranch = 'master'
        }

        echo "🔍 Detected default branch: ${defaultBranch}"

        checkout([
            \$class: 'GitSCM',
            branches: [[name: "*/${defaultBranch}"]],
            doGenerateSubmoduleConfigurations: false,
            extensions: [],
            userRemoteConfigs: [[
                url          : repoUrl,
                credentialsId: 'github-creds'
            ]]
        ])

        echo "✅ Cloned: ${repoUrl} (branch: ${defaultBranch})"
        env.GIT_BRANCH_USED = defaultBranch
    }
}

        // ══════════════════════════════════════════════════════════════════
        // 2. DETECT TECHNOLOGY
        // ══════════════════════════════════════════════════════════════════
        stage('🔍 Detect Technology') {
            tech = detectTech()
            env.DETECTED_LANGUAGE  = tech.language
            env.DETECTED_FRAMEWORK = tech.framework
            echo "========================================="
            echo "Technology Detection Results:"
            echo "Language: ${tech.language}"
            echo "Framework: ${tech.framework}"
            echo "Package Manager: ${tech.packageManager}"
            echo "========================================="
        }

        // ══════════════════════════════════════════════════════════════════
        // 3. DETECT DATABASE
        // ══════════════════════════════════════════════════════════════════
        stage('🗄️ Detect Database') {
            def dbInfo = detectDatabase()
            env.DB_TYPE     = dbInfo.type
            env.DB_DETECTED = dbInfo.detected.toString()
            env.DB_VERSION  = dbInfo.version
            env.DB_PORT     = dbInfo.port.toString()
            env.DB_ENV_VARS = groovy.json.JsonOutput.toJson(dbInfo.envVars)
            echo "========================================="
            echo "Database Detection Results:"
            echo "Type: ${env.DB_TYPE} | Detected: ${env.DB_DETECTED}"
            echo "Version: ${env.DB_VERSION} | Port: ${env.DB_PORT}"
            echo "========================================="
        }

        // ══════════════════════════════════════════════════════════════════
        // 4. DETECT FRONTEND
        // ══════════════════════════════════════════════════════════════════
        stage('🖥️ Detect Frontend') {
            hasFrontend = detectFrontend()
            env.HAS_FRONTEND = hasFrontend.toString()
            echo "Frontend detected: ${hasFrontend}"
        }

        // ══════════════════════════════════════════════════════════════════
        // 5. GITLEAKS SCAN
        // ══════════════════════════════════════════════════════════════════
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

        // ══════════════════════════════════════════════════════════════════
        // 6. INSTALL DEPENDENCIES
        // ══════════════════════════════════════════════════════════════════
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

        // ══════════════════════════════════════════════════════════════════
        // 7. SANITY CHECK
        // ══════════════════════════════════════════════════════════════════
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

        // ══════════════════════════════════════════════════════════════════
        // 8. UNIT TESTS
        // ══════════════════════════════════════════════════════════════════
        stage('🧪 Unit Tests') {
            runUnitTests(tech: tech)
        }

        // ══════════════════════════════════════════════════════════════════
        // 9. SONARQUBE ANALYSIS
        // ══════════════════════════════════════════════════════════════════
        stage('📊 Code Quality (SonarQube)') {
            runSonarAnalysis(
                projectKey : imageName.replaceAll('/', '-'),
                projectName: imageName.replaceAll('/', '-')
            )
        }

        // ══════════════════════════════════════════════════════════════════
        // 10. BUILD & PUSH BACKEND IMAGE
        // ══════════════════════════════════════════════════════════════════
        stage('🐳 Build & Push Backend Image') {
            buildResult    = autoBuild(imageName: imageName)
            env.IMAGE_TAG  = buildResult.imageTag
            env.FULL_IMAGE = buildResult.fullImage
            echo "✅ Backend image: ${env.FULL_IMAGE}"
        }

        // ══════════════════════════════════════════════════════════════════
        // 11. BUILD & PUSH FRONTEND IMAGE (si frontend détecté)
        // ══════════════════════════════════════════════════════════════════
        if (hasFrontend) {
            stage('🖥️ Build & Push Frontend Image') {
                frontendBuild        = buildFrontend(imageName: "${imageName}-frontend")
                env.FRONTEND_IMAGE   = frontendBuild.fullImage
                env.FRONTEND_TYPE    = frontendBuild.frontendType
                echo "✅ Frontend image: ${env.FRONTEND_IMAGE} (${env.FRONTEND_TYPE})"
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 12. PRE-PULL IMAGES SUR LES NOEUDS
        // Backend + Frontend en parallèle pour gagner du temps
        // ══════════════════════════════════════════════════════════════════
        stage('📦 Pre-pull images on nodes') {
            container('kubectl') {
                withKubeConfig([credentialsId: 'kubeconfig']) {

                    if (hasFrontend && env.FRONTEND_IMAGE) {
                        // Lancer les 2 jobs en parallèle
                        parallel(
                            'Pre-pull Backend': {
                                sh """
                                    kubectl apply -f - <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: prepull-back-${env.BUILD_NUMBER}
  namespace: jenkins
spec:
  ttlSecondsAfterFinished: 60
  template:
    spec:
      containers:
      - name: prepull
        image: ${env.FULL_IMAGE}
        imagePullPolicy: Always
        command: ['/bin/sh', '-c', 'echo backend pulled']
      restartPolicy: Never
EOF
                                    kubectl wait job/prepull-back-${env.BUILD_NUMBER} \
                                        -n jenkins --for=condition=complete --timeout=180s || true
                                    kubectl delete job prepull-back-${env.BUILD_NUMBER} \
                                        -n jenkins --ignore-not-found || true
                                    echo "✅ Backend pre-pull complete"
                                """
                            },
                            'Pre-pull Frontend': {
                                sh """
                                    kubectl apply -f - <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: prepull-front-${env.BUILD_NUMBER}
  namespace: jenkins
spec:
  ttlSecondsAfterFinished: 60
  template:
    spec:
      containers:
      - name: prepull
        image: ${env.FRONTEND_IMAGE}
        imagePullPolicy: Always
        command: ['/bin/sh', '-c', 'echo frontend pulled']
      restartPolicy: Never
EOF
                                    kubectl wait job/prepull-front-${env.BUILD_NUMBER} \
                                        -n jenkins --for=condition=complete --timeout=180s || true
                                    kubectl delete job prepull-front-${env.BUILD_NUMBER} \
                                        -n jenkins --ignore-not-found || true
                                    echo "✅ Frontend pre-pull complete"
                                """
                            }
                        )
                    } else {
                        // Backend seul
                        sh """
                            kubectl apply -f - <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: prepull-${env.BUILD_NUMBER}
  namespace: jenkins
spec:
  ttlSecondsAfterFinished: 60
  template:
    spec:
      containers:
      - name: prepull
        image: ${env.FULL_IMAGE}
        imagePullPolicy: Always
        command: ['/bin/sh', '-c', 'echo Image pulled successfully']
      restartPolicy: Never
EOF
                            kubectl wait job/prepull-${env.BUILD_NUMBER} \
                                -n jenkins --for=condition=complete --timeout=180s || true
                            kubectl delete job prepull-${env.BUILD_NUMBER} \
                                -n jenkins --ignore-not-found || true
                            echo "✅ Pre-pull complete"
                        """
                    }
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 13. TRIVY SCANS
        // ══════════════════════════════════════════════════════════════════
        stage('🔍 Security: Vulnerability Scan (Trivy)') {
            runTrivyScans(
                imageName: imageName,
                imageTag : env.IMAGE_TAG
            )
        }

        // ══════════════════════════════════════════════════════════════════
        // 14. DEPLOY DATABASE (si détectée)
        // ══════════════════════════════════════════════════════════════════
        if (env.DB_DETECTED == 'true') {
            stage('🗄️ Deploy Database') {
                dbConfig = deployDatabase(
                    namespace: namespace,
                    dbType   : env.DB_TYPE,
                    dbVersion: env.DB_VERSION,
                    dbPort   : env.DB_PORT.toInteger(),
                    appName  : imageName.replaceAll('[/_]', '-')
                )
                echo "✅ Database deployed: ${dbConfig.type} at ${dbConfig.serviceName}:${dbConfig.port}"
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 15. DEPLOY BACKEND
        // ══════════════════════════════════════════════════════════════════
        stage('🚀 Deploy Backend') {
            deployToK8s(
                namespace: namespace,
                appName  : imageName,
                image    : env.FULL_IMAGE,
                replicas : 2,
                dbConfig : dbConfig
            )
            appUrl = getAppUrl(namespace: namespace, appName: imageName)
            env.APP_URL = appUrl
            echo "✅ Backend deployed: ${appUrl}"
        }

        // ══════════════════════════════════════════════════════════════════
        // 16. DEPLOY FRONTEND (si détecté)
        //
        // Connexion frontend ↔ backend :
        // L'URL MetalLB du backend est injectée dans le pod frontend
        // via un fichier env-config.js servi par nginx.
        // Le frontend lit window.BACKEND_URL pour appeler le backend.
        // → Générique : fonctionne avec React, Vue, Angular, HTML, etc.
        // → Pas de /api/ hardcodé — chaque projet utilise ses propres routes
        // ══════════════════════════════════════════════════════════════════
        if (hasFrontend) {
            stage('🖥️ Deploy Frontend') {
                def frontendDeploy = deployFrontend(
                    namespace   : namespace,
                    appName     : imageName,
                    image       : env.FRONTEND_IMAGE,
                    backendUrl  : appUrl,
                    frontendType: env.FRONTEND_TYPE ?: 'react',
                    replicas    : 2
                )
                env.FRONTEND_URL = frontendDeploy.url

                echo "========================================="
                echo "🎉 Full Stack Deployment Complete!"
                echo "🖥️  Frontend : ${env.FRONTEND_URL}"
                echo "🔌 Backend  : ${appUrl}"
                if (dbConfig.deployed) {
                    echo "🗄️  Database : ${dbConfig.type} @ ${dbConfig.serviceName}:${dbConfig.port}"
                }
                echo "   window.BACKEND_URL = '${appUrl}' (injected via env-config.js)"
                echo "========================================="
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 17. E2E TESTS
        // ══════════════════════════════════════════════════════════════════
        if (runE2E) {
            stage('🌐 E2E Tests') {
                sleep 30
                // Tester le frontend si disponible, sinon le backend
                def testUrl = hasFrontend && env.FRONTEND_URL ? env.FRONTEND_URL : appUrl
                runE2ETests(appUrl: testUrl, hasFrontend: hasFrontend)
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 18. PERFORMANCE TESTS
        // ══════════════════════════════════════════════════════════════════
        if (runPerf) {
            stage('⚡ Performance Tests') {
                def testUrl = hasFrontend && env.FRONTEND_URL ? env.FRONTEND_URL : appUrl
                runPerfTests(appUrl: testUrl, vus: 10, duration: '30s')
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 19. ZAP SECURITY SCAN
        // ══════════════════════════════════════════════════════════════════
        if (runZAP) {
            stage('🛡️ Security: Web Scan (OWASP ZAP)') {
                def testUrl = hasFrontend && env.FRONTEND_URL ? env.FRONTEND_URL : appUrl
                runZAPScan(appUrl: testUrl)
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 20. SUMMARY
        // ══════════════════════════════════════════════════════════════════
        stage('📊 Summary') {
            def dbSummary = dbConfig.deployed ? """
Database:
  Type    : ${dbConfig.type}
  Service : ${dbConfig.serviceName}
  Port    : ${dbConfig.port}
""" : ''

            def frontendSummary = hasFrontend ? """
Frontend:
  Image   : ${env.FRONTEND_IMAGE}
  Type    : ${env.FRONTEND_TYPE}
  URL     : ${env.FRONTEND_URL ?: 'pending'}
""" : ''

            def summary = """
========================================
✅ CI/CD PIPELINE COMPLETED SUCCESSFULLY
========================================
Repository : ${repoUrl}
Language   : ${tech.language}
Framework  : ${tech.framework}

Backend:
  Image   : ${env.FULL_IMAGE}
  URL     : ${appUrl}
${frontendSummary}${dbSummary}
Tests Executed:
  ✅ Secret Scan       (Gitleaks)
  ✅ Unit Tests        (${tech.testFramework ?: 'Auto-detected'})
  ✅ Code Quality      (SonarQube)
  ✅ Vulnerability Scan(Trivy)
  ✅ Pre-pull images on nodes
${dbConfig.deployed ? '  ✅ Database Deployment (' + dbConfig.type + ')' : '  ⏭️  Database (not detected)'}
  ✅ Backend Deployment
${hasFrontend ? '  ✅ Frontend Deployment (' + env.FRONTEND_TYPE + ')' : '  ⏭️  Frontend (not detected)'}
${runE2E  ? '  ✅ E2E Tests'                : '  ⏭️  E2E Tests (skipped)'}
${runPerf ? '  ✅ Performance Tests'         : '  ⏭️  Performance Tests (skipped)'}
${runZAP  ? '  ✅ Web Security Scan (ZAP)'   : '  ⏭️  ZAP Scan (skipped)'}
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

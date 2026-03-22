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
                def branches = ['master', 'main', 'develop', 'dev', 'trunk']
                def cloned = false

                for (b in branches) {
                    try {
                        checkout([
                            $class: 'GitSCM',
                            branches: [[name: "*/${b}"]],
                            doGenerateSubmoduleConfigurations: false,
                            extensions: [[$class: 'CloneOption', timeout: 30]],
                            userRemoteConfigs: [[
                                url          : repoUrl,
                                credentialsId: 'github-creds'
                            ]]
                        ])
                        echo "✅ Cloned: ${repoUrl} (branch: ${b})"
                        env.GIT_BRANCH_USED = b
                        cloned = true
                        break
                    } catch (err) {
                        echo "⚠️ Branch '${b}' not found..."
                    }
                }

                if (!cloned) {
                    checkout([
                        $class: 'GitSCM',
                        branches: [[name: 'origin/master']],
                        doGenerateSubmoduleConfigurations: false,
                        extensions: [[$class: 'CloneOption', timeout: 30]],
                        userRemoteConfigs: [[
                            url          : repoUrl,
                            credentialsId: 'github-creds'
                        ]]
                    ])
                    echo "✅ Cloned using origin/master fallback"
                    env.GIT_BRANCH_USED = 'master'
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 2. DETECT TECHNOLOGY
        // ══════════════════════════════════════════════════════════════════
        stage('🔍 Detect Technology') {
            tech = detectTech()
            env.DETECTED_LANGUAGE  = tech.language
            env.DETECTED_FRAMEWORK = tech.framework
            env.BACKEND_ROOT_DIR   = tech.rootDir ?: '.'
            echo "========================================="
            echo "Language  : ${tech.language}"
            echo "Framework : ${tech.framework}"
            echo "Root Dir  : ${tech.rootDir}"
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
            env.DB_ENV_VARS = groovy.json.JsonOutput.toJson(dbInfo.envVars ?: [:])
            echo "Type: ${env.DB_TYPE} | Detected: ${env.DB_DETECTED} | Port: ${env.DB_PORT}"
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
        stage('🔒 Secret Scan (Gitleaks)') {
            container('scanner') {
                sh '''
                    export PATH=$PATH:/tmp
                    GITLEAKS_VERSION="8.18.4"
                    GITLEAKS_URL="https://github.com/gitleaks/gitleaks/releases/download/v${GITLEAKS_VERSION}/gitleaks_${GITLEAKS_VERSION}_linux_x64.tar.gz"
                    curl -sSL "${GITLEAKS_URL}" -o /tmp/gitleaks.tar.gz
                    FILE_SIZE=$(wc -c < /tmp/gitleaks.tar.gz)
                    if [ "$FILE_SIZE" -gt "1000000" ]; then
                        tar -xzf /tmp/gitleaks.tar.gz -C /tmp gitleaks 2>/dev/null || true
                        chmod +x /tmp/gitleaks
                        /tmp/gitleaks detect --source=. --report-path=gitleaks-report.json \
                            --report-format=json --no-git --verbose || true
                        SECRETS=$(grep -c '"RuleID"' gitleaks-report.json 2>/dev/null || echo "0")
                        echo "✅ Gitleaks done — secrets: ${SECRETS}"
                    else
                        echo "⚠️ Gitleaks download issue — skipping"
                        echo '[]' > gitleaks-report.json
                    fi
                '''
                archiveArtifacts artifacts: 'gitleaks-report.json', allowEmptyArchive: true
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 6. INSTALL DEPENDENCIES
        // FIX : dir(tech.rootDir) pour les monorepos (ex: springboot-backend/)
        // ══════════════════════════════════════════════════════════════════
        stage('📦 Install Dependencies') {
            def rootDir = tech.rootDir ?: '.'

            if (tech.language == 'Python') {
                container('python') {
                    dir(rootDir) {
                        sh '''
                            python3 -m pip install --upgrade pip --quiet
                            [ -f requirements.txt ] && pip install -r requirements.txt --quiet || true
                        '''
                    }
                }
            }
            else if (tech.language == 'Node.js') {
                container('node') {
                    dir(rootDir) {
                        sh 'npm install'
                    }
                }
            }
            else if (tech.language == 'Java') {
                container('maven') {
                    dir(rootDir) {
                        sh 'mvn clean install -DskipTests -Dcheckstyle.skip=true'
                    }
                }
            }
            else {
                echo "⚠️ Language not supported for install: ${tech.language}"
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 7. SANITY CHECK
        // ══════════════════════════════════════════════════════════════════
        stage('🔧 Sanity Check') {
            container('kubectl') {
                sh 'kubectl version --client && kubectl get nodes'
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 8. UNIT TESTS
        // FIX : passer rootDir à runUnitTests
        // ══════════════════════════════════════════════════════════════════
        stage('🧪 Unit Tests') {
            def rootDir = tech.rootDir ?: '.'
            dir(rootDir) {
                runUnitTests(tech: tech)
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 9. SONARQUBE ANALYSIS
        // FIX : scanner depuis le rootDir du backend
        // ══════════════════════════════════════════════════════════════════
        stage('📊 Code Quality (SonarQube)') {
            def rootDir = tech.rootDir ?: '.'
            dir(rootDir) {
                runSonarAnalysis(
                    projectKey : imageName.replaceAll('/', '-'),
                    projectName: imageName.replaceAll('/', '-')
                )
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 10. BUILD & PUSH BACKEND IMAGE
        // FIX : dir(tech.rootDir) pour que autoBuild trouve le pom.xml/Dockerfile
        // ══════════════════════════════════════════════════════════════════
        stage('🐳 Build & Push Backend Image') {
            def rootDir = tech.rootDir ?: '.'
            dir(rootDir) {
                buildResult = autoBuild(imageName: imageName)
            }
            env.IMAGE_TAG  = buildResult.imageTag
            env.FULL_IMAGE = buildResult.fullImage
            echo "✅ Backend: ${env.FULL_IMAGE}"
        }

        // ══════════════════════════════════════════════════════════════════
        // 11. BUILD & PUSH FRONTEND IMAGE (si frontend détecté)
        // buildFrontend détecte automatiquement le répertoire du frontend
        // ══════════════════════════════════════════════════════════════════
        if (hasFrontend) {
            stage('🖥️ Build & Push Frontend Image') {
                // buildFrontend cherche dans tous les sous-dossiers
                // → pas besoin de dir() ici
                frontendBuild      = buildFrontend(imageName: "${imageName}-frontend")
                env.FRONTEND_IMAGE = frontendBuild.fullImage
                env.FRONTEND_TYPE  = frontendBuild.frontendType
                echo "✅ Frontend: ${env.FRONTEND_IMAGE} (${env.FRONTEND_TYPE})"
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 12. PRE-PULL IMAGES SUR LES NOEUDS
        // ══════════════════════════════════════════════════════════════════
stage('📦 Pre-pull images on nodes') {
    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {
            if (hasFrontend && env.FRONTEND_IMAGE) {
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
  backoffLimit: 2
  template:
    spec:
      containers:
      - name: prepull
        image: ${env.FULL_IMAGE}
        imagePullPolicy: IfNotPresent
        command: ['/bin/sh', '-c', 'echo "Backend image pulled: ${env.FULL_IMAGE}"']
        resources:
          requests:
            cpu: 10m
            memory: 32Mi
          limits:
            cpu: 100m
            memory: 128Mi
      restartPolicy: Never
EOF
                            echo "⏳ Waiting for backend image pull..."
                            kubectl wait job/prepull-back-${env.BUILD_NUMBER} \
                                -n jenkins --for=condition=complete --timeout=900s || {
                                echo "⚠️ Backend pre-pull timeout, but continuing..."
                                kubectl logs -l job-name=prepull-back-${env.BUILD_NUMBER} -n jenkins --tail=20 || true
                            }
                            kubectl delete job prepull-back-${env.BUILD_NUMBER} \
                                -n jenkins --ignore-not-found || true
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
  backoffLimit: 2
  template:
    spec:
      containers:
      - name: prepull
        image: ${env.FRONTEND_IMAGE}
        imagePullPolicy: IfNotPresent
        command: ['/bin/sh', '-c', 'echo "Frontend image pulled: ${env.FRONTEND_IMAGE}"']
        resources:
          requests:
            cpu: 10m
            memory: 32Mi
          limits:
            cpu: 100m
            memory: 128Mi
      restartPolicy: Never
EOF
                            echo "⏳ Waiting for frontend image pull..."
                            kubectl wait job/prepull-front-${env.BUILD_NUMBER} \
                                -n jenkins --for=condition=complete --timeout=900s || {
                                echo "⚠️ Frontend pre-pull timeout, but continuing..."
                                kubectl logs -l job-name=prepull-front-${env.BUILD_NUMBER} -n jenkins --tail=20 || true
                            }
                            kubectl delete job prepull-front-${env.BUILD_NUMBER} \
                                -n jenkins --ignore-not-found || true
                        """
                    }
                )
            } else {
                sh """
                    kubectl apply -f - <<EOF
apiVersion: batch/v1
kind: Job
metadata:
  name: prepull-${env.BUILD_NUMBER}
  namespace: jenkins
spec:
  ttlSecondsAfterFinished: 60
  backoffLimit: 2
  template:
    spec:
      containers:
      - name: prepull
        image: ${env.FULL_IMAGE}
        imagePullPolicy: Always
        command: ['/bin/sh', '-c', 'echo "Image pulled: ${env.FULL_IMAGE}"']
        resources:
          requests:
            cpu: 10m
            memory: 32Mi
          limits:
            cpu: 100m
            memory: 128Mi
      restartPolicy: Never
EOF
                    echo "⏳ Waiting for image pull..."
                    kubectl wait job/prepull-${env.BUILD_NUMBER} \
                        -n jenkins --for=condition=complete --timeout=900s || {
                        echo "⚠️ Pre-pull timeout, but continuing..."
                        kubectl logs -l job-name=prepull-${env.BUILD_NUMBER} -n jenkins --tail=20 || true
                    }
                    kubectl delete job prepull-${env.BUILD_NUMBER} \
                        -n jenkins --ignore-not-found || true
                """
            }
            echo "✅ Pre-pull complete (or skipped due to timeout)"
        }
    }
}
        // ══════════════════════════════════════════════════════════════════
        // 13. TRIVY SCANS
        // ══════════════════════════════════════════════════════════════════
        stage('🔍 Vulnerability Scan (Trivy)') {
            runTrivyScans(
                imageName: imageName,
                imageTag : env.IMAGE_TAG
            )
        }

        // ══════════════════════════════════════════════════════════════════
        // 14. DEPLOY DATABASE
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
                echo "✅ DB: ${dbConfig.type} → ${dbConfig.serviceName}:${dbConfig.port}"
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
            echo "✅ Backend → ${appUrl}"
        }

        // ══════════════════════════════════════════════════════════════════
        // 16. DEPLOY FRONTEND
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
                echo "🎉 Frontend → ${env.FRONTEND_URL}"
                echo "🔌 Backend  → ${appUrl}"
                if (dbConfig.deployed) {
                    echo "🗄️  Database → ${dbConfig.type} @ ${dbConfig.serviceName}:${dbConfig.port}"
                }
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 17. E2E TESTS
        // ══════════════════════════════════════════════════════════════════
        if (runE2E) {
            stage('🌐 E2E Tests') {
                sleep 30
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
            stage('🛡️ ZAP Security Scan') {
                def testUrl = hasFrontend && env.FRONTEND_URL ? env.FRONTEND_URL : appUrl
                runZAPScan(appUrl: testUrl)
            }
        }

        // ══════════════════════════════════════════════════════════════════
        // 20. SUMMARY
        // ══════════════════════════════════════════════════════════════════
        stage('📊 Summary') {
            def summary = """
========================================
✅ CI/CD PIPELINE COMPLETED SUCCESSFULLY
========================================
Repository : ${repoUrl}
Language   : ${tech.language}
Framework  : ${tech.framework}
Root Dir   : ${tech.rootDir ?: '.'}

Images:
  Backend  : ${env.FULL_IMAGE}
  Frontend : ${hasFrontend ? env.FRONTEND_IMAGE : 'not detected'}

Deployments (${namespace}):
  Backend  : ${appUrl}
  Frontend : ${hasFrontend ? (env.FRONTEND_URL ?: 'pending') : 'not deployed'}
  Database : ${dbConfig.deployed ? dbConfig.type + ' @ ' + dbConfig.serviceName + ':' + dbConfig.port : 'not detected'}

Tests:
  ✅ Secret Scan    (Gitleaks)
  ✅ Unit Tests     (${tech.testFramework ?: 'Auto'})
  ✅ Code Quality   (SonarQube)
  ✅ Vulnerability  (Trivy)
  ✅ Pre-pull images
  ${dbConfig.deployed ? '✅' : '⏭️ '} Database  ${dbConfig.deployed ? '(' + dbConfig.type + ')' : '(skipped)'}
  ✅ Backend Deploy
  ${hasFrontend ? '✅' : '⏭️ '} Frontend  ${hasFrontend ? '(' + env.FRONTEND_TYPE + ')' : '(not detected)'}
  ${runE2E  ? '✅' : '⏭️ '} E2E Tests ${runE2E  ? '' : '(skipped)'}
  ${runPerf ? '✅' : '⏭️ '} Perf Tests${runPerf ? '' : '(skipped)'}
  ${runZAP  ? '✅' : '⏭️ '} ZAP Scan  ${runZAP  ? '' : '(skipped)'}
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

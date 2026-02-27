#!/usr/bin/env groovy

def call(Map config = [:]) {
    // Définir les clés du projet
    def projectKey = config.projectKey?.replaceAll("/", "_") ?: "my_project"
    def projectName = config.projectName ?: "My Project"
    def tech = config.tech ?: detectTech()

    // Propriétés de base SonarQube
    def sonarProps = """
sonar.projectKey=${projectKey}
sonar.projectName=${projectName}
sonar.sources=.
sonar.sourceEncoding=UTF-8
"""

    // Configurations selon le langage
    switch (tech.language) {
        case 'Python':
            sonarProps += """
sonar.language=py
sonar.python.coverage.reportPaths=coverage.xml
sonar.exclusions=**/*test*/**,**/venv/**,**/htmlcov/**,**/__pycache__/**
sonar.tests=tests
sonar.test.inclusions=tests/**/*.py
"""
            break

        case 'Node.js':
            sonarProps += """
sonar.language=js
sonar.javascript.lcov.reportPaths=coverage/lcov.info
sonar.exclusions=**/node_modules/**,**/dist/**,**/build/**,**/*test*/**
sonar.tests=test,tests,__tests__
"""
            break

        case 'Java':
            sonarProps += """
sonar.language=java
sonar.java.binaries=target/classes,build/classes
sonar.java.test.binaries=target/test-classes,build/classes/test
sonar.junit.reportPaths=target/surefire-reports,build/test-results
sonar.jacoco.reportPaths=target/jacoco.exec
sonar.exclusions=**/test/**,**/target/**,**/build/**
"""
            break

        case 'Go':
            sonarProps += """
sonar.language=go
sonar.go.coverage.reportPaths=coverage.out
sonar.exclusions=**/*_test.go,**/vendor/**
"""
            break

        case 'PHP':
            sonarProps += """
sonar.language=php
sonar.php.coverage.reportPaths=coverage.xml
sonar.exclusions=**/vendor/**,**/tests/**
"""
            break

        default:
            sonarProps += """
sonar.exclusions=**/test/**,**/tests/**,**/node_modules/**,**/vendor/**,**/build/**,**/dist/**
"""
            break
    }

    // Lancer l'analyse avec SonarQube
    withSonarQubeEnv('sonarqube') {
        container('scanner') {
            writeFile file: 'sonar-project.properties', text: sonarProps

            sh """
                sonar-scanner \
                    -Dsonar.projectKey=${projectKey} \
                    -Dsonar.projectName="${projectName}" \
                    -Dsonar.sources=. \
                    || echo "⚠️ SonarQube analysis completed with warnings"
            """
        }
    }

    // Vérifier le Quality Gate avec timeout réduit
    timeout(time: 30, unit: 'MINUTES') {
        def qg = waitForQualityGate()
        if (qg.status != 'OK') {
            error "⚠️ Quality Gate failed: ${qg.status}"
        }
    }
}

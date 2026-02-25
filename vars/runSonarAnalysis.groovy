#!/usr/bin/env groovy

def call(Map config = [:]) {
    def projectKey = config.projectKey
    def projectName = config.projectName
    def tech = config.tech ?: detectTech()
    
    // Build SonarQube properties based on language
    def sonarProps = """
sonar.projectKey=${projectKey}
sonar.projectName=${projectName}
sonar.sources=.
sonar.sourceEncoding=UTF-8
"""
    
    // Language-specific configurations
    if (tech.language == 'Python') {
        sonarProps += """
sonar.language=py
sonar.python.coverage.reportPaths=coverage.xml
sonar.exclusions=**/*test*/**,**/venv/**,**/htmlcov/**,**/__pycache__/**
sonar.tests=tests
sonar.test.inclusions=tests/**/*.py
"""
    }
    else if (tech.language == 'Node.js') {
        sonarProps += """
sonar.language=js
sonar.javascript.lcov.reportPaths=coverage/lcov.info
sonar.exclusions=**/node_modules/**,**/dist/**,**/build/**,**/*test*/**
sonar.tests=test,tests,__tests__
"""
    }
    else if (tech.language == 'Java') {
        sonarProps += """
sonar.language=java
sonar.java.binaries=target/classes,build/classes
sonar.java.test.binaries=target/test-classes,build/classes/test
sonar.junit.reportPaths=target/surefire-reports,build/test-results
sonar.jacoco.reportPaths=target/jacoco.exec
sonar.exclusions=**/test/**,**/target/**,**/build/**
"""
    }
    else if (tech.language == 'Go') {
        sonarProps += """
sonar.language=go
sonar.go.coverage.reportPaths=coverage.out
sonar.exclusions=**/*_test.go,**/vendor/**
"""
    }
    else if (tech.language == 'PHP') {
        sonarProps += """
sonar.language=php
sonar.php.coverage.reportPaths=coverage.xml
sonar.exclusions=**/vendor/**,**/tests/**
"""
    }
    else {
        sonarProps += """
sonar.exclusions=**/test/**,**/tests/**,**/node_modules/**,**/vendor/**
"""
    }
    
    withSonarQubeEnv('sonarqube') {
        container('scanner') {
            writeFile file: 'sonar-project.properties', text: sonarProps
            
            sh """
                sonar-scanner || echo "⚠️  SonarQube analysis completed with warnings"
            """
        }
    }
    
    timeout(time: 5, unit: 'MINUTES') {
        def qg = waitForQualityGate()
        if (qg.status != 'OK') {
            echo "⚠️  Quality Gate failed: ${qg.status}"
        }
    }
}

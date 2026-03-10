def call(Map config = [:]) {

    def projectKey = config.projectKey?.replaceAll("/", "_") ?: "my_project"
    def projectName = config.projectName ?: "My Project"
    def tech = config.tech ?: detectTech()

    def sonarProps = """
sonar.projectKey=${projectKey}
sonar.projectName=${projectName}
sonar.sources=.
sonar.sourceEncoding=UTF-8
sonar.ws.timeout=240
"""

    switch (tech.language) {

        case 'Python':
            sonarProps += """
sonar.python.coverage.reportPaths=coverage.xml
sonar.exclusions=**/*test*/**,**/venv/**,**/htmlcov/**,**/__pycache__/**
sonar.tests=tests
sonar.test.inclusions=tests/**/*.py
"""
            break

        case 'Node.js':
            sonarProps += """
sonar.javascript.lcov.reportPaths=coverage/lcov.info
sonar.exclusions=**/node_modules/**,**/dist/**,**/build/**,**/*test*/**
sonar.tests=test,tests,__tests__
"""
            break

        case 'Java':
            sonarProps += """
sonar.java.binaries=target/classes
sonar.java.test.binaries=target/test-classes
sonar.junit.reportPaths=target/surefire-reports
sonar.coverage.jacoco.xmlReportPaths=target/site/jacoco/jacoco.xml
sonar.exclusions=**/test/**,**/target/**
"""
            break

        case 'Go':
            sonarProps += """
sonar.go.coverage.reportPaths=coverage.out
sonar.exclusions=**/*_test.go,**/vendor/**
"""
            break

        case 'PHP':
            sonarProps += """
sonar.php.coverage.reportPaths=coverage.xml
sonar.exclusions=**/vendor/**,**/tests/**
"""
            break

        default:
            sonarProps += """
sonar.exclusions=**/test/**,**/tests/**,**/node_modules/**,**/vendor/**,**/build/**,**/dist/**
"""
    }

    stage('📊 SonarQube Analysis') {
        withSonarQubeEnv('sonarqube') {
            container('scanner') {
                writeFile file: 'sonar-project.properties', text: sonarProps

                // Wrap Sonar scan in try/catch for better logging
                try {
                    sh '''
                        echo "Running SonarQube scan..."
                        sonar-scanner
                    '''
                } catch (err) {
                    echo "❌ SonarQube scan failed!"
                    echo "Check network connectivity to SonarQube server: ${SONAR_HOST_URL}"
                    echo "Error: ${err}"
                    error("SonarQube scan could not complete.")
                }
            }
        }
    }

    stage('🚦 SonarQube Quality Gate') {
        timeout(time: 20, unit: 'MINUTES') {
            try {
                def qg = waitForQualityGate()
                if (qg.status != 'OK') {
                    echo "⚠️ Quality Gate failed: ${qg.status}"
                    echo "Pipeline continues but code quality should be improved."
                } else {
                    echo "✅ Quality Gate passed"
                }
            } catch (err) {
                echo "⚠️ Could not retrieve SonarQube Quality Gate status!"
                echo "Check SonarQube server or network connectivity."
                echo "Error: ${err}"
            }
        }
    }
}

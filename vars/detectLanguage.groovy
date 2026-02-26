#!/usr/bin/env groovy

def call(String repoUrl) {
    echo "🔍 Detecting language for: ${repoUrl}"
    
    def language = 'unknown'
    
    try {
        // Clone shallow (seulement les derniers fichiers, pas l'historique)
        sh """
            rm -rf /tmp/repo-detect
            git clone --depth 1 ${repoUrl} /tmp/repo-detect 2>&1 || true
        """
        
        // Vérifier si le clone a réussi
        def cloneSuccess = sh(
            script: "test -d /tmp/repo-detect/.git && echo 'true' || echo 'false'",
            returnStdout: true
        ).trim()
        
        if (cloneSuccess == 'false') {
            echo "⚠️  Clone failed, trying alternative detection..."
            return 'unknown'
        }
        
        // Liste les fichiers pour debug
        sh "ls -la /tmp/repo-detect/ || true"
        
        // Vérifier Python
        def pythonCheck = sh(
            script: """
                if [ -f /tmp/repo-detect/requirements.txt ] || [ -f /tmp/repo-detect/setup.py ] || [ -f /tmp/repo-detect/pyproject.toml ]; then
                    echo 'python'
                else
                    echo 'no'
                fi
            """,
            returnStdout: true
        ).trim()
        
        if (pythonCheck == 'python') {
            language = 'python'
            sh "rm -rf /tmp/repo-detect"
            return language
        }
        
        // Vérifier Node.js
        def nodeCheck = sh(
            script: """
                if [ -f /tmp/repo-detect/package.json ]; then
                    echo 'nodejs'
                else
                    echo 'no'
                fi
            """,
            returnStdout: true
        ).trim()
        
        if (nodeCheck == 'nodejs') {
            language = 'nodejs'
            sh "rm -rf /tmp/repo-detect"
            return language
        }
        
        // Vérifier Java Maven
        def mavenCheck = sh(
            script: """
                if [ -f /tmp/repo-detect/pom.xml ]; then
                    echo 'java-maven'
                else
                    echo 'no'
                fi
            """,
            returnStdout: true
        ).trim()
        
        if (mavenCheck == 'java-maven') {
            language = 'java-maven'
            sh "rm -rf /tmp/repo-detect"
            return language
        }
        
        // Vérifier Java Gradle
        def gradleCheck = sh(
            script: """
                if [ -f /tmp/repo-detect/build.gradle ] || [ -f /tmp/repo-detect/build.gradle.kts ]; then
                    echo 'java-gradle'
                else
                    echo 'no'
                fi
            """,
            returnStdout: true
        ).trim()
        
        if (gradleCheck == 'java-gradle') {
            language = 'java-gradle'
            sh "rm -rf /tmp/repo-detect"
            return language
        }
        
        // Vérifier Go
        def goCheck = sh(
            script: """
                if [ -f /tmp/repo-detect/go.mod ]; then
                    echo 'golang'
                else
                    echo 'no'
                fi
            """,
            returnStdout: true
        ).trim()
        
        if (goCheck == 'golang') {
            language = 'golang'
            sh "rm -rf /tmp/repo-detect"
            return language
        }
        
        // Vérifier PHP
        def phpCheck = sh(
            script: """
                if [ -f /tmp/repo-detect/composer.json ]; then
                    echo 'php'
                else
                    echo 'no'
                fi
            """,
            returnStdout: true
        ).trim()
        
        if (phpCheck == 'php') {
            language = 'php'
            sh "rm -rf /tmp/repo-detect"
            return language
        }
        
        // Vérifier Ruby
        def rubyCheck = sh(
            script: """
                if [ -f /tmp/repo-detect/Gemfile ]; then
                    echo 'ruby'
                else
                    echo 'no'
                fi
            """,
            returnStdout: true
        ).trim()
        
        if (rubyCheck == 'ruby') {
            language = 'ruby'
            sh "rm -rf /tmp/repo-detect"
            return language
        }
        
    } catch (Exception e) {
        echo "⚠️  Error during detection: ${e.message}"
        language = 'unknown'
    } finally {
        // Nettoyer dans tous les cas
        sh "rm -rf /tmp/repo-detect || true"
    }
    
    echo "✅ Detected language: ${language}"
    return language
}

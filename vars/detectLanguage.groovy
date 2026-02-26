#!/usr/bin/env groovy

def call(String repoUrl) {
    echo "🔍 Detecting language for: ${repoUrl}"
    
    // Clone le repo
    sh """
        rm -rf /tmp/repo-detect
        git clone ${repoUrl} /tmp/repo-detect 2>/dev/null || echo "Clone done"
    """
    
    def language = 'unknown'
    
    // Vérifier les fichiers caractéristiques
    def pythonExists = sh(script: "test -f /tmp/repo-detect/requirements.txt && echo 'true' || echo 'false'", returnStdout: true).trim()
    def nodeExists = sh(script: "test -f /tmp/repo-detect/package.json && echo 'true' || echo 'false'", returnStdout: true).trim()
    def mavenExists = sh(script: "test -f /tmp/repo-detect/pom.xml && echo 'true' || echo 'false'", returnStdout: true).trim()
    def gradleExists = sh(script: "test -f /tmp/repo-detect/build.gradle && echo 'true' || echo 'false'", returnStdout: true).trim()
    def goExists = sh(script: "test -f /tmp/repo-detect/go.mod && echo 'true' || echo 'false'", returnStdout: true).trim()
    def phpExists = sh(script: "test -f /tmp/repo-detect/composer.json && echo 'true' || echo 'false'", returnStdout: true).trim()
    def rubyExists = sh(script: "test -f /tmp/repo-detect/Gemfile && echo 'true' || echo 'false'", returnStdout: true).trim()
    
    if (pythonExists == 'true') {
        language = 'python'
    } else if (nodeExists == 'true') {
        language = 'nodejs'
    } else if (mavenExists == 'true') {
        language = 'java-maven'
    } else if (gradleExists == 'true') {
        language = 'java-gradle'
    } else if (goExists == 'true') {
        language = 'golang'
    } else if (phpExists == 'true') {
        language = 'php'
    } else if (rubyExists == 'true') {
        language = 'ruby'
    }
    
    // Nettoyer
    sh "rm -rf /tmp/repo-detect"
    
    echo "✅ Detected language: ${language}"
    return language
}

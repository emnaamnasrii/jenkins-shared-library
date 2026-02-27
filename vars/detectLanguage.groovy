#!/usr/bin/env groovy

def call() {
    echo "🔍 Scanning repository for language..."

    def language = 'unknown'

    // Recherche récursive des fichiers
    def pythonFiles = findFiles(glob: '**/{requirements.txt,setup.py,pyproject.toml,Pipfile}')
    def nodeFiles   = findFiles(glob: '**/package.json')
    def mavenFiles  = findFiles(glob: '**/pom.xml')
    def gradleFiles = findFiles(glob: '**/build.gradle,**/build.gradle.kts')
    def goFiles     = findFiles(glob: '**/go.mod')
    def phpFiles    = findFiles(glob: '**/composer.json')
    def rubyFiles   = findFiles(glob: '**/Gemfile')

    if (pythonFiles.size() > 0) {
        language = 'python'
        echo "Detected Python based on: ${pythonFiles*.path}"
    } 
    else if (nodeFiles.size() > 0) {
        language = 'nodejs'
        echo "Detected Node.js based on: ${nodeFiles*.path}"
    } 
    else if (mavenFiles.size() > 0) {
        language = 'java-maven'
        echo "Detected Java Maven based on: ${mavenFiles*.path}"
    } 
    else if (gradleFiles.size() > 0) {
        language = 'java-gradle'
        echo "Detected Java Gradle based on: ${gradleFiles*.path}"
    } 
    else if (goFiles.size() > 0) {
        language = 'golang'
        echo "Detected Go based on: ${goFiles*.path}"
    } 
    else if (phpFiles.size() > 0) {
        language = 'php'
        echo "Detected PHP based on: ${phpFiles*.path}"
    } 
    else if (rubyFiles.size() > 0) {
        language = 'ruby'
        echo "Detected Ruby based on: ${rubyFiles*.path}"
    } 
    else {
        echo "⚠️ Could not detect language, returning 'unknown'"
    }

    return language
}

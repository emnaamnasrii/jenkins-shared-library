#!/usr/bin/env groovy

def call() {
    def tech = [
        language: 'unknown',
        framework: 'unknown',
        packageManager: 'unknown',
        hasDockerfile: false
    ]
    
    echo "🔍 Scanning repository for technology stack..."
    
    // Détection Python
    if (fileExists('requirements.txt') || fileExists('setup.py')) {
        tech.language = 'Python'
        tech.packageManager = 'pip'
        
        if (fileExists('requirements.txt')) {
            def reqs = readFile('requirements.txt')
            if (reqs.contains('flask')) {
                tech.framework = 'Flask'
            } else if (reqs.contains('django')) {
                tech.framework = 'Django'
            } else if (reqs.contains('fastapi')) {
                tech.framework = 'FastAPI'
            }
        }
    }
    // Détection Node.js
    else if (fileExists('package.json')) {
        tech.language = 'Node.js'
        tech.packageManager = 'npm'
        
        def packageJson = readJSON file: 'package.json'
        if (packageJson.dependencies?.react) {
            tech.framework = 'React'
        } else if (packageJson.dependencies?.express) {
            tech.framework = 'Express'
        } else if (packageJson.dependencies?.vue) {
            tech.framework = 'Vue.js'
        }
    }
    // Détection Java Maven
    else if (fileExists('pom.xml')) {
        tech.language = 'Java'
        tech.packageManager = 'Maven'
        tech.framework = 'Spring Boot'
    }
    // Détection Java Gradle
    else if (fileExists('build.gradle')) {
        tech.language = 'Java'
        tech.packageManager = 'Gradle'
        tech.framework = 'Spring Boot'
    }
    // Détection Go
    else if (fileExists('go.mod')) {
        tech.language = 'Go'
        tech.packageManager = 'go mod'
    }
    
    tech.hasDockerfile = fileExists('Dockerfile')
    
    echo "✅ Detection completed:"
    echo "   Language: ${tech.language}"
    echo "   Framework: ${tech.framework}"
    echo "   Package Manager: ${tech.packageManager}"
    echo "   Has Dockerfile: ${tech.hasDockerfile}"
    
    return tech
}

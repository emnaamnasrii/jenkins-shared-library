#!/usr/bin/env groovy

def call() {
    def language = 'unknown'
    
    if (fileExists('requirements.txt') || fileExists('setup.py')) {
        language = 'python'
    }
    else if (fileExists('package.json')) {
        language = 'nodejs'
    }
    else if (fileExists('pom.xml')) {
        language = 'java-maven'
    }
    else if (fileExists('build.gradle')) {
        language = 'java-gradle'
    }
    else if (fileExists('go.mod')) {
        language = 'golang'
    }
    else if (fileExists('composer.json')) {
        language = 'php'
    }
    else if (fileExists('Gemfile')) {
        language = 'ruby'
    }
    
    return language
}

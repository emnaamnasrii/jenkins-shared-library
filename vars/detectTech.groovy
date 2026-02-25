#!/usr/bin/env groovy

def call() {
    def tech = [
        language: 'unknown',
        framework: 'unknown',
        packageManager: 'unknown',
        buildTool: 'unknown',
        testFramework: 'unknown',
        linter: 'unknown',
        securityTool: 'unknown',
        hasDockerfile: false,
        hasTests: false
    ]
    
    echo "🔍 Scanning repository for technology stack..."
    
    // ========================================
    // PYTHON
    // ========================================
    if (fileExists('requirements.txt') || fileExists('setup.py') || fileExists('pyproject.toml') || fileExists('Pipfile')) {
        tech.language = 'Python'
        tech.packageManager = 'pip'
        tech.linter = 'flake8'
        tech.securityTool = 'bandit'
        
        // Detect framework
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
        
        // Detect test framework
        if (fileExists('pytest.ini') || fileExists('tests/') || fileExists('test/')) {
            tech.testFramework = 'pytest'
            tech.hasTests = true
        } else if (fileExists('unittest')) {
            tech.testFramework = 'unittest'
            tech.hasTests = true
        }
    }
    
    // ========================================
    // NODE.JS
    // ========================================
    else if (fileExists('package.json')) {
        tech.language = 'Node.js'
        tech.packageManager = 'npm'
        tech.buildTool = 'npm'
        tech.linter = 'eslint'
        tech.securityTool = 'npm-audit'
        
        def packageJson = readJSON file: 'package.json'
        
        // Detect framework
        if (packageJson.dependencies?.react) {
            tech.framework = 'React'
        } else if (packageJson.dependencies?.vue) {
            tech.framework = 'Vue.js'
        } else if (packageJson.dependencies?.express) {
            tech.framework = 'Express'
        } else if (packageJson.dependencies?.next) {
            tech.framework = 'Next.js'
        } else if (packageJson.dependencies?.['@angular/core']) {
            tech.framework = 'Angular'
        }
        
        // Detect test framework
        if (packageJson.devDependencies?.jest || packageJson.dependencies?.jest) {
            tech.testFramework = 'Jest'
            tech.hasTests = true
        } else if (packageJson.devDependencies?.mocha) {
            tech.testFramework = 'Mocha'
            tech.hasTests = true
        } else if (packageJson.scripts?.test) {
            tech.testFramework = 'npm test'
            tech.hasTests = true
        }
    }
    
    // ========================================
    // JAVA (MAVEN)
    // ========================================
    else if (fileExists('pom.xml')) {
        tech.language = 'Java'
        tech.packageManager = 'Maven'
        tech.buildTool = 'mvn'
        tech.testFramework = 'JUnit'
        tech.linter = 'checkstyle'
        tech.securityTool = 'spotbugs'
        tech.framework = 'Spring Boot'
        tech.hasTests = fileExists('src/test/')
    }
    
    // ========================================
    // JAVA (GRADLE)
    // ========================================
    else if (fileExists('build.gradle') || fileExists('build.gradle.kts')) {
        tech.language = 'Java'
        tech.packageManager = 'Gradle'
        tech.buildTool = 'gradle'
        tech.testFramework = 'JUnit'
        tech.linter = 'checkstyle'
        tech.securityTool = 'spotbugs'
        tech.framework = 'Spring Boot'
        tech.hasTests = fileExists('src/test/')
    }
    
    // ========================================
    // GO
    // ========================================
    else if (fileExists('go.mod')) {
        tech.language = 'Go'
        tech.packageManager = 'go mod'
        tech.buildTool = 'go'
        tech.testFramework = 'go test'
        tech.linter = 'golangci-lint'
        tech.securityTool = 'gosec'
        tech.hasTests = sh(script: 'find . -name "*_test.go" | wc -l', returnStdout: true).trim().toInteger() > 0
    }
    
    // ========================================
    // PHP
    // ========================================
    else if (fileExists('composer.json')) {
        tech.language = 'PHP'
        tech.packageManager = 'composer'
        tech.buildTool = 'composer'
        tech.testFramework = 'PHPUnit'
        tech.linter = 'phpcs'
        tech.securityTool = 'psalm'
        
        def composerJson = readJSON file: 'composer.json'
        if (composerJson.require?.['laravel/framework']) {
            tech.framework = 'Laravel'
        } else if (composerJson.require?.['symfony/symfony']) {
            tech.framework = 'Symfony'
        }
        
        tech.hasTests = fileExists('tests/') || fileExists('phpunit.xml')
    }
    
    // ========================================
    // RUBY
    // ========================================
    else if (fileExists('Gemfile')) {
        tech.language = 'Ruby'
        tech.packageManager = 'bundler'
        tech.buildTool = 'bundle'
        tech.testFramework = 'RSpec'
        tech.linter = 'rubocop'
        tech.securityTool = 'brakeman'
        tech.framework = 'Rails'
        tech.hasTests = fileExists('spec/')
    }
    
    // ========================================
    // C# (.NET)
    // ========================================
    else if (fileExists('*.csproj') || fileExists('*.sln')) {
        tech.language = 'C#'
        tech.packageManager = 'NuGet'
        tech.buildTool = 'dotnet'
        tech.testFramework = 'xUnit'
        tech.framework = '.NET'
        tech.linter = 'dotnet-format'
        tech.hasTests = fileExists('*.Tests.csproj')
    }
    
    // ========================================
    // RUST
    // ========================================
    else if (fileExists('Cargo.toml')) {
        tech.language = 'Rust'
        tech.packageManager = 'cargo'
        tech.buildTool = 'cargo'
        tech.testFramework = 'cargo test'
        tech.linter = 'clippy'
        tech.hasTests = true
    }
    
    // Check for Dockerfile
    tech.hasDockerfile = fileExists('Dockerfile')
    
    echo "✅ Detection completed:"
    echo "   Language: ${tech.language}"
    echo "   Framework: ${tech.framework}"
    echo "   Package Manager: ${tech.packageManager}"
    echo "   Build Tool: ${tech.buildTool}"
    echo "   Test Framework: ${tech.testFramework}"
    echo "   Linter: ${tech.linter}"
    echo "   Security Tool: ${tech.securityTool}"
    echo "   Has Dockerfile: ${tech.hasDockerfile}"
    echo "   Has Tests: ${tech.hasTests}"
    
    return tech
}

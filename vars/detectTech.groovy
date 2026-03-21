#!/usr/bin/env groovy

def call() {
    def tech = [
        language      : 'unknown',
        framework     : 'unknown',
        packageManager: 'unknown',
        buildTool     : 'unknown',
        testFramework : 'unknown',
        linter        : 'unknown',
        securityTool  : 'unknown',
        hasDockerfile : false,
        hasTests      : false,
        rootDir       : '.'   // répertoire où se trouve le fichier principal
    ]

    echo "🔍 Scanning repository for technology stack..."

    // ─────────────────────────────────────────────────────────────────────
    // Helpers — trouver un fichier même dans les sous-dossiers
    // ─────────────────────────────────────────────────────────────────────
    def findFirst = { String glob ->
        def files = findFiles(glob: glob)
        return files.size() > 0 ? files[0].path : null
    }

    def findFirstDir = { String filePath ->
        if (!filePath) return '.'
        def parts = filePath.split('/')
        return parts.size() > 1 ? parts[0..-2].join('/') : '.'
    }

    def readFirst = { String glob ->
        def f = findFirst(glob)
        return f ? readFile(f).toLowerCase() : ''
    }

    // ─────────────────────────────────────────────────────────────────────
    // PYTHON
    // ─────────────────────────────────────────────────────────────────────
    def reqFile = findFirst('**/requirements.txt')
    def setupFile = findFirst('**/setup.py')
    def pyprojectFile = findFirst('**/pyproject.toml')
    def pipfileFile = findFirst('**/Pipfile')

    if (reqFile || setupFile || pyprojectFile || pipfileFile) {
        tech.language      = 'Python'
        tech.packageManager = 'pip'
        tech.linter        = 'flake8'
        tech.securityTool  = 'bandit'
        tech.rootDir       = findFirstDir(reqFile ?: setupFile ?: pyprojectFile ?: pipfileFile)

        def reqs = reqFile ? readFile(reqFile).toLowerCase() : ''
        if (reqs.contains('flask'))   tech.framework = 'Flask'
        else if (reqs.contains('django'))  tech.framework = 'Django'
        else if (reqs.contains('fastapi')) tech.framework = 'FastAPI'

        tech.testFramework = 'pytest'
        tech.hasTests = findFirst('**/pytest.ini') != null ||
                        findFirst('**/tests/*.py') != null ||
                        findFirst('**/test/*.py')  != null
    }

    // ─────────────────────────────────────────────────────────────────────
    // JAVA MAVEN — priorité sur Node.js pour les monorepos fullstack
    // ─────────────────────────────────────────────────────────────────────
    else if (findFirst('**/pom.xml') != null) {
        def pomPath = findFirst('**/pom.xml')
        tech.language      = 'Java'
        tech.packageManager = 'Maven'
        tech.buildTool     = 'mvn'
        tech.testFramework = 'JUnit'
        tech.linter        = 'checkstyle'
        tech.securityTool  = 'spotbugs'
        tech.framework     = 'Spring Boot'
        tech.rootDir       = findFirstDir(pomPath)
        tech.hasTests      = findFirst('**/src/test/**/*.java') != null
    }

    // ─────────────────────────────────────────────────────────────────────
    // JAVA GRADLE
    // ─────────────────────────────────────────────────────────────────────
    else if (findFirst('**/build.gradle') != null || findFirst('**/build.gradle.kts') != null) {
        def gradlePath = findFirst('**/build.gradle') ?: findFirst('**/build.gradle.kts')
        tech.language      = 'Java'
        tech.packageManager = 'Gradle'
        tech.buildTool     = 'gradle'
        tech.testFramework = 'JUnit'
        tech.linter        = 'checkstyle'
        tech.securityTool  = 'spotbugs'
        tech.framework     = 'Spring Boot'
        tech.rootDir       = findFirstDir(gradlePath)
        tech.hasTests      = findFirst('**/src/test/**/*.java') != null
    }

    // ─────────────────────────────────────────────────────────────────────
    // NODE.JS — après Java pour ne pas confondre avec react-frontend
    // ─────────────────────────────────────────────────────────────────────
    else if (findFirst('**/package.json') != null) {
        def pkgPath = findFirst('**/package.json')
        tech.language      = 'Node.js'
        tech.packageManager = 'npm'
        tech.buildTool     = 'npm'
        tech.linter        = 'eslint'
        tech.securityTool  = 'npm-audit'
        tech.rootDir       = findFirstDir(pkgPath)

        def pkgContent = readFile(pkgPath).toLowerCase()
        if (pkgContent.contains('"react"'))         tech.framework = 'React'
        else if (pkgContent.contains('"vue"'))      tech.framework = 'Vue.js'
        else if (pkgContent.contains('"express"'))  tech.framework = 'Express'
        else if (pkgContent.contains('"next"'))     tech.framework = 'Next.js'
        else if (pkgContent.contains('"@angular/')) tech.framework = 'Angular'
        else if (pkgContent.contains('"nestjs"'))   tech.framework = 'NestJS'

        tech.testFramework = pkgContent.contains('"jest"') ? 'Jest' :
                             pkgContent.contains('"mocha"') ? 'Mocha' : 'npm test'
        tech.hasTests = pkgContent.contains('"jest"') || pkgContent.contains('"mocha"') ||
                        pkgContent.contains('"test"')
    }

    // ─────────────────────────────────────────────────────────────────────
    // GO
    // ─────────────────────────────────────────────────────────────────────
    else if (findFirst('**/go.mod') != null) {
        tech.language      = 'Go'
        tech.packageManager = 'go mod'
        tech.buildTool     = 'go'
        tech.testFramework = 'go test'
        tech.linter        = 'golangci-lint'
        tech.securityTool  = 'gosec'
        tech.rootDir       = findFirstDir(findFirst('**/go.mod'))
        tech.hasTests      = findFirst('**/*_test.go') != null
    }

    // ─────────────────────────────────────────────────────────────────────
    // PHP
    // ─────────────────────────────────────────────────────────────────────
    else if (findFirst('**/composer.json') != null) {
        def composerPath = findFirst('**/composer.json')
        tech.language      = 'PHP'
        tech.packageManager = 'composer'
        tech.buildTool     = 'composer'
        tech.testFramework = 'PHPUnit'
        tech.linter        = 'phpcs'
        tech.securityTool  = 'psalm'
        tech.rootDir       = findFirstDir(composerPath)

        def composerContent = readFile(composerPath).toLowerCase()
        if (composerContent.contains('laravel'))  tech.framework = 'Laravel'
        else if (composerContent.contains('symfony')) tech.framework = 'Symfony'

        tech.hasTests = findFirst('**/tests/*.php') != null ||
                        findFirst('**/phpunit.xml') != null
    }

    // ─────────────────────────────────────────────────────────────────────
    // RUBY
    // ─────────────────────────────────────────────────────────────────────
    else if (findFirst('**/Gemfile') != null) {
        tech.language      = 'Ruby'
        tech.packageManager = 'bundler'
        tech.buildTool     = 'bundle'
        tech.testFramework = 'RSpec'
        tech.linter        = 'rubocop'
        tech.securityTool  = 'brakeman'
        tech.framework     = 'Rails'
        tech.rootDir       = findFirstDir(findFirst('**/Gemfile'))
        tech.hasTests      = findFirst('**/spec/**/*.rb') != null
    }

    // Dockerfile
    tech.hasDockerfile = findFirst('**/Dockerfile') != null

    echo "✅ Detection completed:"
    echo "   Language       : ${tech.language}"
    echo "   Framework      : ${tech.framework}"
    echo "   Package Manager: ${tech.packageManager}"
    echo "   Build Tool     : ${tech.buildTool}"
    echo "   Test Framework : ${tech.testFramework}"
    echo "   Root Dir       : ${tech.rootDir}"
    echo "   Has Tests      : ${tech.hasTests}"
    echo "========================================="

    return tech
}

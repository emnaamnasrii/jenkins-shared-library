#!/usr/bin/env groovy

def call() {

    echo "🔍 Scanning repository to detect language and framework..."

    // Debug workspace
    sh '''
    echo "Workspace path:"
    pwd
    echo "Repository files:"
    ls -R
    '''

    def language = "unknown"
    def framework = ""

    // ========================
    // Python
    // ========================

    def pythonFiles =
        findFiles(glob: '**/*.py') +
        findFiles(glob: '**/requirements.txt') +
        findFiles(glob: '**/pyproject.toml') +
        findFiles(glob: '**/Pipfile')

    if (pythonFiles.size() > 0) {

        language = "python"

        if (fileExists("requirements.txt")) {

            def req = readFile("requirements.txt").toLowerCase()

            if (req.contains("fastapi")) {
                framework = "fastapi"
            }
            else if (req.contains("django")) {
                framework = "django"
            }
            else if (req.contains("flask")) {
                framework = "flask"
            }
            else if (req.contains("streamlit")) {
                framework = "streamlit"
            }
        }

        echo "✅ Detected Python ${framework ?: ''}"
        return [language: language, framework: framework]
    }

    // ========================
    // NodeJS
    // ========================

    def nodeFiles = findFiles(glob: '**/package.json')

    if (nodeFiles.size() > 0) {

        language = "nodejs"

        def pkg = readFile(nodeFiles[0].path).toLowerCase()

        if (pkg.contains("next")) framework = "nextjs"
        else if (pkg.contains("react")) framework = "react"
        else if (pkg.contains("express")) framework = "express"
        else if (pkg.contains("nestjs")) framework = "nestjs"

        echo "✅ Detected Node.js ${framework ?: ''}"
        return [language: language, framework: framework]
    }

    // ========================
    // Java Maven
    // ========================

    def mavenFiles = findFiles(glob: '**/pom.xml')

    if (mavenFiles.size() > 0) {

        language = "java-maven"

        def pom = readFile(mavenFiles[0].path).toLowerCase()

        if (pom.contains("spring-boot")) {
            framework = "springboot"
        }

        echo "✅ Detected Java Maven ${framework ?: ''}"
        return [language: language, framework: framework]
    }

    // ========================
    // Java Gradle
    // ========================

    def gradleFiles =
        findFiles(glob: '**/build.gradle') +
        findFiles(glob: '**/build.gradle.kts')

    if (gradleFiles.size() > 0) {

        language = "java-gradle"

        echo "✅ Detected Java Gradle"
        return [language: language]
    }

    // ========================
    // Go
    // ========================

    if (findFiles(glob: '**/go.mod').size() > 0) {

        language = "golang"

        echo "✅ Detected Go"
        return [language: language]
    }

    // ========================
    // PHP
    // ========================

    if (findFiles(glob: '**/composer.json').size() > 0) {

        language = "php"

        def composer = readFile(findFiles(glob: '**/composer.json')[0].path).toLowerCase()

        if (composer.contains("laravel")) {
            framework = "laravel"
        }

        echo "✅ Detected PHP ${framework ?: ''}"
        return [language: language, framework: framework]
    }

    // ========================
    // Ruby
    // ========================

    if (findFiles(glob: '**/Gemfile').size() > 0) {

        language = "ruby"

        echo "✅ Detected Ruby"
        return [language: language]
    }

    // ========================
    // .NET
    // ========================

    if (findFiles(glob: '**/*.csproj').size() > 0) {

        language = "dotnet"

        echo "✅ Detected .NET"
        return [language: language]
    }

    // ========================
    // Rust
    // ========================

    if (findFiles(glob: '**/Cargo.toml').size() > 0) {

        language = "rust"

        echo "✅ Detected Rust"
        return [language: language]
    }

    // ========================
    // C / C++
    // ========================

    if (findFiles(glob: '**/*.c').size() > 0 || findFiles(glob: '**/*.cpp').size() > 0) {

        language = "cpp"

        echo "✅ Detected C/C++"
        return [language: language]
    }

    // ========================
    // Kotlin
    // ========================

    if (findFiles(glob: '**/*.kt').size() > 0) {

        language = "kotlin"

        echo "✅ Detected Kotlin"
        return [language: language]
    }

    // ========================
    // Swift
    // ========================

    if (findFiles(glob: '**/*.swift').size() > 0) {

        language = "swift"

        echo "✅ Detected Swift"
        return [language: language]
    }

    // ========================
    // Unknown
    // ========================

    echo "⚠️ Could not detect language"
    return [language: "unknown"]
}

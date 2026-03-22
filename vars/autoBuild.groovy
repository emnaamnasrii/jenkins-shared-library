#!/usr/bin/env groovy

def call(Map config = [:]) {

    // ═══════════════════════════════════════════════════════════════
    // TECH DETECTION
    // ═══════════════════════════════════════════════════════════════
    
    def techStack = config.language ?: detectTech()
    def language = techStack.language
    def framework = techStack.framework
    
    def imageName = config.imageName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def imageTag = "${env.BUILD_NUMBER}"

    echo "========================================="
    echo "🚀 Build Backend Pipeline"
    echo "Language       : ${language}"
    echo "Framework      : ${framework}"
    echo "Package Manager: ${techStack.packageManager}"
    echo "Build Tool     : ${techStack.buildTool}"
    echo "Docker Image   : ${imageName}:${imageTag}"
    echo "========================================="


    // ═══════════════════════════════════════════════════════════════
    // DEBUG SOURCE
    // ═══════════════════════════════════════════════════════════════

    stage('🔎 Debug Source') {
        sh 'ls -la'
        sh 'pwd'
    }


    // ═══════════════════════════════════════════════════════════════
    // INSTALL DEPENDENCIES & BUILD
    // ═══════════════════════════════════════════════════════════════

    stage('📦 Install Dependencies & Build') {

        // PYTHON
        if (language == 'Python') {
            container('python') {
                sh '''
                set -e
                pip install --upgrade pip --quiet

                if [ -f requirements.txt ]; then
                    pip install -r requirements.txt --quiet
                elif [ -f setup.py ]; then
                    pip install -e . --quiet
                elif [ -f pyproject.toml ]; then
                    pip install . --quiet
                fi
                '''
            }
        }

        // NODE.JS
        else if (language == 'Node.js') {
            container('node') {
                sh '''
                set -e
                if [ -f package-lock.json ]; then
                    npm ci
                elif [ -f package.json ]; then
                    npm install
                fi
                '''
            }
        }

        // JAVA (Maven)
        else if (language == 'Java' && techStack.packageManager == 'Maven') {
            container('maven') {
                sh "mvn clean package -DskipTests -Dcheckstyle.skip=true"
            }
        }

        // JAVA (Gradle)
        else if (language == 'Java' && techStack.packageManager == 'Gradle') {
            container('gradle') {
                sh './gradlew clean build -x test'
            }
        }

        // GO
        else if (language == 'Go') {
            container('golang') {
                sh 'go mod download'
            }
        }

        // PHP
        else if (language == 'PHP') {
            container('php') {
                sh 'composer install --no-dev --optimize-autoloader'
            }
        }

        // RUBY
        else if (language == 'Ruby') {
            container('ruby') {
                sh 'bundle install --without development test'
            }
        }

        else {
            echo "⚠️ Unknown language: ${language}, skipping dependency installation"
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // GENERATE DOCKERFILE
    // ═══════════════════════════════════════════════════════════════

    stage('🐳 Generate Dockerfile') {
        container('docker') {

            if (!fileExists("Dockerfile")) {
                echo "⚠️ No Dockerfile found, generating automatically..."
                generateDockerfile(techStack)
            } else {
                echo "✅ Dockerfile already exists"
            }
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // BUILD IMAGE
    // ═══════════════════════════════════════════════════════════════

    stage('🐳 Build Docker Image') {
        container('docker') {

            sh """
            set -e
            echo "Building image: ${imageName}:${imageTag}"
            docker build -t ${imageName}:${imageTag} .
            docker tag ${imageName}:${imageTag} ${imageName}:latest
            echo "✅ Image built successfully"
            """
        }
    }


    // ═══════════════════════════════════════════════════════════════
    // PUSH IMAGE
    // ═══════════════════════════════════════════════════════════════

    stage('📤 Push Docker Image') {

        container('docker') {

            withCredentials([usernamePassword(
                credentialsId: 'dockerhub-creds',
                usernameVariable: 'DOCKER_USER',
                passwordVariable: 'DOCKER_PASS'
            )]) {

                sh '''
                set -e
                echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin
                '''

                sh """
                docker push ${imageName}:${imageTag}
                docker push ${imageName}:latest
                echo "✅ Image pushed to Docker Hub"
                """
            }
        }
    }

    return [
        imageName: imageName,
        imageTag: imageTag,
        fullImage: "${imageName}:${imageTag}"
    ]
}



// ═══════════════════════════════════════════════════════════════
// GENERATE DOCKERFILE (adapté au Map detectTech)
// ═══════════════════════════════════════════════════════════════

def generateDockerfile(techStack) {

    def language = techStack.language
    def framework = techStack.framework
    def dockerfileContent = ""

    // ───────────────────────────────────────────────────────────────
    // PYTHON
    // ───────────────────────────────────────────────────────────────
    
    if (language == "Python") {

        def entryFile = detectPythonEntry()
        def pythonFramework = detectPythonFramework()

        echo "Detected Python entry: ${entryFile}"
        echo "Detected Python framework: ${pythonFramework}"

        def module = entryFile.replace(".py","").replace("/",".")
        def entryPath = entryFile

        if (pythonFramework == "fastapi" || framework == "FastAPI") {
            dockerfileContent = """
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt* ./

RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

CMD ["uvicorn","${module}:app","--host","0.0.0.0","--port","8000"]
"""
        }

        else if (pythonFramework == "django" || framework == "Django") {
            dockerfileContent = """
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt* ./

RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 8000

CMD ["python","manage.py","runserver","0.0.0.0:8000"]
"""
        }

        else if (pythonFramework == "flask" || framework == "Flask") {
            dockerfileContent = """
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt* ./

RUN pip install --no-cache-dir -r requirements.txt

COPY . .

EXPOSE 5000

CMD ["python","${entryPath}"]
"""
        }

        else {
            dockerfileContent = """
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt* setup.py* pyproject.toml* ./

RUN pip install --no-cache-dir -r requirements.txt 2>/dev/null || true

COPY . .

EXPOSE 5000

CMD ["python","${entryPath}"]
"""
        }
    }

    // ───────────────────────────────────────────────────────────────
    // NODE.JS
    // ───────────────────────────────────────────────────────────────

    else if (language == "Node.js") {

        if (framework == "Express") {
            dockerfileContent = """
FROM node:18-alpine

WORKDIR /app

COPY package*.json ./

RUN npm install --production

COPY . .

EXPOSE 3000

CMD ["npm","start"]
"""
        }

        else if (framework == "NestJS") {
            dockerfileContent = """
FROM node:18-alpine

WORKDIR /app

COPY package*.json ./

RUN npm install --production

COPY . .

RUN npm run build || true

EXPOSE 3000

CMD ["npm","run","start:prod"]
"""
        }

        else {
            dockerfileContent = """
FROM node:18-alpine

WORKDIR /app

COPY package*.json ./

RUN npm install --production

COPY . .

EXPOSE 3000

CMD ["npm","start"]
"""
        }
    }

    // ───────────────────────────────────────────────────────────────
    // JAVA (Maven ou Gradle)
    // ───────────────────────────────────────────────────────────────

    else if (language == "Java") {

        if (techStack.packageManager == 'Maven') {
            dockerfileContent = """
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .

COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-Xms128m","-Xmx512m","-jar","app.jar"]
"""
        }

        else if (techStack.packageManager == 'Gradle') {
            dockerfileContent = """
FROM gradle:8.5-jdk17-alpine AS build

WORKDIR /app

COPY build.gradle* settings.gradle* ./

COPY src ./src

RUN gradle clean build -x test --no-daemon

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=build /app/build/libs/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-Xms128m","-Xmx512m","-jar","app.jar"]
"""
        }

        else {
            // Default Maven
            dockerfileContent = """
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .

COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-Xms128m","-Xmx512m","-jar","app.jar"]
"""
        }
    }

    // ───────────────────────────────────────────────────────────────
    // GO
    // ───────────────────────────────────────────────────────────────

    else if (language == "Go") {
        dockerfileContent = """
FROM golang:1.21-alpine AS builder

WORKDIR /app

COPY go.* ./

RUN go mod download

COPY . .

RUN go build -o main .

FROM alpine:latest

WORKDIR /app

COPY --from=builder /app/main .

EXPOSE 8080

CMD ["./main"]
"""
    }

    // ───────────────────────────────────────────────────────────────
    // PHP
    // ───────────────────────────────────────────────────────────────

    else if (language == "PHP") {
        dockerfileContent = """
FROM php:8.2-apache

WORKDIR /var/www/html

COPY . .

RUN chown -R www-data:www-data /var/www/html

EXPOSE 80

CMD ["apache2-foreground"]
"""
    }

    // ───────────────────────────────────────────────────────────────
    // RUBY
    // ───────────────────────────────────────────────────────────────

    else if (language == "Ruby") {
        dockerfileContent = """
FROM ruby:3.2-alpine

WORKDIR /app

COPY Gemfile* ./

RUN bundle install

COPY . .

EXPOSE 3000

CMD ["ruby", "app.rb"]
"""
    }

    // ───────────────────────────────────────────────────────────────
    // UNKNOWN
    // ───────────────────────────────────────────────────────────────

    else {
        echo "❌ ERROR: Unknown language '${language}'"
        echo "Supported: Java, Python, Node.js, Go, PHP, Ruby"
        error("Cannot generate Dockerfile for unknown language: ${language}")
    }

    writeFile file: 'Dockerfile', text: dockerfileContent

    echo "✅ Dockerfile generated for ${language}"
    sh 'cat Dockerfile'
}



// ═══════════════════════════════════════════════════════════════
// PYTHON HELPERS
// ═══════════════════════════════════════════════════════════════

def detectPythonEntry() {
    def candidates = ["app.py","main.py","server.py","run.py"]

    for (f in candidates) {
        def files = findFiles(glob: "**/${f}")
        if (files.size() > 0) {
            return files[0].path
        }
    }

    def files = findFiles(glob: "**/*.py")
    return files.size() > 0 ? files[0].path : "app.py"
}

def detectPythonFramework() {
    if (!fileExists("requirements.txt")) {
        return ""
    }

    def req = readFile("requirements.txt").toLowerCase()

    if (req.contains("fastapi")) return "fastapi"
    if (req.contains("django")) return "django"
    if (req.contains("flask")) return "flask"

    return ""
}

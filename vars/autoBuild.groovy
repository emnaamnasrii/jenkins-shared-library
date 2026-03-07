#!/usr/bin/env groovy

def call(Map config = [:]) {

    def language = config.language ?: detectLanguage()
    def imageName = config.imageName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def imageTag = "${env.BUILD_NUMBER}"

    echo "========================================="
    echo "🚀 AutoBuild Pipeline"
    echo "Detected Language: ${language}"
    echo "Docker Image: ${imageName}:${imageTag}"
    echo "========================================="


    // =========================================
    // INSTALL DEPENDENCIES
    // =========================================

    stage('📦 Install Dependencies') {

        if (language == 'python') {
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
        } else if (language == 'nodejs') {
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
        } else if (language == 'java-maven') {
container('maven') {
        sh "mvn clean package -DskipTests -Dcheckstyle.skip=true"
    }
        } else if (language == 'java-gradle') {
            container('gradle') { sh './gradlew clean build -x test' }
        } else if (language == 'golang') {
            container('golang') { sh 'go mod download' }
        } else if (language == 'php') {
            container('php') { sh 'composer install --no-dev --optimize-autoloader' }
        } else if (language == 'ruby') {
            container('ruby') { sh 'bundle install --without development test' }
        } else {
            echo "⚠️ Unknown language, skipping dependency installation"
        }
    }


    // =========================================
    // GENERATE DOCKERFILE
    // =========================================

    stage('🐳 Generate Dockerfile') {
        container('docker') {
            if (!fileExists("Dockerfile")) {
                echo "⚠️ No Dockerfile found, generating automatically..."
                generateDockerfile(language)
            } else {
                echo "Dockerfile already exists"
            }
        }
    }


    // =========================================
    // BUILD DOCKER IMAGE
    // =========================================

    stage('🐳 Build Docker Image') {
        container('docker') {
            sh """
            set -e
            docker build -t ${imageName}:${imageTag} .
            docker tag ${imageName}:${imageTag} ${imageName}:latest
            """
        }
    }


    // =========================================
    // PUSH IMAGE
    // =========================================

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


// =====================================================
// GENERATE DOCKERFILE AUTOMATICALLY
// =====================================================

def generateDockerfile(language) {

    def dockerfileContent = ""

    // =========================
    // PYTHON
    // =========================
    if (language == "python") {

        def entryFile = detectPythonEntry()
        def framework = detectPythonFramework()

        if (framework == "fastapi") {
            dockerfileContent = """
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt* ./
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["uvicorn","${entryFile.replace('.py','')}:app","--host","0.0.0.0","--port","8000"]
"""
        } else if (framework == "django") {
            dockerfileContent = """
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt* ./
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 8000
CMD ["python","manage.py","runserver","0.0.0.0:8000"]
"""
        } else {
            dockerfileContent = """
FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt* setup.py* pyproject.toml* ./
RUN pip install --no-cache-dir -r requirements.txt 2>/dev/null || true
COPY . .
EXPOSE 5000
CMD ["python","${entryFile}"]
"""
        }
    }

    // =========================
    // NODEJS
    // =========================
    else if (language == "nodejs") {
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

    // =========================
    // JAVA
    // =========================
    else if (language == "java-maven" || language == "java-gradle") {
        dockerfileContent = """
FROM openjdk:17-jdk-slim
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
"""
    }

    // =========================
    // GO
    // =========================
    else if (language == "golang") {
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

    // =========================
    // PHP
    // =========================
    else if (language == "php") {
        dockerfileContent = """
FROM php:8.2-apache
WORKDIR /var/www/html
COPY . .
RUN docker-php-ext-install pdo pdo_mysql
EXPOSE 80
"""
    }

    // =========================
    // RUBY
    // =========================
    else if (language == "ruby") {
        dockerfileContent = """
FROM ruby:3.2-slim
WORKDIR /app
COPY Gemfile* ./
RUN bundle install
COPY . .
EXPOSE 3000
CMD ["rails","server","-b","0.0.0.0"]
"""
    }

    // =========================
    // UNKNOWN
    // =========================
    else {
        dockerfileContent = """
FROM alpine:latest
WORKDIR /app
COPY . .
CMD ["echo","Unknown application type"]
"""
    }

    writeFile file: 'Dockerfile', text: dockerfileContent
    echo "✅ Dockerfile generated automatically for ${language}"
}


// =========================
// Helpers for Python
// =========================
def detectPythonEntry() {
    def candidates = ["app.py", "main.py", "server.py"]
    for (f in candidates) {
        if (fileExists(f)) return f
    }
    // fallback
    def files = findFiles(glob: "*.py")
    return files.size() > 0 ? files[0].name : "app.py"
}

def detectPythonFramework() {
    if (!fileExists("requirements.txt")) return ""
    def req = readFile("requirements.txt").toLowerCase()
    if (req.contains("fastapi")) return "fastapi"
    if (req.contains("django")) return "django"
    if (req.contains("flask")) return "flask"
    return ""
}

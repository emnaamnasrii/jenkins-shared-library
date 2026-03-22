#!/usr/bin/env groovy

def call(Map config = [:]) {

    def language = config.language ?: detectTech()
    def imageName = config.imageName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def imageTag = "${env.BUILD_NUMBER}"

    echo "========================================="
    echo "🚀 AutoBuild Pipeline"
    echo "Detected Language: ${language}"
    echo "Docker Image: ${imageName}:${imageTag}"
    echo "========================================="


    // =========================================
    // DEBUG SOURCE
    // =========================================

    stage('🔎 Debug Source') {
        sh 'ls -R'
    }


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
        }

        else if (language == 'nodejs') {
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

        else if (language == 'java-maven') {
            container('maven') {
                sh "mvn clean package -DskipTests -Dcheckstyle.skip=true"
            }
        }

        else if (language == 'java-gradle') {
            container('gradle') {
                sh './gradlew clean build -x test'
            }
        }

        else if (language == 'golang') {
            container('golang') {
                sh 'go mod download'
            }
        }

        else if (language == 'php') {
            container('php') {
                sh 'composer install --no-dev --optimize-autoloader'
            }
        }

        else if (language == 'ruby') {
            container('ruby') {
                sh 'bundle install --without development test'
            }
        }

        else {
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
    // BUILD IMAGE
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



//////////////////////////////////////////////////////////
// GENERATE DOCKERFILE
//////////////////////////////////////////////////////////

def generateDockerfile(language) {

    def dockerfileContent = ""

    if (language == "python") {

        def entryFile = detectPythonEntry()
        def framework = detectPythonFramework()

        echo "Detected Python entry: ${entryFile}"
        echo "Detected framework: ${framework}"

        def module = entryFile.replace(".py","").replace("/",".")
        def entryPath = entryFile

        if (framework == "fastapi") {

            dockerfileContent = """
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt* ./

RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN ls -R /app

EXPOSE 8000

CMD ["uvicorn","${module}:app","--host","0.0.0.0","--port","8000"]
"""

        }

        else if (framework == "django") {

            dockerfileContent = """
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt* ./

RUN pip install --no-cache-dir -r requirements.txt

COPY . .

RUN ls -R /app

EXPOSE 8000

CMD ["python","manage.py","runserver","0.0.0.0:8000"]
"""

        }

        else {

            dockerfileContent = """
FROM python:3.11-slim

WORKDIR /app

COPY requirements.txt* setup.py* pyproject.toml* ./

RUN pip install --no-cache-dir -r requirements.txt 2>/dev/null || true

COPY . .

RUN ls -R /app

EXPOSE 5000

CMD ["python","${entryPath}"]
"""

        }
    }


    //////////////////////////////////////////////////
    // NODEJS
    //////////////////////////////////////////////////

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


    //////////////////////////////////////////////////
    // JAVA
    //////////////////////////////////////////////////

    else if (language == "java-maven" || language == "java-gradle") {

        dockerfileContent = """
FROM maven:3.9.9-eclipse-temurin-17 AS build

WORKDIR /app

COPY pom.xml .

COPY src ./src

RUN mvn clean package -DskipTests

FROM eclipse-temurin:17-jdk

WORKDIR /app

COPY --from=build /app/target/*.jar app.jar

EXPOSE 8080

ENTRYPOINT ["java","-Xms128m","-Xmx384m","-jar","app.jar"]
"""
    }


    //////////////////////////////////////////////////
    // GO
    //////////////////////////////////////////////////

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


    //////////////////////////////////////////////////
    // DEFAULT
    //////////////////////////////////////////////////

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



//////////////////////////////////////////////////////////
// PYTHON HELPERS
//////////////////////////////////////////////////////////

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

    if (req.contains("fastapi")) {
        return "fastapi"
    }

    if (req.contains("django")) {
        return "django"
    }

    if (req.contains("flask")) {
        return "flask"
    }

    return ""
}

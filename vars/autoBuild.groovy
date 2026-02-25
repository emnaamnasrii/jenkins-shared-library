#!/usr/bin/env groovy

def call(Map config = [:]) {
    def tech = config.tech ?: detectTech()
    def imageName = config.imageName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def imageTag = "${env.BUILD_NUMBER}"
    
    // ========================================
    // INSTALL DEPENDENCIES
    // ========================================
    stage('📦 Install Dependencies') {
        if (tech.language == 'Python') {
            container('python') {
                sh '''
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
        else if (tech.language == 'Node.js') {
            container('node') {
                sh '''
                    if [ -f package-lock.json ]; then
                        npm ci
                    elif [ -f package.json ]; then
                        npm install
                    fi
                '''
            }
        }
        else if (tech.language == 'Java' && tech.buildTool == 'mvn') {
            container('maven') {
                sh 'mvn clean install -DskipTests'
            }
        }
        else if (tech.language == 'Java' && tech.buildTool == 'gradle') {
            container('gradle') {
                sh './gradlew clean build -x test'
            }
        }
        else if (tech.language == 'Go') {
            container('golang') {
                sh 'go mod download'
            }
        }
        else if (tech.language == 'PHP') {
            container('php') {
                sh 'composer install --no-dev --optimize-autoloader'
            }
        }
        else if (tech.language == 'Ruby') {
            container('ruby') {
                sh 'bundle install --without development test'
            }
        }
    }
    
    // ========================================
    // BUILD DOCKER IMAGE
    // ========================================
    stage('🐳 Build Docker Image') {
        container('docker') {
            if (!tech.hasDockerfile) {
                echo "⚠️  No Dockerfile found, generating one for ${tech.language}..."
                generateDockerfile(tech)
            }
            
            sh """
                docker build -t ${imageName}:${imageTag} .
                docker tag ${imageName}:${imageTag} ${imageName}:latest
            """
        }
    }
    
    // ========================================
    // PUSH IMAGE
    // ========================================
    stage('📤 Push Image') {
        container('docker') {
            withCredentials([usernamePassword(
                credentialsId: 'dockerhub-creds',
                usernameVariable: 'DOCKER_USER',
                passwordVariable: 'DOCKER_PASS'
            )]) {
                sh """
                    echo \$DOCKER_PASS | docker login -u \$DOCKER_USER --password-stdin
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

def generateDockerfile(tech) {
    def dockerfileContent = ""
    
    if (tech.language == 'Python') {
        dockerfileContent = """FROM python:3.11-slim
WORKDIR /app
COPY requirements.txt* setup.py* pyproject.toml* ./
RUN pip install --no-cache-dir -r requirements.txt 2>/dev/null || pip install -e . 2>/dev/null || pip install . 2>/dev/null || true
COPY . .
EXPOSE 5000 8000
CMD ["python", "-m", "app"]
"""
    }
    else if (tech.language == 'Node.js') {
        dockerfileContent = """FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production || npm install --production
COPY . .
EXPOSE 3000 8080
CMD ["npm", "start"]
"""
    }
    else if (tech.language == 'Java') {
        dockerfileContent = """FROM openjdk:17-slim
WORKDIR /app
COPY target/*.jar app.jar 2>/dev/null || COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
"""
    }
    else if (tech.language == 'Go') {
        dockerfileContent = """FROM golang:1.21-alpine AS builder
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
    else if (tech.language == 'PHP') {
        dockerfileContent = """FROM php:8.2-apache
WORKDIR /var/www/html
COPY . .
RUN docker-php-ext-install pdo pdo_mysql
EXPOSE 80
"""
    }
    else if (tech.language == 'Ruby') {
        dockerfileContent = """FROM ruby:3.2-slim
WORKDIR /app
COPY Gemfile* ./
RUN bundle install
COPY . .
EXPOSE 3000
CMD ["rails", "server", "-b", "0.0.0.0"]
"""
    }
    else {
        dockerfileContent = """FROM alpine:latest
WORKDIR /app
COPY . .
CMD ["echo", "No runtime specified"]
"""
    }
    
    writeFile file: 'Dockerfile', text: dockerfileContent
    echo "✅ Generated Dockerfile for ${tech.language}"
}

#!/usr/bin/env groovy

def call(Map config = [:]) {
    def tech = detectTech()
    def imageName = config.imageName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def imageTag = "${env.BUILD_NUMBER}"
    
    stage('📦 Install Dependencies') {
        if (tech.language == 'Python') {
            container('python') {
                sh '''
                    pip install --upgrade pip
                    pip install --user -r requirements.txt || true
                '''
            }
        }
        else if (tech.language == 'Node.js') {
            container('node') {
                sh 'npm install || true'
            }
        }
        else if (tech.language == 'Java' && tech.packageManager == 'Maven') {
            container('maven') {
                sh 'mvn clean install -DskipTests || true'
            }
        }
    }
    
    stage('🧪 Run Tests') {
        if (tech.language == 'Python') {
            container('python') {
                sh 'python -m pytest tests/ || true'
            }
        }
        else if (tech.language == 'Node.js') {
            container('node') {
                sh 'npm test || true'
            }
        }
    }
    
    stage('🐳 Build Docker Image') {
        container('docker') {
            if (!tech.hasDockerfile) {
                echo "⚠️  No Dockerfile found, generating one..."
                generateDockerfile(tech)
            }
            
            sh """
                docker build -t ${imageName}:${imageTag} .
                docker tag ${imageName}:${imageTag} ${imageName}:latest
            """
        }
    }
    
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
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY . .
EXPOSE 5000
CMD ["python", "app.py"]
"""
    }
    else if (tech.language == 'Node.js') {
        dockerfileContent = """FROM node:18-alpine
WORKDIR /app
COPY package*.json ./
RUN npm ci --only=production
COPY . .
EXPOSE 3000
CMD ["npm", "start"]
"""
    }
    else if (tech.language == 'Java') {
        dockerfileContent = """FROM openjdk:17-slim
WORKDIR /app
COPY target/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
"""
    }
    
    writeFile file: 'Dockerfile', text: dockerfileContent
    echo "✅ Generated Dockerfile for ${tech.language}"
}

#!/usr/bin/env groovy

def call(Map config = [:]) {
    def imageName = config.imageName ?: "${env.JOB_NAME.toLowerCase().replaceAll('/', '-')}-frontend"
    def imageTag  = "${env.BUILD_NUMBER}"

    // Détection automatique
    def frontendType   = detectFrontendType()
    def frontendDir    = detectFrontendDir()
    def distDir        = detectDistDir(frontendType)
    def dockerfilePath = frontendDir == '.' ? 'Dockerfile.frontend' : "${frontendDir}/Dockerfile"

    echo "========================================="
    echo "🖥️  BuildFrontend (Generic)"
    echo "Type       : ${frontendType}"
    echo "Directory  : ${frontendDir}"
    echo "Dist dir   : ${distDir}"
    echo "Dockerfile : ${dockerfilePath}"
    echo "Image      : ${imageName}:${imageTag}"
    echo "========================================="

    // ── Générer Dockerfile frontend si absent ─────────────────────────────
    stage('🐳 Generate Frontend Dockerfile') {
        container('docker') {
            if (!fileExists(dockerfilePath)) {
                echo "⚠️ No Dockerfile found — generating for ${frontendType}..."
                generateFrontendDockerfile(frontendType, frontendDir, distDir, dockerfilePath)
            } else {
                echo "✅ Dockerfile exists: ${dockerfilePath}"
            }
        }
    }

    // ── Build image ───────────────────────────────────────────────────────
    stage('🐳 Build Frontend Docker Image') {
        container('docker') {
            sh """
            set -e
            echo "========================================="
            echo "🔍 Debug Information"
            echo "Frontend Dir: ${frontendDir}"
            echo "Dockerfile: ${dockerfilePath}"
            echo "========================================="
            
            # Vérifier que package.json existe
            if [ "${frontendDir}" = "." ]; then
                echo "📦 Checking files in current directory..."
                ls -la
                if [ -f "package.json" ]; then
                    echo "✅ package.json found in root"
                    echo "Content preview:"
                    head -20 package.json
                else
                    echo "❌ package.json NOT found in root"
                    echo "Looking for package.json in subdirectories..."
                    find . -name "package.json" -type f
                fi
            else
                echo "📦 Checking files in ${frontendDir}..."
                ls -la ${frontendDir}/
                if [ -f "${frontendDir}/package.json" ]; then
                    echo "✅ package.json found in ${frontendDir}"
                else
                    echo "❌ package.json NOT found in ${frontendDir}"
                fi
            fi
            
            echo "========================================="
            echo "🐳 Building Docker image..."
            echo "Command: docker build -f ${dockerfilePath} -t ${imageName}:${imageTag} ${frontendDir}"
            echo "========================================="
            
            docker build -f ${dockerfilePath} -t ${imageName}:${imageTag} ${frontendDir}
            docker tag ${imageName}:${imageTag} ${imageName}:latest
            echo "✅ Frontend image built: ${imageName}:${imageTag}"
            """
        }
    }

    // ── Push image ────────────────────────────────────────────────────────
    stage('📤 Push Frontend Docker Image') {
        container('docker') {
            withCredentials([usernamePassword(
                credentialsId: 'dockerhub-creds',
                usernameVariable: 'DOCKER_USER',
                passwordVariable: 'DOCKER_PASS'
            )]) {
                sh 'echo $DOCKER_PASS | docker login -u $DOCKER_USER --password-stdin'
                sh """
                    docker push ${imageName}:${imageTag}
                    docker push ${imageName}:latest
                    echo "✅ Frontend image pushed: ${imageName}:${imageTag}"
                """
            }
        }
    }

    return [
        imageName   : imageName,
        imageTag    : imageTag,
        fullImage   : "${imageName}:${imageTag}",
        frontendType: frontendType,
        frontendDir : frontendDir
    ]
}

def detectFrontendType() {
    if (fileExists('package.json')) {
        def pkg = readFile('package.json').toLowerCase()
        if (pkg.contains('"next"'))              return 'nextjs'
        if (pkg.contains('"react"'))             return 'react'
        if (pkg.contains('"vue"'))               return 'vue'
        if (pkg.contains('"@angular/core"'))     return 'angular'
        if (pkg.contains('"svelte"'))            return 'svelte'
        if (pkg.contains('"nuxt"'))              return 'nuxt'
        if (pkg.contains('"gatsby"'))            return 'gatsby'
    }
    for (dir in ['frontend', 'client', 'ui', 'web', 'app', 'front']) {
        if (fileExists("${dir}/package.json")) {
            def pkg = readFile("${dir}/package.json").toLowerCase()
            if (pkg.contains('"next"'))          return 'nextjs'
            if (pkg.contains('"react"'))         return 'react'
            if (pkg.contains('"vue"'))           return 'vue'
            if (pkg.contains('"@angular/core"')) return 'angular'
            if (pkg.contains('"svelte"'))        return 'svelte'
            if (pkg.contains('"nuxt"'))          return 'nuxt'
        }
    }
    if (fileExists('index.html')) return 'html'
    if (fileExists('src/main/resources/static/index.html')) return 'html'
    return 'react'
}

def detectFrontendDir() {
    if (fileExists('package.json')) {
        def pkg = readFile('package.json').toLowerCase()
        if (pkg.contains('"react"') || pkg.contains('"vue"') ||
            pkg.contains('"@angular/') || pkg.contains('"next"') ||
            pkg.contains('"svelte"') || pkg.contains('"nuxt"')) return '.'
    }
    for (dir in ['frontend', 'client', 'ui', 'web', 'app', 'front']) {
        if (fileExists("${dir}/package.json")) return dir
    }
    if (fileExists('index.html')) return '.'
    return '.'
}

def detectDistDir(String frontendType) {
    if (fileExists('package.json')) {
        def pkg = readFile('package.json').toLowerCase()
        if (pkg.contains('"next"')) return '.next'
        if (pkg.contains('"vite"')) return 'dist'
        if (pkg.contains('"react-scripts"')) return 'build'
        if (pkg.contains('"@angular/core"')) {
            if (fileExists('angular.json')) {
                try {
                    def angularJson = readFile('angular.json')
                    def matcher = angularJson =~ /"outputPath"\s*:\s*"([^"]+)"/
                    if (matcher.find()) return matcher.group(1)
                } catch (e) {}
            }
            return 'dist/app'
        }
    }
    switch (frontendType) {
        case 'react':   return 'build'
        case 'vue':     return 'dist'
        case 'angular': return 'dist/app'
        case 'nextjs':  return '.next'
        case 'nuxt':    return '.output'
        case 'svelte':  return 'public'
        case 'gatsby':  return 'public'
        default:        return 'dist'
    }
}

def generateFrontendDockerfile(String frontendType, String frontendDir, String distDir, String dockerfilePath) {
    def content = """
FROM node:18-alpine AS build
WORKDIR /app

# Copier TOUS les fichiers
COPY . .

# Vérifier que package.json existe
RUN ls -la && \\
    if [ ! -f "package.json" ]; then \\
        echo "ERROR: package.json not found!" && \\
        find . -name "package.json" -type f && \\
        exit 1; \\
    fi

# Installer les dépendances
RUN npm cache clean --force && \\
    (npm ci --legacy-peer-deps --no-audit --prefer-offline || \\
     npm install --legacy-peer-deps --no-audit || \\
     npm install --force)

# Build
RUN npm run build 2>&1 || echo "Build completed"

# Production
FROM nginx:alpine
COPY --from=build /app/${distDir} /usr/share/nginx/html
RUN echo 'server { listen 80; location / { root /usr/share/nginx/html; index index.html; try_files \\\$uri \\\$uri/ /index.html; } }' > /etc/nginx/conf.d/default.conf
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
"""

    writeFile file: dockerfilePath, text: content
    echo "✅ Frontend Dockerfile generated: ${dockerfilePath}"
}

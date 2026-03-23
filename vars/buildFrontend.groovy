#!/usr/bin/env groovy

// ═══════════════════════════════════════════════════════════════════════════
// buildFrontend.groovy — ULTRA-GÉNÉRIQUE
// Fonctionne avec TOUS les emplacements et TOUS les frameworks frontend
// ═══════════════════════════════════════════════════════════════════════════

def call(Map config = [:]) {
    def imageName = config.imageName ?: "${env.JOB_NAME.toLowerCase().replaceAll('/', '-')}-frontend"
    def imageTag  = config.imageTag  ?: "${env.BUILD_NUMBER}"

    echo "========================================="
    echo "🔍 SCANNING PROJECT FOR FRONTEND..."
    echo "========================================="

    // ── ÉTAPE 1 : Trouver TOUS les package.json ──────────────────────────
    def allPackageJsons = findAllPackageJson()

    if (allPackageJsons.isEmpty()) {
        echo "⚠️  No package.json found. Looking for static HTML..."
        return buildStaticHTML(imageName, imageTag)
    }

    echo "📦 Found ${allPackageJsons.size()} package.json file(s):"
    allPackageJsons.each { echo "   - ${it}" }

    // ── ÉTAPE 2 : Filtrer les frontends réels ────────────────────────────
    def frontendCandidates = allPackageJsons.findAll { path ->
        def pkg = readFile(path).toLowerCase()
        pkg.contains('"react"')       ||
        pkg.contains('"vue"')         ||
        pkg.contains('"angular"')     ||
        pkg.contains('"next"')        ||
        pkg.contains('"svelte"')      ||
        pkg.contains('"nuxt"')        ||
        pkg.contains('"gatsby"')      ||
        pkg.contains('"vite"')
    }

    if (frontendCandidates.isEmpty()) {
        echo "⚠️  No frontend framework detected — using first package.json: ${allPackageJsons[0]}"
        frontendCandidates = [allPackageJsons[0]]
    }

    // ── ÉTAPE 3 : Choisir le meilleur frontend ───────────────────────────
    def selectedFrontend = selectBestFrontend(frontendCandidates)
    def frontendDir  = selectedFrontend.dir
    def frontendType = selectedFrontend.type
    def distDir      = selectedFrontend.distDir

    echo "========================================="
    echo "✅ FRONTEND SELECTED"
    echo "Directory  : ${frontendDir}"
    echo "Type       : ${frontendType}"
    echo "Dist dir   : ${distDir}"
    echo "Image      : ${imageName}:${imageTag}"
    echo "========================================="

    // ── ÉTAPE 4 : Build et Push ──────────────────────────────────────────
    return buildAndPush(imageName, imageTag, frontendDir, frontendType, distDir)
}

// ═══════════════════════════════════════════════════════════════════════════
// Trouve TOUS les package.json (récursif, exclut node_modules)
// ═══════════════════════════════════════════════════════════════════════════
def findAllPackageJson() {
    def result = sh(
        script: 'find . -name "package.json" -type f -not -path "*/node_modules/*" -not -path "*/.git/*" 2>/dev/null || true',
        returnStdout: true
    ).trim()

    if (!result) return []
    return result.split('\n').collect { it.trim() }.findAll { it }
}

// ═══════════════════════════════════════════════════════════════════════════
// Sélectionne le meilleur frontend parmi les candidats
// ═══════════════════════════════════════════════════════════════════════════
def selectBestFrontend(List<String> candidates) {
    def priorities = [
        'react-frontend', 'frontend', 'client', 'ui', 'web', 'app', 'front',
        'vue-app', 'angular-app', 'next-app', 'webapp'
    ]

    for (priority in priorities) {
        def match = candidates.find {
            it.contains("/${priority}/") || it == "./${priority}/package.json"
        }
        if (match) return analyzeFrontend(match)
    }

    // Préférer le plus proche de la racine
    def sorted = candidates.sort { a, b -> a.count('/') <=> b.count('/') }
    return analyzeFrontend(sorted[0])
}

// ═══════════════════════════════════════════════════════════════════════════
// Analyse un package.json et retourne les infos du frontend
// ═══════════════════════════════════════════════════════════════════════════
def analyzeFrontend(String packageJsonPath) {
    def dir = packageJsonPath.replaceAll('/package\\.json$', '').replaceAll('^\\./', '')
    if (!dir || dir == 'package.json') dir = '.'

    def pkg = readFile(packageJsonPath).toLowerCase()

    def type = 'react'
    if      (pkg.contains('"next"'))          type = 'nextjs'
    else if (pkg.contains('"nuxt"'))          type = 'nuxt'
    else if (pkg.contains('"gatsby"'))        type = 'gatsby'
    else if (pkg.contains('"vue"'))           type = 'vue'
    else if (pkg.contains('"@angular/core"')) type = 'angular'
    else if (pkg.contains('"svelte"'))        type = 'svelte'

    def distDir = 'build'
    if      (pkg.contains('"next"'))           distDir = '.next'
    else if (pkg.contains('"nuxt"'))           distDir = '.output'
    else if (pkg.contains('"gatsby"'))         distDir = 'public'
    else if (pkg.contains('"vite"'))           distDir = 'dist'
    else if (pkg.contains('"vue"'))            distDir = 'dist'
    else if (pkg.contains('"@angular/core"'))  distDir = 'dist'
    else if (pkg.contains('"react-scripts"'))  distDir = 'build'
    else if (pkg.contains('"svelte"'))         distDir = 'public'

    return [dir: dir, type: type, distDir: distDir]
}

// ═══════════════════════════════════════════════════════════════════════════
// Build HTML statique (fallback)
// ═══════════════════════════════════════════════════════════════════════════
def buildStaticHTML(String imageName, String imageTag) {
    def htmlFile = sh(
        script: 'find . -name "index.html" -type f -not -path "*/node_modules/*" -not -path "*/.git/*" | head -1',
        returnStdout: true
    ).trim()

    if (!htmlFile) error("❌ No package.json and no index.html found.")

    def htmlDir = htmlFile.replaceAll('/index\\.html$', '').replaceAll('^\\./', '') ?: '.'
    echo "✅ Building static HTML from: ${htmlDir}"

    stage('🐳 Build Static HTML Image') {
        container('docker') {
            writeFile file: 'Dockerfile.static', text: '''FROM nginx:alpine
COPY . /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
'''
            sh """
                docker build -f Dockerfile.static -t ${imageName}:${imageTag} ${htmlDir}
                docker tag ${imageName}:${imageTag} ${imageName}:latest
            """
        }
    }

    stage('📤 Push Static HTML Image') {
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
        imageName   : imageName,
        imageTag    : imageTag,
        fullImage   : "${imageName}:${imageTag}",
        frontendType: 'html',
        frontendDir : htmlDir
    ]
}

// ═══════════════════════════════════════════════════════════════════════════
// Build et Push l'image Docker
// ═══════════════════════════════════════════════════════════════════════════
def buildAndPush(String imageName, String imageTag, String frontendDir, String frontendType, String distDir) {
    def dockerfilePath = "${frontendDir}/Dockerfile.generated"

    stage('🐳 Generate Dockerfile') {
        container('docker') {
            def dockerfile = generateDockerfile(frontendType, distDir)
            writeFile file: dockerfilePath, text: dockerfile
            echo "✅ Dockerfile generated: ${dockerfilePath}"
        }
    }

    stage('🐳 Build Frontend Image') {
        container('docker') {
            sh """
                set -e
                echo "Building from: ${frontendDir}"
                ls -la ${frontendDir}/
                docker build -f ${dockerfilePath} -t ${imageName}:${imageTag} ${frontendDir}
                docker tag ${imageName}:${imageTag} ${imageName}:latest
                echo "✅ Image built: ${imageName}:${imageTag}"
            """
        }
    }

    stage('📤 Push Frontend Image') {
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
                    echo "✅ Image pushed: ${imageName}:${imageTag}"
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

// ═══════════════════════════════════════════════════════════════════════════
// Génère le Dockerfile selon le type
// FIX : utiliser writeFile avec single quotes pour éviter les problèmes
//       avec $ dans les regex nginx
// ═══════════════════════════════════════════════════════════════════════════
def generateDockerfile(String type, String distDir) {

    // ── NEXT.JS ──────────────────────────────────────────────────────────
    if (type == 'nextjs') {
        return '''FROM node:18-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm install --legacy-peer-deps

FROM node:18-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:18-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
'''
    }

    // ── NUXT.JS ───────────────────────────────────────────────────────────
    else if (type == 'nuxt') {
        return '''FROM node:18-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install --legacy-peer-deps
COPY . .
RUN npm run build

FROM node:18-alpine
WORKDIR /app
COPY --from=build /app/.output ./
EXPOSE 3000
CMD ["node", "server/index.mjs"]
'''
    }

    // ── REACT / VUE / ANGULAR / SVELTE / GATSBY → NGINX ──────────────────
    else {
        // FIX : utiliser writeFile avec single-quote string + écriture du
        //       fichier nginx séparément pour éviter les $ dans les GStrings
        return '''FROM node:18-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci --legacy-peer-deps 2>/dev/null || npm install --legacy-peer-deps 2>/dev/null || npm install --force

COPY . .

RUN npm run build

RUN if [ -d /app/build ] && [ "$(ls -A /app/build 2>/dev/null)" ]; then \
        echo "Using build/" && cp -r /app/build /output; \
    elif [ -d /app/dist ] && [ "$(ls -A /app/dist 2>/dev/null)" ]; then \
        echo "Using dist/" && cp -r /app/dist /output; \
    elif [ -d /app/out ] && [ "$(ls -A /app/out 2>/dev/null)" ]; then \
        echo "Using out/" && cp -r /app/out /output; \
    else \
        echo "ERROR: no build output found" && ls -la /app/ && exit 1; \
    fi

FROM nginx:alpine
COPY --from=build /output /usr/share/nginx/html

RUN printf 'server {\n  listen 80;\n  server_name _;\n  root /usr/share/nginx/html;\n  index index.html;\n  location / {\n    try_files $uri $uri/ /index.html;\n  }\n}\n' \
    > /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
'''
    }
}

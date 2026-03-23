#!/usr/bin/env groovy

// ═══════════════════════════════════════════════════════════════════════════
// buildFrontend.groovy — ULTRA-GÉNÉRIQUE
// Fonctionne avec TOUS les emplacements et TOUS les frameworks frontend
// ═══════════════════════════════════════════════════════════════════════════

def call(Map config = [:]) {
    def imageName = config.imageName ?: "${env.JOB_NAME.toLowerCase().replaceAll('/', '-')}-frontend"
    def imageTag  = config.imageTag ?: "${env.BUILD_NUMBER}"

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
        pkg.contains('"react"') || pkg.contains('"vue"') || 
        pkg.contains('"angular"') || pkg.contains('"next"') ||
        pkg.contains('"svelte"') || pkg.contains('"nuxt"') ||
        pkg.contains('"gatsby"') || pkg.contains('"vite"')
    }

    if (frontendCandidates.isEmpty()) {
        echo "⚠️  No frontend framework detected in package.json files"
        echo "📦 Using first package.json found: ${allPackageJsons[0]}"
        frontendCandidates = [allPackageJsons[0]]
    }

    // ── ÉTAPE 3 : Choisir le bon frontend ────────────────────────────────
    def selectedFrontend = selectBestFrontend(frontendCandidates)
    def frontendDir = selectedFrontend.dir
    def frontendType = selectedFrontend.type
    def distDir = selectedFrontend.distDir

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
// Trouve TOUS les package.json dans le projet (récursif)
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
    // Priorité aux noms de dossiers évidents
    def priorities = [
        'react-frontend', 'frontend', 'client', 'ui', 'web', 'app', 'front',
        'vue-app', 'angular-app', 'next-app', 'webapp'
    ]
    
    // Chercher par priorité de nom de dossier
    for (priority in priorities) {
        def match = candidates.find { it.contains("/${priority}/") || it == "./${priority}/package.json" }
        if (match) {
            return analyzeFrontend(match)
        }
    }
    
    // Préférer le plus proche de la racine (moins de / dans le chemin)
    def sorted = candidates.sort { a, b ->
        a.count('/') <=> b.count('/')
    }
    
    return analyzeFrontend(sorted[0])
}

// ═══════════════════════════════════════════════════════════════════════════
// Analyse un package.json et retourne les infos du frontend
// ═══════════════════════════════════════════════════════════════════════════
def analyzeFrontend(String packageJsonPath) {
    def dir = packageJsonPath.replaceAll('/package\\.json$', '').replaceAll('^\\./', '')
    if (!dir || dir == 'package.json') dir = '.'
    
    def pkg = readFile(packageJsonPath).toLowerCase()
    
    // Détection du type
    def type = 'react' // default
    if (pkg.contains('"next"')) type = 'nextjs'
    else if (pkg.contains('"nuxt"')) type = 'nuxt'
    else if (pkg.contains('"gatsby"')) type = 'gatsby'
    else if (pkg.contains('"vue"')) type = 'vue'
    else if (pkg.contains('"@angular/core"')) type = 'angular'
    else if (pkg.contains('"svelte"')) type = 'svelte'
    
    // Détection du distDir
    def distDir = 'build' // default
    if (pkg.contains('"next"')) distDir = '.next'
    else if (pkg.contains('"nuxt"')) distDir = '.output'
    else if (pkg.contains('"gatsby"')) distDir = 'public'
    else if (pkg.contains('"vite"')) distDir = 'dist'
    else if (pkg.contains('"vue"')) distDir = 'dist'
    else if (pkg.contains('"@angular/core"')) distDir = 'dist'
    else if (pkg.contains('"react-scripts"')) distDir = 'build'
    else if (pkg.contains('"svelte"')) distDir = 'public'
    
    return [
        dir: dir,
        type: type,
        distDir: distDir
    ]
}

// ═══════════════════════════════════════════════════════════════════════════
// Build HTML statique (fallback si pas de package.json)
// ═══════════════════════════════════════════════════════════════════════════
def buildStaticHTML(String imageName, String imageTag) {
    def htmlFiles = sh(
        script: 'find . -name "index.html" -type f -not -path "*/node_modules/*" -not -path "*/.git/*" | head -1',
        returnStdout: true
    ).trim()
    
    if (!htmlFiles) {
        error("❌ No package.json and no index.html found. Cannot build frontend.")
    }
    
    def htmlDir = htmlFiles.replaceAll('/index\\.html$', '').replaceAll('^\\./', '') ?: '.'
    
    echo "✅ Building static HTML from: ${htmlDir}"
    
    stage('🐳 Build Static HTML Image') {
        container('docker') {
            def dockerfile = """
FROM nginx:alpine
COPY . /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
"""
            writeFile file: 'Dockerfile.static', text: dockerfile
            
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
        imageName: imageName,
        imageTag: imageTag,
        fullImage: "${imageName}:${imageTag}",
        frontendType: 'html',
        frontendDir: htmlDir
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
        imageName: imageName,
        imageTag: imageTag,
        fullImage: "${imageName}:${imageTag}",
        frontendType: frontendType,
        frontendDir: frontendDir
    ]
}

// ═══════════════════════════════════════════════════════════════════════════
// Génère le Dockerfile selon le type
// ═══════════════════════════════════════════════════════════════════════════
// ─────────────────────────────────────────────────────────────────────────────
// REMPLACE la fonction generateDockerfile dans buildFrontend.groovy
// FIX : suppression du || echo qui masquait les erreurs de build
//       + vérification que les fichiers sont bien générés
//       + fallback automatique build/ → dist/ → out/
// ─────────────────────────────────────────────────────────────────────────────
def generateDockerfile(String type, String distDir) {
    
    // ═══════════════════════════════════════════════════════════════
    // NEXT.JS
    // ═══════════════════════════════════════════════════════════════
    
    if (type == 'nextjs') {
        return """
FROM node:16-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm install --legacy-peer-deps

FROM node:16-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build

FROM node:16-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static
EXPOSE 3000
CMD ["node", "server.js"]
"""
    }
    
    // ═══════════════════════════════════════════════════════════════
    // NUXT.JS
    // ═══════════════════════════════════════════════════════════════
    
    else if (type == 'nuxt') {
        return """
FROM node:16-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm install --legacy-peer-deps
COPY . .
RUN npm run build

FROM node:16-alpine
WORKDIR /app
COPY --from=build /app/.output ./
EXPOSE 3000
CMD ["node", "server/index.mjs"]
"""
    }
    
    // ═══════════════════════════════════════════════════════════════
    // REACT / VUE / ANGULAR / SVELTE → NGINX
    // ═══════════════════════════════════════════════════════════════
    
    else {
        return """
FROM node:16-alpine AS build
WORKDIR /app

# Copier package files
COPY package*.json ./

# Install avec triple fallback strategy
RUN npm ci --legacy-peer-deps 2>/dev/null || \\
    npm install --legacy-peer-deps 2>/dev/null || \\
    npm install --force

# Copier le code source
COPY . .

# Build
RUN npm run build

# Chercher automatiquement le dossier de sortie
RUN if [ -d /app/build ] && [ "\$(ls -A /app/build)" ]; then \\
        echo "✅ Using build/" && cp -r /app/build /output; \\
    elif [ -d /app/dist ] && [ "\$(ls -A /app/dist)" ]; then \\
        echo "✅ Using dist/" && cp -r /app/dist /output; \\
    elif [ -d /app/out ] && [ "\$(ls -A /app/out)" ]; then \\
        echo "✅ Using out/" && cp -r /app/out /output; \\
    else \\
        echo "❌ Build failed — no output directory found" && \\
        echo "Contents of /app:" && ls -la /app/ && exit 1; \\
    fi

# Stage final : Nginx
FROM nginx:alpine

# Copier le build
COPY --from=build /output /usr/share/nginx/html

# Configuration Nginx pour SPA (toutes les routes → index.html)
RUN printf 'server {\\n\\
  listen 80;\\n\\
  server_name _;\\n\\
  root /usr/share/nginx/html;\\n\\
  index index.html;\\n\\
\\n\\
  location / {\\n\\
    try_files \$uri \$uri/ /index.html;\\n\\
  }\\n\\
\\n\\
  location ~* \\.(js|css|png|jpg|jpeg|gif|ico|svg|woff|woff2|ttf|eot)$ {\\n\\
    expires 1y;\\n\\
    add_header Cache-Control "public, immutable";\\n\\
  }\\n\\
}\\n' > /etc/nginx/conf.d/default.conf

EXPOSE 80

CMD ["nginx", "-g", "daemon off;"]
"""
    }
}

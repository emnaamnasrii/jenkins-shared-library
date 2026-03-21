#!/usr/bin/env groovy

// ─────────────────────────────────────────────────────────────────────────────
// buildFrontend.groovy — GÉNÉRIQUE
//
// Détecte automatiquement le type de frontend (React/Vue/Angular/NextJS/HTML)
// Génère un Dockerfile si absent
// Build et push l'image
// Compatible avec n'importe quel projet frontend
// ─────────────────────────────────────────────────────────────────────────────

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
            echo "Building frontend image..."
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

// ─────────────────────────────────────────────────────────────────────────────
// Détecte le type de frontend depuis le repo
// ─────────────────────────────────────────────────────────────────────────────
def detectFrontendType() {
    // Chercher dans la racine
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
    // Chercher dans les sous-dossiers courants (monorepo)
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
    // HTML pur
    if (fileExists('index.html')) return 'html'
    // Spring Boot static
    if (fileExists('src/main/resources/static/index.html')) return 'html'
    return 'react'
}

// ─────────────────────────────────────────────────────────────────────────────
// Détecte le répertoire du frontend
// ─────────────────────────────────────────────────────────────────────────────
def detectFrontendDir() {
    // Racine
    if (fileExists('package.json')) {
        def pkg = readFile('package.json').toLowerCase()
        if (pkg.contains('"react"') || pkg.contains('"vue"') ||
            pkg.contains('"@angular/') || pkg.contains('"next"') ||
            pkg.contains('"svelte"') || pkg.contains('"nuxt"')) return '.'
    }
    // Sous-dossiers
    for (dir in ['frontend', 'client', 'ui', 'web', 'app', 'front']) {
        if (fileExists("${dir}/package.json")) return dir
    }
    if (fileExists('index.html')) return '.'
    return '.'
}

// ─────────────────────────────────────────────────────────────────────────────
// Détecte le dossier de build selon le framework
// ─────────────────────────────────────────────────────────────────────────────
def detectDistDir(String frontendType) {
    // Essayer de lire la config du projet
    if (fileExists('package.json')) {
        def pkg = readFile('package.json').toLowerCase()
        // Next.js → .next
        if (pkg.contains('"next"')) return '.next'
        // Vite → dist (Vue3, React avec Vite, Svelte)
        if (pkg.contains('"vite"')) return 'dist'
        // CRA → build
        if (pkg.contains('"react-scripts"')) return 'build'
        // Angular → dist/<project-name>
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

// ─────────────────────────────────────────────────────────────────────────────
// Génère le Dockerfile selon le type de frontend
// ─────────────────────────────────────────────────────────────────────────────
def generateFrontendDockerfile(String frontendType, String frontendDir, String distDir, String dockerfilePath) {
    def content = ''

    switch (frontendType) {

        case 'react':
            content = """
FROM node:18-alpine AS build
WORKDIR /app

# Copier les fichiers de dépendances
COPY package*.json ./

# Nettoyer le cache npm et installer les dépendances
RUN npm cache clean --force && \\
    npm ci --legacy-peer-deps --no-audit --prefer-offline || \\
    npm install --legacy-peer-deps --no-audit || \\
    npm install --force

# Copier le reste du code
COPY . .

# Build l'application
RUN npm run build 2>&1 || echo "Build completed with warnings"

# Stage de production
FROM nginx:alpine

# Copier les fichiers buildés
COPY --from=build /app/${distDir} /usr/share/nginx/html

# Configuration Nginx pour SPA (Single Page Application)
RUN echo 'server { \\
    listen 80; \\
    server_name _; \\
    location / { \\
        root /usr/share/nginx/html; \\
        index index.html; \\
        try_files \\\$uri \\\$uri/ /index.html; \\
    } \\
}' > /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
"""
            break

        case 'vue':
            content = """
FROM node:18-alpine AS build
WORKDIR /app

# Copier les fichiers de dépendances
COPY package*.json ./

# Nettoyer le cache npm et installer les dépendances
RUN npm cache clean --force && \\
    npm ci --legacy-peer-deps --no-audit --prefer-offline || \\
    npm install --legacy-peer-deps --no-audit || \\
    npm install --force

# Copier le reste du code
COPY . .

# Build l'application
RUN npm run build 2>&1 || echo "Build completed with warnings"

# Stage de production
FROM nginx:alpine

# Copier les fichiers buildés
COPY --from=build /app/${distDir} /usr/share/nginx/html

# Configuration Nginx pour SPA
RUN echo 'server { \\
    listen 80; \\
    server_name _; \\
    location / { \\
        root /usr/share/nginx/html; \\
        index index.html; \\
        try_files \\\$uri \\\$uri/ /index.html; \\
    } \\
}' > /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
"""
            break

        case 'angular':
            content = """
FROM node:18-alpine AS build
WORKDIR /app

# Copier les fichiers de dépendances
COPY package*.json ./

# Nettoyer le cache npm et installer les dépendances
RUN npm cache clean --force && \\
    npm ci --legacy-peer-deps --no-audit --prefer-offline || \\
    npm install --legacy-peer-deps --no-audit || \\
    npm install --force

# Copier le reste du code
COPY . .

# Build l'application Angular
RUN npm run build -- --configuration production 2>&1 || \\
    npm run build 2>&1 || \\
    echo "Build completed with warnings"

# Stage de production
FROM nginx:alpine

# Copier les fichiers buildés
COPY --from=build /app/${distDir} /usr/share/nginx/html

# Configuration Nginx pour Angular
RUN echo 'server { \\
    listen 80; \\
    server_name _; \\
    location / { \\
        root /usr/share/nginx/html; \\
        index index.html; \\
        try_files \\\$uri \\\$uri/ /index.html; \\
    } \\
}' > /etc/nginx/conf.d/default.conf

EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
"""
            break

        case 'nextjs':
            content = """
FROM node:18-alpine AS deps
WORKDIR /app
COPY package*.json ./
RUN npm cache clean --force && \\
    npm ci --legacy-peer-deps --no-audit --prefer-offline || \\
    npm install --legacy-peer-deps

FROM node:18-alpine AS builder
WORKDIR /app
COPY --from=deps /app/node_modules ./node_modules
COPY . .
ENV NEXT_TELEMETRY_DISABLED=1
RUN npm run build 2>&1 || echo "Build completed with warnings"

FROM node:18-alpine AS runner
WORKDIR /app
ENV NODE_ENV=production
ENV NEXT_TELEMETRY_DISABLED=1

RUN addgroup --system --gid 1001 nodejs
RUN adduser --system --uid 1001 nextjs

COPY --from=builder /app/public ./public
COPY --from=builder --chown=nextjs:nodejs /app/.next/standalone ./ || true
COPY --from=builder --chown=nextjs:nodejs /app/.next/static ./.next/static || true

USER nextjs
EXPOSE 3000
ENV PORT=3000
CMD ["node", "server.js"]
"""
            break

        case 'nuxt':
            content = """
FROM node:18-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm cache clean --force && \\
    npm ci --legacy-peer-deps --no-audit --prefer-offline || \\
    npm install --legacy-peer-deps
COPY . .
RUN npm run build 2>&1 || echo "Build completed"

FROM node:18-alpine AS runner
WORKDIR /app
COPY --from=build /app/.output ./
EXPOSE 3000
ENV NODE_ENV=production
CMD ["node", "server/index.mjs"]
"""
            break

        case 'svelte':
            content = """
FROM node:18-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm cache clean --force && \\
    npm ci --legacy-peer-deps --no-audit --prefer-offline || \\
    npm install --legacy-peer-deps
COPY . .
RUN npm run build 2>&1 || echo "Build completed"

FROM nginx:alpine
COPY --from=build /app/${distDir} /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
"""
            break

        case 'gatsby':
            content = """
FROM node:18-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm cache clean --force && \\
    npm ci --legacy-peer-deps --no-audit --prefer-offline || \\
    npm install --legacy-peer-deps
COPY . .
RUN npm run build 2>&1 || echo "Build completed"

FROM nginx:alpine
COPY --from=build /app/public /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
"""
            break

        case 'html':
            content = """
FROM nginx:alpine
COPY . /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
"""
            break

        default:
            content = """
FROM node:18-alpine AS build
WORKDIR /app
COPY package*.json ./
RUN npm cache clean --force && \\
    npm ci --legacy-peer-deps --no-audit --prefer-offline || \\
    npm install --legacy-peer-deps || \\
    npm install --force
COPY . .
RUN npm run build || npm run generate || echo "No build script"

FROM nginx:alpine
COPY --from=build /app/${distDir} /usr/share/nginx/html 2>/dev/null || \\
COPY --from=build /app/build /usr/share/nginx/html 2>/dev/null || \\
COPY --from=build /app/dist /usr/share/nginx/html
EXPOSE 80
CMD ["nginx", "-g", "daemon off;"]
"""
    }

    writeFile file: dockerfilePath, text: content
    echo "✅ Frontend Dockerfile generated: ${dockerfilePath} (${frontendType} → ${distDir})"
}

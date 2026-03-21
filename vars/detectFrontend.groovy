#!/usr/bin/env groovy

def call() {

    // ─────────────────────────────────────────────────────────────────────
    // Détecte automatiquement si le projet a un frontend web
    // Cherche à la RACINE et dans les SOUS-DOSSIERS (monorepo)
    // Retourne true  → frontend détecté
    // Retourne false → pas de frontend (API REST pure)
    // ─────────────────────────────────────────────────────────────────────

    // ── React / Vue / Angular / Next.js / Svelte ─────────────────────────
    // Chercher package.json dans tous les dossiers sauf node_modules
    def pkgFiles = findFiles(glob: '**/package.json')
    for (f in pkgFiles) {
        // Ignorer node_modules et .git
        if (f.path.contains('node_modules') || f.path.contains('.git')) continue
        // Ignorer les package.json de backend Node.js pur (express, fastify, etc.)
        try {
            def pkg = readFile(f.path).toLowerCase()
            if (pkg.contains('"react"')         ||
                pkg.contains('"react-dom"')      ||
                pkg.contains('"next"')           ||
                pkg.contains('"vue"')            ||
                pkg.contains('"@vue/')           ||
                pkg.contains('"@angular/core"')  ||
                pkg.contains('"svelte"')         ||
                pkg.contains('"nuxt"')           ||
                pkg.contains('"gatsby"')         ||
                pkg.contains('"vite"')) {
                echo "🖥️  Frontend détecté : ${f.path}"
                return true
            }
        } catch (e) {
            // Ignorer les erreurs de lecture
        }
    }

    // ── HTML pur ─────────────────────────────────────────────────────────
    if (fileExists('index.html')) {
        echo "🖥️  Frontend détecté : index.html (racine)"
        return true
    }

    // ── Spring Boot static / Thymeleaf ───────────────────────────────────
    def staticFiles = findFiles(glob: '**/src/main/resources/static/index.html')
    if (staticFiles.size() > 0) {
        echo "🖥️  Frontend détecté : Spring Boot static"
        return true
    }
    def templateFiles = findFiles(glob: '**/src/main/resources/templates/index.html')
    if (templateFiles.size() > 0) {
        echo "🖥️  Frontend détecté : Spring Boot Thymeleaf"
        return true
    }

    // ── Django / Flask templates ─────────────────────────────────────────
    def djangoTemplates = findFiles(glob: '**/templates/index.html')
    if (djangoTemplates.size() > 0) {
        echo "🖥️  Frontend détecté : templates Python"
        return true
    }
    def baseTemplates = findFiles(glob: '**/templates/base.html')
    if (baseTemplates.size() > 0) {
        echo "🖥️  Frontend détecté : templates Python (base.html)"
        return true
    }

    // ── PHP ───────────────────────────────────────────────────────────────
    if (fileExists('index.php') || fileExists('public/index.php')) {
        echo "🖥️  Frontend détecté : PHP"
        return true
    }

    // ── Ruby on Rails ─────────────────────────────────────────────────────
    def railsViews = findFiles(glob: '**/app/views/**/*.html.erb')
    if (railsViews.size() > 0) {
        echo "🖥️  Frontend détecté : Rails views"
        return true
    }

    echo "🔌 Pas de frontend détecté → tests API REST"
    return false
}

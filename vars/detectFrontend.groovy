#!/usr/bin/env groovy

def call() {

    // ─────────────────────────────────────────────────────────────────────────
    // Détecte automatiquement si le projet a un frontend web
    // Retourne true  → Selenium (pages, formulaires, navigation)
    // Retourne false → requests/pytest (API REST)
    // ─────────────────────────────────────────────────────────────────────────

    // React / Next.js / Vue / Angular — package.json avec framework frontend
    if (fileExists('package.json')) {
        def pkg = readFile('package.json').toLowerCase()
        if (pkg.contains('"react"')    ||
            pkg.contains('"next"')     ||
            pkg.contains('"vue"')      ||
            pkg.contains('"angular"')  ||
            pkg.contains('"svelte"')) {
            echo "🖥️  Frontend détecté : package.json (React/Vue/Angular/Next/Svelte)"
            return true
        }
    }

    // HTML pur à la racine
    if (fileExists('index.html')) {
        echo "🖥️  Frontend détecté : index.html"
        return true
    }

    // Spring Boot avec ressources statiques (Thymeleaf / HTML embarqué)
    if (fileExists('src/main/resources/static/index.html') ||
        fileExists('src/main/resources/templates/index.html')) {
        echo "🖥️  Frontend détecté : Spring Boot static/templates"
        return true
    }

    // Django / Flask templates
    if (fileExists('templates/index.html') ||
        fileExists('templates/base.html')) {
        echo "🖥️  Frontend détecté : templates Python"
        return true
    }

    // PHP avec index.php
    if (fileExists('index.php') || fileExists('public/index.php')) {
        echo "🖥️  Frontend détecté : PHP index"
        return true
    }

    // Ruby on Rails views
    if (fileExists('app/views')) {
        echo "🖥️  Frontend détecté : Rails views"
        return true
    }

    // Aucun frontend trouvé — API REST pure
    echo "🔌 Pas de frontend détecté → tests API REST (requests/pytest)"
    return false
}

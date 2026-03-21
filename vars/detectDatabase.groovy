#!/usr/bin/env groovy

def call() {
    def dbInfo = [
        type    : 'none',
        detected: false,
        version : 'latest',
        port    : 0,
        envVars : [:]
    ]

    echo "🔍 Scanning for database configuration..."

    // ─────────────────────────────────────────────────────────────────────
    // Chercher dans TOUS les fichiers pertinents — racine ET sous-dossiers
    // ─────────────────────────────────────────────────────────────────────

    def filesToCheck = [
        // Spring Boot
        '**/src/main/resources/application.properties',
        '**/src/main/resources/application.yml',
        '**/src/main/resources/application.yaml',
        '**/src/main/resources/application-dev.properties',
        '**/pom.xml',
        '**/build.gradle',
        '**/build.gradle.kts',
        // Python
        '**/requirements.txt',
        '**/Pipfile',
        '**/pyproject.toml',
        // Node.js
        '**/package.json',
        '**/ormconfig.json',
        // PHP
        '**/composer.json',
        '**/config/database.php',
        // Ruby
        '**/Gemfile',
        '**/config/database.yml',
        // .NET
        '**/appsettings.json',
        // Env files
        '**/.env',
        '**/.env.example',
        '**/.env.sample',
        '**/docker-compose.yml',
        '**/docker-compose.yaml'
    ]

    for (glob in filesToCheck) {
        def files = findFiles(glob: glob)
        for (f in files) {
            // Ignorer node_modules et .git
            if (f.path.contains('node_modules') || f.path.contains('.git')) continue
            dbInfo = detectFromFile(f.path, dbInfo)
            if (dbInfo.detected) break
        }
        if (dbInfo.detected) break
    }

    // Chercher settings.py (Django)
    if (!dbInfo.detected) {
        def settingsPyFiles = findFiles(glob: '**/settings.py')
        for (f in settingsPyFiles) {
            if (f.path.contains('node_modules')) continue
            dbInfo = detectFromFile(f.path, dbInfo)
            if (dbInfo.detected) break
        }
    }

    if (dbInfo.detected) {
        dbInfo = setDatabaseDefaults(dbInfo)
    }

    echo "========================================="
    echo "📊 Database Detection Results:"
    echo "   Type    : ${dbInfo.type}"
    echo "   Detected: ${dbInfo.detected}"
    echo "   Version : ${dbInfo.version}"
    echo "   Port    : ${dbInfo.port}"
    echo "========================================="

    return dbInfo
}

def detectFromFile(String filePath, Map dbInfo) {
    if (!fileExists(filePath)) return dbInfo

    try {
        def content = readFile(filePath).toLowerCase()

        if (content.contains('mysql') || content.contains('jdbc:mysql') ||
            content.contains('mysql-connector')) {
            dbInfo.type     = 'mysql'
            dbInfo.detected = true
        }
        else if (content.contains('postgres') || content.contains('jdbc:postgresql') ||
                 content.contains('postgresql')) {
            dbInfo.type     = 'postgresql'
            dbInfo.detected = true
        }
        else if (content.contains('mongodb') || content.contains('mongo') ||
                 content.contains('mongoose')) {
            dbInfo.type     = 'mongodb'
            dbInfo.detected = true
        }
        else if (content.contains('mariadb')) {
            dbInfo.type     = 'mariadb'
            dbInfo.detected = true
        }
        else if (content.contains('redis') || content.contains('jedis') ||
                 content.contains('lettuce')) {
            dbInfo.type     = 'redis'
            dbInfo.detected = true
        }
        else if (content.contains('sqlite')) {
            dbInfo.type     = 'sqlite'
            dbInfo.detected = false  // SQLite = fichier local, pas de pod à déployer
        }
    } catch (Exception e) {
        echo "⚠️ Error reading ${filePath}: ${e.message}"
    }

    return dbInfo
}

def setDatabaseDefaults(Map dbInfo) {
    switch(dbInfo.type) {
        case 'mysql':
            dbInfo.version = '8.0'
            dbInfo.port    = 3306
            break
        case 'postgresql':
            dbInfo.version = '15'
            dbInfo.port    = 5432
            break
        case 'mongodb':
            dbInfo.version = '7.0'
            dbInfo.port    = 27017
            break
        case 'mariadb':
            dbInfo.version = '11.0'
            dbInfo.port    = 3306
            break
        case 'redis':
            dbInfo.version = '7.2'
            dbInfo.port    = 6379
            break
        default:
            dbInfo.detected = false
    }
    return dbInfo
}

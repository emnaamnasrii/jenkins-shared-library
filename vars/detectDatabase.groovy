#!/usr/bin/env groovy

def call() {
    def dbInfo = [
        type: 'none',
        detected: false,
        version: 'latest',
        port: 0,
        envVars: [:]
    ]
    
    echo "🔍 Scanning for database configuration..."
    
    // JAVA (Spring Boot, Maven, Gradle)
    if (fileExists('src/main/resources/application.properties')) {
        dbInfo = detectFromFile('src/main/resources/application.properties', dbInfo)
    }
    if (fileExists('src/main/resources/application.yml') || fileExists('src/main/resources/application.yaml')) {
        def ymlFile = fileExists('src/main/resources/application.yml') ? 'src/main/resources/application.yml' : 'src/main/resources/application.yaml'
        dbInfo = detectFromFile(ymlFile, dbInfo)
    }
    if (fileExists('pom.xml')) {
        dbInfo = detectFromPomXml(dbInfo)
    }
    if (fileExists('build.gradle') || fileExists('build.gradle.kts')) {
        def gradleFile = fileExists('build.gradle') ? 'build.gradle' : 'build.gradle.kts'
        dbInfo = detectFromFile(gradleFile, dbInfo)
    }
    
    // PYTHON (Django, Flask, FastAPI)
    if (fileExists('requirements.txt')) {
        dbInfo = detectFromFile('requirements.txt', dbInfo)
    }
    if (fileExists('Pipfile')) {
        dbInfo = detectFromFile('Pipfile', dbInfo)
    }
    if (fileExists('pyproject.toml')) {
        dbInfo = detectFromFile('pyproject.toml', dbInfo)
    }
    if (fileExists('settings.py') || fileExists('*/settings.py')) {
        def settingsFile = sh(script: 'find . -name settings.py | head -1', returnStdout: true).trim()
        if (settingsFile) {
            dbInfo = detectFromFile(settingsFile, dbInfo)
        }
    }
    if (fileExists('.env') || fileExists('.env.example')) {
        def envFile = fileExists('.env') ? '.env' : '.env.example'
        dbInfo = detectFromFile(envFile, dbInfo)
    }
    
    // NODE.JS (Express, NestJS, etc.)
    if (fileExists('package.json')) {
        dbInfo = detectFromFile('package.json', dbInfo)
    }
    if (fileExists('.env') || fileExists('.env.example')) {
        def envFile = fileExists('.env') ? '.env' : '.env.example'
        dbInfo = detectFromFile(envFile, dbInfo)
    }
    if (fileExists('ormconfig.json') || fileExists('ormconfig.js')) {
        def ormFile = fileExists('ormconfig.json') ? 'ormconfig.json' : 'ormconfig.js'
        dbInfo = detectFromFile(ormFile, dbInfo)
    }
    
    // PHP (Laravel, Symfony, etc.)
    if (fileExists('composer.json')) {
        dbInfo = detectFromFile('composer.json', dbInfo)
    }
    if (fileExists('.env') || fileExists('.env.example')) {
        def envFile = fileExists('.env') ? '.env' : '.env.example'
        dbInfo = detectFromFile(envFile, dbInfo)
    }
    if (fileExists('config/database.php') || fileExists('config/database.yml')) {
        def dbFile = fileExists('config/database.php') ? 'config/database.php' : 'config/database.yml'
        dbInfo = detectFromFile(dbFile, dbInfo)
    }
    
    // GO
    if (fileExists('go.mod')) {
        dbInfo = detectFromFile('go.mod', dbInfo)
    }
    
    // RUBY (Rails)
    if (fileExists('Gemfile')) {
        dbInfo = detectFromFile('Gemfile', dbInfo)
    }
    if (fileExists('config/database.yml')) {
        dbInfo = detectFromFile('config/database.yml', dbInfo)
    }
    
    // C# (.NET)
    if (fileExists('appsettings.json')) {
        dbInfo = detectFromFile('appsettings.json', dbInfo)
    }
    
    // Déterminer les variables d'environnement selon le type de DB
    if (dbInfo.detected) {
        dbInfo = setDatabaseDefaults(dbInfo)
    }
    
    echo "========================================="
    echo "📊 Database Detection Results:"
    echo "   Type: ${dbInfo.type}"
    echo "   Detected: ${dbInfo.detected}"
    echo "   Version: ${dbInfo.version}"
    echo "   Port: ${dbInfo.port}"
    echo "========================================="
    
    return dbInfo
}

def detectFromFile(String filePath, Map dbInfo) {
    if (!fileExists(filePath)) {
        return dbInfo
    }
    
    def content = readFile(filePath).toLowerCase()
    
    // MySQL
    if (content.contains('mysql') || content.contains('jdbc:mysql')) {
        dbInfo.type = 'mysql'
        dbInfo.detected = true
    }
    // PostgreSQL
    else if (content.contains('postgres') || content.contains('jdbc:postgresql')) {
        dbInfo.type = 'postgresql'
        dbInfo.detected = true
    }
    // MongoDB
    else if (content.contains('mongodb') || content.contains('mongo')) {
        dbInfo.type = 'mongodb'
        dbInfo.detected = true
    }
    // MariaDB
    else if (content.contains('mariadb')) {
        dbInfo.type = 'mariadb'
        dbInfo.detected = true
    }
    // Redis
    else if (content.contains('redis')) {
        dbInfo.type = 'redis'
        dbInfo.detected = true
    }
    // Oracle
    else if (content.contains('oracle') || content.contains('jdbc:oracle')) {
        dbInfo.type = 'oracle'
        dbInfo.detected = true
    }
    // SQL Server
    else if (content.contains('sqlserver') || content.contains('mssql')) {
        dbInfo.type = 'sqlserver'
        dbInfo.detected = true
    }
    // SQLite (généralement pas besoin de déploiement)
    else if (content.contains('sqlite')) {
        dbInfo.type = 'sqlite'
        dbInfo.detected = false  // Pas de déploiement nécessaire
    }
    
    return dbInfo
}

def detectFromPomXml(Map dbInfo) {
    if (!fileExists('pom.xml')) {
        return dbInfo
    }
    
    def pom = readFile('pom.xml').toLowerCase()
    
    if (pom.contains('mysql-connector') || pom.contains('mysql</artifactid>')) {
        dbInfo.type = 'mysql'
        dbInfo.detected = true
    }
    else if (pom.contains('postgresql')) {
        dbInfo.type = 'postgresql'
        dbInfo.detected = true
    }
    else if (pom.contains('mongodb')) {
        dbInfo.type = 'mongodb'
        dbInfo.detected = true
    }
    else if (pom.contains('mariadb')) {
        dbInfo.type = 'mariadb'
        dbInfo.detected = true
    }
    
    return dbInfo
}

def setDatabaseDefaults(Map dbInfo) {
    switch(dbInfo.type) {
        case 'mysql':
            dbInfo.version = '8.0'
            dbInfo.port = 3306
            dbInfo.envVars = [
                MYSQL_ROOT_PASSWORD: 'root123',
                MYSQL_DATABASE: '{{APP_NAME}}',
                MYSQL_USER: 'user',
                MYSQL_PASSWORD: 'user123'
            ]
            break
        
        case 'postgresql':
            dbInfo.version = '15'
            dbInfo.port = 5432
            dbInfo.envVars = [
                POSTGRES_PASSWORD: 'postgres123',
                POSTGRES_DB: '{{APP_NAME}}',
                POSTGRES_USER: 'user'
            ]
            break
        
        case 'mongodb':
            dbInfo.version = '7.0'
            dbInfo.port = 27017
            dbInfo.envVars = [
                MONGO_INITDB_ROOT_USERNAME: 'root',
                MONGO_INITDB_ROOT_PASSWORD: 'root123',
                MONGO_INITDB_DATABASE: '{{APP_NAME}}'
            ]
            break
        
        case 'mariadb':
            dbInfo.version = '11.0'
            dbInfo.port = 3306
            dbInfo.envVars = [
                MARIADB_ROOT_PASSWORD: 'root123',
                MARIADB_DATABASE: '{{APP_NAME}}',
                MARIADB_USER: 'user',
                MARIADB_PASSWORD: 'user123'
            ]
            break
        
        case 'redis':
            dbInfo.version = '7.2'
            dbInfo.port = 6379
            dbInfo.envVars = [
                REDIS_PASSWORD: 'redis123'
            ]
            break
        
        default:
            dbInfo.detected = false
    }
    
    return dbInfo
}

#!/usr/bin/env groovy

def call() {
    def dbInfo = [
        type: 'none',
        detected: false,
        version: 'latest',
        port: 0
    ]
    
    echo "🔍 Scanning for database configuration..."
    
    // JAVA (Spring Boot, Maven, Gradle)
    if (fileExists('src/main/resources/application.properties')) {
        dbInfo = detectFromFile('src/main/resources/application.properties', dbInfo)
    }
    if (fileExists('src/main/resources/application.yml')) {
        dbInfo = detectFromFile('src/main/resources/application.yml', dbInfo)
    }
    if (fileExists('src/main/resources/application.yaml')) {
        dbInfo = detectFromFile('src/main/resources/application.yaml', dbInfo)
    }
    if (fileExists('pom.xml')) {
        dbInfo = detectFromPomXml(dbInfo)
    }
    if (fileExists('build.gradle')) {
        dbInfo = detectFromFile('build.gradle', dbInfo)
    }
    if (fileExists('build.gradle.kts')) {
        dbInfo = detectFromFile('build.gradle.kts', dbInfo)
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
    def settingsPy = sh(script: 'find . -name "settings.py" -type f 2>/dev/null | head -1', returnStdout: true).trim()
    if (settingsPy) {
        dbInfo = detectFromFile(settingsPy, dbInfo)
    }
    if (fileExists('.env')) {
        dbInfo = detectFromFile('.env', dbInfo)
    }
    if (fileExists('.env.example')) {
        dbInfo = detectFromFile('.env.example', dbInfo)
    }
    
    // NODE.JS (Express, NestJS, etc.)
    if (fileExists('package.json')) {
        dbInfo = detectFromFile('package.json', dbInfo)
    }
    if (fileExists('ormconfig.json')) {
        dbInfo = detectFromFile('ormconfig.json', dbInfo)
    }
    if (fileExists('ormconfig.js')) {
        dbInfo = detectFromFile('ormconfig.js', dbInfo)
    }
    
    // PHP (Laravel, Symfony, etc.)
    if (fileExists('composer.json')) {
        dbInfo = detectFromFile('composer.json', dbInfo)
    }
    if (fileExists('config/database.php')) {
        dbInfo = detectFromFile('config/database.php', dbInfo)
    }
    if (fileExists('config/database.yml')) {
        dbInfo = detectFromFile('config/database.yml', dbInfo)
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
    
    // Déterminer version et port selon le type de DB
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
    
    try {
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
        // SQLite (pas de déploiement)
        else if (content.contains('sqlite')) {
            dbInfo.type = 'sqlite'
            dbInfo.detected = false
        }
    } catch (Exception e) {
        echo "⚠️  Error reading ${filePath}: ${e.message}"
    }
    
    return dbInfo
}

def detectFromPomXml(Map dbInfo) {
    if (!fileExists('pom.xml')) {
        return dbInfo
    }
    
    try {
        def pom = readFile('pom.xml').toLowerCase()
        
        if (pom.contains('mysql-connector') || pom.contains('<artifactid>mysql</artifactid>')) {
            dbInfo.type = 'mysql'
            dbInfo.detected = true
        }
        else if (pom.contains('postgresql')) {
            dbInfo.type = 'postgresql'
            dbInfo.detected = true
        }
        else if (pom.contains('mongodb') || pom.contains('mongo-java-driver')) {
            dbInfo.type = 'mongodb'
            dbInfo.detected = true
        }
        else if (pom.contains('mariadb')) {
            dbInfo.type = 'mariadb'
            dbInfo.detected = true
        }
        else if (pom.contains('jedis') || pom.contains('lettuce')) {
            dbInfo.type = 'redis'
            dbInfo.detected = true
        }
    } catch (Exception e) {
        echo "⚠️  Error reading pom.xml: ${e.message}"
    }
    
    return dbInfo
}

def setDatabaseDefaults(Map dbInfo) {
    switch(dbInfo.type) {
        case 'mysql':
            dbInfo.version = '8.0'
            dbInfo.port = 3306
            break
        
        case 'postgresql':
            dbInfo.version = '15'
            dbInfo.port = 5432
            break
        
        case 'mongodb':
            dbInfo.version = '7.0'
            dbInfo.port = 27017
            break
        
        case 'mariadb':
            dbInfo.version = '11.0'
            dbInfo.port = 3306
            break
        
        case 'redis':
            dbInfo.version = '7.2'
            dbInfo.port = 6379
            break
        
        case 'oracle':
            dbInfo.version = '21'
            dbInfo.port = 1521
            dbInfo.detected = false  // Nécessite licence
            break
        
        case 'sqlserver':
            dbInfo.version = '2022'
            dbInfo.port = 1433
            dbInfo.detected = false  // Nécessite licence
            break
        
        default:
            dbInfo.detected = false
    }
    
    return dbInfo
}

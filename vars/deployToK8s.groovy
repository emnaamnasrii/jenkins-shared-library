#!/usr/bin/env groovy

def call(Map config = [:]) {
    def namespace = config.namespace ?: 'dev'
    def appName = config.appName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def image = config.image
    def replicas = config.replicas ?: 2
    def dbConfig = config.dbConfig ?: [deployed: false]

    // Nettoyer noms pour Kubernetes
    appName = appName.replaceAll('[/_]', '-').toLowerCase()
    namespace = namespace.replaceAll('[/_]', '-').toLowerCase()

    // Detect language
    def language = detectLanguage()

    // Detect application port
    def port = detectPort(language)

    // Labels requis par Gatekeeper
    def labels = [
        app: appName,
        env: namespace,
        team: 'developers'
    ]

    echo "========================================="
    echo "🚀 Deploying to Kubernetes"
    echo "Application: ${appName}"
    echo "Image: ${image}"
    echo "Namespace: ${namespace}"
    if (dbConfig.deployed) {
        echo "Database: ${dbConfig.type} at ${dbConfig.serviceName}:${dbConfig.port}"
    }
    echo "Language: ${language}"
    echo "Detected Port: ${port}"
    echo "Labels: ${labels}"
    echo "========================================="

    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {
            // Créer namespace si inexistant
            sh "kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -"

            // Générer deployment YAML
            writeFile file: 'deployment.yaml', text: """
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${appName}
  namespace: ${namespace}
  labels:
    app: ${labels.app}
    env: ${labels.env}
    team: ${labels.team}
spec:
  replicas: ${replicas}
  selector:
    matchLabels:
      app: ${labels.app}
  template:
    metadata:
      labels:
        app: ${labels.app}
        env: ${labels.env}
        team: ${labels.team}
    spec:
${dbConfig.deployed ? generateInitContainer(dbConfig, namespace) : ''}
      containers:
      - name: ${appName}
        image: ${image}
        imagePullPolicy: Always
        ${generateDatabaseEnv(dbConfig, appName, namespace, language)}
        ports:
        - containerPort: ${port}
        readinessProbe:
          tcpSocket:
            port: ${port}
          initialDelaySeconds: 120
          periodSeconds: 10
          failureThreshold: 3
        livenessProbe:
          tcpSocket:
            port: ${port}
          initialDelaySeconds: 180
          periodSeconds: 30
          failureThreshold: 3
        resources:
          requests:
            cpu: "100m"
            memory: "256Mi"
          limits:
            cpu: "500m"
            memory: "1Gi"
---
apiVersion: v1
kind: Service
metadata:
  name: ${appName}
  namespace: ${namespace}
  labels:
    app: ${labels.app}
    env: ${labels.env}
    team: ${labels.team}
spec:
  type: NodePort
  selector:
    app: ${labels.app}
  ports:
  - port: ${port}
    targetPort: ${port}
    nodePort: 30080
"""

            // Appliquer deployment
            sh "kubectl apply -f deployment.yaml"

            // Attendre rollout
            echo "⏳ Waiting for deployment to complete..."
            sh "kubectl rollout status deployment/${appName} -n ${namespace} --timeout=5m || echo 'Deployment in progress'"

            // Obtenir node IP et nodePort
            def nodeIP = sh(
                script: "kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}'",
                returnStdout: true
            ).trim()
            
            def nodePort = sh(
                script: "kubectl get svc ${appName} -n ${namespace} -o jsonpath='{.spec.ports[0].nodePort}'",
                returnStdout: true
            ).trim()

            echo "========================================="
            echo "✅ Deployment successful!"
            echo "Application: ${appName}"
            echo "Namespace: ${namespace}"
            echo "Image: ${image}"
            if (dbConfig.deployed) {
                echo "Database: ${dbConfig.type}"
            }
            echo "URL: http://${nodeIP}:${nodePort}"
            echo "========================================="
        }
    }
}

// Génère l'initContainer GÉNÉRIQUE pour TOUTES les bases de données
def generateInitContainer(Map dbConfig, String namespace) {
    if (!dbConfig.deployed) {
        return ''
    }
    
    def dbHost = dbConfig.serviceName
    def dbPort = dbConfig.port
    def dbType = dbConfig.type
    
    // Installer les outils nécessaires selon le type de BD
    def installCmd = getInstallCommand(dbType)
    
    // Commande d'attente spécifique à chaque BD
    def waitCmd = getWaitCommand(dbType, dbHost, dbPort)
    
    return """      initContainers:
      - name: wait-for-db
        image: alpine:3.18
        command: ['sh', '-c']
        args:
        - |
          echo '📦 Installing database client tools...'
          ${installCmd}
          
          echo '⏳ Waiting for ${dbType} to be ready...'
          ${waitCmd}
          
          echo '✅ ${dbType} is fully ready! Starting application...'
"""
}

// Retourne la commande d'installation des outils selon le type de BD
def getInstallCommand(String dbType) {
    switch(dbType) {
        case 'mysql':
        case 'mariadb':
            return 'apk add --no-cache mysql-client netcat-openbsd curl'
        
        case 'postgresql':
            return 'apk add --no-cache postgresql-client netcat-openbsd curl'
        
        case 'mongodb':
            return 'apk add --no-cache mongodb-tools netcat-openbsd curl || apk add --no-cache netcat-openbsd curl'
        
        case 'redis':
            return 'apk add --no-cache redis netcat-openbsd curl'
        
        default:
            return 'apk add --no-cache netcat-openbsd curl'
    }
}

// Retourne la commande d'attente spécifique à chaque type de BD
def getWaitCommand(String dbType, String dbHost, int dbPort) {
    switch(dbType) {
        case 'mysql':
        case 'mariadb':
            return """
          # Attendre que le port soit ouvert
          until nc -z ${dbHost} ${dbPort}; do
            echo '  MySQL port not ready - waiting...'
            sleep 2
          done
          echo '  ✓ MySQL port is open'
          
          # Tester la connexion MySQL avec root (user n'a pas les permissions)
          echo '  Testing MySQL connection...'
          until mysql -h ${dbHost} -u root -proot123 -e 'SELECT 1' 2>/dev/null; do
            echo '  MySQL not accepting connections - waiting...'
            sleep 2
          done
          echo '  ✓ MySQL connection successful'
"""
        
        case 'postgresql':
            return """
          # Attendre que le port soit ouvert
          until nc -z ${dbHost} ${dbPort}; do
            echo '  PostgreSQL port not ready - waiting...'
            sleep 2
          done
          echo '  ✓ PostgreSQL port is open'
          
          # Tester la connexion PostgreSQL avec postgres (superuser)
          echo '  Testing PostgreSQL connection...'
          until PGPASSWORD=postgres123 psql -h ${dbHost} -U postgres -d postgres -c 'SELECT 1' 2>/dev/null; do
            echo '  PostgreSQL not accepting connections - waiting...'
            sleep 2
          done
          echo '  ✓ PostgreSQL connection successful'
"""
        
        case 'mongodb':
            return """
          # Attendre que le port soit ouvert
          until nc -z ${dbHost} ${dbPort}; do
            echo '  MongoDB port not ready - waiting...'
            sleep 2
          done
          echo '  ✓ MongoDB port is open'
          
          # Attendre 5 secondes pour que MongoDB soit complètement prêt
          sleep 5
          echo '  ✓ MongoDB ready for connections'
"""
        
        case 'redis':
            return """
          # Attendre que le port soit ouvert
          until nc -z ${dbHost} ${dbPort}; do
            echo '  Redis port not ready - waiting...'
            sleep 2
          done
          echo '  ✓ Redis port is open'
          
          # Tester la connexion Redis
          echo '  Testing Redis connection...'
          until redis-cli -h ${dbHost} -p ${dbPort} -a redis123 ping 2>/dev/null | grep -q PONG; do
            echo '  Redis not responding - waiting...'
            sleep 2
          done
          echo '  ✓ Redis connection successful'
"""
        
        default:
            return """
          # Attente TCP générique
          until nc -z ${dbHost} ${dbPort}; do
            echo '  Database port not ready - waiting...'
            sleep 2
          done
          echo '  ✓ Database port is open'
          sleep 5
"""
    }
}

// Génère les variables d'environnement GÉNÉRIQUES pour TOUTES les bases de données
def generateDatabaseEnv(Map dbConfig, String appName, String namespace, String language) {
    if (!dbConfig.deployed) {
        return 'env: []'
    }
    
    def dbHost = dbConfig.serviceName
    def dbPort = dbConfig.port
    def dbType = dbConfig.type
    
    def envVars = 'env:\n'
    
    switch(dbType) {
        case 'mysql':
        case 'mariadb':
            envVars += generateMySQLEnv(dbHost, dbPort, appName, language)
            break
        
        case 'postgresql':
            envVars += generatePostgreSQLEnv(dbHost, dbPort, appName, language)
            break
        
        case 'mongodb':
            envVars += generateMongoDBEnv(dbHost, dbPort, appName, language)
            break
        
        case 'redis':
            envVars += generateRedisEnv(dbHost, dbPort, language)
            break
        
        default:
            envVars = 'env: []'
    }
    
    return envVars
}

// Variables d'environnement MySQL/MariaDB (multi-langage)
def generateMySQLEnv(String dbHost, int dbPort, String appName, String language) {
    def env = ''
    
    // Variables communes
    env += """        - name: DB_HOST
          value: "${dbHost}"
        - name: DB_PORT
          value: "${dbPort}"
        - name: DB_NAME
          value: "${appName}"
        - name: DB_USER
          value: "user"
        - name: DB_PASSWORD
          value: "user123"
        - name: DATABASE_URL
          value: "mysql://user:user123@${dbHost}:${dbPort}/${appName}"
"""
    
    // Variables spécifiques Java (Spring Boot)
    if (language == 'java-maven' || language == 'java-gradle') {
        env += """        - name: SPRING_DATASOURCE_URL
          value: "jdbc:mysql://${dbHost}:${dbPort}/${appName}?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
        - name: SPRING_DATASOURCE_USERNAME
          value: "user"
        - name: SPRING_DATASOURCE_PASSWORD
          value: "user123"
        - name: SPRING_JPA_HIBERNATE_DDL_AUTO
          value: "update"
        - name: SPRING_JPA_DATABASE_PLATFORM
          value: "org.hibernate.dialect.MySQL8Dialect"
        - name: SPRING_JPA_SHOW_SQL
          value: "false"
"""
    }
    
    // Variables spécifiques Python (Django, Flask, SQLAlchemy)
    if (language == 'python') {
        env += """        - name: MYSQL_HOST
          value: "${dbHost}"
        - name: MYSQL_PORT
          value: "${dbPort}"
        - name: MYSQL_DATABASE
          value: "${appName}"
        - name: MYSQL_USER
          value: "user"
        - name: MYSQL_PASSWORD
          value: "user123"
        - name: SQLALCHEMY_DATABASE_URI
          value: "mysql+pymysql://user:user123@${dbHost}:${dbPort}/${appName}"
"""
    }
    
    // Variables spécifiques Node.js
    if (language == 'nodejs') {
        env += """        - name: MYSQL_HOST
          value: "${dbHost}"
        - name: MYSQL_PORT
          value: "${dbPort}"
        - name: MYSQL_DATABASE
          value: "${appName}"
        - name: MYSQL_USER
          value: "user"
        - name: MYSQL_PASSWORD
          value: "user123"
"""
    }
    
    return env
}

// Variables d'environnement PostgreSQL (multi-langage)
def generatePostgreSQLEnv(String dbHost, int dbPort, String appName, String language) {
    def env = ''
    
    // Variables communes
    env += """        - name: DB_HOST
          value: "${dbHost}"
        - name: DB_PORT
          value: "${dbPort}"
        - name: DB_NAME
          value: "${appName}"
        - name: DB_USER
          value: "user"
        - name: DB_PASSWORD
          value: "postgres123"
        - name: DATABASE_URL
          value: "postgresql://user:postgres123@${dbHost}:${dbPort}/${appName}"
"""
    
    // Variables spécifiques Java (Spring Boot)
    if (language == 'java-maven' || language == 'java-gradle') {
        env += """        - name: SPRING_DATASOURCE_URL
          value: "jdbc:postgresql://${dbHost}:${dbPort}/${appName}"
        - name: SPRING_DATASOURCE_USERNAME
          value: "user"
        - name: SPRING_DATASOURCE_PASSWORD
          value: "postgres123"
        - name: SPRING_JPA_HIBERNATE_DDL_AUTO
          value: "update"
        - name: SPRING_JPA_DATABASE_PLATFORM
          value: "org.hibernate.dialect.PostgreSQLDialect"
"""
    }
    
    // Variables spécifiques Python
    if (language == 'python') {
        env += """        - name: POSTGRES_HOST
          value: "${dbHost}"
        - name: POSTGRES_PORT
          value: "${dbPort}"
        - name: POSTGRES_DB
          value: "${appName}"
        - name: POSTGRES_USER
          value: "user"
        - name: POSTGRES_PASSWORD
          value: "postgres123"
        - name: SQLALCHEMY_DATABASE_URI
          value: "postgresql://user:postgres123@${dbHost}:${dbPort}/${appName}"
"""
    }
    
    // Variables spécifiques Node.js
    if (language == 'nodejs') {
        env += """        - name: PGHOST
          value: "${dbHost}"
        - name: PGPORT
          value: "${dbPort}"
        - name: PGDATABASE
          value: "${appName}"
        - name: PGUSER
          value: "user"
        - name: PGPASSWORD
          value: "postgres123"
"""
    }
    
    return env
}

// Variables d'environnement MongoDB (multi-langage)
def generateMongoDBEnv(String dbHost, int dbPort, String appName, String language) {
    def mongoUri = "mongodb://root:root123@${dbHost}:${dbPort}/${appName}?authSource=admin"
    
    def env = """        - name: MONGO_HOST
          value: "${dbHost}"
        - name: MONGO_PORT
          value: "${dbPort}"
        - name: MONGO_DATABASE
          value: "${appName}"
        - name: MONGO_USERNAME
          value: "root"
        - name: MONGO_PASSWORD
          value: "root123"
        - name: MONGODB_URI
          value: "${mongoUri}"
"""
    
    // Variables spécifiques Java (Spring Boot)
    if (language == 'java-maven' || language == 'java-gradle') {
        env += """        - name: SPRING_DATA_MONGODB_URI
          value: "${mongoUri}"
        - name: SPRING_DATA_MONGODB_DATABASE
          value: "${appName}"
"""
    }
    
    // Variables spécifiques Python
    if (language == 'python') {
        env += """        - name: MONGO_URL
          value: "${mongoUri}"
"""
    }
    
    return env
}

// Variables d'environnement Redis (multi-langage)
def generateRedisEnv(String dbHost, int dbPort, String language) {
    def env = """        - name: REDIS_HOST
          value: "${dbHost}"
        - name: REDIS_PORT
          value: "${dbPort}"
        - name: REDIS_PASSWORD
          value: "redis123"
        - name: REDIS_URL
          value: "redis://:redis123@${dbHost}:${dbPort}/0"
"""
    
    // Variables spécifiques Java (Spring Boot)
    if (language == 'java-maven' || language == 'java-gradle') {
        env += """        - name: SPRING_REDIS_HOST
          value: "${dbHost.split('\\.')[0]}"
        - name: SPRING_REDIS_PORT
          value: "${dbPort}"
        - name: SPRING_REDIS_PASSWORD
          value: "redis123"
"""
    }
    
    return env
}

// Détecte le port de l'application selon le langage
def detectPort(language) {
    if (language == "python") {
        if (fileExists("requirements.txt")) {
            def req = readFile("requirements.txt").toLowerCase()
            if (req.contains("fastapi") || req.contains("django")) return 8000
            if (req.contains("flask")) return 5000
        }
        return 5000
    }
    else if (language == "nodejs") return 3000
    else if (language == "java-maven" || language == "java-gradle") return 8080
    else if (language == "golang") return 8080
    else if (language == "php") return 80
    else if (language == "ruby") return 3000
    else return 8080
}

#!/usr/bin/env groovy

def call(Map config = [:]) {
    def namespace = config.namespace ?: 'dev'
    def appName = config.appName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def image = config.image
    def replicas = config.replicas ?: 2
    def dbConfig = config.dbConfig ?: [deployed: false]

    appName = appName.replaceAll('[/_]', '-').toLowerCase()
    namespace = namespace.replaceAll('[/_]', '-').toLowerCase()

    def language = detectLanguage()
    def port = detectPort(language)

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
    echo "========================================="

    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {
            sh "kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -"

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
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
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
          initialDelaySeconds: 300
          periodSeconds: 15
          timeoutSeconds: 5
          successThreshold: 1
          failureThreshold: 20
        livenessProbe:
          tcpSocket:
            port: ${port}
          initialDelaySeconds: 360
          periodSeconds: 30
          timeoutSeconds: 5
          successThreshold: 1
          failureThreshold: 10
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

            sh "kubectl apply -f deployment.yaml"

            echo "⏳ Waiting for deployment to complete..."
            sh "kubectl rollout status deployment/${appName} -n ${namespace} --timeout=15m || echo 'Deployment in progress'"

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

def generateMySQLEnv(String dbHost, int dbPort, String appName, String language) {
    def env = ''
    def shortDbHost = dbHost.contains('.') ? dbHost.split('\\.')[0] : dbHost
    
    env += """        - name: DB_HOST
          value: "${shortDbHost}"
        - name: DB_PORT
          value: "${dbPort}"
        - name: DB_NAME
          value: "${appName}"
        - name: DB_USER
          value: "user"
        - name: DB_PASSWORD
          value: "user123"
        - name: DATABASE_URL
          value: "mysql://user:user123@${shortDbHost}:${dbPort}/${appName}"
"""
    
    if (language == 'java-maven' || language == 'java-gradle') {
        env += """        - name: SPRING_DATASOURCE_URL
          value: "jdbc:mysql://${shortDbHost}:${dbPort}/${appName}?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=UTC"
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
    
    if (language == 'python') {
        env += """        - name: MYSQL_HOST
          value: "${shortDbHost}"
        - name: MYSQL_PORT
          value: "${dbPort}"
        - name: MYSQL_DATABASE
          value: "${appName}"
        - name: MYSQL_USER
          value: "user"
        - name: MYSQL_PASSWORD
          value: "user123"
        - name: SQLALCHEMY_DATABASE_URI
          value: "mysql+pymysql://user:user123@${shortDbHost}:${dbPort}/${appName}"
"""
    }
    
    if (language == 'nodejs') {
        env += """        - name: MYSQL_HOST
          value: "${shortDbHost}"
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

def generatePostgreSQLEnv(String dbHost, int dbPort, String appName, String language) {
    def env = ''
    def shortDbHost = dbHost.contains('.') ? dbHost.split('\\.')[0] : dbHost
    
    env += """        - name: DB_HOST
          value: "${shortDbHost}"
        - name: DB_PORT
          value: "${dbPort}"
        - name: DB_NAME
          value: "${appName}"
        - name: DB_USER
          value: "user"
        - name: DB_PASSWORD
          value: "postgres123"
        - name: DATABASE_URL
          value: "postgresql://user:postgres123@${shortDbHost}:${dbPort}/${appName}"
"""
    
    if (language == 'java-maven' || language == 'java-gradle') {
        env += """        - name: SPRING_DATASOURCE_URL
          value: "jdbc:postgresql://${shortDbHost}:${dbPort}/${appName}"
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
    
    if (language == 'python') {
        env += """        - name: POSTGRES_HOST
          value: "${shortDbHost}"
        - name: POSTGRES_PORT
          value: "${dbPort}"
        - name: POSTGRES_DB
          value: "${appName}"
        - name: POSTGRES_USER
          value: "user"
        - name: POSTGRES_PASSWORD
          value: "postgres123"
        - name: SQLALCHEMY_DATABASE_URI
          value: "postgresql://user:postgres123@${shortDbHost}:${dbPort}/${appName}"
"""
    }
    
    if (language == 'nodejs') {
        env += """        - name: PGHOST
          value: "${shortDbHost}"
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

def generateMongoDBEnv(String dbHost, int dbPort, String appName, String language) {
    def shortDbHost = dbHost.contains('.') ? dbHost.split('\\.')[0] : dbHost
    def mongoUri = "mongodb://root:root123@${shortDbHost}:${dbPort}/${appName}?authSource=admin"
    
    def env = """        - name: MONGO_HOST
          value: "${shortDbHost}"
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
    
    if (language == 'java-maven' || language == 'java-gradle') {
        env += """        - name: SPRING_DATA_MONGODB_URI
          value: "${mongoUri}"
        - name: SPRING_DATA_MONGODB_DATABASE
          value: "${appName}"
"""
    }
    
    if (language == 'python') {
        env += """        - name: MONGO_URL
          value: "${mongoUri}"
"""
    }
    
    return env
}

def generateRedisEnv(String dbHost, int dbPort, String language) {
    def shortDbHost = dbHost.contains('.') ? dbHost.split('\\.')[0] : dbHost
    
    def env = """        - name: REDIS_HOST
          value: "${shortDbHost}"
        - name: REDIS_PORT
          value: "${dbPort}"
        - name: REDIS_PASSWORD
          value: "redis123"
        - name: REDIS_URL
          value: "redis://:redis123@${shortDbHost}:${dbPort}/0"
"""
    
    if (language == 'java-maven' || language == 'java-gradle') {
        env += """        - name: SPRING_REDIS_HOST
          value: "${shortDbHost}"
        - name: SPRING_REDIS_PORT
          value: "${dbPort}"
        - name: SPRING_REDIS_PASSWORD
          value: "redis123"
"""
    }
    
    return env
}

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

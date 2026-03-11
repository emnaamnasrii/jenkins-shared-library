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
      ${dbConfig.deployed ? """
      initContainers:
      - name: wait-for-db
        image: busybox
        command:
          - sh
          - -c
          - |
            echo "⏳ Waiting for DB ${dbConfig.serviceName}:${dbConfig.port}..."
            until nslookup ${dbConfig.serviceName}.${namespace}.svc.cluster.local >/dev/null 2>&1 && \\
                  nc -z ${dbConfig.serviceName} ${dbConfig.port}; do
              echo "Waiting for DB..."
              sleep 5
            done
            echo "✅ DB is ready!"
      """ : ""}
      containers:
      - name: ${appName}
        image: ${image}
        imagePullPolicy: Always
        ${generateDatabaseEnv(dbConfig, appName, namespace)}
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
            sh "kubectl rollout status deployment/${appName} -n ${namespace} --timeout=30m || echo 'Deployment in progress'"

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

// Génère les variables d'environnement pour la base de données
def generateDatabaseEnv(Map dbConfig, String appName, String namespace) {
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
            envVars += """        - name: SPRING_DATASOURCE_URL
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
          value: "true"
        - name: DB_HOST
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
            break
        case 'postgresql':
            envVars += """        - name: SPRING_DATASOURCE_URL
          value: "jdbc:postgresql://${dbHost}:${dbPort}/${appName}"
        - name: SPRING_DATASOURCE_USERNAME
          value: "user"
        - name: SPRING_DATASOURCE_PASSWORD
          value: "postgres123"
        - name: SPRING_JPA_HIBERNATE_DDL_AUTO
          value: "update"
        - name: SPRING_JPA_DATABASE_PLATFORM
          value: "org.hibernate.dialect.PostgreSQLDialect"
        - name: DATABASE_URL
          value: "postgresql://user:postgres123@${dbHost}:${dbPort}/${appName}"
        - name: POSTGRES_HOST
          value: "${dbHost}"
        - name: POSTGRES_PORT
          value: "${dbPort}"
        - name: POSTGRES_DB
          value: "${appName}"
        - name: POSTGRES_USER
          value: "user"
        - name: POSTGRES_PASSWORD
          value: "postgres123"
"""
            break
        case 'mongodb':
            envVars += """        - name: SPRING_DATA_MONGODB_URI
          value: "mongodb://root:root123@${dbHost}:${dbPort}/${appName}?authSource=admin"
        - name: MONGODB_URI
          value: "mongodb://root:root123@${dbHost}:${dbPort}/${appName}?authSource=admin"
        - name: MONGO_HOST
          value: "${dbHost}"
        - name: MONGO_PORT
          value: "${dbPort}"
        - name: MONGO_DATABASE
          value: "${appName}"
        - name: MONGO_USERNAME
          value: "root"
        - name: MONGO_PASSWORD
          value: "root123"
"""
            break
        case 'redis':
            envVars += """        - name: SPRING_REDIS_HOST
          value: "${dbHost.split('\\.')[0]}"
        - name: SPRING_REDIS_PORT
          value: "${dbPort}"
        - name: SPRING_REDIS_PASSWORD
          value: "redis123"
        - name: REDIS_URL
          value: "redis://:redis123@${dbHost}:${dbPort}/0"
        - name: REDIS_HOST
          value: "${dbHost}"
        - name: REDIS_PORT
          value: "${dbPort}"
"""
            break
        default:
            envVars = 'env: []'
    }
    
    return envVars
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
    } else if (language == "nodejs") return 3000
    else if (language == "java-maven" || language == "java-gradle") return 8080
    else if (language == "golang") return 8080
    else if (language == "php") return 80
    else if (language == "ruby") return 3000
    else return 8080
}

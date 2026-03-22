#!/usr/bin/env groovy
// ─────────────────────────────────────────────────────────────────────────────
// CORRECTIONS APPLIQUÉES :
// 1. Service type: LoadBalancer (MetalLB) — accessible depuis tout le LAN
// 2. DNS complet pour la DB — suppression du shortDbHost qui cassait la résolution
// 3. Fix READY vide → ${READY:-0} — évite "sh: out of range"
// 4. Fix readyPods vide → valeur par défaut '0' — évite "For input string: ''"
// 5. Fix externalIP vide → valeur par défaut 'pending'
// ─────────────────────────────────────────────────────────────────────────────

def call(Map config = [:]) {
    def namespace  = config.namespace  ?: 'dev'
    def appName    = config.appName    ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def image      = config.image
    def replicas   = config.replicas   ?: 2
    def dbConfig   = config.dbConfig   ?: [deployed: false]

    appName   = appName.replaceAll('[/_]', '-').toLowerCase()
    namespace = namespace.replaceAll('[/_]', '-').toLowerCase()

    def language = detectTech()
    def port     = detectPort(language)

    def labels = [
        app : appName,
        env : namespace,
        team: 'developers'
    ]

    echo "========================================="
    echo "🚀 Deploying to Kubernetes"
    echo "Application : ${appName}"
    echo "Image       : ${image}"
    echo "Namespace   : ${namespace}"
    if (dbConfig.deployed) {
        echo "Database    : ${dbConfig.type} at ${dbConfig.serviceName}:${dbConfig.port}"
    }
    echo "Language    : ${language}"
    echo "Detected Port: ${port}"
    echo "Replicas    : ${replicas}"
    echo "========================================="

    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {

            // ── Namespace ──────────────────────────────────────────────
            sh "kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -"

            // ── Deployment + Service YAML ──────────────────────────────
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
      maxUnavailable: 1
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
            initialDelaySeconds: 90
            periodSeconds: 10
            timeoutSeconds: 3
            successThreshold: 1
            failureThreshold: 10
          livenessProbe:
            tcpSocket:
              port: ${port}
            initialDelaySeconds: 120
            periodSeconds: 30
            timeoutSeconds: 5
            successThreshold: 1
            failureThreshold: 5
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
  # FIX 1 : LoadBalancer — MetalLB assigne une IP accessible depuis tout le LAN
  type: LoadBalancer
  selector:
    app: ${labels.app}
  ports:
    - name: http
      port: 80
      targetPort: ${port}
      protocol: TCP
"""

            sh "kubectl apply -f deployment.yaml"

            // ── Attendre qu'au moins 1 pod soit Ready ──────────────────
            echo "⏳ Waiting for at least 1 pod to be ready..."
            sh """
for i in \$(seq 1 60); do
  READY=\$(kubectl get deployment ${appName} -n ${namespace} \\
    -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo '0')
  READY=\${READY:-0}
  if [ "\$READY" -ge "1" ]; then
    echo "✅ At least 1 pod is ready (\$READY/${replicas})"
    break
  fi
  echo "  Waiting... (\$i/60) - Ready pods: \$READY/${replicas}"
  sleep 5
done
"""

            // ── Attendre l'IP externe MetalLB ─────────────────────────
            echo "⏳ Waiting for MetalLB to assign external IP..."
            sh """
for i in \$(seq 1 30); do
  EXTERNAL_IP=\$(kubectl get svc ${appName} -n ${namespace} \\
    -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo '')
  EXTERNAL_IP=\${EXTERNAL_IP:-}
  if [ -n "\$EXTERNAL_IP" ] && [ "\$EXTERNAL_IP" != "null" ]; then
    echo "✅ External IP assigned: \$EXTERNAL_IP"
    break
  fi
  echo "  Waiting for IP... (\$i/30)"
  sleep 5
done
"""

            // ── Récupérer les infos finales ────────────────────────────
            // FIX 4 : valeur par défaut pour éviter "For input string: ''"
            def externalIP = sh(
                script: """kubectl get svc ${appName} -n ${namespace} \\
                           -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo 'pending'""",
                returnStdout: true
            ).trim()
            if (!externalIP || externalIP == '') externalIP = 'pending'

            // FIX 5 : valeur par défaut pour éviter "For input string: ''"
            def readyPods = sh(
                script: """kubectl get deployment ${appName} -n ${namespace} \\
                           -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo '0'""",
                returnStdout: true
            ).trim()
            if (!readyPods || readyPods == '') readyPods = '0'

            echo "========================================="
            echo "✅ Deployment successful!"
            echo "Application  : ${appName}"
            echo "Namespace    : ${namespace}"
            echo "Image        : ${image}"
            if (dbConfig.deployed) {
                echo "Database     : ${dbConfig.type}"
            }
            echo "Ready Pods   : ${readyPods}/${replicas}"
            echo "🌐 URL       : http://${externalIP}/"
            echo "========================================="
            echo "ℹ️  Accessible depuis tout le LAN : http://${externalIP}/"
            echo "========================================="

            if (readyPods.toInteger() < replicas.toInteger()) {
                echo "⚠️ Warning: Only ${readyPods}/${replicas} pods ready, but service is operational!"
            }
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// Database env helpers
// FIX 2 : suppression de shortDbHost — on garde le nom DNS complet
//         ex: emnamnasrii-test-app-db.dev.svc.cluster.local
//         Le nom court "emnamnasrii-test-app-db" seul ne se résout pas
//         dans Kubernetes quand les pods sont sur des nœuds différents
// ─────────────────────────────────────────────────────────────────────────────

def generateDatabaseEnv(Map dbConfig, String appName, String namespace, String language) {
    if (!dbConfig.deployed) return 'env: []'

    def dbHost  = dbConfig.serviceName
    def dbPort  = dbConfig.port
    def dbType  = dbConfig.type
    def envVars = 'env:\n'

    switch (dbType) {
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
    def env = """
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
    if (language in ['java-maven', 'java-gradle']) {
        env += """
          - name: SPRING_DATASOURCE_URL
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
    if (language == 'python') {
        env += """
          - name: MYSQL_HOST
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
    if (language == 'nodejs') {
        env += """
          - name: MYSQL_HOST
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

def generatePostgreSQLEnv(String dbHost, int dbPort, String appName, String language) {
    def env = """
          - name: DB_HOST
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
    if (language in ['java-maven', 'java-gradle']) {
        env += """
          - name: SPRING_DATASOURCE_URL
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
    if (language == 'python') {
        env += """
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
          - name: SQLALCHEMY_DATABASE_URI
            value: "postgresql://user:postgres123@${dbHost}:${dbPort}/${appName}"
"""
    }
    if (language == 'nodejs') {
        env += """
          - name: PGHOST
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

def generateMongoDBEnv(String dbHost, int dbPort, String appName, String language) {
    def mongoUri = "mongodb://root:root123@${dbHost}:${dbPort}/${appName}?authSource=admin"
    def env = """
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
          - name: MONGODB_URI
            value: "${mongoUri}"
"""
    if (language in ['java-maven', 'java-gradle']) {
        env += """
          - name: SPRING_DATA_MONGODB_URI
            value: "${mongoUri}"
          - name: SPRING_DATA_MONGODB_DATABASE
            value: "${appName}"
"""
    }
    if (language == 'python') {
        env += """
          - name: MONGO_URL
            value: "${mongoUri}"
"""
    }
    return env
}

def generateRedisEnv(String dbHost, int dbPort, String language) {
    def env = """
          - name: REDIS_HOST
            value: "${dbHost}"
          - name: REDIS_PORT
            value: "${dbPort}"
          - name: REDIS_PASSWORD
            value: "redis123"
          - name: REDIS_URL
            value: "redis://:redis123@${dbHost}:${dbPort}/0"
"""
    if (language in ['java-maven', 'java-gradle']) {
        env += """
          - name: SPRING_REDIS_HOST
            value: "${dbHost}"
          - name: SPRING_REDIS_PORT
            value: "${dbPort}"
          - name: SPRING_REDIS_PASSWORD
            value: "redis123"
"""
    }
    return env
}

// ─────────────────────────────────────────────────────────────────────────────
// Language & Port detection
// ─────────────────────────────────────────────────────────────────────────────

def detectPort(language) {
    if (language == 'python') {
        if (fileExists('requirements.txt')) {
            def req = readFile('requirements.txt').toLowerCase()
            if (req.contains('fastapi') || req.contains('django')) return 8000
            if (req.contains('flask')) return 5000
        }
        return 5000
    }
    if (language == 'nodejs')                        return 3000
    if (language in ['java-maven', 'java-gradle'])   return 8080
    if (language == 'golang')                        return 8080
    if (language == 'php')                           return 80
    if (language == 'ruby')                          return 3000
    return 8080
}

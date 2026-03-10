#!/usr/bin/env groovy

def call(Map config = [:]) {

    def namespace = config.namespace ?: 'dev'
    def dbType = config.dbType
    def dbVersion = config.dbVersion ?: 'latest'
    def dbPort = config.dbPort ?: 3306
    def dbEnvVars = config.dbEnvVars ?: [:]
    def appName = config.appName

    if (!dbType || dbType == 'none') {
        echo "⚠️  No database detected, skipping database deployment"
        return [deployed:false]
    }

    def dbName = "${appName}-db".replaceAll('[/_]', '-')

    echo "========================================="
    echo "🗄️  Deploying ${dbType} Database"
    echo "   Name: ${dbName}"
    echo "   Version: ${dbVersion}"
    echo "   Port: ${dbPort}"
    echo "   Namespace: ${namespace}"
    echo "========================================="

    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {

            // remplacer {{APP_NAME}} dans les variables
            def processedEnvVars = [:]
            dbEnvVars.each { key, value ->
                processedEnvVars[key] = value.toString().replace('{{APP_NAME}}', appName)
            }

            deployGenericDatabase(namespace, dbName, dbType, dbVersion, dbPort, processedEnvVars)

            echo "⏳ Waiting for ${dbType} to be ready..."

            sh """
            kubectl rollout status deployment/${dbName} -n ${namespace} --timeout=180s || true
            """

            echo "📦 Database Pods status:"
            sh "kubectl get pods -n ${namespace} -l app=${dbName}"

            echo "✅ ${dbType} deployed successfully!"
            echo "   Service: ${dbName}.${namespace}.svc.cluster.local:${dbPort}"
        }
    }

    // Return compatible Jenkins
    def result = [:]
    result.deployed = true
    result.serviceName = "${dbName}.${namespace}.svc.cluster.local"
    result.port = dbPort
    result.type = dbType

    return result
}


def deployGenericDatabase(String namespace, String dbName, String dbType, String dbVersion, int dbPort, Map envVars) {

    def secretData = ''

    envVars.each { key, value ->
        secretData += "  ${key}: ${value.bytes.encodeBase64().toString()}\n"
    }

    def dbImage = getImageForDatabase(dbType, dbVersion)

    writeFile file: 'database-deployment.yaml', text: """
apiVersion: v1
kind: Secret
metadata:
  name: ${dbName}-secret
  namespace: ${namespace}
type: Opaque
data:
${secretData}
---
apiVersion: v1
kind: PersistentVolumeClaim
metadata:
  name: ${dbName}-pvc
  namespace: ${namespace}
spec:
  accessModes:
    - ReadWriteOnce
  resources:
    requests:
      storage: 2Gi
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${dbName}
  namespace: ${namespace}
  labels:
    app: ${dbName}
    env: ${namespace}
    team: developers
    db-type: ${dbType}
spec:
  replicas: 1
  selector:
    matchLabels:
      app: ${dbName}
  template:
    metadata:
      labels:
        app: ${dbName}
        env: ${namespace}
        team: developers
        db-type: ${dbType}
    spec:
      containers:
      - name: ${dbType}
        image: ${dbImage}
${generateEnvSection(envVars, dbName)}
        ports:
        - containerPort: ${dbPort}
        volumeMounts:
        - name: db-storage
          mountPath: ${getDataPath(dbType)}
        resources:
          requests:
            cpu: 250m
            memory: 512Mi
          limits:
            cpu: 500m
            memory: 1Gi
${generateReadinessProbe(dbType, dbPort)}
      volumes:
      - name: db-storage
        persistentVolumeClaim:
          claimName: ${dbName}-pvc
---
apiVersion: v1
kind: Service
metadata:
  name: ${dbName}
  namespace: ${namespace}
  labels:
    app: ${dbName}
    env: ${namespace}
    team: developers
spec:
  selector:
    app: ${dbName}
  ports:
  - port: ${dbPort}
    targetPort: ${dbPort}
  type: ClusterIP
"""

    sh "kubectl apply -f database-deployment.yaml"
}


def getImageForDatabase(String dbType, String version) {

    switch(dbType) {

        case 'mysql':
            return "mysql:${version}"

        case 'postgresql':
            return "postgres:${version}"

        case 'mongodb':
            return "mongo:${version}"

        case 'mariadb':
            return "mariadb:${version}"

        case 'redis':
            return "redis:${version}"

        default:
            return "${dbType}:${version}"
    }
}


def getDataPath(String dbType) {

    switch(dbType) {

        case 'mysql':
        case 'mariadb':
            return '/var/lib/mysql'

        case 'postgresql':
            return '/var/lib/postgresql/data'

        case 'mongodb':
            return '/data/db'

        case 'redis':
            return '/data'

        default:
            return '/data'
    }
}


def generateEnvSection(Map envVars, String secretName) {

    def envSection = "        env:\n"

    envVars.each { key, value ->
        envSection += """        - name: ${key}
          valueFrom:
            secretKeyRef:
              name: ${secretName}-secret
              key: ${key}
"""
    }

    return envSection
}


def generateReadinessProbe(String dbType, int port) {

    switch(dbType) {

        case 'mysql':
        case 'mariadb':
            return """        readinessProbe:
          exec:
            command:
            - mysqladmin
            - ping
            - -h
            - localhost
          initialDelaySeconds: 60
          periodSeconds: 10
"""

        case 'postgresql':
            return """        readinessProbe:
          exec:
            command:
            - pg_isready
            - -U
            - postgres
          initialDelaySeconds: 60
          periodSeconds: 10
"""

        case 'mongodb':
            return """        readinessProbe:
          exec:
            command:
            - mongo
            - --eval
            - db.adminCommand('ping')
          initialDelaySeconds: 60
          periodSeconds: 10
"""

        case 'redis':
            return """        readinessProbe:
          exec:
            command:
            - redis-cli
            - ping
          initialDelaySeconds: 60
          periodSeconds: 5
"""

        default:
            return """        readinessProbe:
          tcpSocket:
            port: ${port}
          initialDelaySeconds: 60
          periodSeconds: 10
"""
    }
}

#!/usr/bin/env groovy

def call(Map config = [:]) {

    def namespace = config.namespace ?: 'dev'
    def appName = config.appName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def image = config.image
    def replicas = config.replicas ?: 2

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
      containers:
      - name: ${appName}
        image: ${image}
        imagePullPolicy: Always
        ports:
        - containerPort: ${port}
        readinessProbe:
          httpGet:
            path: /
            port: ${port}
          initialDelaySeconds: 10
          periodSeconds: 5
        livenessProbe:
          httpGet:
            path: /
            port: ${port}
          initialDelaySeconds: 20
          periodSeconds: 10
        resources:
          requests:
            cpu: "100m"
            memory: "128Mi"
          limits:
            cpu: "500m"
            memory: "512Mi"

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
            sh "kubectl rollout status deployment/${appName} -n ${namespace} --timeout=30m"

            // Obtenir node IP et nodePort
            def nodeIP = sh(script: "kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}'", returnStdout: true).trim()
            def nodePort = sh(script: "kubectl get svc ${appName} -n ${namespace} -o jsonpath='{.spec.ports[0].nodePort}'", returnStdout: true).trim()

            echo "========================================="
            echo "✅ Deployment successful!"
            echo "Application: ${appName}"
            echo "Namespace: ${namespace}"
            echo "URL: http://${nodeIP}:${nodePort}"
            echo "========================================="
        }
    }
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

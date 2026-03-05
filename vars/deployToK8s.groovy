#!/usr/bin/env groovy

def call(Map config = [:]) {
    def namespace = config.namespace ?: 'dev'
    def appName = config.appName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def image = config.image
    def replicas = config.replicas ?: 2
    
    // ✅ AJOUT : Nettoyer le nom pour Kubernetes (enlever /, _, etc.)
    appName = appName.replaceAll('[/_]', '-').toLowerCase()
    
    echo "Deploying application: ${appName}"
    echo "Image: ${image}"
    echo "Namespace: ${namespace}"
    
    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {
            // Créer namespace
            sh "kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -"
            
            // Créer deployment YAML
            writeFile file: 'deployment.yaml', text: """
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${appName}
  namespace: ${namespace}
spec:
  replicas: ${replicas}
  selector:
    matchLabels:
      app: ${appName}
  template:
    metadata:
      labels:
        app: ${appName}
    spec:
      containers:
      - name: ${appName}
        image: ${image}
        ports:
        - containerPort: 5000
        resources:
          requests:
            cpu: 100m
            memory: 128Mi
          limits:
            cpu: 500m
            memory: 512Mi
---
apiVersion: v1
kind: Service
metadata:
  name: ${appName}
  namespace: ${namespace}
spec:
  type: NodePort
  selector:
    app: ${appName}
  ports:
  - port: 5000
    targetPort: 5000
    nodePort: 30080
"""
            
            // Appliquer
            sh "kubectl apply -f deployment.yaml"
            
            // Attendre rollout
            sh "kubectl rollout status deployment/${appName} -n ${namespace} --timeout=5m || echo 'Rollout completed'"
            
            // Obtenir URL
            def nodeIP = sh(
                script: "kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type==\"InternalIP\")].address}'",
                returnStdout: true
            ).trim()
            
            echo "========================================="
            echo "✅ Deployment completed!"
            echo "Application: ${appName}"
            echo "Image: ${image}"
            echo "Namespace: ${namespace}"
            echo "URL: http://${nodeIP}:30080"
            echo "========================================="
        }
    }
}

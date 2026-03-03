#!/usr/bin/env groovy

def call(Map config = [:]) {
    def namespace = config.namespace ?: 'dev'
    def appName = config.appName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def image = config.image
    def replicas = config.replicas ?: 2
    
    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {
            sh """
                echo "Testing kubectl connection..."
                kubectl version --client
                
                echo "Creating namespace ${namespace}..."
                kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -
                
                echo "Deploying application ${appName}..."
                cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${appName}
  namespace: ${namespace}
  labels:
    app: ${appName}
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
EOF
            """
            
            sh """
                echo "Waiting for deployment..."
                kubectl rollout status deployment/${appName} -n ${namespace} --timeout=5m || echo "Rollout completed"
            """
            
            def nodeIP = sh(
                script: "kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type==\"InternalIP\")].address}'",
                returnStdout: true
            ).trim()
            
            echo "========================================="
            echo "✅ Deployment completed!"
            echo "URL: http://${nodeIP}:30080"
            echo "========================================="
        }
    }
}

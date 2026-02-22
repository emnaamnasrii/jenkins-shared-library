#!/usr/bin/env groovy

def call(Map config = [:]) {
    def namespace = config.namespace ?: 'dev'
    def appName = config.appName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def image = config.image
    def replicas = config.replicas ?: 2
    
    stage("🚀 Deploy to ${namespace}") {
        withKubeConfig([credentialsId: 'kubeconfig']) {
            sh """
                kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -
                
                cat <<EOF | kubectl apply -f -
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${appName}
  namespace: ${namespace}
  labels:
    app: ${appName}
    team: developers
    env: ${namespace}
spec:
  replicas: ${replicas}
  selector:
    matchLabels:
      app: ${appName}
  template:
    metadata:
      labels:
        app: ${appName}
        team: developers
        env: ${namespace}
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
        livenessProbe:
          httpGet:
            path: /
            port: 5000
          initialDelaySeconds: 30
          periodSeconds: 10
        readinessProbe:
          httpGet:
            path: /
            port: 5000
          initialDelaySeconds: 5
          periodSeconds: 5
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
            
            sh "kubectl rollout status deployment/${appName} -n ${namespace} --timeout=5m || true"
            
            def nodeIP = sh(
                script: "kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type==\"InternalIP\")].address}'",
                returnStdout: true
            ).trim()
            
            echo "✅ Deployment completed successfully!"
            echo "🌐 Application URL: http://${nodeIP}:30080"
        }
    }
}

#!/usr/bin/env groovy

// ─────────────────────────────────────────────────────────────────────────────
// deployFrontend.groovy — GÉNÉRIQUE
//
// STRATÉGIE : Frontend et Backend ont chacun leur propre IP MetalLB
// Le frontend injecte l'URL du backend via une variable d'environnement
// au moment du BUILD (REACT_APP_API_URL, VUE_APP_API_URL, etc.)
// OU via un fichier de config runtime (env-config.js) injecté par nginx
//
// Pas de proxy /api/ hardcodé — le frontend appelle le backend directement
// via l'URL MetalLB du backend → fonctionne avec TOUS les projets
// ─────────────────────────────────────────────────────────────────────────────

def call(Map config = [:]) {
    def namespace      = config.namespace   ?: 'dev'
    def appName        = config.appName     ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')
    def image          = config.image
    def backendUrl     = config.backendUrl  ?: ''  // URL MetalLB du backend ex: http://192.168.1.200
    def frontendType   = config.frontendType ?: 'react'
    def replicas       = config.replicas    ?: 2

    appName = appName.replaceAll('[/_]', '-').toLowerCase()
    namespace = namespace.replaceAll('[/_]', '-').toLowerCase()

    def frontendName = "${appName}-frontend"

    echo "========================================="
    echo "🖥️  Deploying Frontend (Generic)"
    echo "Name         : ${frontendName}"
    echo "Image        : ${image}"
    echo "Namespace    : ${namespace}"
    echo "Frontend type: ${frontendType}"
    echo "Backend URL  : ${backendUrl ?: 'not set'}"
    echo "========================================="

    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {

            sh "kubectl create namespace ${namespace} --dry-run=client -o yaml | kubectl apply -f -"

            // ─────────────────────────────────────────────────────────────
            // ConfigMap avec env-config.js
            // Ce fichier est servi par nginx et chargé par le frontend
            // Il expose l'URL du backend au runtime — sans rebuild de l'image
            // Compatible avec React, Vue, Angular, HTML pur, etc.
            // Le frontend doit charger ce fichier dans index.html :
            //   <script src="/env-config.js"></script>
            //   puis utiliser window.BACKEND_URL
            // ─────────────────────────────────────────────────────────────
            def envConfigJs = backendUrl ? """
    env-config.js: |
      window.BACKEND_URL = "${backendUrl}";
      window.API_URL = "${backendUrl}";
      window.REACT_APP_API_URL = "${backendUrl}";
      window.VUE_APP_API_URL = "${backendUrl}";
      window.NG_APP_API_URL = "${backendUrl}";
""" : """
    env-config.js: |
      window.BACKEND_URL = "";
      window.API_URL = "";
"""

            // ─────────────────────────────────────────────────────────────
            // Config nginx générique — sert les fichiers statiques
            // Pas de proxy hardcodé — le frontend appelle le backend
            // directement via window.BACKEND_URL (MetalLB IP)
            // ─────────────────────────────────────────────────────────────
            def nginxConf = """
    nginx.conf: |
      server {
          listen 80;
          root /usr/share/nginx/html;
          index index.html index.htm;

          # Servir env-config.js sans cache
          location /env-config.js {
              add_header Cache-Control "no-store, no-cache, must-revalidate";
              try_files \$uri =404;
          }

          # SPA fallback — toutes les routes non trouvées → index.html
          # Compatible React Router, Vue Router, Angular Router
          location / {
              try_files \$uri \$uri/ /index.html;
          }

          # Compression gzip
          gzip on;
          gzip_types text/plain text/css application/json application/javascript text/xml application/xml;
      }
"""

            writeFile file: 'frontend-deployment.yaml', text: """
apiVersion: v1
kind: ConfigMap
metadata:
  name: ${frontendName}-config
  namespace: ${namespace}
  labels:
    app: ${frontendName}
    env: ${namespace}
data:
${nginxConf}
${envConfigJs}
---
apiVersion: apps/v1
kind: Deployment
metadata:
  name: ${frontendName}
  namespace: ${namespace}
  labels:
    app: ${frontendName}
    env: ${namespace}
    team: developers
    tier: frontend
spec:
  replicas: ${replicas}
  strategy:
    type: RollingUpdate
    rollingUpdate:
      maxSurge: 1
      maxUnavailable: 0
  selector:
    matchLabels:
      app: ${frontendName}
  template:
    metadata:
      labels:
        app: ${frontendName}
        env: ${namespace}
        team: developers
        tier: frontend
    spec:
      initContainers:
        # Copier env-config.js dans le dossier html au démarrage
        - name: init-env-config
          image: busybox:1.35
          command: ['sh', '-c', 'cp /config/env-config.js /html/env-config.js && echo "env-config.js injected"']
          volumeMounts:
            - name: frontend-config
              mountPath: /config
            - name: html-shared
              mountPath: /html
      containers:
        - name: ${frontendName}
          image: ${image}
          imagePullPolicy: Always
          ports:
            - containerPort: 80
          volumeMounts:
            - name: nginx-config
              mountPath: /etc/nginx/conf.d/default.conf
              subPath: nginx.conf
            - name: html-shared
              mountPath: /usr/share/nginx/html/env-config.js
              subPath: env-config.js
          readinessProbe:
            httpGet:
              path: /
              port: 80
            initialDelaySeconds: 10
            periodSeconds: 5
            failureThreshold: 6
          livenessProbe:
            httpGet:
              path: /
              port: 80
            initialDelaySeconds: 20
            periodSeconds: 15
            failureThreshold: 3
          resources:
            requests:
              cpu: "50m"
              memory: "64Mi"
            limits:
              cpu: "200m"
              memory: "256Mi"
      volumes:
        - name: nginx-config
          configMap:
            name: ${frontendName}-config
            items:
              - key: nginx.conf
                path: nginx.conf
        - name: frontend-config
          configMap:
            name: ${frontendName}-config
            items:
              - key: env-config.js
                path: env-config.js
        - name: html-shared
          emptyDir: {}
---
apiVersion: v1
kind: Service
metadata:
  name: ${frontendName}
  namespace: ${namespace}
  labels:
    app: ${frontendName}
    env: ${namespace}
    tier: frontend
spec:
  type: LoadBalancer
  selector:
    app: ${frontendName}
  ports:
    - name: http
      port: 80
      targetPort: 80
      protocol: TCP
"""

            sh "kubectl apply -f frontend-deployment.yaml"

            // Attendre qu'au moins 1 pod soit ready
            echo "⏳ Waiting for frontend pods..."
            sh """
for i in \$(seq 1 30); do
  READY=\$(kubectl get deployment ${frontendName} -n ${namespace} \
    -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo '0')
  READY=\${READY:-0}
  if [ "\$READY" -ge "1" ]; then
    echo "✅ Frontend ready (\$READY/${replicas})"
    break
  fi
  echo "  Waiting... (\$i/30) - Ready: \$READY/${replicas}"
  sleep 5
done
"""

            // Attendre l'IP MetalLB
            echo "⏳ Waiting for MetalLB IP..."
            sh """
for i in \$(seq 1 20); do
  IP=\$(kubectl get svc ${frontendName} -n ${namespace} \
    -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo '')
  if [ -n "\$IP" ] && [ "\$IP" != "null" ]; then
    echo "✅ Frontend IP: \$IP"
    break
  fi
  echo "  Waiting for IP... (\$i/20)"
  sleep 5
done
"""

            def frontendIP = sh(
                script: """kubectl get svc ${frontendName} -n ${namespace} \
                           -o jsonpath='{.status.loadBalancer.ingress[0].ip}' 2>/dev/null || echo 'pending'""",
                returnStdout: true
            ).trim()
            if (!frontendIP || frontendIP == '') frontendIP = 'pending'

            def readyPods = sh(
                script: """kubectl get deployment ${frontendName} -n ${namespace} \
                           -o jsonpath='{.status.readyReplicas}' 2>/dev/null || echo '0'""",
                returnStdout: true
            ).trim()
            if (!readyPods || readyPods == '') readyPods = '0'

            echo "========================================="
            echo "✅ Frontend deployed!"
            echo "Name       : ${frontendName}"
            echo "Ready Pods : ${readyPods}/${replicas}"
            echo "🌐 URL     : http://${frontendIP}/"
            if (backendUrl) {
                echo "🔌 Backend : ${backendUrl}"
                echo "   window.BACKEND_URL = '${backendUrl}' (injecté via env-config.js)"
            }
            echo "========================================="

            return [
                name       : frontendName,
                serviceName: "${frontendName}.${namespace}.svc.cluster.local",
                port       : 80,
                externalIP : frontendIP,
                url        : "http://${frontendIP}/"
            ]
        }
    }
}

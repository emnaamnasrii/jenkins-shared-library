#!/usr/bin/env groovy

def call(Map config = [:]) {
    def namespace = config.namespace ?: 'dev'
    def appName   = config.appName ?: env.JOB_NAME.toLowerCase().replaceAll('/', '-')

    appName = appName.replaceAll('[/_]', '-').toLowerCase()

    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {

            // ── Lire l'IP externe assignée par MetalLB ────────────────
            def externalIP = sh(
                script: """kubectl get svc ${appName} -n ${namespace} \
                           -o jsonpath='{.status.loadBalancer.ingress[0].ip}' \
                           2>/dev/null || echo ''""",
                returnStdout: true
            ).trim()

            // ── Valeur par défaut si MetalLB pas encore prêt ──────────
            if (!externalIP || externalIP == '' || externalIP == 'null') {
                echo "⚠️ MetalLB IP not yet assigned — retrying..."

                // Attendre jusqu'à 30 secondes
                for (int i = 0; i < 6; i++) {
                    sleep 5
                    externalIP = sh(
                        script: """kubectl get svc ${appName} -n ${namespace} \
                                   -o jsonpath='{.status.loadBalancer.ingress[0].ip}' \
                                   2>/dev/null || echo ''""",
                        returnStdout: true
                    ).trim()

                    if (externalIP && externalIP != '' && externalIP != 'null') {
                        echo "✅ MetalLB IP found: ${externalIP}"
                        break
                    }
                }
            }

            // ── Si toujours pas d'IP — fallback sur NodeIP ────────────
            if (!externalIP || externalIP == '' || externalIP == 'null') {
                echo "⚠️ MetalLB IP not available — falling back to NodeIP"
                def nodeIP = sh(
                    script: "kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}'",
                    returnStdout: true
                ).trim()
                def nodePort = sh(
                    script: """kubectl get svc ${appName} -n ${namespace} \
                               -o jsonpath='{.spec.ports[0].nodePort}' \
                               2>/dev/null || echo '30080'""",
                    returnStdout: true
                ).trim()
                return "http://${nodeIP}:${nodePort}"
            }

            // ── Retourner l'URL MetalLB sur le port 80 ────────────────
            echo "🌐 App URL (MetalLB): http://${externalIP}/"
            return "http://${externalIP}"
        }
    }
}

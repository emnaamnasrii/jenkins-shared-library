#!/usr/bin/env groovy

def call(Map config = [:]) {
    def appUrl    = config.appUrl    ?: env.APP_URL
    def duration  = config.duration  ?: 120  // secondes de scan actif
    def threshold = config.threshold ?: 'Medium'  // Low, Medium, High

    echo "========================================="
    echo "🛡️  OWASP ZAP Security Scan (Daemon Mode)"
    echo "Target    : ${appUrl}"
    echo "Duration  : ${duration}s"
    echo "Threshold : ${threshold}"
    echo "========================================="

    container('docker') {
        sh """
            set -e

            TARGET_URL="${appUrl}"
            ZAP_PORT=8090
            ZAP_API_KEY="zap-jenkins-\$(date +%s)"
            REPORT_DIR="\$(pwd)/zap-reports"
            mkdir -p \${REPORT_DIR}

            echo "🔍 Pulling ZAP image..."
            docker pull ghcr.io/zaproxy/zaproxy:stable 2>/dev/null || \
            docker pull owasp/zap2docker-stable 2>/dev/null || \
            echo "⚠️ Using cached ZAP image"

            # Déterminer l'image ZAP disponible
            ZAP_IMAGE="ghcr.io/zaproxy/zaproxy:stable"
            docker image inspect \${ZAP_IMAGE} > /dev/null 2>&1 || \
                ZAP_IMAGE="owasp/zap2docker-stable"

            echo "Using ZAP image: \${ZAP_IMAGE}"

            # ── Démarrer ZAP en mode daemon ──────────────────────────────
            echo "🚀 Starting ZAP daemon..."
            docker run -d \
                --name zap-daemon-${env.BUILD_NUMBER} \
                --network host \
                -v \${REPORT_DIR}:/zap/wrk:rw \
                \${ZAP_IMAGE} \
                zap.sh -daemon \
                    -host 127.0.0.1 \
                    -port \${ZAP_PORT} \
                    -config api.key=\${ZAP_API_KEY} \
                    -config api.addrs.addr.name=.* \
                    -config api.addrs.addr.regex=true \
                    -config connection.timeoutInSecs=30 \
                    -config scanner.maxScanDurationInMins=2 \
                    -config ajaxSpider.browserId=htmlunit \
                    -config connection.dnsTtlSuccessfulQueries=0 \
                    -Xmx512m

            echo "⏳ Waiting for ZAP daemon to start..."
            for i in \$(seq 1 30); do
                if curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/core/view/version/?apikey=\${ZAP_API_KEY}" > /dev/null 2>&1; then
                    echo "✅ ZAP daemon ready (attempt \${i}/30)"
                    break
                fi
                echo "  Waiting... (\${i}/30)"
                sleep 3
            done

            # Vérifier que ZAP est bien démarré
            ZAP_VERSION=\$(curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/core/view/version/?apikey=\${ZAP_API_KEY}" | grep -o '"version":"[^"]*"' || echo "unknown")
            echo "ZAP version: \${ZAP_VERSION}"

            # ── PHASE 1 : Spider passif ───────────────────────────────────
            echo "🕷️  Phase 1: Spider scan..."
            SPIDER_ID=\$(curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/spider/action/scan/?apikey=\${ZAP_API_KEY}&url=\${TARGET_URL}&maxChildren=10&recurse=true" | grep -o '"scan":"[0-9]*"' | grep -o '[0-9]*' || echo "0")
            echo "Spider ID: \${SPIDER_ID}"

            # Attendre la fin du spider (max 60s)
            for i in \$(seq 1 20); do
                SPIDER_PROGRESS=\$(curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/spider/view/status/?apikey=\${ZAP_API_KEY}&scanId=\${SPIDER_ID}" | grep -o '"status":"[^"]*"' | grep -o '[0-9]*' || echo "0")
                echo "  Spider progress: \${SPIDER_PROGRESS}%"
                if [ "\${SPIDER_PROGRESS}" = "100" ]; then
                    echo "✅ Spider complete"
                    break
                fi
                sleep 3
            done

            # ── PHASE 2 : Scan passif des alertes ────────────────────────
            echo "🔍 Phase 2: Passive scan..."
            sleep 10  # Laisser le scan passif analyser les résultats du spider

            # ── PHASE 3 : Scan actif (si durée > 0) ──────────────────────
            if [ "${duration}" -gt "0" ]; then
                echo "⚔️  Phase 3: Active scan (${duration}s max)..."
                ACTIVE_ID=\$(curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/ascan/action/scan/?apikey=\${ZAP_API_KEY}&url=\${TARGET_URL}&recurse=true&inScopeOnly=false&scanPolicyName=&method=&postData=" | grep -o '"scan":"[0-9]*"' | grep -o '[0-9]*' || echo "0")
                echo "Active scan ID: \${ACTIVE_ID}"

                # Attendre max ${duration} secondes
                END_TIME=\$((SECONDS + ${duration}))
                while [ \$SECONDS -lt \$END_TIME ]; do
                    ACTIVE_PROGRESS=\$(curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/ascan/view/status/?apikey=\${ZAP_API_KEY}&scanId=\${ACTIVE_ID}" | grep -o '"status":"[^"]*"' | grep -o '[0-9]*' || echo "0")
                    echo "  Active scan: \${ACTIVE_PROGRESS}%"
                    if [ "\${ACTIVE_PROGRESS}" = "100" ]; then
                        echo "✅ Active scan complete"
                        break
                    fi
                    sleep 10
                done

                # Arrêter le scan actif si encore en cours
                curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/ascan/action/stop/?apikey=\${ZAP_API_KEY}&scanId=\${ACTIVE_ID}" > /dev/null 2>&1 || true
            fi

            # ── PHASE 4 : Générer les rapports ───────────────────────────
            echo "📊 Generating reports..."

            # Rapport HTML
            curl -sf "http://127.0.0.1:\${ZAP_PORT}/OTHER/core/other/htmlreport/?apikey=\${ZAP_API_KEY}" \
                -o \${REPORT_DIR}/zap-report.html 2>/dev/null || \
                echo "⚠️ Could not generate HTML report"

            # Rapport JSON
            curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/core/view/alerts/?apikey=\${ZAP_API_KEY}&baseurl=\${TARGET_URL}&start=0&count=100" \
                -o \${REPORT_DIR}/zap-alerts.json 2>/dev/null || \
                echo "{}" > \${REPORT_DIR}/zap-alerts.json

            # ── PHASE 5 : Analyser les résultats ─────────────────────────
            echo "📈 Analyzing results..."

            HIGH=\$(curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/core/view/numberOfAlerts/?apikey=\${ZAP_API_KEY}&baseurl=\${TARGET_URL}&riskId=3" | grep -o '"numberOfAlerts":"[0-9]*"' | grep -o '[0-9]*' || echo "0")
            MEDIUM=\$(curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/core/view/numberOfAlerts/?apikey=\${ZAP_API_KEY}&baseurl=\${TARGET_URL}&riskId=2" | grep -o '"numberOfAlerts":"[0-9]*"' | grep -o '[0-9]*' || echo "0")
            LOW=\$(curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/core/view/numberOfAlerts/?apikey=\${ZAP_API_KEY}&baseurl=\${TARGET_URL}&riskId=1" | grep -o '"numberOfAlerts":"[0-9]*"' | grep -o '[0-9]*' || echo "0")
            INFO=\$(curl -sf "http://127.0.0.1:\${ZAP_PORT}/JSON/core/view/numberOfAlerts/?apikey=\${ZAP_API_KEY}&baseurl=\${TARGET_URL}&riskId=0" | grep -o '"numberOfAlerts":"[0-9]*"' | grep -o '[0-9]*' || echo "0")

            echo "========================================="
            echo "🛡️  ZAP Security Scan Results"
            echo "Target : \${TARGET_URL}"
            echo "-----------------------------------------"
            echo "🔴 HIGH   : \${HIGH:-0}"
            echo "🟠 MEDIUM : \${MEDIUM:-0}"
            echo "🟡 LOW    : \${LOW:-0}"
            echo "ℹ️  INFO   : \${INFO:-0}"
            echo "========================================="

            # ── Copier les rapports dans le workspace ─────────────────────
            cp \${REPORT_DIR}/zap-report.html ./zap-report.html 2>/dev/null || true
            cp \${REPORT_DIR}/zap-alerts.json ./zap-alerts.json 2>/dev/null || true

            # ── Arrêter et supprimer le container ZAP ────────────────────
            docker stop zap-daemon-${env.BUILD_NUMBER} 2>/dev/null || true
            docker rm   zap-daemon-${env.BUILD_NUMBER} 2>/dev/null || true
            echo "✅ ZAP container cleaned up"

            # ── Seuil d'alerte ────────────────────────────────────────────
            # Ne bloque pas le pipeline — affiche juste un warning
            if [ "\${HIGH:-0}" -gt "0" ]; then
                echo "⚠️ WARNING: \${HIGH} HIGH severity vulnerabilities found!"
                echo "   Review zap-report.html for details"
            else
                echo "✅ No HIGH severity vulnerabilities found"
            fi
        """

        // Archiver les rapports
        archiveArtifacts artifacts: 'zap-report.html, zap-alerts.json', allowEmptyArchive: true

        // Publier le rapport HTML dans Jenkins
        publishHTML([
            allowMissing         : true,
            alwaysLinkToLastBuild: true,
            keepAll              : true,
            reportDir            : '.',
            reportFiles          : 'zap-report.html',
            reportName           : 'ZAP Security Report'
        ])
    }
}

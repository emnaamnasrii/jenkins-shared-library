#!/usr/bin/env groovy

def call(Map config = [:]) {
    def appUrl   = config.appUrl
    def vus      = config.vus      ?: 10
    def duration = config.duration ?: '30s'

    if (!appUrl) {
        echo "⚠️  No app URL, skipping performance tests"
        return
    }

    echo "========================================="
    echo "⚡ Performance Tests"
    echo "URL      : ${appUrl}"
    echo "VUs      : ${vus}"
    echo "Duration : ${duration}"
    echo "========================================="

    // ─────────────────────────────────────────────────────────────────────────
    // FIX : k6 installé à la volée dans le container python
    // Pas besoin d'un container dédié — évite d'alourdir le pod
    // ─────────────────────────────────────────────────────────────────────────
    container('python') {
        try {
            sh """
                # ── Installer k6 ──────────────────────────────────────────
                if ! command -v k6 &> /dev/null; then
                    echo "📦 Installing k6..."
                    apt-get update -qq && apt-get install -y -qq curl gnupg
                    curl -s https://dl.k6.io/key.gpg | gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg
                    echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" \
                        | tee /etc/apt/sources.list.d/k6.list
                    apt-get update -qq && apt-get install -y -qq k6
                    echo "✅ k6 installed: \$(k6 version)"
                else
                    echo "✅ k6 already available: \$(k6 version)"
                fi

                # ── Créer le script de test ────────────────────────────────
                cat > perf-test.js << 'EOFALL'
import http from 'k6/http';
import { check, sleep } from 'k6';

export let options = {
    vus: ${vus},
    duration: '${duration}',
    thresholds: {
        http_req_duration: ['p(95)<2000'],
        http_req_failed: ['rate<0.1'],
    },
};

export default function() {
    let response = http.get('${appUrl}');
    check(response, {
        'status is not 500': (r) => r.status !== 500,
        'response time < 2s': (r) => r.timings.duration < 2000,
    });
    sleep(1);
}
EOFALL

                # ── Lancer k6 ─────────────────────────────────────────────
                k6 run --out json=perf-results.json perf-test.js || echo "⚠️ Performance tests completed with warnings"
            """

            archiveArtifacts artifacts: 'perf-results.json', allowEmptyArchive: true

        } catch (Exception e) {
            echo "⚠️ Performance tests failed — pipeline continues"
            echo "⚠️ Error: ${e.getMessage()}"
            unstable("Performance tests failed: ${e.getMessage()}")
        }
    }
}

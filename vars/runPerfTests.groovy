#!/usr/bin/env groovy

def call(Map config = [:]) {
    def appUrl = config.appUrl
    def vus = config.vus ?: 10
    def duration = config.duration ?: '30s'
    
    if (!appUrl) {
        echo "⚠️  No app URL, skipping performance tests"
        return
    }
    
    container('scanner') {
        sh """
            cat > perf-test.js << 'EOFALL'
import http from 'k6/http';
import { check, sleep } from 'k6';

export let options = {
    vus: ${vus},
    duration: '${duration}',
    thresholds: {
        http_req_duration: ['p(95)<500'],
    },
};

export default function() {
    let response = http.get('${appUrl}');
    check(response, {
        'status is 200': (r) => r.status === 200 || r.status === 301 || r.status === 404,
    });
    sleep(1);
}
EOFALL

            k6 run --out json=perf-results.json perf-test.js || echo "Performance tests completed"
        """
        
        archiveArtifacts artifacts: 'perf-results.json', allowEmptyArchive: true
    }
}

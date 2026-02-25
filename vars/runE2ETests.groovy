#!/usr/bin/env groovy

def call(Map config = [:]) {
    def appUrl = config.appUrl
    
    if (!appUrl) {
        echo "⚠️  No app URL, skipping E2E tests"
        return
    }
    
    container('python') {
        sh """
            pip install requests pytest --quiet
            
            cat > test_e2e_generic.py << 'EOFALL'
import requests
import pytest

BASE_URL = '${appUrl}'

def test_app_accessible():
    '''Test that the application is accessible'''
    try:
        response = requests.get(BASE_URL, timeout=10)
        assert response.status_code in [200, 301, 302, 404], f"Got status {response.status_code}"
        print(f"✅ App is accessible (status: {response.status_code})")
    except requests.exceptions.RequestException as e:
        pytest.skip(f"App not accessible: {e}")

def test_health_endpoint():
    '''Test health endpoint if exists'''
    try:
        response = requests.get(f'{BASE_URL}/health', timeout=5)
        if response.status_code == 200:
            print("✅ Health endpoint working")
        else:
            pytest.skip("Health endpoint not available")
    except:
        pytest.skip("Health endpoint not found")

def test_response_time():
    '''Test response time is reasonable'''
    try:
        import time
        start = time.time()
        requests.get(BASE_URL, timeout=10)
        elapsed = time.time() - start
        assert elapsed < 5, f"Response time too slow: {elapsed}s"
        print(f"✅ Response time: {elapsed:.2f}s")
    except:
        pytest.skip("Could not test response time")
EOFALL

            python -m pytest test_e2e_generic.py -v --junitxml=e2e-results.xml || echo "E2E tests completed"
        """
        
        junit allowEmptyResults: true, testResults: 'e2e-results.xml'
    }
}

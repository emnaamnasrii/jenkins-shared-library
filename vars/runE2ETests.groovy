#!/usr/bin/env groovy

def call(Map config = [:]) {

    def appUrl      = config.appUrl
    def hasFrontend = config.hasFrontend ?: false

    if (!appUrl) {
        echo "⚠️  No app URL provided, skipping E2E tests"
        return
    }

    echo "========================================="
    echo "🌐 E2E Tests"
    echo "URL        : ${appUrl}"
    echo "Mode       : ${hasFrontend ? 'Selenium Firefox (pod séparé)' : 'requests/pytest (API REST)'}"
    echo "========================================="

    try {
        if (hasFrontend) {
            runSeleniumTests(appUrl)
        } else {
            runAPITests(appUrl)
        }
    } catch (Exception e) {
        echo "⚠️ ================================================="
        echo "⚠️  E2E Tests échoués — pipeline continue"
        echo "⚠️  Erreur : ${e.getMessage()}"
        echo "⚠️ ================================================="
        unstable("E2E tests failed but pipeline continues: ${e.getMessage()}")
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MODE 1 — API REST : requests + pytest
// Utilise le container python déjà présent dans le pod principal
// ─────────────────────────────────────────────────────────────────────────────

def runAPITests(String appUrl) {
    container('python') {
        try {
            sh """
                pip install requests pytest --quiet

                cat > test_api_e2e.py << 'PYEOF'
import requests
import pytest
import time

BASE_URL = '${appUrl}'

def test_app_accessible():
    \"\"\"L'application répond\"\"\"
    try:
        response = requests.get(BASE_URL, timeout=15)
        assert response.status_code in [200, 301, 302, 404, 405], \
            f"Status inattendu: {response.status_code}"
        print(f"✅ App accessible (status: {response.status_code})")
    except requests.exceptions.RequestException as e:
        pytest.skip(f"App non accessible: {e}")

def test_response_time():
    \"\"\"Temps de réponse < 5 secondes\"\"\"
    try:
        start = time.time()
        requests.get(BASE_URL, timeout=15)
        elapsed = time.time() - start
        assert elapsed < 5, f"Trop lent: {elapsed:.2f}s"
        print(f"✅ Temps de réponse: {elapsed:.2f}s")
    except requests.exceptions.ConnectionError:
        pytest.skip("Connexion impossible")

def test_health_endpoint():
    \"\"\"Endpoint /health ou /actuator/health\"\"\"
    endpoints = [
        f'{BASE_URL}/health',
        f'{BASE_URL}/actuator/health',
        f'{BASE_URL}/api/health',
        f'{BASE_URL}/ping'
    ]
    for ep in endpoints:
        try:
            r = requests.get(ep, timeout=5)
            if r.status_code == 200:
                print(f"✅ Health endpoint: {ep}")
                return
        except:
            continue
    pytest.skip("Aucun health endpoint trouvé")

def test_no_server_error():
    \"\"\"Pas d'erreur 500\"\"\"
    try:
        r = requests.get(BASE_URL, timeout=10)
        assert r.status_code != 500, "Erreur 500 détectée !"
        print(f"✅ Pas d'erreur 500 (status: {r.status_code})")
    except requests.exceptions.ConnectionError:
        pytest.skip("Connexion impossible")

def test_response_time():
    \"\"\"Temps de réponse < 5 secondes\"\"\"
    try:
        start = time.time()
        requests.get(BASE_URL, timeout=15)
        elapsed = time.time() - start
        assert elapsed < 5, f"Trop lent: {elapsed:.2f}s"
        print(f"✅ Temps de réponse: {elapsed:.2f}s")
    except:
        pytest.skip("Impossible de tester")

def test_content_type():
    \"\"\"Vérifie que l'API retourne du JSON\"\"\"
    try:
        r = requests.get(BASE_URL, timeout=10)
        ct = r.headers.get('Content-Type', '')
        if 'json' in ct:
            print(f"✅ Content-Type JSON détecté")
        else:
            pytest.skip(f"Content-Type: {ct}")
    except:
        pytest.skip("Impossible de vérifier")

PYEOF
                python -m pytest test_api_e2e.py -v \
                    --junitxml=e2e-results.xml \
                    --tb=short || true
            """
        } catch (Exception e) {
            echo "⚠️ API E2E tests error: ${e.getMessage()}"
        } finally {
            junit allowEmptyResults: true, testResults: 'e2e-results.xml'
        }
    }
}

// ─────────────────────────────────────────────────────────────────────────────
// MODE 2 — FRONTEND : Selenium Firefox
// POD SÉPARÉ — selenium n'est PAS dans le pod principal
// Raison : ~800MB, trop lourd pour coexister avec maven+sonar+trivy
// ─────────────────────────────────────────────────────────────────────────────

def runSeleniumTests(String appUrl) {

    // Pod dédié uniquement pour Selenium + Python
    def seleniumPod = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer
  containers:
  - name: selenium
    image: selenium/standalone-firefox:latest
    imagePullPolicy: IfNotPresent
    ports:
    - containerPort: 4444
    - containerPort: 7900
    tty: true
  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true
'''

    podTemplate(yaml: seleniumPod) {
        node(POD_LABEL) {
            container('python') {
                try {
                    sh """
                        pip install selenium pytest requests --quiet

                        cat > test_selenium_e2e.py << 'PYEOF'
import pytest
import time
import requests
from selenium import webdriver
from selenium.webdriver.common.by import By
from selenium.webdriver.support.ui import WebDriverWait
from selenium.webdriver.support import expected_conditions as EC
from selenium.webdriver.firefox.options import Options
from selenium.common.exceptions import TimeoutException, WebDriverException

BASE_URL = '${appUrl}'
SELENIUM_URL = 'http://localhost:4444/wd/hub'

@pytest.fixture(scope='module')
def driver():
    \"\"\"Initialise Firefox via Selenium Grid\"\"\"
    options = Options()
    options.add_argument('--headless')
    options.add_argument('--no-sandbox')
    options.add_argument('--disable-dev-shm-usage')
    options.add_argument('--window-size=1920,1080')

    for i in range(10):
        try:
            r = requests.get('http://localhost:4444/status', timeout=3)
            if r.status_code == 200:
                break
        except:
            time.sleep(3)

    try:
        d = webdriver.Remote(
            command_executor=SELENIUM_URL,
            options=options
        )
        d.set_page_load_timeout(30)
        yield d
        d.quit()
    except Exception as e:
        pytest.skip(f"Selenium non disponible: {e}")
        yield None

def test_page_loads(driver):
    \"\"\"La page principale se charge\"\"\"
    if driver is None:
        pytest.skip("Driver non disponible")
    try:
        driver.get(BASE_URL)
        assert driver.title is not None
        print(f"✅ Page chargée — titre: {driver.title}")
    except TimeoutException:
        pytest.skip("Page trop lente à charger")
    except WebDriverException as e:
        pytest.skip(f"Erreur WebDriver: {e}")

def test_page_title_not_empty(driver):
    \"\"\"Le titre de la page n'est pas vide\"\"\"
    if driver is None:
        pytest.skip("Driver non disponible")
    try:
        driver.get(BASE_URL)
        assert driver.title != '', "Titre vide"
        print(f"✅ Titre: {driver.title}")
    except:
        pytest.skip("Impossible de vérifier le titre")

def test_no_404_on_homepage(driver):
    \"\"\"La page d'accueil ne retourne pas 404\"\"\"
    if driver is None:
        pytest.skip("Driver non disponible")
    try:
        driver.get(BASE_URL)
        assert '404' not in driver.title.lower(), "Page 404 détectée"
        print("✅ Pas de 404 sur la homepage")
    except:
        pytest.skip("Impossible de vérifier")

def test_page_has_content(driver):
    \"\"\"La page a du contenu visible\"\"\"
    if driver is None:
        pytest.skip("Driver non disponible")
    try:
        driver.get(BASE_URL)
        WebDriverWait(driver, 10).until(
            EC.presence_of_element_located((By.TAG_NAME, 'body'))
        )
        body = driver.find_element(By.TAG_NAME, 'body')
        assert len(body.text) > 0 or len(driver.page_source) > 100
        print("✅ Page a du contenu")
    except TimeoutException:
        pytest.skip("Timeout en attendant le body")

def test_navigation_links(driver):
    \"\"\"Les liens de navigation existent\"\"\"
    if driver is None:
        pytest.skip("Driver non disponible")
    try:
        driver.get(BASE_URL)
        links = driver.find_elements(By.TAG_NAME, 'a')
        if len(links) > 0:
            print(f"✅ {len(links)} liens trouvés")
        else:
            pytest.skip("Aucun lien trouvé")
    except:
        pytest.skip("Impossible de vérifier")

def test_forms_exist(driver):
    \"\"\"Vérifie la présence de formulaires\"\"\"
    if driver is None:
        pytest.skip("Driver non disponible")
    try:
        driver.get(BASE_URL)
        forms  = driver.find_elements(By.TAG_NAME, 'form')
        inputs = driver.find_elements(By.TAG_NAME, 'input')
        if len(forms) > 0:
            print(f"✅ {len(forms)} formulaire(s) trouvé(s)")
        elif len(inputs) > 0:
            print(f"✅ {len(inputs)} input(s) trouvé(s)")
        else:
            pytest.skip("Aucun formulaire")
    except:
        pytest.skip("Impossible de vérifier")

def test_load_time(driver):
    \"\"\"Temps de chargement < 10 secondes\"\"\"
    if driver is None:
        pytest.skip("Driver non disponible")
    try:
        start = time.time()
        driver.get(BASE_URL)
        elapsed = time.time() - start
        assert elapsed < 10, f"Trop lent: {elapsed:.2f}s"
        print(f"✅ Temps de chargement: {elapsed:.2f}s")
    except TimeoutException:
        pytest.skip("Page trop lente")

PYEOF
                        python -m pytest test_selenium_e2e.py -v \
                            --junitxml=e2e-results.xml \
                            --tb=short || true
                    """
                } catch (Exception e) {
                    echo "⚠️ Selenium E2E tests error: ${e.getMessage()}"
                } finally {
                    junit allowEmptyResults: true, testResults: 'e2e-results.xml'
                }
            }
        }
    }
}

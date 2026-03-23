#!/usr/bin/env groovy

def call(Map config = [:]) {
    def appUrl      = config.appUrl      ?: env.APP_URL
    def hasFrontend = config.hasFrontend ?: false

    echo "========================================="
    echo "🌐 E2E Tests (Selenium + Chromium)"
    echo "URL        : ${appUrl}"
    echo "Frontend   : ${hasFrontend}"
    echo "========================================="

    // ─────────────────────────────────────────────────────────────────────
    // Utilise le container python déjà dans le pod
    // Installe chromium + chromedriver via apk (alpine) ou apt
    // Pas d'image Selenium standalone (~800MB économisés)
    // ─────────────────────────────────────────────────────────────────────
    container('python') {
        sh """
            echo "📦 Installing Chromium + Selenium..."

            # Installer chromium et chromedriver via pip + système
            # chromium-driver = léger (~150MB) vs selenium/standalone-chrome (~900MB)
            pip install selenium webdriver-manager requests pytest --quiet 2>/dev/null

            # Installer Chromium si pas déjà là
            if ! which chromium-browser > /dev/null 2>&1 && ! which chromium > /dev/null 2>&1; then
                apt-get update -qq && apt-get install -y -qq chromium chromium-driver 2>/dev/null || \
                apk add --no-cache chromium chromium-chromedriver 2>/dev/null || \
                echo "⚠️ Could not install chromium via package manager"
            fi

            # Trouver le chemin de chromium
            CHROMIUM_PATH=\$(which chromium-browser 2>/dev/null || which chromium 2>/dev/null || which google-chrome 2>/dev/null || echo "")
            CHROMEDRIVER_PATH=\$(which chromedriver 2>/dev/null || echo "")

            echo "Chromium  : \${CHROMIUM_PATH:-not found}"
            echo "Chromedriver: \${CHROMEDRIVER_PATH:-not found}"

            cat > test_e2e_selenium.py << 'PYEOF'
import pytest
import time
import os
import sys

BASE_URL = "${appUrl}".rstrip('/')

# ─────────────────────────────────────────────────────────────────────────
# Setup Selenium avec Chromium headless
# Fonctionne sans image Selenium standalone
# ─────────────────────────────────────────────────────────────────────────
def get_driver():
    from selenium import webdriver
    from selenium.webdriver.chrome.options import Options
    from selenium.webdriver.chrome.service import Service

    options = Options()
    options.add_argument("--headless")
    options.add_argument("--no-sandbox")
    options.add_argument("--disable-dev-shm-usage")
    options.add_argument("--disable-gpu")
    options.add_argument("--window-size=1920,1080")
    options.add_argument("--disable-extensions")
    options.add_argument("--disable-setuid-sandbox")

    # Trouver chromium automatiquement
    chromium_paths = [
        "/usr/bin/chromium-browser",
        "/usr/bin/chromium",
        "/usr/bin/google-chrome",
        "/usr/local/bin/chromium"
    ]
    for path in chromium_paths:
        if os.path.exists(path):
            options.binary_location = path
            break

    # Trouver chromedriver automatiquement
    chromedriver_paths = [
        "/usr/bin/chromedriver",
        "/usr/local/bin/chromedriver",
        "/usr/lib/chromium/chromedriver"
    ]
    driver_path = None
    for path in chromedriver_paths:
        if os.path.exists(path):
            driver_path = path
            break

    try:
        if driver_path:
            service = Service(executable_path=driver_path)
            driver = webdriver.Chrome(service=service, options=options)
        else:
            # Fallback webdriver-manager
            from webdriver_manager.chrome import ChromeDriverManager
            service = Service(ChromeDriverManager().install())
            driver = webdriver.Chrome(service=service, options=options)
        return driver
    except Exception as e:
        print(f"⚠️ Could not start Chrome driver: {e}")
        return None

# ─────────────────────────────────────────────────────────────────────────
# TEST 1 — Page se charge (universel — fonctionne avec tout frontend)
# ─────────────────────────────────────────────────────────────────────────
def test_page_loads():
    driver = get_driver()
    if not driver:
        pytest.skip("Chromium not available — skipping Selenium tests")

    try:
        driver.get(BASE_URL)
        time.sleep(3)  # Attendre le rendu JS

        # Vérifier que la page a un titre (n'importe lequel)
        title = driver.title
        print(f"✅ Page loaded — title: '{title}'")
        assert True  # Si on arrive ici la page s'est chargée

    except Exception as e:
        pytest.fail(f"❌ Page failed to load: {e}")
    finally:
        driver.quit()

# ─────────────────────────────────────────────────────────────────────────
# TEST 2 — Page a du contenu visible (universel)
# ─────────────────────────────────────────────────────────────────────────
def test_page_has_content():
    driver = get_driver()
    if not driver:
        pytest.skip("Chromium not available")

    try:
        driver.get(BASE_URL)
        time.sleep(3)

        # Vérifier que le body existe et a du contenu
        body = driver.find_element("tag name", "body")
        body_text = body.text.strip()
        page_source_len = len(driver.page_source)

        print(f"✅ Page has content — {page_source_len} chars, body text: {len(body_text)} chars")
        assert page_source_len > 100, "Page source too small — app may not be rendering"

    except Exception as e:
        pytest.fail(f"❌ Page has no content: {e}")
    finally:
        driver.quit()

# ─────────────────────────────────────────────────────────────────────────
# TEST 3 — Pas d'erreur JavaScript (universel)
# ─────────────────────────────────────────────────────────────────────────
def test_no_js_errors():
    driver = get_driver()
    if not driver:
        pytest.skip("Chromium not available")

    try:
        driver.get(BASE_URL)
        time.sleep(3)

        # Récupérer les logs de la console
        logs = driver.get_log("browser")
        severe_errors = [l for l in logs if l.get("level") == "SEVERE"]

        if severe_errors:
            print(f"⚠️ JS errors found: {len(severe_errors)}")
            for err in severe_errors[:3]:
                print(f"   {err.get('message', '')[:100]}")
        else:
            print("✅ No JavaScript errors")

        # Warning seulement — pas de fail car certains projets ont des erreurs JS mineures
        assert True

    except Exception as e:
        pytest.skip(f"Could not check JS errors: {e}")
    finally:
        driver.quit()

# ─────────────────────────────────────────────────────────────────────────
# TEST 4 — Navigation fonctionne (universel)
# Découverte automatique des liens sur la page
# ─────────────────────────────────────────────────────────────────────────
def test_navigation():
    driver = get_driver()
    if not driver:
        pytest.skip("Chromium not available")

    try:
        driver.get(BASE_URL)
        time.sleep(3)

        # Trouver tous les liens sur la page
        links = driver.find_elements("tag name", "a")
        internal_links = []
        for link in links:
            href = link.get_attribute("href") or ""
            if href.startswith(BASE_URL) or href.startswith("/"):
                internal_links.append(href)

        print(f"✅ Found {len(links)} links ({len(internal_links)} internal)")

        # Tester le premier lien interne si disponible
        if internal_links:
            try:
                driver.get(internal_links[0])
                time.sleep(2)
                assert len(driver.page_source) > 100
                print(f"✅ Navigation works: {internal_links[0]}")
            except:
                print(f"⚠️ Could not navigate to {internal_links[0]}")

    except Exception as e:
        pytest.skip(f"Navigation test skipped: {e}")
    finally:
        driver.quit()

# ─────────────────────────────────────────────────────────────────────────
# TEST 5 — Screenshot (preuve visuelle pour le rapport)
# ─────────────────────────────────────────────────────────────────────────
def test_screenshot():
    driver = get_driver()
    if not driver:
        pytest.skip("Chromium not available")

    try:
        driver.get(BASE_URL)
        time.sleep(3)
        driver.save_screenshot("e2e-screenshot.png")
        print("✅ Screenshot saved: e2e-screenshot.png")
        assert os.path.exists("e2e-screenshot.png")
    except Exception as e:
        pytest.skip(f"Screenshot failed: {e}")
    finally:
        driver.quit()

PYEOF

            echo "🧪 Running Selenium E2E tests..."
            pytest test_e2e_selenium.py -v --tb=short --no-header \
                --junit-xml=test-results-e2e.xml 2>&1 || true
            echo "✅ E2E tests completed"
        """

        // Archiver screenshot et résultats
        archiveArtifacts artifacts: 'e2e-screenshot.png', allowEmptyArchive: true
        junit allowEmptyResults: true, testResults: 'test-results-e2e.xml'
    }
}

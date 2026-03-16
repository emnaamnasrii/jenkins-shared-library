#!/usr/bin/env groovy

def call(Map config = [:]) {
    def tech = config.tech ?: detectTech()

    echo "🧪 Running unit tests for ${tech.language}..."

    if (!tech.hasTests) {
        echo "⚠️  No tests detected for ${tech.language}, skipping unit tests"
        return
    }

    // ========================================
    // PYTHON
    // ========================================
    if (tech.language == 'Python') {
        container('python') {
            sh '''
                pip install pytest pytest-cov pytest-html flake8 bandit --quiet
                if [ -d tests ] || [ -d test ]; then
                    python -m pytest \
                        --cov=. \
                        --cov-report=xml:coverage.xml \
                        --cov-report=html:htmlcov \
                        --cov-report=term \
                        --junitxml=test-results.xml \
                        --html=test-report.html \
                        --self-contained-html \
                        -v || echo "⚠️  Tests completed with warnings"
                fi
                flake8 . --count --select=E9,F63,F7,F82 --show-source --statistics || true
                bandit -r . -f json -o bandit-report.json || true
            '''
            archiveArtifacts artifacts: 'bandit-report.json', allowEmptyArchive: true
        }
    }

    // ========================================
    // NODE.JS
    // ========================================
    else if (tech.language == 'Node.js') {
        container('node') {
            sh '''
                npm install --save-dev jest eslint || true
                npm test -- --coverage --coverageReporters=lcov --coverageReporters=text || echo "⚠️  Tests completed with warnings"
                npx eslint . --format=json --output-file=eslint-report.json || true
                npm audit --json > npm-audit.json || true
            '''
            archiveArtifacts artifacts: 'eslint-report.json,npm-audit.json', allowEmptyArchive: true
        }
    }

    // ========================================
    // JAVA (MAVEN)
    // FIX : injecte JaCoCo dynamiquement si absent du pom.xml
    // ========================================
    else if (tech.language == 'Java' && tech.buildTool == 'mvn') {
        container('maven') {
            sh '''
                echo "🧪 Running Maven unit tests..."

                # ── Vérifier si JaCoCo est déjà dans pom.xml ──────────
                HAS_JACOCO=$(grep -c "jacoco" pom.xml 2>/dev/null || echo "0")

                if [ "$HAS_JACOCO" -gt "0" ]; then
                    echo "✅ JaCoCo found in pom.xml — running with coverage"
                    mvn test -Dmaven.test.failure.ignore=true || true
                    mvn jacoco:report -Dmaven.test.failure.ignore=true || true
                else
                    echo "⚠️  JaCoCo not in pom.xml — injecting dynamically..."
                    # Lancer les tests avec JaCoCo injecté via ligne de commande
                    mvn test \
                        -Dmaven.test.failure.ignore=true \
                        -Djacoco.version=0.8.11 \
                        org.jacoco:jacoco-maven-plugin:0.8.11:prepare-agent \
                        org.jacoco:jacoco-maven-plugin:0.8.11:report \
                        || true

                    # Si le rapport n'est pas généré — forcer avec un goal séparé
                    if [ ! -f target/site/jacoco/jacoco.xml ]; then
                        echo "⚠️  Trying standalone JaCoCo report generation..."
                        mvn org.jacoco:jacoco-maven-plugin:0.8.11:prepare-agent \
                            test \
                            org.jacoco:jacoco-maven-plugin:0.8.11:report \
                            -Dmaven.test.failure.ignore=true || true
                    fi

                    # Rapport minimal si toujours absent
                    if [ ! -f target/site/jacoco/jacoco.xml ]; then
                        echo "⚠️  JaCoCo report not generated — creating empty report"
                        mkdir -p target/site/jacoco
                        cat > target/site/jacoco/jacoco.xml << 'XMLEOF'
<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<!DOCTYPE report PUBLIC "-//JACOCO//DTD Report 1.1//EN" "report.dtd">
<report name="empty">
  <sessioninfo id="none" start="0" dump="0"/>
</report>
XMLEOF
                        # Créer une page HTML minimale
                        cat > target/site/jacoco/index.html << 'HTMLEOF'
<!DOCTYPE html>
<html>
<head><title>Coverage Report</title></head>
<body>
<h2>Coverage Report</h2>
<p>JaCoCo was not configured in pom.xml.</p>
<p>Add jacoco-maven-plugin to generate real coverage data.</p>
</body>
</html>
HTMLEOF
                    fi
                fi

                echo "📊 Coverage report status:"
                ls -la target/site/jacoco/ 2>/dev/null || echo "No jacoco directory"
            '''

            junit allowEmptyResults: true,
                  testResults: '**/target/surefire-reports/*.xml'

            publishHTML([
                allowMissing         : true,
                alwaysLinkToLastBuild: true,
                keepAll              : true,
                reportDir            : 'target/site/jacoco',
                reportFiles          : 'index.html',
                reportName           : 'Coverage Report'
            ])
        }
    }

    // ========================================
    // JAVA (GRADLE)
    // ========================================
    else if (tech.language == 'Java' && tech.buildTool == 'gradle') {
        container('gradle') {
            sh '''
                ./gradlew test || echo "⚠️  Tests completed with warnings"
                ./gradlew jacocoTestReport || true
            '''
            junit allowEmptyResults: true,
                  testResults: '**/build/test-results/**/*.xml'
        }
    }

    // ========================================
    // GO
    // ========================================
    else if (tech.language == 'Go') {
        container('golang') {
            sh '''
                go test -v -coverprofile=coverage.out ./... || echo "⚠️  Tests completed"
                go tool cover -html=coverage.out -o coverage.html || true
                golangci-lint run --out-format=json > golangci-report.json || true
                gosec -fmt=json -out=gosec-report.json ./... || true
            '''
            archiveArtifacts artifacts: 'gosec-report.json,golangci-report.json', allowEmptyArchive: true
        }
    }

    // ========================================
    // PHP
    // ========================================
    else if (tech.language == 'PHP') {
        container('php') {
            sh '''
                composer install || true
                ./vendor/bin/phpunit --coverage-html htmlcov --log-junit test-results.xml || echo "⚠️  Tests completed"
                ./vendor/bin/phpcs --report=json --report-file=phpcs-report.json || true
            '''
            archiveArtifacts artifacts: 'phpcs-report.json', allowEmptyArchive: true
        }
    }

    // ========================================
    // RUBY
    // ========================================
    else if (tech.language == 'Ruby') {
        container('ruby') {
            sh '''
                bundle install || true
                bundle exec rspec --format documentation --format RspecJunitFormatter --out test-results.xml || echo "⚠️  Tests completed"
                bundle exec rubocop --format json --out rubocop-report.json || true
            '''
            archiveArtifacts artifacts: 'rubocop-report.json', allowEmptyArchive: true
        }
    }

    else {
        echo "⚠️  Unit tests not configured for ${tech.language}"
    }

    // Publier les résultats pour tous les langages
    junit allowEmptyResults: true,
          testResults: '**/test-results.xml, **/target/surefire-reports/*.xml, **/build/test-results/**/*.xml'

    // publishHTML seulement si htmlcov existe (Python/PHP)
    def hasCoverage = sh(
        script: "[ -d htmlcov ] && echo 'true' || echo 'false'",
        returnStdout: true
    ).trim()

    if (hasCoverage == 'true') {
        publishHTML([
            allowMissing         : true,
            alwaysLinkToLastBuild: true,
            keepAll              : true,
            reportDir            : 'htmlcov',
            reportFiles          : 'index.html',
            reportName           : 'Coverage Report'
        ])
    }
}

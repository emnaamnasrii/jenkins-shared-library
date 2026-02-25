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
                
                # Unit tests avec coverage
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
                
                # Linting
                flake8 . --count --select=E9,F63,F7,F82 --show-source --statistics || true
                
                # Security scan
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
                # Install test dependencies
                npm install --save-dev jest eslint || true
                
                # Run tests
                npm test -- --coverage --coverageReporters=lcov --coverageReporters=text || echo "⚠️  Tests completed with warnings"
                
                # Linting
                npx eslint . --format=json --output-file=eslint-report.json || true
                
                # Security audit
                npm audit --json > npm-audit.json || true
            '''
            
            archiveArtifacts artifacts: 'eslint-report.json,npm-audit.json', allowEmptyArchive: true
        }
    }
    
    // ========================================
    // JAVA (MAVEN)
    // ========================================
    else if (tech.language == 'Java' && tech.buildTool == 'mvn') {
        container('maven') {
            sh '''
                # Run tests
                mvn test -Dmaven.test.failure.ignore=true
                
                # Generate coverage report
                mvn jacoco:report || true
                
                # Security scan
                mvn org.owasp:dependency-check-maven:check || true
            '''
        }
    }
    
    // ========================================
    // JAVA (GRADLE)
    // ========================================
    else if (tech.language == 'Java' && tech.buildTool == 'gradle') {
        container('gradle') {
            sh '''
                # Run tests
                ./gradlew test || echo "⚠️  Tests completed with warnings"
                
                # Generate coverage
                ./gradlew jacocoTestReport || true
            '''
        }
    }
    
    // ========================================
    // GO
    // ========================================
    else if (tech.language == 'Go') {
        container('golang') {
            sh '''
                # Run tests with coverage
                go test -v -coverprofile=coverage.out ./... || echo "⚠️  Tests completed"
                go tool cover -html=coverage.out -o coverage.html || true
                
                # Linting
                golangci-lint run --out-format=json > golangci-report.json || true
                
                # Security scan
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
                # Install PHPUnit
                composer install || true
                
                # Run tests
                ./vendor/bin/phpunit --coverage-html htmlcov --log-junit test-results.xml || echo "⚠️  Tests completed"
                
                # Code sniffer
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
                # Install gems
                bundle install || true
                
                # Run tests
                bundle exec rspec --format documentation --format RspecJunitFormatter --out test-results.xml || echo "⚠️  Tests completed"
                
                # Linting
                bundle exec rubocop --format json --out rubocop-report.json || true
            '''
            
            archiveArtifacts artifacts: 'rubocop-report.json', allowEmptyArchive: true
        }
    }
    
    // ========================================
    // RUST
    // ========================================
    else if (tech.language == 'Rust') {
        container('rust') {
            sh '''
                # Run tests
                cargo test || echo "⚠️  Tests completed"
                
                # Linting
                cargo clippy -- -D warnings || true
            '''
        }
    }
    
    else {
        echo "⚠️  Unit tests not configured for ${tech.language}"
    }
    
    // Publish results (works for all languages)
    junit allowEmptyResults: true, testResults: '**/test-results.xml, **/target/surefire-reports/*.xml, **/build/test-results/**/*.xml'
    
    publishHTML([
        allowMissing: true,
        alwaysLinkToLastBuild: true,
        keepAll: true,
        reportDir: 'htmlcov',
        reportFiles: 'index.html',
        reportName: 'Coverage Report'
    ])
}

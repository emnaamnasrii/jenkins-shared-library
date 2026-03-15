#!/usr/bin/env groovy

def call(String language) {

    def yaml = ""

    // ─────────────────────────────────────────────────────────────────────────
    // Containers communs à TOUS les langages :
    //   - python   : tests E2E API (pytest + requests)
    //   - selenium : tests E2E Frontend (Firefox headless)
    //   - docker   : build & push image
    //   - scanner  : SonarQube
    //   - trivy    : scan sécurité
    //   - kubectl  : déploiement Kubernetes
    // ─────────────────────────────────────────────────────────────────────────

    def commonContainers = '''
  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true

  - name: selenium
    image: selenium/standalone-firefox:latest
    ports:
    - containerPort: 4444
    - containerPort: 7900
    shm_size: '2g'
    tty: true

  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run

  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    command: ['cat']
    tty: true

  - name: trivy
    image: aquasec/trivy:latest
    command: ['cat']
    tty: true

  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true
'''

    def volumes = '''
  volumes:
  - name: docker-sock
    emptyDir: {}
'''

    def header = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer

  containers:
'''

    if (language == 'python') {

        yaml = header + '''  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true
''' + '''
  - name: selenium
    image: selenium/standalone-firefox:latest
    ports:
    - containerPort: 4444
    - containerPort: 7900
    shm_size: '2g'
    tty: true

  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run

  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    command: ['cat']
    tty: true

  - name: trivy
    image: aquasec/trivy:latest
    command: ['cat']
    tty: true

  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true

  volumes:
  - name: docker-sock
    emptyDir: {}
'''

    }

    else if (language == 'nodejs') {

        yaml = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer

  containers:
  - name: node
    image: node:18-alpine
    command: ['cat']
    tty: true

  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true

  - name: selenium
    image: selenium/standalone-firefox:latest
    ports:
    - containerPort: 4444
    - containerPort: 7900
    shm_size: '2g'
    tty: true

  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run

  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    command: ['cat']
    tty: true

  - name: trivy
    image: aquasec/trivy:latest
    command: ['cat']
    tty: true

  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true

  volumes:
  - name: docker-sock
    emptyDir: {}
'''

    }

    else if (language == 'java-maven') {

        yaml = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer

  containers:
  - name: maven
    image: maven:3.8.3-openjdk-17
    command: ['cat']
    tty: true

  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true

  - name: selenium
    image: selenium/standalone-firefox:latest
    ports:
    - containerPort: 4444
    - containerPort: 7900
    shm_size: '2g'
    tty: true

  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run

  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    command: ['cat']
    tty: true

  - name: trivy
    image: aquasec/trivy:latest
    command: ['cat']
    tty: true

  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true

  volumes:
  - name: docker-sock
    emptyDir: {}
'''

    }

    else if (language == 'java-gradle') {

        yaml = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer

  containers:
  - name: gradle
    image: gradle:8-jdk17
    command: ['cat']
    tty: true

  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true

  - name: selenium
    image: selenium/standalone-firefox:latest
    ports:
    - containerPort: 4444
    - containerPort: 7900
    shm_size: '2g'
    tty: true

  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run

  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    command: ['cat']
    tty: true

  - name: trivy
    image: aquasec/trivy:latest
    command: ['cat']
    tty: true

  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true

  volumes:
  - name: docker-sock
    emptyDir: {}
'''

    }

    else if (language == 'golang') {

        yaml = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer

  containers:
  - name: golang
    image: golang:1.21-alpine
    command: ['cat']
    tty: true

  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true

  - name: selenium
    image: selenium/standalone-firefox:latest
    ports:
    - containerPort: 4444
    - containerPort: 7900
    shm_size: '2g'
    tty: true

  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run

  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    command: ['cat']
    tty: true

  - name: trivy
    image: aquasec/trivy:latest
    command: ['cat']
    tty: true

  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true

  volumes:
  - name: docker-sock
    emptyDir: {}
'''

    }

    else if (language == 'php') {

        yaml = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer

  containers:
  - name: php
    image: php:8.2-cli
    command: ['cat']
    tty: true

  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true

  - name: selenium
    image: selenium/standalone-firefox:latest
    ports:
    - containerPort: 4444
    - containerPort: 7900
    shm_size: '2g'
    tty: true

  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run

  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    command: ['cat']
    tty: true

  - name: trivy
    image: aquasec/trivy:latest
    command: ['cat']
    tty: true

  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true

  volumes:
  - name: docker-sock
    emptyDir: {}
'''

    }

    else if (language == 'ruby') {

        yaml = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer

  containers:
  - name: ruby
    image: ruby:3.2-slim
    command: ['cat']
    tty: true

  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true

  - name: selenium
    image: selenium/standalone-firefox:latest
    ports:
    - containerPort: 4444
    - containerPort: 7900
    shm_size: '2g'
    tty: true

  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run

  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    command: ['cat']
    tty: true

  - name: trivy
    image: aquasec/trivy:latest
    command: ['cat']
    tty: true

  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true

  volumes:
  - name: docker-sock
    emptyDir: {}
'''

    }

    else {

        yaml = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer

  containers:
  - name: alpine
    image: alpine:3.18
    command: ['cat']
    tty: true

  - name: python
    image: python:3.11-slim
    command: ['cat']
    tty: true

  - name: selenium
    image: selenium/standalone-firefox:latest
    ports:
    - containerPort: 4444
    - containerPort: 7900
    shm_size: '2g'
    tty: true

  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run

  - name: trivy
    image: aquasec/trivy:latest
    command: ['cat']
    tty: true

  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true

  volumes:
  - name: docker-sock
    emptyDir: {}
'''

    }

    return yaml
}

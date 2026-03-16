#!/usr/bin/env groovy

def call(String language) {

    def yaml = ""

    // ─────────────────────────────────────────────────────────────────────────
    // MODIFICATION : ajout du cache Trivy (hostPath /var/cache/trivy)
    // → Trivy utilise la DB locale au lieu de la télécharger à chaque fois
    // → Scan passe de ~2h à ~30 secondes
    // ─────────────────────────────────────────────────────────────────────────

    if (language == 'python') {
        yaml = '''
apiVersion: v1
kind: Pod
spec:
  serviceAccountName: jenkins-deployer
  containers:
  - name: python
    image: python:3.11-slim
    command: ['cat']
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
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true
  volumes:
  - name: docker-sock
    emptyDir: {}
  - name: trivy-cache
    hostPath:
      path: /var/cache/trivy
      type: DirectoryOrCreate
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
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true
  volumes:
  - name: docker-sock
    emptyDir: {}
  - name: trivy-cache
    hostPath:
      path: /var/cache/trivy
      type: DirectoryOrCreate
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
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true
  volumes:
  - name: docker-sock
    emptyDir: {}
  - name: trivy-cache
    hostPath:
      path: /var/cache/trivy
      type: DirectoryOrCreate
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
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true
  volumes:
  - name: docker-sock
    emptyDir: {}
  - name: trivy-cache
    hostPath:
      path: /var/cache/trivy
      type: DirectoryOrCreate
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
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true
  volumes:
  - name: docker-sock
    emptyDir: {}
  - name: trivy-cache
    hostPath:
      path: /var/cache/trivy
      type: DirectoryOrCreate
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
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true
  volumes:
  - name: docker-sock
    emptyDir: {}
  - name: trivy-cache
    hostPath:
      path: /var/cache/trivy
      type: DirectoryOrCreate
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
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true
  volumes:
  - name: docker-sock
    emptyDir: {}
  - name: trivy-cache
    hostPath:
      path: /var/cache/trivy
      type: DirectoryOrCreate
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
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    command: ['sh']
    args: ['-c', 'while true; do sleep 1000; done']
    tty: true
  volumes:
  - name: docker-sock
    emptyDir: {}
  - name: trivy-cache
    hostPath:
      path: /var/cache/trivy
      type: DirectoryOrCreate
'''
    }

    return yaml
}

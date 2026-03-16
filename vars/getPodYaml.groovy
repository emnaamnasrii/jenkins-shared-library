#!/usr/bin/env groovy

def call(String language) {

    def yaml = ""

    // ─────────────────────────────────────────────────────────────────────────
    // MODIFICATIONS :
    // 1. Cache Trivy (hostPath /var/cache/trivy) → scan ~30s au lieu de ~2h
    // 2. imagePullPolicy: IfNotPresent → utilise l'image locale si disponible
    //    → évite les téléchargements inutiles à chaque pipeline
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
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: docker
    image: docker:24-dind
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run
  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: trivy
    image: aquasec/trivy:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    imagePullPolicy: IfNotPresent
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
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: python
    image: python:3.11-slim
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: docker
    image: docker:24-dind
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run
  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: trivy
    image: aquasec/trivy:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    imagePullPolicy: IfNotPresent
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
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: python
    image: python:3.11-slim
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: docker
    image: docker:24-dind
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run
  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: trivy
    image: aquasec/trivy:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    imagePullPolicy: IfNotPresent
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
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: python
    image: python:3.11-slim
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: docker
    image: docker:24-dind
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run
  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: trivy
    image: aquasec/trivy:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    imagePullPolicy: IfNotPresent
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
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: python
    image: python:3.11-slim
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: docker
    image: docker:24-dind
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run
  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: trivy
    image: aquasec/trivy:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    imagePullPolicy: IfNotPresent
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
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: python
    image: python:3.11-slim
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: docker
    image: docker:24-dind
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run
  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: trivy
    image: aquasec/trivy:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    imagePullPolicy: IfNotPresent
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
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: python
    image: python:3.11-slim
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: docker
    image: docker:24-dind
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run
  - name: scanner
    image: sonarsource/sonar-scanner-cli:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: trivy
    image: aquasec/trivy:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    imagePullPolicy: IfNotPresent
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
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: python
    image: python:3.11-slim
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
  - name: docker
    image: docker:24-dind
    imagePullPolicy: IfNotPresent
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run
  - name: trivy
    image: aquasec/trivy:latest
    imagePullPolicy: IfNotPresent
    command: ['cat']
    tty: true
    volumeMounts:
    - name: trivy-cache
      mountPath: /root/.cache/trivy
  - name: kubectl
    image: lachlanevenson/k8s-kubectl:latest
    imagePullPolicy: IfNotPresent
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

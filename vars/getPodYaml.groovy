#!/usr/bin/env groovy

def call(String language) {
    def yaml = ""
    
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
    image: maven:3.9-openjdk-17
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
  volumes:
  - name: docker-sock
    emptyDir: {}
'''
    }
    else {
        // Unknown language - pod minimal
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
  - name: docker
    image: docker:24-dind
    securityContext:
      privileged: true
    tty: true
    volumeMounts:
    - name: docker-sock
      mountPath: /var/run
  volumes:
  - name: docker-sock
    emptyDir: {}
'''
    }
    
    return yaml
}

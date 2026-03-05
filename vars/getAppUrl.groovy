#!/usr/bin/env groovy

def call(Map config = [:]) {
    def namespace = config.namespace ?: 'dev'
    def appName = config.appName
    
    container('kubectl') {
        withKubeConfig([credentialsId: 'kubeconfig']) {
            def nodeIP = sh(
                script: "kubectl get nodes -o jsonpath='{.items[0].status.addresses[0].address}'",
                returnStdout: true
            ).trim()
            
            return "http://${nodeIP}:30080"
        }
    }
}

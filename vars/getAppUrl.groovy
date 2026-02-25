#!/usr/bin/env groovy

def call(Map config = [:]) {
    def namespace = config.namespace ?: 'dev'
    def appName = config.appName
    
    def nodeIP = sh(
        script: """
            kubectl get nodes -o jsonpath='{.items[0].status.addresses[?(@.type=="InternalIP")].address}'
        """,
        returnStdout: true
    ).trim()
    
    def nodePort = sh(
        script: """
            kubectl get svc ${appName} -n ${namespace} -o jsonpath='{.spec.ports[0].nodePort}'
        """,
        returnStdout: true
    ).trim()
    
    return "http://${nodeIP}:${nodePort}"
}

#!/usr/bin/env groovy

def call(Map config = [:]) {
    def dbType = config.dbType
    def dbHost = config.dbHost
    def dbPort = config.dbPort
    def dbName = config.dbName
    def language = config.language ?: 'java'
    
    def connString = ''
    
    switch(dbType) {
        case 'mysql':
        case 'mariadb':
            if (language == 'java') {
                connString = "jdbc:mysql://${dbHost}:${dbPort}/${dbName}?createDatabaseIfNotExist=true&useSSL=false&allowPublicKeyRetrieval=true"
            } else if (language == 'python') {
                connString = "mysql+pymysql://user:user123@${dbHost}:${dbPort}/${dbName}"
            } else if (language == 'nodejs') {
                connString = "mysql://user:user123@${dbHost}:${dbPort}/${dbName}"
            } else if (language == 'php') {
                connString = "mysql:host=${dbHost};port=${dbPort};dbname=${dbName}"
            }
            break
        
        case 'postgresql':
            if (language == 'java') {
                connString = "jdbc:postgresql://${dbHost}:${dbPort}/${dbName}"
            } else if (language == 'python') {
                connString = "postgresql://user:postgres123@${dbHost}:${dbPort}/${dbName}"
            } else if (language == 'nodejs') {
                connString = "postgresql://user:postgres123@${dbHost}:${dbPort}/${dbName}"
            } else if (language == 'php') {
                connString = "pgsql:host=${dbHost};port=${dbPort};dbname=${dbName}"
            }
            break
        
        case 'mongodb':
            connString = "mongodb://root:root123@${dbHost}:${dbPort}/${dbName}?authSource=admin"
            break
        
        case 'redis':
            connString = "redis://:redis123@${dbHost}:${dbPort}/0"
            break
        
        default:
            connString = "${dbHost}:${dbPort}"
    }
    
    return connString
}

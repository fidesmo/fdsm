pipeline {
  agent any
  tools {
    maven 'maven'
  }
  environment {
    AWS_ACCESS_KEY_ID = credentials('s3-write-access-key')
    AWS_SECRET_KEY = credentials('s3-write-secret-key')
  }
  stages {
    stage('Build fdsm.jar') {
      steps {
        sh 'mvn clean package -U'
        archiveArtifacts artifacts: 'target/fdsm.jar', fingerprint: true
      }
    }
    stage('Release fdsm.exe') {
      when { tag "*" }
      steps {
        sh 'mvn -P release clean package'
      }
    }
    stage('Deploy') {
      steps {
        sh 'mvn deploy'
      }
    }
  }
}

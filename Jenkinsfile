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
        // Removed until libc6 i386 is available
        // sh 'mvn -P release clean package'
        sh 'true'
      }
    }
    stage('Deploy') {
      when { branch "master" }
      steps {
        sh 'mvn deploy'
      }
    }
  }
}

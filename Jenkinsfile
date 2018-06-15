pipeline {
  agent any
  tools {
    maven 'maven'
  }
  stages {
    stage('Build fdsm.jar') {
      steps {
        sh 'mvn clean package -U'
        archiveArtifacts artifacts: 'target/fdsm.jar', fingerprint: true
      }
    }
  }
}

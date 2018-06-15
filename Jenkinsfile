pipeline {
  agent any
  tools {
    maven 'maven'
  }
  stages {
    stage('Build fdsm.jar') {
      steps {
        sh 'mvn package'
        archiveArtifacts artifacts: 'target/fdsm.jar', fingerprint: true
      }
    }
  }
}

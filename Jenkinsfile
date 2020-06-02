pipeline {
  agent any
  environment {
    AWS_ACCESS_KEY_ID = credentials('s3-write-access-key')
    AWS_SECRET_KEY = credentials('s3-write-secret-key')
  }
  stages {
    stage('Build fdsm.jar') {
      steps {
        sh './mvnw -B clean package -U'
        archiveArtifacts artifacts: 'target/fdsm.jar', fingerprint: true
      }
    }
    stage('Deploy') {
      when { branch "master" }
      steps {
        sh './mvnw -B deploy'
      }
    }
  }
}

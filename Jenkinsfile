pipeline {
    agent any
    tools { jdk 'jdk21'; maven 'maven39'; nodejs 'node22' }
    options { timestamps(); timeout(time: 30, unit: 'MINUTES') }
    stages {
        stage('Checkout') { steps { checkout scm } }
        stage('Verify') {
            parallel {
                stage('Backend') {
                    steps { sh './mvnw -B verify' }
                    post { always {
                        junit 'target/surefire-reports/*.xml'
                        archiveArtifacts artifacts: 'target/site/jacoco/**', allowEmptyArchive: true
                    } }
                }
                stage('Frontend') {
                    steps { dir('frontend') { sh 'npm ci && npm test && npm run build' } }
                    post { always { archiveArtifacts artifacts: 'frontend/dist/**', allowEmptyArchive: true } }
                }
            }
        }
    }
}

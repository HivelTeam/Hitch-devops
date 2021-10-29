def call() {
pipeline {
  agent {
    docker {
      image 'node:14-buster-slim'
    }
  }

  environment {
    HOME = '.'
  }

  options {
    disableConcurrentBuilds()
    buildDiscarder logRotator(numToKeepStr: '5')
    timeout(time: 20, unit: 'MINUTES')
    timestamps()
  }

  parameters {
    choice(name: 'ENVIRONMENT',
           choices: EnvironmentChoices,
           description: 'Choose environment to deploy')
  }

  stages {
    stage('Install') {
      steps {
        sh "npm ci"
      }
    }

    stage('Build') {
      steps {
        sh "npm run build:prod"
      }
    }

    stage('Archive') {
      steps {
        sh "mkdir ${FolderToZip}"
        sh "cp -a ${BuildFolder}/. ${FolderToZip}/"

        zip zipFile: "${ArtifactName}", archive: false, dir: "${FolderToZip}"
        archiveArtifacts artifacts: "${ArtifactName}", fingerprint: true
      }
    }

    stage('Deploy') {
      when {
        equals expected: 'true', actual: DeployToAws
      }
      steps {
        step($class: 'AWSEBDeploymentBuilder',
        credentialId:'jenkins-AWS-beanstalk',
        awsRegion:'eu-north-1',
        checkHealth:true,
        applicationName:"${ApplicationName}",
        environmentName:"${params.ENVIRONMENT}",
        keyPrefix:"${ApplicationName}/builds/${params.ENVIRONMENT}",
        rootObject:"${ArtifactName}",
        versionLabelFormat:"${env.BUILD_TAG}")
      }
    }
  }

  post {
    always {
      sh "rm -rf ${FolderToZip}"
      sh "rm -f ${ArtifactName}"

      script {
        if (currentBuild.currentResult =='SUCCESS') JobStatus = 'good' else JobStatus = 'danger';
      }

      slackSend message: "Status: ${currentBuild.currentResult}\n" +
                         "Application: ${ApplicationName}\n" +
                         "Environment: ${params.ENVIRONMENT}\n" +
                         "Branch: ${env.BRANCH_NAME}\n" +
                         "More info at ${env.BUILD_URL}",
                color: JobStatus
    }
  }
}
}

def call(ApplicationName, EnvironmentChoices, BuildWithParameter, BuildFolder, DeployToAws) {
    final FOLDER_TO_ZIP = 'folder_to_zip'
    final ARTIFACT_NAME = "${env.BUILD_TAG}.zip"

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
            timeout(time: 15, unit: 'MINUTES')
            timestamps()
        }

        parameters {
            choice(name: 'ENVIRONMENT',
                    choices: EnvironmentChoices,
                    description: 'Choose environment to deploy')
        }

        stages {
            stage('Notify') {
                steps {
                    wrap([$class: 'BuildUser']) {
                        slackSend message: "User ${env.BUILD_USER_ID} started build\n" +
                                           "Application: ${ApplicationName}\n" +
                                           "Environment: ${params.ENVIRONMENT}\n" +
                                           "Branch: ${env.BRANCH_NAME}",
                                  color: '#b3c6e8'
                    }
                }
            }

            stage('Install') {
                steps {
                    sh "npm ci"
                }
            }

            stage('Build') {
                steps {
                    script {
                        if (BuildWithParameter == 'true') {
                            sh "npm run build:prod -- ${params.ENVIRONMENT}"
                        } else {
                            sh "npm run build:prod"
                        }
                    }
                }
            }

            stage('Archive') {
                steps {
                    sh "mkdir ${FOLDER_TO_ZIP}"
                    sh "cp -a ${BuildFolder}/. ${FOLDER_TO_ZIP}/"

                    zip zipFile: "${ARTIFACT_NAME}", archive: false, dir: "${FOLDER_TO_ZIP}"
                    archiveArtifacts artifacts: "${ARTIFACT_NAME}", fingerprint: true
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
                            rootObject:"${ARTIFACT_NAME}",
                            versionLabelFormat:"${env.BUILD_TAG}")
                }
            }
        }

        post {
            always {
                sh "rm -rf ${FOLDER_TO_ZIP}"
                sh "rm -f ${ARTIFACT_NAME}"

                script {
                    if (currentBuild.currentResult =='SUCCESS') {
                        JobStatusColor = 'good'
                        JobStatusIcon = ':ok_hand:'
                    } else {
                        JobStatusColor = 'danger'
                        JobStatusIcon = ':rage:'
                    }
                }

                slackSend message: "Status: ${currentBuild.currentResult} ${JobStatusIcon}\n" +
                                   "Application: ${ApplicationName}\n" +
                                   "Environment: ${params.ENVIRONMENT}\n" +
                                   "Branch: ${env.BRANCH_NAME}\n" +
                                   "More info at ${env.BUILD_URL}",
                          color: JobStatusColor

                cleanWs()
            }
        }
    }
}

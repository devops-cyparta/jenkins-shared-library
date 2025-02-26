def call() {
    pipeline {
        agent any

        environment {
            REPO_NAME = "${env.JOB_NAME}".split("/")[1]
            BRANCH_NAME = "${env.BRANCH_NAME}"
            USER_NAME = sh(script: "git log --format='%an' -n 1", returnStdout: true).trim()
            BUILD_NUM = "${env.BUILD_NUMBER}"
            STORAGE_PATH = "/mnt/Storage/${env.GITHUB_ORG}/${USER_NAME}/${REPO_NAME}/${BRANCH_NAME}"
            IMAGE_NAME = "${BRANCH_NAME}-${REPO_NAME}-${USER_NAME}:${BUILD_NUM}"
        }

        stages {
            stage('Checkout') {
                steps {
                    script {
                        sh "mkdir -p \"${STORAGE_PATH}\""
                        sh "rm -rf \"${STORAGE_PATH}/*\""
                        checkout scm
                        sh "cp -r * \"${STORAGE_PATH}/\""
                    }
                }
            }

            stage('Build Docker Image') {
                steps {
                    script {
                        sh """
                        cd \"${STORAGE_PATH}\"
                        docker build -t \"${IMAGE_NAME}\" .
                        """
                    }
                }
            }

            stage('Stop & Remove Old Containers') {
                steps {
                    script {
                        sh """
                        docker ps -a | grep \"${BRANCH_NAME}-${REPO_NAME}-${USER_NAME}\" | awk '{print \$1}' | xargs -r docker stop
                        docker ps -a | grep \"${BRANCH_NAME}-${REPO_NAME}-${USER_NAME}\" | awk '{print \$1}' | xargs -r docker rm
                        """
                    }
                }
            }

            stage('Run New Container') {
                steps {
                    script {
                        def port = sh(script: "shuf -i 2000-65000 -n 1", returnStdout: true).trim()
                        try {
                            // Save the last successful image before replacing it
                            sh "docker tag \"${IMAGE_NAME}\" latest-${BRANCH_NAME}-${REPO_NAME}-${USER_NAME}"

                            // Run new container
                            sh """
                            docker run -d -p \"${port}:80\" --name \"${IMAGE_NAME}\" \"${IMAGE_NAME}\"
                            echo \"Running on port ${port}\"
                            """
                        } catch (Exception e) {
                            // If failed, rollback to last working version
                            echo "Deployment failed, rolling back to previous version..."
                            sh "docker run -d -p \"${port}:80\" --name rollback-${IMAGE_NAME} latest-${BRANCH_NAME}-${REPO_NAME}-${USER_NAME}"
                        }
                    }
                }
            }

            stage('Cleanup Old Images') {
                steps {
                    script {
                        sh """
                        docker images | grep \"${BRANCH_NAME}-${REPO_NAME}-${USER_NAME}\" | awk '{print \$3}' | tail -n +3 | xargs -r docker rmi -f
                        """
                    }
                }
            }

            stage('Cleanup Old Builds') {
                steps {
                    script {
                        sh """
                        ls -dt \"${STORAGE_PATH}/../*/\" | tail -n +6 | xargs -r rm -rf
                        """
                    }
                }
            }
        }

        post {
            always {
                script {
                    sh "docker system prune -f"
                }
            }
        }
    }
}

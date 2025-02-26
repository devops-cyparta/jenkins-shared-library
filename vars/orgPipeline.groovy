def call() {
    pipeline {
        agent any

        environment {
            GITHUB_ORG = "devops-cyparta"  // Ensure org is set
            REPO_NAME = "${env.JOB_NAME.split('/')[1]}"
            BRANCH_NAME = "${env.BRANCH_NAME}"
            USER_NAME = "${sh(script: 'git log --format=%an -n 1', returnStdout: true).trim()}"
            BUILD_NUM = "${env.BUILD_NUMBER}"
            STORAGE_PATH = "/mnt/Storage/${GITHUB_ORG}/${USER_NAME}/${REPO_NAME}/${BRANCH_NAME}"
            IMAGE_NAME = "${REPO_NAME}:${BUILD_NUM}"
            LATEST_IMAGE = "${REPO_NAME}:latest"
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
                        sh "cd \"${STORAGE_PATH}\" && sudo docker build -t \"${IMAGE_NAME}\" ."
                        sh "sudo docker tag \"${IMAGE_NAME}\" \"${LATEST_IMAGE}\""
                    }
                }
            }

            stage('Stop & Remove Old Containers') {
                steps {
                    script {
                        sh """
                        CONTAINERS=\$(sudo docker ps -a --filter "name=${REPO_NAME}" --format "{{.ID}}")
                        if [ -n "$CONTAINERS" ]; then
                            echo "$CONTAINERS" | xargs -r sudo docker stop
                            echo "$CONTAINERS" | xargs -r sudo docker rm
                        else
                            echo "No old containers to remove."
                        fi
                        """
                    }
                }
            }

            stage('Run New Container') {
                steps {
                    script {
                        def port = sh(script: "shuf -i 2000-65000 -n 1", returnStdout: true).trim()
                        try {
                            sh "sudo docker run -d -p \"${port}:80\" --name \"${REPO_NAME}\" \"${IMAGE_NAME}\""
                            echo "Running on port ${port}"
                        } catch (Exception e) {
                            echo "Deployment failed, rolling back to previous version..."
                            sh "if sudo docker inspect ${LATEST_IMAGE} &>/dev/null; then sudo docker run -d -p \"${port}:80\" --name \"rollback-${REPO_NAME}\" \"${LATEST_IMAGE}\"; else echo 'No previous version available'; fi"
                        }
                    }
                }
            }

            stage('Cleanup Old Images') {
                steps {
                    script {
                        sh """
                        IMAGES_TO_DELETE=$(sudo docker images --format "{{.Repository}}:{{.Tag}}" | grep "${REPO_NAME}" | awk -F':' '{print $2}' | sort -nr | tail -n +3)
                        if [ -n "$IMAGES_TO_DELETE" ]; then
                            echo "$IMAGES_TO_DELETE" | xargs -r -I {} sudo docker rmi -f "${REPO_NAME}:{}"
                        else
                            echo "No old images to remove."
                        fi
                        """
                    }
                }
            }

            stage('Cleanup Old Builds') {
                steps {
                    script {
                        sh """
                        OLD_BUILDS=$(ls -dt "${STORAGE_PATH}/../*/" 2>/dev/null | tail -n +6)
                        if [ -n "$OLD_BUILDS" ]; then
                            echo "$OLD_BUILDS" | xargs -r rm -rf
                        else
                            echo "No old builds to clean."
                        fi
                        """
                    }
                }
            }
        }

        post {
            always {
                script {
                    sh "sudo docker system prune -f"
                }
            }
        }
    }
}



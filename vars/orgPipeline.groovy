def call() {
    pipeline {
        agent any

        environment {
            REPO_NAME = "${env.JOB_NAME.split('/')[1]}"
            BRANCH_NAME = "${env.BRANCH_NAME}"
            USER_NAME = "${sh(script: 'git log --format=%an -n 1', returnStdout: true).trim()}"
            BUILD_NUM = "${env.BUILD_NUMBER}"
            STORAGE_PATH = "/mnt/Storage/${env.GITHUB_ORG}/${USER_NAME}/${REPO_NAME}/${BRANCH_NAME}"
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
                        echo "Building Docker image: ${IMAGE_NAME}"
                        sh """
                        cd "${STORAGE_PATH}" && sudo docker build -t "${REPO_NAME}:${BUILD_NUM}" .
                        sudo docker tag "${REPO_NAME}:${BUILD_NUM}" "${REPO_NAME}:latest"
                        """
                    }
                }
            }


            stage('Stop & Remove Old Containers') {
                steps {
                    script {
                        sh """
                        sudo docker ps -a --filter "name=${REPO_NAME}" --format "{{.ID}}" | xargs -r sudo docker stop
                        sudo docker ps -a --filter "name=${REPO_NAME}" --format "{{.ID}}" | xargs -r sudo docker rm
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
                            sh "sudo docker run -d -p \"${port}:80\" --name \"rollback-${REPO_NAME}\" \"${LATEST_IMAGE}\""
                        }
                    }
                }
            }

            stage('Cleanup Old Images') {
                steps {
                    script {
                        sh """
                        echo "Listing all images for repository: ${REPO_NAME}"
                        sudo docker images --format "{{.Repository}}:{{.Tag}}" | grep "${REPO_NAME}"
            
                        echo "Selecting images to delete..."
                        sudo docker images --format "{{.Repository}}:{{.Tag}}" | grep "${REPO_NAME}" | awk -F':' '{print \$2}' | sort -nr | tail -n +3 | while read tag; do
                            echo "Checking if ${REPO_NAME}:${tag} exists..."
                            if sudo docker images "${REPO_NAME}:${tag}" | grep -q "${REPO_NAME}"; then
                                echo "Deleting ${REPO_NAME}:${tag}..."
                                sudo docker rmi -f "${REPO_NAME}:${tag}"
                            else
                                echo "Image ${REPO_NAME}:${tag} does not exist, skipping..."
                            fi
                        done
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
                    sh "sudo docker system prune -f"
                }
            }
        }
    }
}


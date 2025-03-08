def call() {
    pipeline {
        agent any

        environment {
            REPO_NAME = "${sh(script: 'basename -s .git $(git config --get remote.origin.url)', returnStdout: true).trim()}"
            BRANCH_NAME = "${env.BRANCH_NAME}".replaceAll("/", "-")
            USER_NAME = "${sh(script: 'git log --format=%an -n 1', returnStdout: true).trim()}"
            BUILD_NUM = "${env.BUILD_NUMBER}"
            STORAGE_PATH = "/mnt/Storage/${USER_NAME}/${REPO_NAME}/${BRANCH_NAME}"
            IMAGE_NAME = "${REPO_NAME}-${BRANCH_NAME}:${BUILD_NUM}"
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
                        ls -l ${STORAGE_PATH}/Dockerfile  # Debug: Check if Dockerfile exists
                        cd "${STORAGE_PATH}" && sudo docker build -f Dockerfile -t "${IMAGE_NAME}" .
                        sudo docker tag "${IMAGE_NAME}" "${LATEST_IMAGE}"
                        """
                    }
                }
            }


            stage('Stop & Remove Old Containers') {
                steps {
                    script {
                        sh """
                        # Stop containers gracefully
                        sudo docker ps -a --filter "name=${REPO_NAME}-${BRANCH_NAME}" --format "{{.ID}}" | xargs -r sudo docker stop
            
                        # Wait a moment to ensure all containers are fully stopped
                        sleep 5
            
                        # Ensure all stopped containers are removed
                        sudo docker ps -a --filter "name=${REPO_NAME}-${BRANCH_NAME}" --format "{{.ID}}" | xargs -r sudo docker rm || true
                        """
                    }
                }
            }


            stage('Ensure MySQL Container') {
                steps {
                    script {
                        def mysqlExists = sh(script: "sudo docker ps -a --filter 'name=mysql_db' --format '{{.Names}}'", returnStdout: true).trim()
                        
                        if (!mysqlExists) {
                            echo "Starting MySQL container..."
                            sh """
                            sudo docker network create my-network || true
                            sudo docker run -d --network my-network --name mysql_db \
                                -e MYSQL_ROOT_PASSWORD=root \
                                -e MYSQL_DATABASE=mydb \
                                -e MYSQL_USER=user \
                                -e MYSQL_PASSWORD=password \
                                -p 3307:3306 mysql:5.7
                            """
                        } else {
                            echo "MySQL container already running."
                        }
                    }
                }
            }

            
            stage('Run New Container') {
                steps {
                    script {
                        def port = sh(script: "shuf -i 2000-65000 -n 1", returnStdout: true).trim()
                        try {
                            sh """
                            sudo docker run -d -p "${port}:8000" --name "${REPO_NAME}-${BRANCH_NAME}" --network my-network \
                                -e DB_HOST=mysql_db -e DB_USER=user -e DB_PASSWORD=password -e DB_NAME=mydb \
                                "${IMAGE_NAME}"
                            """
                            echo "Running on port ${port}, connected to MySQL"
                        } catch (Exception e) {
                            echo "Deployment failed, rolling back to previous version..."
                            sh """
                            sudo docker run -d -p "${port}:8000" --name "rollback-${REPO_NAME}-${BRANCH_NAME}" --network my-network \
                                -e DB_HOST=mysql_db -e DB_USER=user -e DB_PASSWORD=password -e DB_NAME=mydb \
                                "${LATEST_IMAGE}"
                            """
                        }
                    }
                }
            }

            stage('Cleanup Old Images') {
                steps {
                    script {
                        echo "Listing all images before cleanup:"
                        sh "sudo docker images"
            
                        sh """
                        echo "Finding old images for ${REPO_NAME}"
            
                        # Get a list of tags to delete (all except the latest 2)
                        IMAGE_LIST=\$(sudo docker images --format "{{.Repository}}:{{.Tag}}" | grep "^${REPO_NAME}:" | awk -F':' '{print \$2}' | sort -nr | tail -n +3)
            
                        for tag in \$IMAGE_LIST; do
                            IMAGE="${REPO_NAME}:\$tag"
                            if sudo docker images --format "{{.Repository}}:{{.Tag}}" | grep -q "\$IMAGE"; then
                                echo "Deleting \$IMAGE..."
                                sudo docker rmi -f "\$IMAGE"
                            else
                                echo "Skipping non-existing image \$IMAGE"
                            fi
                        done
                        """
            
                        echo "Listing all images after cleanup:"
                        sh "sudo docker images"
                    }
                }
            }

            stage('Cleanup Old Builds') {
                steps {
                    script {
                        sh """
                        if ls -d "${STORAGE_PATH}/../"*/ 1>/dev/null 2>&1; then
                            echo "Old builds before cleanup:"
                            ls -dt "${STORAGE_PATH}/../"*/
            
                            echo "Deleting the following old builds:"
                            ls -dt "${STORAGE_PATH}/../"*/ | tail -n +9 | tee /tmp/deleted_builds.txt | xargs -r rm -rf
            
                            echo "Deleted builds:"
                            cat /tmp/deleted_builds.txt
                        else
                            echo "No old builds to clean up."
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

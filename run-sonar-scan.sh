#!/bin/bash
# Exit immediately if any command fails
set -e

CONTAINER_NAME="throwaway-sonar"
PORT=9000
TOKEN_NAME="maven-scanner-token-$(date +%s)"

echo "========================================="
echo "1. Checking SonarQube Docker Container..."
echo "========================================="

# Check if container exists
if [ "$(docker ps -a -q -f name=^/${CONTAINER_NAME}$)" ]; then
    # Check if it is running
    if [ "$(docker ps -q -f name=^/${CONTAINER_NAME}$)" ]; then
        echo "Container '${CONTAINER_NAME}' is already running."
    else
        echo "Starting existing container '${CONTAINER_NAME}'..."
        docker start ${CONTAINER_NAME}
    fi
else
    echo "Container '${CONTAINER_NAME}' not found. Pulling and running fresh instance..."
    docker run -d --name ${CONTAINER_NAME} -p ${PORT}:9000 sonarqube:community
fi

echo ""
echo "========================================="
echo "2. Waiting for SonarQube to initialize..."
echo "========================================="
echo "This takes about 30-40 seconds. Monitoring logs..."

# Loop until operational message appears in logs
until docker logs ${CONTAINER_NAME} 2>&1 | grep -q "SonarQube is operational"; do
    sleep 3
    echo -n "."
done
echo "\nSonarQube server is fully up and running!"

echo ""
echo "========================================="
echo "3. Generating API Authentication Token..."
echo "========================================="

# Call API to generate global analysis token
RESPONSE=$(curl -s -X POST -u admin:admin "http://localhost:${PORT}/api/user_tokens/generate?name=${TOKEN_NAME}&type=GLOBAL_ANALYSIS_TOKEN")

# Extract token using grep/sed to avoid mandatory jq dependency
SONAR_TOKEN=$(echo "$RESPONSE" | grep -o '"token":"[^"]*' | grep -o '[^"]*$')

if [ -z "$SONAR_TOKEN" ]; then
    echo "ERROR: Failed to generate token. Server response was:"
    echo "$RESPONSE"
    exit 1
fi

echo "Successfully generated token: ${SONAR_TOKEN:0:4}************************"

echo ""
echo "========================================="
echo "4. Executing Deep Maven Sonar Scan..."
echo "========================================="

mvn clean verify org.sonarsource.scanner.maven:sonar-maven-plugin:3.11.0.3922:sonar   -DskipTests   -Dsonar.token=${SONAR_TOKEN}

echo ""
echo "========================================="
echo "SUCCESS! Scan completed."
echo "View interactive results here: http://localhost:${PORT}"
echo "========================================="

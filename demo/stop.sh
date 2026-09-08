#!/usr/bin/env bash
# Stops the demo deployment and removes the containers (data is ephemeral).
#   ./demo/stop.sh
set -euo pipefail

cd "$(dirname "$0")/.."
docker compose -f demo/docker-compose.yml --profile trino down
echo "Demo stopped. Start again with ./demo/start.sh"

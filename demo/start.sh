#!/usr/bin/env bash
# Starts the demo deployment: OPA (demo bundle) + local Trino coordinator with
# the plugin baked in. Builds whatever is missing. Idempotent — safe to re-run.
#
#   ./demo/start.sh
#
# Then connect DBeaver: host localhost, port 8080, user admin (see demo/README.md).
# Stop again with ./demo/stop.sh
set -euo pipefail

cd "$(dirname "$0")/.."

# The build requires JDK 23+ (Trino 474 ships Java 23 bytecode); the script
# below picks one if JAVA_HOME is not already set.

echo "== 1/3 building the plugin directory (target/plugin) =="
./deploy/build-plugin-dir.sh

echo "== 2/3 starting OPA + Trino (first run pulls trinodb/trino:474, ~1 GB) =="
docker compose -f demo/docker-compose.yml --profile trino up -d --build

echo "== 3/3 waiting for the coordinator =="
for i in $(seq 1 60); do
  # The trino service is profile-gated, so exec needs --profile trino. Probe as
  # the admin user: the demo policy denies the coordinator identity (no groups),
  # so a bare "SELECT 1" would fail forever even though the coordinator is up.
  if docker compose -f demo/docker-compose.yml --profile trino exec -T trino \
      trino --user admin --execute "SELECT 1" >/dev/null 2>&1; then
    echo ""
    echo "Demo is up."
    echo "  DBeaver : host localhost, port 8080, user admin, no password"
    echo "  Allowed : SELECT * FROM tpch.tiny.nation"
    echo "  Denied  : SELECT * FROM tpch.tiny.customer  (Access Denied — by policy)"
    echo "  Stop    : ./demo/stop.sh"
    exit 0
  fi
  printf '.'
  sleep 2
done
echo "" >&2
echo "Coordinator did not become ready in ~2 minutes; check: docker compose -f demo/docker-compose.yml logs trino" >&2
exit 1

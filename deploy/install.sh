#!/usr/bin/env bash
# Plugin install script (Milestone 7, item 3).
#
# Builds the plugin jar + its runtime dependencies and installs them into a
# Trino coordinator's plugin directory. Works for a local directory or a remote
# coordinator over SSH.
#
# Usage:
#   ./deploy/install.sh                          # local install
#   ./deploy/install.sh --coordinator user@host  # remote install over SSH
#
# Env overrides:
#   COORDINATOR  SSH target (default: empty = local install)
#   PLUGIN_DIR   coordinator plugin dir (default: /data/trino/plugin/opa-access-control)
#
# Requires JDK 23+ to build (Trino 474 ships Java 23 bytecode). After install,
# copy deploy/access-control.properties.example to etc/access-control.properties
# and restart the coordinator (see deploy/UPGRADE-ROLLBACK.md).
set -euo pipefail
cd "$(dirname "$0")/.."

COORDINATOR="${COORDINATOR:-}"
PLUGIN_DIR="${PLUGIN_DIR:-/data/trino/plugin/opa-access-control}"

# The build requires JDK 23+ (Trino 474 ships Java 23 bytecode); the script
# below picks one if JAVA_HOME is not already set.

echo "== 1/3 building the plugin directory (target/plugin) =="
./deploy/build-plugin-dir.sh

echo "== 2/3 installing into plugin dir: $PLUGIN_DIR =="
if [ -n "$COORDINATOR" ]; then
  ssh "$COORDINATOR" "mkdir -p '$PLUGIN_DIR'"
  scp target/plugin/*.jar "$COORDINATOR:'$PLUGIN_DIR'/"
else
  mkdir -p "$PLUGIN_DIR"
  cp target/plugin/*.jar "$PLUGIN_DIR/"
fi

echo "== 3/3 done. Next steps =="
echo "  - Copy deploy/access-control.properties.example to the coordinator's"
echo "    etc/access-control.properties and edit the opa.* keys."
echo "  - Restart the coordinator (see deploy/UPGRADE-ROLLBACK.md)."
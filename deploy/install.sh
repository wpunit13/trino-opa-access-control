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

# The build requires JDK 23+ (Trino 474 ships Java 23 bytecode). Pick one if
# JAVA_HOME is not already set (macOS via java_home, else assume PATH is right).
if [ -z "${JAVA_HOME:-}" ]; then
  if [ "$(uname)" = "Darwin" ] && /usr/libexec/java_home -v 23+ >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 23+)"
    export JAVA_HOME
    echo "Using JAVA_HOME=$JAVA_HOME"
  else
    echo "WARNING: JAVA_HOME is not set and no JDK 23+ was auto-detected; the build may fail." >&2
  fi
fi

echo "== 1/3 building the plugin jar + runtime deps =="
# Start clean: dependency:copy-dependencies does not remove stale jars, and a
# leftover trino-parser/trino-grammar from an older build would violate the
# "coordinator provides trino" invariant.
rm -rf target/plugin
mvn -q package dependency:copy-dependencies -DincludeScope=runtime \
    -DexcludeGroupIds=io.trino \
    -DoutputDirectory=target/plugin -DskipTests
# dependency:copy-dependencies does not include the project's own artifact — the
# plugin jar must sit next to its deps in the plugin directory (never the CLI jar).
ls target/trino-opa-access-control-*.jar | grep -v conformance-cli | xargs -I{} cp {} target/plugin/
# airlift (config framework) and slf4j-api are provided-scope for us but NOT
# provided by the coordinator's plugin classloader — bundle them. Never bundle
# trino-spi/trino-parser (coordinator-owned). NOTE: -DincludeScope=runtime also
# pulls compile-scope deps, so trino-parser (compile-scope) must be excluded via
# -DexcludeGroupIds=io.trino on BOTH copies.
mvn -q dependency:copy-dependencies -DincludeScope=provided \
    -DexcludeGroupIds=io.trino \
    -DoutputDirectory=target/plugin -DskipTests

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
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
# Trino's plugin classloader is isolated and does NOT provide airlift (config
# framework) or slf4j-api even though they are provided-scope for us — bundle
# them, but never trino-spi (coordinator-owned). NOTE:
# -DincludeScope=runtime also pulls compile-scope deps, so trino-parser
# (compile-scope) must be excluded via -DexcludeGroupIds=io.trino on BOTH copies.
mvn -q dependency:copy-dependencies -DincludeScope=provided \
    -DexcludeGroupIds=io.trino \
    -DoutputDirectory=target/plugin -DskipTests

# The plugin's SqlExpressionValidator (passthrough SQL validation) references
# trino-parser types at runtime, but the coordinator's plugin classloader does
# NOT provide trino-parser to plugins (verified: ClassNotFoundException at
# startup). Bundle trino-parser + trino-grammar + antlr4-runtime explicitly —
# the same set the shaded conformance-cli jar bundles. trino-spi stays
# coordinator-owned and is never bundled.
mvn -q dependency:copy-dependencies \
    -DincludeArtifactIds=trino-parser,trino-grammar,antlr4-runtime \
    -DoutputDirectory=target/plugin -DskipTests

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

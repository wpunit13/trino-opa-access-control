#!/usr/bin/env bash
# Builds target/plugin/ — the complete contents of a Trino plugin directory for
# the opa-access-control plugin: the plugin jar plus every jar it needs at
# runtime, and nothing the coordinator already provides.
#
# This is the SINGLE SOURCE OF TRUTH for that recipe. demo/start.sh,
# deploy/install.sh, deploy/Dockerfile and demo/trino/Dockerfile all consume
# target/plugin/ instead of re-deriving it. The recipe is non-obvious enough
# (see the four notes below) that hand-copied variants drifted apart and two of
# them silently produced a plugin directory that fails at coordinator startup.
#
# Requires JDK 23+ (Trino 474 ships Java 23 bytecode).
set -euo pipefail
cd "$(dirname "$0")/.."

# Pick a JDK 23+ if JAVA_HOME is not already set (macOS via java_home, else
# assume PATH is right).
if [ -z "${JAVA_HOME:-}" ]; then
  if [ "$(uname)" = "Darwin" ] && /usr/libexec/java_home -v 23+ >/dev/null 2>&1; then
    JAVA_HOME="$(/usr/libexec/java_home -v 23+)"
    export JAVA_HOME
    echo "Using JAVA_HOME=$JAVA_HOME"
  else
    echo "WARNING: JAVA_HOME is not set and no JDK 23+ was auto-detected; the build may fail." >&2
  fi
fi

echo "== building target/plugin (plugin jar + runtime deps) =="

# Start clean: dependency:copy-dependencies does not remove stale jars, and a
# leftover trino-spi from an older build would violate the "never bundle trino"
# invariant enforced below.
rm -rf target/plugin

# 1. Compile + runtime deps (Jackson, Caffeine, Micrometer, ...). io.trino is
#    excluded here because -DincludeScope=runtime also pulls compile-scope deps,
#    and trino-parser is compile-scope (step 4 adds back exactly what is needed).
mvn -q package dependency:copy-dependencies -DincludeScope=runtime \
    -DexcludeGroupIds=io.trino \
    -DoutputDirectory=target/plugin -DskipTests

# 2. dependency:copy-dependencies never copies the project's own artifact — the
#    plugin jar must sit next to its deps in the plugin directory. Copy only the
#    unclassified plugin jar: never -conformance-cli (a build-time policy gate,
#    not a plugin), and never -sources/-javadoc (built by the Central publishing
#    config in pom.xml, but not loadable plugin content).
ls target/trino-opa-access-control-*.jar \
    | grep -vE 'conformance-cli|sources|javadoc' \
    | xargs -I{} cp {} target/plugin/

# 3. Provided-scope deps that the coordinator's plugin classloader does NOT
#    supply: airlift (the config framework) and slf4j-api. trino-spi is
#    coordinator-owned, hence the io.trino exclusion.
mvn -q dependency:copy-dependencies -DincludeScope=provided \
    -DexcludeGroupIds=io.trino \
    -DoutputDirectory=target/plugin -DskipTests

# 4. SqlExpressionValidator (passthrough SQL validation) references trino-parser
#    types at runtime, and the coordinator's plugin classloader does NOT expose
#    trino-parser to plugins (verified: ClassNotFoundException at startup), so it
#    must be bundled explicitly — the same set the shaded conformance-cli jar
#    bundles. trino-spi stays coordinator-owned and is never bundled.
mvn -q dependency:copy-dependencies \
    -DincludeArtifactIds=trino-parser,trino-grammar,antlr4-runtime \
    -DoutputDirectory=target/plugin -DskipTests

echo "target/plugin/ ready ($(ls target/plugin/*.jar | wc -l | tr -d ' ') jars)"

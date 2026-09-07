# Sample Trino coordinator with the OPA access-control plugin baked in.
# Quick-start only — NOT a production template (no HA OPA, no TLS, no auth).
#
# Build from the project root after building the plugin + runtime deps:
#
#   JAVA_HOME=<JDK 23+> mvn package dependency:copy-dependencies \
#       -DincludeScope=runtime -DoutputDirectory=target/plugin -DskipTests
#   docker build -f deploy/Dockerfile -t trino-opa:latest .
#
# Then run with an OPA endpoint reachable from the container (e.g. docker
# compose, or --add-host=host.docker.internal:host-gateway for a host OPA).
FROM trinodb/trino:474

# The plugin jar + its runtime deps (Jackson, Caffeine, Micrometer, airlift).
# The coordinator provides trino-spi/trino-parser, so they are NOT copied.
COPY target/plugin/ /usr/lib/trino/plugin/opa-access-control/

# Sample access-control.properties (edit opa.endpoint.url for your PDP).
COPY deploy/access-control.properties.example /etc/trino/access-control.properties

# Quick-start: point the plugin at a host-side OPA (docker run --add-host).
# Override with -e OPA_ENDPOINT_URL=http://opa:8181 in a compose network.
ENV OPA_ENDPOINT_URL=http://host.docker.internal:8181
RUN sed -i "s|^opa.endpoint.url=.*|opa.endpoint.url=${OPA_ENDPOINT_URL}|" /etc/trino/access-control.properties

EXPOSE 8080
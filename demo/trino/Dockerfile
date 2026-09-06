# Demo Trino coordinator with the OPA access-control plugin baked in.
# Built from the project root: docker compose --profile trino build
# (run `mvn package dependency:copy-dependencies -DincludeScope=runtime
#  -DoutputDirectory=target/plugin` first so the runtime deps are present).
FROM trinodb/trino:474

COPY target/plugin/ /usr/lib/trino/plugin/opa-access-control/
COPY demo/trino/etc/access-control.properties /etc/trino/access-control.properties

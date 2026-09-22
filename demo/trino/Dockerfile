# Demo Trino coordinator with the OPA access-control plugin baked in.
# Built from the project root: docker compose --profile trino build
# (run ./deploy/build-plugin-dir.sh first so target/plugin/ is populated).
FROM trinodb/trino:474

COPY target/plugin/ /usr/lib/trino/plugin/opa-access-control/
COPY demo/trino/etc/access-control.properties /etc/trino/access-control.properties

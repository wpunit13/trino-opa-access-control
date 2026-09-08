# Demo deployment (Milestone 6)

**Explicitly not a production template** — no HA OPA, no TLS, no bearer token,
default cache settings. It exists so you can run an end-to-end verification of
the plugin + example policy in about 10 minutes on a laptop.

Topology:

```
trino (coordinator, plugin jar) --HTTP--> opa (stock OPA, M5 example bundle)
```

## Prerequisites

- Docker + docker compose
- JDK 23+, Maven (to build the plugin jar)
- `opa` >= 1.0 on PATH only if you also want the conformance kit quickstart

## 10-minute walkthrough

```bash
# 1. Build the plugin jar + the CLI conformance gate (~2 min)
mvn package

# 2. Start OPA with the M5 safe-mode example bundle (~1 min)
docker compose -f demo/docker-compose.yml up -d opa

# 3. Verify the policy answers Contract 2 over the plugin's data paths (~2 min)
./demo/verify.sh

# 4. (Optional) start a local coordinator with the plugin baked in (~5 min first build)
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/plugin
docker compose -f demo/docker-compose.yml --profile trino up -d --build

# 5. Prove policy-driven access control from the CLI (demo bundle: nation
#    allowed, customer denied for user admin)
docker compose -f demo/docker-compose.yml exec trino trino --execute "SELECT * FROM tpch.tiny.nation LIMIT 1"
docker compose -f demo/docker-compose.yml exec trino trino --execute "SELECT * FROM tpch.tiny.customer LIMIT 1" || echo "denied by policy (expected)"

# 6. Prove the policy itself conforms before publishing a bundle (the CI gate)
java -jar target/trino-opa-access-control-*-conformance-cli.jar conformance \
    --policy-dir policy-conformance-kit/examples/safe --mode safe
```

## Running on any machine

Prerequisites: JDK 23+, Maven, Docker with compose. Then:

```bash
git clone <this repo> && cd trino-opa-plugin
./demo/start.sh     # builds the jar + image, starts OPA + Trino, waits for readiness
./demo/stop.sh      # stops and removes the containers
```

`start.sh` is idempotent — re-run it any time; it rebuilds only what changed.

## Connecting a SQL client (DBeaver) from your Mac

The demo bundle (`demo/opa-bundle/`) grants the user `admin` a TABLE-LEVEL
demonstration (the demo coordinator has no GroupProvider, so identities carry
no groups and the strict M5 example would deny everything):

| Query (as `admin`) | Result |
|---|---|
| `SELECT * FROM tpch.tiny.nation` | **allowed** |
| `SELECT * FROM tpch.tiny.customer` | **Access Denied** (default deny — by policy) |
| metadata browsing, other statements | allowed |
| any statement as another user | denied |

```bash
./demo/start.sh
```

Then in DBeaver:

- **New Connection → Trino**
- Host: `localhost`, Port: `8080`
- Username: `admin`, password empty

Test queries:

```sql
SELECT * FROM tpch.tiny.nation;      -- works
SELECT * FROM tpch.tiny.customer;    -- Access Denied: ... (policy demo)
```

To instead see the strict group-based M5 example (every query denied), switch
the compose volume to `../policy-conformance-kit/examples/safe` and restart.
The demo bundle is itself gate-clean — the conformance CLI passes it:

```bash
java -jar target/trino-opa-access-control-*-conformance-cli.jar conformance \
    --policy-dir demo/opa-bundle --mode safe
```

## What the allow/deny split proves (and what it doesn't)

The demo bundle routes every SPI call through OPA: `SELECT` on `nation` is
granted, `customer` is default-denied, and any non-admin user is denied
outright. That demonstrates the plugin → OPA round trip, Contract-2 parsing,
and fail-closed behavior. To see an ALLOW for a different table or user, edit
`demo/opa-bundle/trino_policy.rego` (the `select_allowed` rule) — OPA reloads
the bundle directory automatically.

## Swapping in your own policy

Point the compose volume at your bundle directory and re-run the gate:

```yaml
volumes:
  - /path/to/your/policy/bundle:/bundle:ro
```

```bash
java -jar target/trino-opa-access-control-*-conformance-cli.jar conformance \
    --policy-dir /path/to/your/policies --mode safe
```

Both the `opa test` fast loop (`policy-conformance-kit/run.sh`) and the CLI jar
must be green before a bundle is published.

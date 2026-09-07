# Trino-OPA Access Control

<p align="center">
  <img alt="Java" src="https://img.shields.io/badge/Java-23%2B-orange">
  <img alt="Trino" src="https://img.shields.io/badge/Trino-474-0059B3">
  <img alt="schema_version" src="https://img.shields.io/badge/schema_version-1-blueviolet">
  <img alt="resilience" src="https://img.shields.io/badge/resilience-fail--closed-red">
</p>

A Trino `SystemAccessControl` plugin that delegates authorization, row
filtering, and column masking to Open Policy Agent (OPA). Every decision is
made in Rego; the plugin marshals request context, enforces a strict response
contract, and **fails closed** — any OPA error, malformed response, or invalid
SQL denies access rather than allowing it.

## Contents

- [Key capabilities](#key-capabilities)
- [Quickstart](#quickstart)
- [Releases](#releases)
- [Policy contract & execution modes](#policy-contract--execution-modes)
- [Identity & group delegation](#identity--group-delegation)
- [Operational characteristics](#operational-characteristics)
- [Testing & verification](#testing--verification)
- [Documentation index](#documentation-index)

---

## Key capabilities

- **Full SPI coverage** — every Trino `SystemAccessControl` method is either routed to OPA or *explicitly* default-denied (never permissive SPI defaults)
- **Row filtering & column masking** — OPA-emitted predicates injected as `ViewExpression`s, structurally validated first
- **Two policy modes** — *safe* (structured descriptors; the plugin renders all SQL) and *passthrough* (raw SQL strings, validated)
- **Per-column authorization** — allow/deny per requested column in one response
- **Fail-closed resilience** — bounded retries with jitter, circuit breaker, negative caching
- **Performance** — Caffeine decision cache keyed on the full marshaled input (minus volatile fields)
- **Auditable** — every decision carries a `decision_id`, echoed to OPA and logged; Micrometer metrics for latency, decisions, fail-closed counts, and breaker state
- **Transport security** — bearer-token auth and TLS with a PKCS12 truststore

---

## Quickstart

### Option A — 10-minute Docker demo (fastest)

```bash
git clone <this repo> && cd trino-opa-plugin
./demo/start.sh
```

Then connect a SQL client (DBeaver) to `localhost:8080` as user `admin` —
the demo policy allows `tpch.tiny.nation` and denies `tpch.tiny.customer`.
Walkthrough: [demo/README.md](demo/README.md).

### Option B — install on a coordinator

<details>
<summary>Build, install & configure</summary>

Requires JDK 23+ to build (Trino 474 SPI ships Java 23 bytecode).

```bash
# 1. Build: produces the plugin jar AND its runtime dependency jars
JAVA_HOME=<JDK 23+> mvn clean package -DskipTests
mvn dependency:copy-dependencies -DincludeScope=runtime -DoutputDirectory=target/plugin
cp target/trino-opa-access-control-*.jar target/plugin/   # never the -conformance-cli jar

# 2. Install: copy ALL jars in target/plugin/ to the coordinator's plugin directory
ssh coordinator 'mkdir -p /data/trino/plugin/opa-access-control'
scp target/plugin/*.jar coordinator:/data/trino/plugin/opa-access-control/

# 3. Configure etc/access-control.properties (see below) and restart Trino
```

> [!NOTE]
> The plugin classloader is isolated: the plugin directory must contain the
> plugin jar **and** its runtime dependencies (Jackson, Caffeine, Micrometer,
> airlift, ...). `dependency:copy-dependencies` handles this; do not add
> `trino-spi`/`trino-parser` (the coordinator provides them) — see
> `demo/start.sh` for a working script that gets this right.

`etc/access-control.properties`:

```properties
access-control.name=opa-access-control
opa.endpoint.url=http://opa:8181
opa.policy.allow.path=/v1/data/trino/allow
opa.policy.row-filters.path=/v1/data/trino/row_filters
opa.policy.column-masks.path=/v1/data/trino/column_masks
opa.policy.filter.path=/v1/data/trino/filter

# SQL mode: must match what your policies emit (see "Execution modes" below)
# opa.sql.mode=safe
# opa.sql.max-in-clause-size=1000

# Security (optional)
# opa.client.auth.token=file:///etc/trino/opa-token          (literal or file://)
# opa.client.tls.enabled=true
# opa.client.tls.truststore.path=/etc/trino/opa-truststore.p12
# opa.client.tls.truststore.password=file:///etc/trino/opa-truststore.pass  (file:// only)
```

</details>

### Deployment kit (Milestone 7)

A ready-to-use deployment kit lives in [`deploy/`](deploy/):

- **`deploy/install.sh`** — builds the plugin + runtime deps and installs them
  into a coordinator's plugin directory (local or over SSH).
- **`deploy/access-control.properties.example`** — a commented sample config.
- **`deploy/UPGRADE-ROLLBACK.md`** — the coordinator upgrade/rollback runbook
  (snapshot, swap jars, restart, smoke test, rollback).
- **`deploy/Dockerfile`** — a sample Trino image with the plugin baked in
  (quick-start only, not a production template).

---

## Releases

Both artifacts are published together on version tags (`v*`) via GitHub Actions
(see `.github/workflows/release.yml`):

- `trino-opa-access-control-<v>.jar` — the **unshaded plugin jar** (deploy this).
- `trino-opa-access-control-<v>-conformance-cli.jar` — the **shaded conformance
  CLI gate** (never deploy into the plugin directory).

Each release includes SHA-256 checksums and a changelog. Artifacts are currently
**unsigned** (signing is deferred — see `docs/ROADMAP.md` M7 Q-C); Maven Central
publishing is deferred until GPG signing lands (Central requires it).

---

## Policy contract & execution modes

Policies respond at four data documents (`data.trino.allow`, `data.trino.row_filters`,
`data.trino.column_masks`, `data.trino.filter`). Every response carries the
envelope `{"schema_version": 1, "result": ...}` — **an unsupported or missing
`schema_version` fails closed**.

### Execution modes

| | **safe mode** (`opa.sql.mode=safe`, default) | **passthrough mode** (`opa.sql.mode=passthrough`, explicit opt-in) |
|---|---|---|
| Row filter / mask result | structured descriptors | raw Trino SQL strings |
| Who writes SQL | the plugin renders it (strict identifiers, `''`-escaped values, IN-bounded) | the policy — quoting/escaping is **your** responsibility |
| Injection risk | none by construction | mitigated by structural validation (fail closed) |
| Expressiveness | `in` / `eq` / `neq` / `is_null` / `is_not_null` | any single Trino expression (CASE, functions, subqueries over the target) |
| Mode/policy mismatch | rejected (fail closed) | rejected (fail closed) |

> [!IMPORTANT]
> **Safe mode is the default** (decision D6, see `docs/ROADMAP.md`). Passthrough
> remains a fully supported explicit opt-in (`opa.sql.mode=passthrough`) for
> expressive masks (CASE, subqueries, functions) that descriptors cannot express
> yet.

### Example responses (Contract 2)

<details>
<summary>View examples</summary>

```jsonc
// allow — boolean (or per-column map, see below)
{"schema_version": 1, "result": true}

// row_filters — passthrough mode
{"schema_version": 1, "result": ["legal_entity_code IN ('LE_DE_01', 'LE_FR_02')"]}

// row_filters — safe mode (descriptor); the plugin renders the SQL
{"schema_version": 1, "result": [{"op": "in", "column": "legal_entity_code", "values": ["LE_DE_01"]}]}

// column_masks — string (passthrough) / descriptor or null (safe); null = unmasked
{"schema_version": 1, "result": {"op": "is_null", "column": "ssn"}}

// filter — allow-listed subset of the requested candidates; [] = allow nothing
{"schema_version": 1, "result": ["finance"]}
```

</details>

**Undefined-rule semantics (learn these — they are the plugin's actual behavior):**

- `allow`: an **undefined rule is a deny** (not an error).
- `row_filters` / `column_masks` / `filter`: an **undefined rule is an ERROR** → fail
  closed. Always return a response (`"result": []` / `null` for "no filter"/"unmasked").

### Per-column authorization

For `SELECT_FROM_COLUMNS`, OPA may answer with a single boolean or a per-column
map — a requested column that is absent or `false` is denied:

```json
{"schema_version": 1, "result": {"name": true, "salary": true, "ssn": false}}
```

Boolean and map responses may be mixed per policy rule.

For the full request/response contract (what the plugin marshals into `input`,
all response shapes, and the SQL validation rules), see
[docs/CONTRACTS.md](docs/CONTRACTS.md) §3.1–3.5.

---

## Identity & group delegation

The plugin is **identity-source-agnostic**: it forwards whatever groups and
roles Trino's `Identity` carries — byte-for-byte, on every decision call. It
does not resolve groups itself.

```
Trino Identity Provider (session groups / roles)
          │
          ▼
trino-opa-access-control  (forwards byte-for-byte as input.identity.*)
          │
          ▼
Open Policy Agent  (evaluates policy against bundle/data)
```

Populating the identity is the deploying organization's responsibility:

- a Trino **`GroupProvider`** backed by your entitlement service, or
- an **IdP claim** (LDAP groups, JWT/OAuth) surfaced by your authenticator, or
- an **OPA data sync** — push user→entitlement mappings into OPA as data
  documents and join `input.identity.user` against them in Rego.

> [!WARNING]
> **Policy-author warning:** if no group source is shipped, `input.identity.groups`
> is `[]` — the plugin cannot tell "no groups" from "group source forgot to
> run". Deny closed on missing claims:
>
> ```rego
> allow if {
>     count(input.identity.groups) > 0
>     "SOME_ENTITLEMENT" in input.identity.groups
> }
> ```

---

## Operational characteristics

### Cache staleness & revocation propagation

Decisions are cached per coordinator; authorization changes are not
instantaneous. Bounds with default configuration:

| Change | Effective within | Config property |
|---|---|---|
| Policy/bundle change (OPA side) | OPA bundle polling interval + up to **30 s** decision cache | `opa.cache.ttl-seconds=30` |
| Entitlement/role change via OPA data | same as above | `opa.cache.ttl-seconds=30` |
| Group/entitlement change via `GroupProvider` | **next session only** — session groups are fixed at login | — (session lifetime) |
| OPA outage | denials negative-cached for **2 s** | `opa.cache.negative-ttl-seconds=2` |

These are a deliberate planning-latency vs. revocation-speed trade-off. Lower
the TTLs if your compliance needs demand faster revocation (at the cost of
more OPA round-trips).

### Resilience guarantees

- **Fail closed, always**: transport error, timeout, non-200, OPA error
  envelope, malformed JSON, missing/unsupported `schema_version`, wrong result
  shape, or invalid SQL → `AccessDeniedException`, negative-cached for 2 s
- **Retry with jitter**: transient failures (transport errors, 5xx) retried
  `opa.client.retry-max` times with exponential backoff ±50% jitter
- **Circuit breaker**: CLOSED/OPEN/HALF_OPEN with `opa.circuit-breaker.*`
  config; while open, calls fail fast instead of hammering a degraded PDP

### Observability

Every decision is correlated end to end by **`decision_id`** (generated per SPI
call, sent to OPA as `input.decision_id`, logged in the audit line: `decision
action=<action> decision_id=<uuid> result=allow|deny|fail_closed|default_deny ...`).
Join plugin logs with OPA decision logs on this field.

Micrometer metrics are registered on a `SimpleMeterRegistry` owned by the
plugin instance (`io.opa.trino.metrics.OpaMetrics#registry()`). Trino does not
expose plugin registries automatically — to scrape, hook a
`PrometheusMeterRegistry`/`JmxMeterRegistry` into that registry from a small
companion plugin or fork, then wire the reporter per Micrometer's docs.

| Metric | Tags | Meaning |
|---|---|---|
| `opa.decision.latency` | `action`, `cache=hit\|miss` | decision latency histogram |
| `opa.decisions` | `action`, `outcome=allow\|deny`, `cache` | decision + cache hit-ratio counters |
| `opa.fail.closed` | `action` (or `DEFAULT_DENY`) | fail-closed count — leading PDP-health indicator |
| `opa.errors` | `kind=transport\|http_status\|timeout\|malformed\|other` | OPA error counts |
| `opa.circuitbreaker.state` | — | gauge: 0=CLOSED, 1=OPEN, 2=HALF_OPEN |
| `opa.cache.size` | `cache=decisions\|volatile\|negative` | gauge: current decision-cache size (D5) |
| `opa.cache.evictions` | `cache=decisions\|volatile\|negative` | cumulative evictions; the backend derives the eviction rate (D5) |

> [!WARNING]
> A rising `opa.fail.closed` or sustained `opa.errors` is your signal that the
> PDP is unhealthy before users notice denials.

---

## Testing & verification

Two harnesses exist for **policy** authors and deployers (the plugin's own
tests run via `mvn test` on every build):

**1. Conformance kit — fast authoring loop (pure `opa test`, no Java):**

```bash
cd policy-conformance-kit
./run.sh examples/safe           # safe-mode (descriptor) example: PASS 7/7 (default mode)
MODE=passthrough ./run.sh examples/passthrough  # passthrough-mode example: PASS 7/7
./run.sh /path/to/your/policies  # conformance-test your own policies (default mode: safe)
```

Exit 0 = every response your policy produces for the fixture inputs conforms.
The fixture inputs are **generated by the plugin's own Java tests** (`mvn test`
refreshes them), so they cannot drift from what the plugin actually sends.

**2. Conformance CLI — the CI gate (real Java parser):**

`mvn package` also produces a self-contained gate jar. It spawns `opa eval`
against your policy directory and judges responses with the plugin's
authoritative parser — catching plugin/policy version skew before deployment:

```bash
java -jar target/trino-opa-access-control-*-conformance-cli.jar conformance \
    --policy-dir /path/to/your/policies --mode safe
```

Exit 0 = bundle publishable; non-zero names the contract clause (§3.2.A–D) and
the failing fixture; exit 2 = gate could not run (missing `opa`, bad
invocation). The Rego-repo CI pattern: `opa test` (fast loop) **and** the CLI
jar (gate) both green → bundle publishable.

**3. Demo deployment** — a local OPA + coordinator stack in
[demo/](demo/README.md) for end-to-end verification in ~10 minutes.

---

## Documentation index

| Document | Read it for |
|---|---|
| [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) | the target architecture: components, flows, deployment topologies, resilience model, config reference |
| [docs/CONTRACTS.md](docs/CONTRACTS.md) | the normative wire contracts (§3.1–§3.5) — what policies must emit and what the plugin sends |
| [docs/SPI-COVERAGE.md](docs/SPI-COVERAGE.md) | reference appendix: per-method SPI mapping — check before assuming an operation is policy-controlled |
| [demo/README.md](demo/README.md) | the demo deployment walkthrough |
| [deploy/UPGRADE-ROLLBACK.md](deploy/UPGRADE-ROLLBACK.md) | the coordinator upgrade/rollback runbook |
| [policy-conformance-kit/README.md](policy-conformance-kit/README.md) | policy-authoring harness details |
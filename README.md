# Trino-OPA Access Control Plugin

A Trino `SystemAccessControl` plugin that delegates authorization, dynamic row
filtering, and column masking to Open Policy Agent (OPA). See
[ROADMAP.md](ROADMAP.md) for **status, all decisions, and pending items** (the
single source of truth — always start there), [SPI-COVERAGE.md](SPI-COVERAGE.md)
for the full SPI matrix, [ARCHITECTURE.md](ARCHITECTURE.md) for the original
design background, and [IMPLEMENTATION-NOTES.md](IMPLEMENTATION-NOTES.md) for
pinned versions, assumptions, and deviations.

## Status — Milestones 1–6

Implemented: config (fail-fast validation), Contract 1 marshaling, OPA HTTP
client, Contract 2 response parsing + schema-version enforcement, Caffeine
decision/negative caching with the canonical full-input cache key, structural
SQL validation before any `ViewExpression` is built, retry-with-jitter and a
circuit breaker (fail-fast while open), the full SPI method matrix (OPA-routed
or explicitly default-deny — see [SPI-COVERAGE.md](SPI-COVERAGE.md)), per-column
allow/deny for `checkCanSelectFromColumns`, and decision_id-correlated audit
logging + Micrometer metrics. Everything fails closed on any OPA error.

## Policy conformance kit (for Rego authors)

`policy-conformance-kit/` is a pure-`opa test` harness that verifies downstream
policies produce Contract-2 responses the plugin accepts — no coordinator
needed:

```bash
cd policy-conformance-kit
./run.sh examples/passthrough      # passthrough-mode example
MODE=safe ./run.sh examples/safe   # safe-mode (descriptor) example
./run.sh /path/to/your/policies    # conformance-test your own policies
```

Exit 0 = conforming. Fixtures are generated from the plugin's own Java tests
(`mvn test` refreshes them), so they cannot drift from what the plugin sends.
Details: `policy-conformance-kit/README.md`.

## Conformance CLI (CI gate for Rego policy repos)

`mvn package` produces **two artifacts from one source tree at one version**:

| Artifact | Contents |
|---|---|
| `trino-opa-access-control-<v>.jar` | the plugin, unshaded, installed per coordinator |
| `trino-opa-access-control-<v>-conformance-cli.jar` | shaded self-contained CLI (Jackson bundled), `Main-Class: io.opa.trino.cli.ConformanceCli` |

The CLI is the deployment gate: a Rego repo's CI calls it before publishing a
bundle. It spawns a throwaway `opa eval` per fixture against the policy
directory (OPA is the only Rego interpreter — the jar never parses Rego) and
judges every response with the plugin's **authoritative**
`OpaResponseParser` / `DescriptorRenderer` / `SqlExpressionValidator`, so a
policy conforming to yesterday's contract fails against a new jar before
deployment, and vice versa.

```bash
# needs opa >= 1.0 on PATH and a JDK >= 23 to run
java -jar trino-opa-access-control-<v>-conformance-cli.jar conformance \
    --policy-dir /path/to/your/policies \
    --mode safe            # must match the plugin's opa.sql.mode (default: passthrough)
```

Options: `--mode passthrough|safe`, `--fixtures <path>` (default: the M5 kit
fixtures), `--opa <path>`, `--max-in-clause-size <n>`, `--schema-version <n>`
(repeat `--policy-dir` to load extra Rego data documents, e.g. the kit's
`cli-negative` mock policy).

Exit codes: **0** all responses conform (bundle publishable); **1** the plugin
would reject a response — the message names the contract clause (§3.2.A–D) and
the failing fixture; **2** fail fast (missing `opa` binary, missing policy dir
or fixtures — the gate can never be silently skipped).

The Rego-repo CI pattern: `opa test` (fast loop, `policy-conformance-kit/run.sh`)
+ the CLI jar (gate) both green → bundle publishable.

## Demo deployment

`demo/docker-compose.yml` runs stock OPA with the M5 example bundle and
(optionally, profile `trino`) a local coordinator with the plugin baked in.
See `demo/README.md` for the 10-minute walkthrough. Explicitly not a
production template.

## Build & test

```
JAVA_HOME=<JDK 23+> mvn -q test
```

## Usage

1. Copy the built jar to `plugin/opa-access-control/` on the coordinator.
2. In `etc/access-control.properties`:

```properties
access-control.name=opa-access-control
opa.endpoint.url=http://127.0.0.1:8181
opa.policy.allow.path=/v1/data/trino/allow
opa.policy.row-filters.path=/v1/data/trino/row_filters
opa.policy.column-masks.path=/v1/data/trino/column_masks

# SQL mode (optional; default safe)
# safe:        OPA emits structured descriptors; the plugin renders and owns
#              quoting/escaping — no raw-string injection path. Policies for
#              row filters / masks must emit descriptors.
# passthrough: OPA emits raw Trino SQL strings (validated by the plugin) —
#              explicit opt-in for expressive masks (CASE, subqueries, functions)
# opa.sql.mode=safe
# opa.sql.max-in-clause-size=1000

# Security (optional): bearer token (literal or file:// path) and TLS truststore
# opa.client.auth.token=file:///etc/trino/opa-token
# opa.client.tls.enabled=true
# opa.client.tls.truststore.path=/etc/trino/opa-truststore.p12
# opa.client.tls.truststore.password=file:///etc/trino/opa-truststore.pass  (file:// only — literals are rejected)
```

3. Author Rego policies that return the Contract 2 shapes
   (`{"schema_version": 1, "result": ...}`); see ARCHITECTURE.md §3.2.
   In **safe mode (the default)**, row-filter/mask policies emit descriptors
   instead of SQL, e.g. `{"op": "in", "column": "org_unit_id", "values": ["a", "b"]}` —
   supported ops: `in`, `eq`, `neq`, `is_null`, `is_not_null`.
   In passthrough mode (explicit opt-in), they emit raw Trino SQL strings,
   which the plugin structurally validates.
4. **Per-column authorization**: for `checkCanSelectFromColumns`, OPA may
   answer either with a single boolean for the whole column set, or with a
   per-column map — `{"ssn": true, "salary": false}`. A requested column that
   is absent from the map or `false` denies the check. Boolean and map
   responses can be mixed per policy rule; both are cached correctly.
5. **Coverage awareness**: SPI methods that have no OPA mapping are
   *explicitly default-denied* (audited with a `decision_id` and counted in
   the `opa.fail.closed{action="DEFAULT_DENY"}` metric) — they never fall
   through to permissive SPI defaults. Check [SPI-COVERAGE.md](SPI-COVERAGE.md)
   before assuming a Trino operation is policy-controlled.

## Observability

Every decision is correlated end to end:

- **`decision_id`** is generated once per SPI call, sent to OPA as
  `input.decision_id`, and logged in the audit line for that call
  (`decision action=<action> decision_id=<uuid> result=allow|deny|fail_closed|default_deny ...`).
  Join plugin logs with OPA decision logs on this field. Logging goes through
  SLF4J (`INFO`), so it lands in the coordinator log with the standard Trino
  log configuration.
- **Micrometer metrics** are registered on a `SimpleMeterRegistry` owned by the
  plugin instance (`io.opa.trino.metrics.OpaMetrics#registry()`). Hook it into
  your monitoring (JMX/Prometheus exporter) if you want it scraped; Trino does
  not expose plugin registries automatically. Emitted metrics:

  | Metric | Tags | Meaning |
  |---|---|---|
  | `opa.decision.latency` | `action`, `cache=hit\|miss` | decision latency histogram |
  | `opa.decisions` | `action`, `outcome=allow\|deny`, `cache` | decision + cache hit-ratio counters |
  | `opa.fail.closed` | `action` (or `DEFAULT_DENY`) | fail-closed count — leading PDP-health indicator |
  | `opa.errors` | `kind=transport\|http_status\|timeout\|malformed\|other` | OPA error counts |
  | `opa.circuitbreaker.state` | — | gauge: 0=CLOSED, 1=OPEN, 2=HALF_OPEN |

  A rising `opa.fail.closed` or sustained `opa.errors` is your signal that the
  PDP is unhealthy before users notice denials.

## Identity sources: groups and entitlements

This plugin is **identity-source-agnostic**. It forwards whatever groups and
roles Trino's `Identity` carries into the OPA input
(`input.identity.groups`, `input.identity.roles`) — byte-for-byte, on every
decision call. It does **not** resolve or populate them itself.

Populating the identity is the deploying organization's responsibility. Common
options:

- A Trino **`GroupProvider`** plugin backed by the organization's
  entitlement/group service (e.g. called once per session at login); groups
  then appear in `input.identity.groups` for the session lifetime.
- An **identity provider claim** (LDAP group membership, JWT/OAuth claims)
  surfaced by Trino's authenticator.
- An **OPA data sync**: the organization pushes its entitlement
  user → entitlement → resource mappings into OPA as data documents/bundles,
  and policies join `input.identity.user` against that data at eval time.

> **Policy-author warning:** if the deployer ships no group source,
> `input.identity.groups` will be `[]` — the plugin cannot tell "no groups"
> apart from "group source forgot to run". Policies should therefore deny
> closed on missing group claims, e.g.:
>
> ```rego
> allow if {
>     count(input.identity.groups) > 0
>     "SOME_ENTITLEMENT" in input.identity.groups
> }
> ```

## Cache staleness & revocation propagation — read before deploying

Decisions are cached per coordinator to keep query planning fast. This means
**authorization changes are not instantaneous**. The exact bounds with the
default configuration (`opa.cache.ttl-seconds=30`,
`opa.cache.negative-ttl-seconds=2`):

| Change | Becomes effective on a coordinator within |
|---|---|
| Policy/bundle change (OPA side) | OPA bundle polling interval (per OPA deployment config) + up to **30 s** of plugin decision cache |
| Entitlement/role change via OPA data | Same as above |
| Group/entitlement change via a Trino `GroupProvider` | **On next session only** — existing sessions keep the groups captured at login for their whole lifetime |
| OPA outage | Denials are negatively cached for **2 s**; recovery is picked up within that window |

These numbers are a **deliberate trade-off between planning latency and
revocation speed**, not an accident. If your compliance requirement demands
faster revocation propagation, lower `opa.cache.ttl-seconds` (at the cost of
more OPA round-trips per unit time) — the configuration is per-deployment.
Where session-lifetime group caching (GroupProvider) is involved, a stricter
requirement can only be met by short session lifetimes or an OPA-data-based
group source rather than the session-embedded one.
# Trino-OPA Access Control Plugin

A Trino `SystemAccessControl` plugin that delegates authorization, dynamic row
filtering, and column masking to Open Policy Agent (OPA). See
[ARCHITECTURE.md](ARCHITECTURE.md) for the design,
[SPI-COVERAGE.md](SPI-COVERAGE.md) for the full SPI matrix,
[ROADMAP.md](ROADMAP.md) for completed milestones and what's next, and
[IMPLEMENTATION-NOTES.md](IMPLEMENTATION-NOTES.md) for the implementation
report (pinned Trino version, assumptions, deviations).

## Status — Milestones 1–4

Implemented: config (fail-fast validation), Contract 1 marshaling, OPA HTTP
client, Contract 2 response parsing + schema-version enforcement, Caffeine
decision/negative caching with the canonical full-input cache key, structural
SQL validation before any `ViewExpression` is built, retry-with-jitter and a
circuit breaker (fail-fast while open), the full SPI method matrix (OPA-routed
or explicitly default-deny — see [SPI-COVERAGE.md](SPI-COVERAGE.md)), per-column
allow/deny for `checkCanSelectFromColumns`, and decision_id-correlated audit
logging + Micrometer metrics. Everything fails closed on any OPA error.

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

# SQL mode (optional; default passthrough)
# passthrough: OPA emits raw SQL strings (validated by the plugin)
# safe:        OPA emits structured descriptors; the plugin renders and owns
#              quoting/escaping — no raw-string injection path
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
   In safe mode, row-filter/mask policies emit descriptors instead of SQL,
   e.g. `{"op": "in", "column": "org_unit_id", "values": ["a", "b"]}` —
   supported ops: `in`, `eq`, `neq`, `is_null`, `is_not_null`.

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

## Build & test
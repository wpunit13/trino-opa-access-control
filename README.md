# Trino-OPA Access Control Plugin

A Trino `SystemAccessControl` plugin that delegates authorization, dynamic row
filtering, and column masking to Open Policy Agent (OPA). See
[ARCHITECTURE.md](ARCHITECTURE.md) for the design and
[IMPLEMENTATION-NOTES.md](IMPLEMENTATION-NOTES.md) for the implementation
report (pinned Trino version, assumptions, deviations).

## Status — Milestones 1–3

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
```

3. Author Rego policies that return the Contract 2 shapes
   (`{"schema_version": 1, "result": ...}`); see ARCHITECTURE.md §3.2.

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

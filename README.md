# Trino-OPA Access Control Plugin

A Trino `SystemAccessControl` plugin that delegates authorization, dynamic row
filtering, and column masking to Open Policy Agent (OPA). See
[ARCHITECTURE.md](ARCHITECTURE.md) for the design and
[IMPLEMENTATION-NOTES.md](IMPLEMENTATION-NOTES.md) for the Milestone 1
implementation report (pinned Trino version, assumptions, deviations).

## Status — Milestones 1 & 2

Implemented: config (fail-fast validation), Contract 1 marshaling, OPA HTTP
client, Contract 2 response parsing + schema-version enforcement, Caffeine
decision/negative caching with the canonical full-input cache key, structural
SQL validation before any `ViewExpression` is built, the SPI methods
`checkCanSelectFromColumns`, `checkCanCreateTable`, `getRowFilters`,
`getColumnMask`, and the bulk-evaluating filter methods `filterCatalogs`,
`filterSchemas`, `filterTables`, `filterColumns`. Resilience: retry-with-jitter
and a hand-rolled circuit breaker (fail-fast while open) around OPA calls.
All unimplemented SPI methods inherit the SPI default (deny/empty). Everything
fails closed on any OPA error.

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

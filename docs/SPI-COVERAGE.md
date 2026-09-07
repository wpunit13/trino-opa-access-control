# SPI Coverage Matrix (Trino 474 `SystemAccessControl`)

> [!NOTE]
> Status, decisions, and pending items live in `docs/ROADMAP.md` (single source of
> truth). This file covers only the per-method SPI mapping. Milestone 6 added
> the conformance CLI gate (`io.opa.trino.cli.ConformanceCli`), which validates
> the Contract-2 response shapes for every one of these paths against the real
> parser — no method mapping changed.

## Contents

- [Session / identity](#session--identity)
- [Catalog](#catalog)
- [Schema](#schema)
- [Table / column](#table--column)
- [View / materialized view](#view--materialized-view)
- [Privileges / roles](#privileges--roles)
- [Query lifecycle](#query-lifecycle)
- [Procedures / functions](#procedures--functions)
- [Row-level security / masking](#row-level-security--masking)
- [Observability](#observability-84)

### Status column (legend)

Each row in the tables below is one `SystemAccessControl` method; the right-hand **`Status`** column says how the plugin handles it, using one of three values:

- **OPA** — routed to OPA via the listed `action`; deny when OPA returns false/undefined.
- **DEFAULT-DENY** — explicitly denied in code (`denyByDefault`), audited with a `decision_id`, **no** OPA call. This is intentional and observable (metric `opa.fail.closed{action="DEFAULT_DENY"}`), never accidental SPI inheritance.
- **NOT-IN-SPI(474)** — the ../docs/ARCHITECTURE.md §5 row does not exist in the Trino 474 SPI under that name; the closest method is noted.

All OPA-routed boolean methods use path `opa.policy.allow.path` (default `/v1/data/trino/allow`); filters use `opa.policy.filter.path` (`/v1/data/trino/filter`); row filters and masks use their own paths. All fail closed (§7).

## Session / identity

| SPI method (Trino 474) | action | Status |
|---|---|---|
| `checkCanSetUser(Optional<Principal>, String)` | `SET_USER` | OPA |
| `checkCanImpersonateUser(Identity, String)` | `IMPERSONATE_USER` | OPA |
| `checkCanSetSystemSessionProperty(Identity, QueryId, String)` | `SET_SYSTEM_SESSION_PROPERTY` | OPA |
| `checkCanSetCatalogSessionProperty(SystemSecurityContext, String, String)` | `SET_CATALOG_SESSION_PROPERTY` | OPA |

> [!NOTE]
> Marshaling note: this SPI surface provides no caller identity for `checkCanSetUser` (the target user is the subject) and no resource field for principals/properties. The target user / property name / procedure name is carried in `resource.columns` as a documented deviation (proposed future field: `resource.target`).

---

## Catalog

| SPI method | action | Status |
|---|---|---|
| `canAccessCatalog(SystemSecurityContext, String)` | `ACCESS_CATALOG` | OPA (returns boolean; SPI 474 has `canAccessCatalog`, not `checkCanAccessCatalog`) |
| `filterCatalogs(SystemSecurityContext, Set<String>)` | `FILTER_CATALOGS` | OPA (bulk) |
| `checkCanCreateCatalog(SystemSecurityContext, String)` | — | DEFAULT-DENY |
| `checkCanDropCatalog(SystemSecurityContext, String)` | — | DEFAULT-DENY |

---

## Schema

| SPI method | action | Status |
|---|---|---|
| `checkCanCreateSchema(SystemSecurityContext, CatalogSchemaName, Map)` | `CREATE_SCHEMA` | OPA |
| `checkCanDropSchema(SystemSecurityContext, CatalogSchemaName)` | `DROP_SCHEMA` | OPA |
| `checkCanRenameSchema(SystemSecurityContext, CatalogSchemaName, String)` | `RENAME_SCHEMA` | OPA |
| `checkCanSetSchemaAuthorization(SystemSecurityContext, CatalogSchemaName, TrinoPrincipal)` | `SET_SCHEMA_AUTHORIZATION` | OPA |
| `checkCanShowSchemas(SystemSecurityContext, String)` | `SHOW_SCHEMAS` | OPA |
| `filterSchemas(SystemSecurityContext, String, Set<String>)` | `FILTER_SCHEMAS` | OPA (bulk) |
| `checkCanShowCreateSchema(SystemSecurityContext, CatalogSchemaName)` | — | DEFAULT-DENY |
| `checkCanGrantSchemaPrivilege` / `checkCanDenySchemaPrivilege` / `checkCanRevokeSchemaPrivilege` | — | DEFAULT-DENY |

---

## Table / column

| SPI method | action | Status |
|---|---|---|
| `checkCanCreateTable(SystemSecurityContext, CatalogSchemaTableName, Map)` | `CREATE_TABLE` | OPA |
| `checkCanDropTable` | `DROP_TABLE` | OPA |
| `checkCanRenameTable` | `RENAME_TABLE` | OPA |
| `checkCanAddColumn` | `ADD_COLUMN` | OPA |
| `checkCanDropColumn` | `DROP_COLUMN` | OPA |
| `checkCanRenameColumn` | `RENAME_COLUMN` | OPA |
| `checkCanSelectFromColumns(SystemSecurityContext, CatalogSchemaTableName, Set<String>)` | `SELECT_FROM_COLUMNS` | OPA — boolean **or** per-column map (missing/false column denies) |
| `checkCanShowTables(SystemSecurityContext, CatalogSchemaName)` | `SHOW_TABLES` | OPA |
| `checkCanShowColumns(SystemSecurityContext, CatalogSchemaTableName)` | `SHOW_COLUMNS` | OPA |
| `filterTables(SystemSecurityContext, String, Set<SchemaTableName>)` | `FILTER_TABLES` | OPA (bulk, `schema.table` round-trip) |
| `filterColumns(SystemSecurityContext, CatalogSchemaTableName, Set<String>)` | `FILTER_COLUMNS` | OPA (bulk) |
| `filterColumns(SystemSecurityContext, String, Map<SchemaTableName, Set<String>>)` (bulk map variant) | — | DEFAULT-DENY (returns empty = allow nothing) |
| `checkCanAlterColumn` | — | DEFAULT-DENY |
| `checkCanInsertIntoTable` / `checkCanDeleteFromTable` / `checkCanTruncateTable` / `checkCanUpdateTableColumns` | — | DEFAULT-DENY |
| `checkCanSetTableAuthorization` / `checkCanSetViewAuthorization` | — | DEFAULT-DENY |
| `checkCanSetTableProperties` / `checkCanSetTableComment` / `checkCanSetViewComment` / `checkCanSetColumnComment` | — | DEFAULT-DENY |
| `checkCanShowCreateTable` | — | DEFAULT-DENY |

---

## View / materialized view

| SPI method | action | Status |
|---|---|---|
| `checkCanCreateView` | `CREATE_VIEW` | OPA |
| `checkCanDropView` | `DROP_VIEW` | OPA |
| `checkCanRenameView` | `RENAME_VIEW` | OPA |
| `checkCanCreateMaterializedView` | `CREATE_MATERIALIZED_VIEW` | OPA |
| `checkCanRefreshMaterializedView` | `REFRESH_MATERIALIZED_VIEW` | OPA |
| `checkCanDropMaterializedView` | `DROP_MATERIALIZED_VIEW` | OPA |
| `checkCanRenameMaterializedView` | `RENAME_MATERIALIZED_VIEW` | OPA |
| `checkCanSetMaterializedViewProperties` | — | DEFAULT-DENY |
| `checkCanCreateViewWithSelectFromColumns` | — | NOT-IN-SPI(474) (removed upstream; superseded by `canCreateViewWithExecuteFunction`, which is DEFAULT-DENY) |

---

## Privileges / roles

| SPI method | action | Status |
|---|---|---|
| `checkCanGrantTablePrivilege` | `GRANT_TABLE_PRIVILEGE` | OPA |
| `checkCanRevokeTablePrivilege` | `REVOKE_TABLE_PRIVILEGE` | OPA |
| `checkCanDenyTablePrivilege` | — | DEFAULT-DENY |
| `checkCanGrantEntityPrivilege` / `checkCanDenyEntityPrivilege` / `checkCanRevokeEntityPrivilege` | — | DEFAULT-DENY |
| `checkCanCreateRole(SystemSecurityContext, String, Optional<TrinoPrincipal>)` | `CREATE_ROLE` | OPA |
| `checkCanDropRole(SystemSecurityContext, String)` | `DROP_ROLE` | OPA |
| `checkCanGrantRoles` / `checkCanRevokeRoles` | `GRANT_ROLES` / `REVOKE_ROLES` | OPA |
| `checkCanShowRoles` / `checkCanShowCurrentRoles` / `checkCanShowRoleGrants` | — | DEFAULT-DENY |
| `checkCanSetRole` | — | NOT-IN-SPI(474) (no such method in the 474 SPI) |

---

## Query lifecycle

| SPI method | action | Status |
|---|---|---|
| `checkCanExecuteQuery(Identity, QueryId)` | `EXECUTE_QUERY` | OPA |
| `checkCanViewQueryOwnedBy(Identity, Identity)` | `VIEW_QUERY_OWNED_BY` | OPA |
| `checkCanKillQueryOwnedBy(Identity, Identity)` | `KILL_QUERY_OWNED_BY` | OPA |
| `filterViewQueryOwnedBy(Identity, Collection<Identity>)` | — | DEFAULT-DENY (returns empty; SPI default is pass-through/allow-all, so the override is mandatory) |
| `checkCanReadSystemInformation` / `checkCanWriteSystemInformation` | — | DEFAULT-DENY |

---

## Procedures / functions

| SPI method | action | Status |
|---|---|---|
| `checkCanExecuteProcedure(SystemSecurityContext, CatalogSchemaRoutineName)` | `EXECUTE_PROCEDURE` | OPA |
| `checkCanExecuteTableProcedure(SystemSecurityContext, CatalogSchemaTableName, String)` | `EXECUTE_TABLE_PROCEDURE` | OPA |
| `canExecuteFunction` / `canCreateViewWithExecuteFunction` | — | DEFAULT-DENY (**returns false**; the SPI default of these two is allow, which is why the override matters) |
| `checkCanShowFunctions` | — | DEFAULT-DENY |
| `filterFunctions(SystemSecurityContext, String, Set<SchemaFunctionName>)` | — | DEFAULT-DENY (returns empty = allow nothing) |
| `checkCanCreateFunction` / `checkCanDropFunction` / `checkCanShowCreateFunction` | — | DEFAULT-DENY |

---

## Row-level security / masking

| SPI method | action | path | Status |
|---|---|---|---|
| `getRowFilters(SystemSecurityContext, CatalogSchemaTableName)` | `GET_ROW_FILTERS` | `row_filters` | OPA + SQL validation |
| `getColumnMask(SystemSecurityContext, CatalogSchemaTableName, String, Type)` | `GET_COLUMN_MASKS` | `column_masks` | OPA + SQL validation |
| `getColumnMasks(SystemSecurityContext, CatalogSchemaTableName, List<ColumnSchema>)` (batch) | — | — | not implemented (out of scope) |

**SQL modes (../docs/ARCHITECTURE.md §3.4):** `opa.sql.mode=safe` (default, D6) accepts
only structured descriptors (`in`/`eq`/`neq`/`is_null`/`is_not_null`) and the plugin
renders the SQL (strict identifiers, `''` escaping, `opa.sql.max-in-clause-size`
bounding); `opa.sql.mode=passthrough` (explicit opt-in) accepts raw SQL strings and
structurally validates them — in both modes anything that fails validation denies. Contract 2
conformance requirements for policy authors are identical for both modes apart
from the filter/mask payload shape. Conformance can be verified without a
coordinator via `policy-conformance-kit/run.sh` (see kit README).

---

## Observability (§8.4)

- **decision_id**: generated once per SPI call, echoed into the OPA request (`input.decision_id`) and into the audit log line for that call (`decision action=<action> decision_id=<uuid> result=allow|deny|fail_closed|default_deny ...`). Correlates plugin logs with OPA decision logs.
- **Metrics** (Micrometer, `SimpleMeterRegistry` owned by the plugin instance; `OpaMetrics.registry()` exposes it for hookup to Trino/JMX exporters):
  - `opa.decision.latency{action, cache=hit|miss}` — latency histogram
  - `opa.decisions{action, outcome=allow|deny, cache}` — decision + cache hit-ratio counters
  - `opa.fail.closed{action}` — fail-closed count (incl. `DEFAULT_DENY`)
  - `opa.errors{kind=transport|http_status|timeout|malformed|other}` — OPA error/5xx/timeout counts
  - `opa.circuitbreaker.state` — gauge (0=CLOSED, 1=OPEN, 2=HALF_OPEN)
  - `opa.cache.size{cache=decisions|volatile|negative}` — gauge: current decision-cache size (D5)
  - `opa.cache.evictions{cache=decisions|volatile|negative}` — cumulative evictions; backend derives the rate (D5)

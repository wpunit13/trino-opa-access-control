# Interface Contracts (Normative Reference)

> [!NOTE]
> **Role of this document.** These are the *normative wire-level interface
> specs* between the plugin, OPA, and Trino — extracted from the original
> architecture document (where they were numbered §3.1–§3.5). They are
> **versioned, load-bearing references**:
>
> - The authoritative *implementation* is the plugin's Java code —
>   `OpaRequestMarshaller` (Contract 1), `OpaResponseParser` /
>   `DescriptorRenderer` / `SqlExpressionValidator` (Contracts 2–4).
> - The policy conformance kit (`policy-conformance-kit/conformance_test.rego`)
>   re-states Contract 2 in Rego as the fast authoring loop.
> - The conformance CLI jar (`io.github.wpunit13.trino.cli`) enforces Contracts 2–4 against
>   the real parser and **names these clause anchors (§3.2.A–D) in its gate
>   failures** — which is why the §3.x numbering must not change.
>
> Architecture (components, flows, deployment) → `ARCHITECTURE.md`.
> Project status and decisions → `ROADMAP.md`.

## Contents

- [§3. The Generality Contracts](#3-the-generality-contracts)
- [§3.1 Contract 1: Context Marshaling (Plugin → OPA `input`)](#31-contract-1-context-marshaling-plugin--opa-input)
- [§3.2 Contract 2: Policy Response Schema (OPA → Plugin)](#32-contract-2-policy-response-schema-opa--plugin)
- [§3.3 Contract 3: Trino SQL Injection Contract](#33-contract-3-trino-sql-injection-contract)
- [§3.4 Contract 4: SQL Validation & "Safe Mode" (Defense-in-Depth)](#34-contract-4-sql-validation--safe-mode-defense-in-depth)
- [§3.5 Contract 5: Schema Versioning](#35-contract-5-schema-versioning)

## §3. The Generality Contracts

To ensure the plugin remains universal across any organization, domain, or data
schema, it adheres to decoupled contracts. Each contract is **schema-versioned**
to permit independent evolution of the plugin and the policies (see §3.5).

---

### §3.1 Contract 1: Context Marshaling (Plugin → OPA `input`)

The plugin makes zero assumptions about enterprise data models or user
metadata. All available context is mapped into a standardized JSON payload:

```json
{
  "schema_version": 1,
  "action": "GET_ROW_FILTERS",
  "decision_id": "3f2b1c9e-7a4d-4e5f-9b1a-2c3d4e5f6a7b",
  "identity": {
    "user": "alice",
    "groups": ["engineering", "analytics_leads"],
    "roles": {
      "system": ["analyst"],
      "catalog_lakehouse": ["developer"]
    },
    "client_tags": ["env:production", "dept:core_platform"],
    "source_ip": "10.0.12.45"
  },
  "resource": {
    "catalog": "lakehouse",
    "schema": "finance",
    "table": "salaries",
    "columns": null
  },
  "session": {
    "query_id": "20260905_001234_00001_abcde",
    "query_type": "SELECT",
    "catalog_session_properties": {}
  }
}
```

Key marshaling rules:

- **`action`** is a canonical enum string derived from the SPI method being
  invoked (see `docs/SPI-COVERAGE.md` for the full mapping). Examples:
  `SELECT_FROM_COLUMNS`, `CREATE_TABLE`, `GET_ROW_FILTERS`, `GET_COLUMN_MASKS`,
  `FILTER_SCHEMAS`.
- **`resource.columns`** is a JSON array of strings for column-scoped
  operations (e.g. `checkCanSelectFromColumns`). It is `null` for whole-table
  or non-column operations. For the bulk filter methods, the *candidate list*
  rides in this field (tables as `schema.table` strings).
- **`decision_id`** is a UUID generated once per SPI call. It is echoed by OPA
  and logged by both the plugin and OPA decision logs, enabling end-to-end
  correlation (see ARCHITECTURE.md §8.4).
- **`query_id`, timestamps, and other volatile fields are never used as cache
  keys** (see ARCHITECTURE.md §6.2).
- **Known Trino 474 gaps:** `client_tags`, `source_ip`, `query_type`, and
  `catalog_session_properties` are not exposed by `SystemSecurityContext` and
  are marshaled as `[]`/`null`/`{}` (see `docs/IMPLEMENTATION-NOTES.md`);
  policies must not rely on them yet.

---

### §3.2 Contract 2: Policy Response Schema (OPA → Plugin)

OPA returns structured responses depending on the access control phase. Every
response carries a `schema_version` so the plugin can detect incompatibility
and fail closed.

#### §3.2.A Authorization Checks (`checkCanSelectFromColumns`, `checkCanCreateTable`, etc.)

* **Path:** `/v1/data/trino/allow` (default; configurable)
* **Schema:**
  ```json
  { "schema_version": 1, "result": true }
  ```
  A single boolean, or a **per-column allow/deny map** for
  `SELECT_FROM_COLUMNS` (a requested column that is absent or `false` is
  denied):
  ```json
  { "schema_version": 1, "result": {"name": true, "salary": true, "ssn": false} }
  ```
  An **undefined rule is a deny** (not an error).

#### §3.2.B Dynamic Row Filtering (`getRowFilters`)

* **Path:** `/v1/data/trino/row_filters`
* **Schema:** passthrough mode — a list of SQL predicate strings; safe mode — a
  list of descriptors (see §3.4). The plugin wraps each predicate into a
  `ViewExpression`; Trino conjoins multiple filters with logical `AND`. An
  empty list means "no filter".
  ```json
  {
    "schema_version": 1,
    "result": [
      "tenant_id = 'org_7718'",
      "org_hierarchy_id IN ('dept_eng_01', 'dept_eng_core')"
    ]
  }
  ```
  An **undefined rule or absent `result` is an ERROR** → fail closed (§3.2.D
  semantics).

#### §3.2.C Column Data Masking (`getColumnMask`)

* **Path:** `/v1/data/trino/column_masks`
* **Schema:** a single SQL projection string (passthrough) or descriptor /
  `null` (safe, null = unmasked).
  ```json
  {
    "schema_version": 1,
    "result": "CASE WHEN 'security_admin' IN (SELECT current_role()) THEN ssn ELSE '***-**-' || SUBSTR(ssn, 8, 4) END"
  }
  ```
  An **undefined rule or absent `result` is an ERROR** → fail closed.

#### §3.2.D Filtering Methods (`filterCatalogs`, `filterSchemas`, `filterTables`, `filterColumns`)

These differ in kind from the boolean `checkCan*` methods: they return a
*filtered subset* of candidate names rather than a boolean.

* **Path:** `/v1/data/trino/filter` (default)
* **Schema:** a list of names to keep. An **empty list means "allow nothing"**;
  an **absent/missing `result` is treated as an error and fails closed**.
  ```json
  {
    "schema_version": 1,
    "result": ["finance", "analytics"]
  }
  ```
* **Bulk evaluation:** the plugin sends the *candidate list* inside
  `input.resource.columns` and expects OPA to return the allow-listed subset in
  a single round-trip.

---

### §3.3 Contract 3: Trino SQL Injection Contract

The plugin injects Trino SQL expressions into queries. Any valid Trino SQL
expression (scalar functions, subqueries, `CASE` statements, regex) can be
emitted by Rego.

> [!NOTE]
> **Note on naming:** this contract is **Trino-specific**, not
> engine-agnostic. Emitted SQL uses Trino functions and syntax. Portability to
> other engines lives in policy translation, not in the plugin.

---

### §3.4 Contract 4: SQL Validation & "Safe Mode" (Defense-in-Depth)

Because OPA-emitted SQL is executed by Trino, the plugin applies structural
validation before injection. Two modes are supported:

1. **Safe mode (default, D6):** OPA emits *structured descriptors* instead of
   raw SQL, and the plugin renders the SQL:
   ```json
   {
     "result": {
       "op": "in",
       "column": "org_unit_id",
       "values": ["dept_eng_01", "dept_eng_core"]
     }
   }
   ```
   Supported ops: `in`, `eq`, `neq`, `is_null`, `is_not_null`. Identifiers must
   match `[A-Za-z_][A-Za-z0-9_]*`; string values are single-quote escaped
   (`''`); `IN (...)` clauses are bounded by `opa.sql.max-in-clause-size`
   (fail closed). This eliminates raw-SQL injection entirely because policies
   can no longer emit arbitrary SQL; the plugin controls quoting/escaping.

2. **Passthrough mode (explicit opt-in, `opa.sql.mode=passthrough`):** OPA
   emits raw SQL. The plugin parses each returned predicate/expression with a
   SQL parser and **rejects** (fails closed) any expression that:
   - does not parse as a single Trino expression,
   - references tables or columns outside the target `resource`, or
   - calls a function outside a configured allow-list.

   Passthrough remains fully supported for expressive masks (CASE, subqueries,
   functions) that descriptors cannot express yet; it is the policy author's
   responsibility to quote/escape correctly.

Both modes share the requirement that the OPA response shape be schema-validated
before use; malformed output fails closed. Rendered SQL also passes through the
structural validator as defense in depth.

---

### §3.5 Contract 5: Schema Versioning

`input.schema_version` and the response `schema_version` allow the plugin and
policies to evolve independently:

- The plugin supports exactly one response `schema_version` (currently **1**);
  a response with an *unknown* version fails closed rather than guessing.
- Version bumps are additive; removing/changing a field requires a
  major-version change and a coordinated rollout.
- The conformance CLI accepts `--schema-version` to validate policies against
  a future/forked supported version — the mechanism for skew-safe rollouts.

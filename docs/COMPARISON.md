# Comparison: Custom Plugin vs. Built-in Trino OPA Access Control

This document provides a functional, architectural, and operational comparison between this plugin (`trino-opa-access-control`, `access-control.name=opa-access-control`) and Trino's official [Built-in Open Policy Agent Access Control](https://trino.io/docs/current/security/opa-access-control.html) (`access-control.name=opa`).

---

## Executive Summary

Trino includes a built-in OPA access control plugin out of the box. For simple deployments with low query concurrency, local loopback OPA daemons, and basic allow/deny rules, the built-in plugin is easy to configure and requires no extra JARs.

However, in **high-throughput, multi-tenant enterprise environments**, the built-in plugin has critical architectural gaps:
1. **Zero caching:** Every query and every schema/table/column authorization check triggers synchronous HTTP calls to OPA, creating latency bottlenecks and risking OPA saturation.
2. **SQL injection risk:** Policies return raw SQL strings that Trino executes directly without validation or sanitization.
3. **No circuit breaker:** If OPA degrades, coordinator planning threads stall on HTTP timeouts, which can cascade into cluster-wide outages.
4. **Index-based batch contracts:** Rego policies must compute integer index pointers over array inputs, making policy logic complex and fragile.
5. **No policy CI/CD gate:** There is no mechanism to validate Rego policy contracts against the Java parser prior to deployment.

This plugin was designed to address these exact enterprise requirements.

---

## Comparison Matrix

| Dimension | Built-in Trino OPA (`access-control.name=opa`) | Custom Plugin (`access-control.name=opa-access-control`) |
| :--- | :--- | :--- |
| **Plugin Identifier** | `access-control.name=opa` | `access-control.name=opa-access-control` |
| **Installation** | Built into standard Trino distribution (zero extra JARs). | Deployed as custom plugin JAR + dependencies into `/plugin/`. |
| **Decision Caching** | ❌ **None.** Every check makes synchronous HTTP POST requests to OPA. | ✅ **Caffeine in-memory cache** with TTL, size bounds, smart key calculation, and negative caching. |
| **Cache Key Strategy** | N/A | Full canonical request hash **excluding volatile fields** (`query_id`, `decision_id`, timestamps). |
| **Negative Caching** | ❌ None. Failing OPA endpoints are hammered on every query. | ✅ **2s negative TTL** for fast failure and OPA recovery protection. |
| **Row Filter / Mask Mode** | Raw SQL strings only (`{"expression": "..."}`). | **Safe mode (default)**: Structured JSON descriptors.<br>**Passthrough mode**: Raw SQL validated by Trino parser. |
| **SQL Injection Defense** | ⚠️ Relies entirely on policy author escaping; no validation. | ✅ Rendered by Java with strict identifier rules and quote escaping (safe mode) or structural AST validation (passthrough). |
| **SQL Bounding** | ❌ None. Policies can emit unbounded `IN (...)` lists. | ✅ Bounded `IN` clauses (`opa.sql.max-in-clause-size`, default 1000). |
| **Resilience & Fault Tolerance** | Basic HTTP client timeouts only. | ✅ Hand-rolled **Circuit Breaker** (`CLOSED`/`OPEN`/`HALF_OPEN`) + retries with **±50% jitter**. |
| **Bulk Resource Filtering** | Returns array of integer *indices* into `filterResources`. | ✅ Returns direct list of allowed names (`["finance", "hr"]`). |
| **Per-Column Authorization** | Evaluated column-by-column or via batch mask index. | ✅ `SELECT_FROM_COLUMNS` accepts a per-column allow/deny map (`{"salary": false, "name": true}`). |
| **Contract Versioning** | ❌ No schema versioning envelope. | ✅ Enforces strict `{"schema_version": 1, ...}` envelope; version mismatch fails closed. |
| **Policy CI/CD Tooling** | ❌ None. Contract regressions are only caught in runtime logs. | ✅ **Policy Conformance Kit** (`opa test`) + **authoritative CLI gate JAR** for Rego CI pipelines. |
| **Determinism Checking** | ❌ None. | ✅ Policy scanner (`scan.sh`) flags non-deterministic builtins (`time.*`, `http.send`, `rand.*`). |
| **Decision Correlation** | ❌ None. | ✅ Unique **`decision_id` (UUID)** injected into request and logged on both Trino and OPA. |
| **Observability** | Verbose HTTP body dumps at `DEBUG` level (`opa.log-requests`). | ✅ **Micrometer metrics** (latency histograms, cache hit ratio, fail-closed counters, breaker state). |
| **SPI Coverage** | Main operations covered; permission toggle (`opa.allow-permission-management-operations`). | ✅ Full SPI coverage matrix (Trino 474); unmapped methods explicitly guarded by `denyByDefault`. |

---

## Detailed Functional Differences

### 1. Decision Caching & Planning Latency
* **Built-in Plugin:** Trino executes access control checks during semantic analysis. For queries with multiple joins, views, and dozens of projected columns, the coordinator performs multiple synchronous HTTP round-trips to OPA per query. Under high concurrency, this overwhelms the OPA cluster and inflates query planning latency.
* **Custom Plugin:** Features an integrated **Caffeine decision cache** (`opa.cache.*`):
  * **Smart Key Generation:** Computes a SHA-256 canonical hash across the entire context, but deliberately strips volatile attributes (`query_id`, `decision_id`, session timestamps). This guarantees cache hits across different queries issued by the same user with identical privileges.
  * **Negative Caching:** Denials and transport failures are cached for a short window (default 2 seconds), preventing degraded OPA instances from being bombarded.
  * **Volatile Context Separation:** Session properties and client tags are isolated with distinct TTLs.

### 2. SQL Injection & The Trust Boundary
* **Built-in Plugin:** OPA policies must construct raw SQL predicate strings for row filters and column masks:
  ```rego
  rowFilters contains {"expression": "user_type <> 'customer'"}
  ```
  If policy authors dynamically concatenate input values or mishandle string quoting, it introduces syntax errors or SQL injection vulnerabilities directly into Trino's query execution engine.
* **Custom Plugin:**
  * **Safe Mode (Default):** Policies never write SQL strings. Instead, they emit structured JSON descriptors:
    ```json
    {
      "schema_version": 1,
      "result": [{"op": "in", "column": "org_unit", "values": ["ENG_01", "ENG_02"]}]
    }
    ```
    The Java plugin validates identifiers against strict regex patterns (`^[A-Za-z_][A-Za-z0-9_]*$`), doubles single quotes for literal escaping, bounds `IN` clause sizes (`opa.sql.max-in-clause-size`), and renders the SQL predicate. SQL injection is eliminated by construction.
  * **Passthrough Mode (Opt-in):** If raw SQL is needed for complex expressions (`CASE`, subqueries), the plugin parses the string using Trino's official `SqlParser` and AST visitor (`SqlExpressionValidator`), rejecting multi-statement injections, unauthorized table references, and disallowed functions (`opa.sql.allowed-functions`).

### 3. Cascading Failure Protection & Resilience
* **Built-in Plugin:** Relies on standard HTTP timeouts. When OPA experiences high CPU, garbage collection pauses, or network congestion, Trino coordinator threads block waiting for OPA responses. In severe cases, coordinator thread pools are exhausted, leading to cluster-wide denial-of-service.
* **Custom Plugin:**
  * **Circuit Breaker:** Tracks consecutive failures and timeouts. When failure rates cross the threshold (`opa.circuit-breaker.failure-threshold`), the breaker trips to `OPEN`. Queries immediately fail closed without initiating network calls, protecting coordinator threads.
  * **Jittered Retries:** Read-only decision requests are retried with exponential backoff and ±50% randomized jitter (`opa.client.retry-max`, `opa.client.retry-backoff-ms`) to prevent thundering herd spikes against recovering OPA replicas.

### 4. Wire Contracts & Batch Filtering Complexity
* **Built-in Plugin:** To filter lists of schemas, tables, or columns, OPA must return an array containing the *integer indices* of allowed items from the request's `filterResources`:
  ```rego
  batch contains i if {
      some i
      raw_resource := input.action.filterResources[i]
      allow with input.action.resource as raw_resource
  }
  ```
  Writing index-mapping logic across nested tables and column arrays in Rego is notoriously tedious and prone to indexing off-by-one errors.
* **Custom Plugin:**
  * **Direct Name Lists:** Bulk filtering methods send the candidate list and receive back the allow-listed names directly (`["finance", "analytics"]`).
  * **Per-Column Authorization Map:** For `SELECT_FROM_COLUMNS`, OPA can return a boolean or a column-to-boolean map (`{"name": true, "ssn": false}`). Any omitted or `false` column is automatically denied, allowing fine-grained column access control in a single round-trip.

### 5. Policy Supply Chain & CI/CD Verification
* **Built-in Plugin:** Provides no offline policy verification harness. Teams must write ad-hoc Rego unit tests, and schema mismatches or syntax errors are only discovered after policies are deployed to live coordinators.
* **Custom Plugin:** Provides a production-ready policy verification toolchain:
  * **Policy Conformance Kit (`policy-conformance-kit/`):** Fast local testing loop (`./run.sh`) using `opa test`. Fixtures are auto-generated directly from the plugin's Java unit tests (`fixtures/contract1_inputs.json`), preventing specification drift.
  * **Conformance CLI Gate (`trino-opa-access-control-*-conformance-cli.jar`):** A standalone shaded JAR that runs in CI against your Rego repository. It evaluates policies with `opa eval` and validates responses against the real Java parser (`OpaResponseParser` and `SqlExpressionValidator`). Failing policies block bundle publishing before reaching production.
  * **Determinism Scanner (`scan.sh`):** Flags non-deterministic Rego builtins (`time.now_ns`, `http.send`, `rand.intn`) that would break decision cache consistency.

### 6. Observability & Audit Trails
* **Built-in Plugin:** Logging is limited to dumping complete HTTP request/response bodies at `DEBUG` level (`opa.log-requests` / `opa.log-responses`), which generates enormous log volumes and lacks correlation identifiers.
* **Custom Plugin:**
  * **`decision_id` Correlation:** Generates a UUID per SPI call, passed to OPA as `input.decision_id` and logged in structured audit lines:
    ```text
    decision action=SELECT_FROM_COLUMNS decision_id=3f2b1c9e-... result=allow ...
    ```
    This allows operators to correlate Trino queries directly with OPA decision logs.
  * **Micrometer Metrics:** Exposes detailed operational metrics including decision latency histograms (`opa.decision.latency`), cache hit/miss counters (`opa.decisions`), fail-closed occurrences (`opa.fail.closed`), circuit breaker state gauges (`opa.circuitbreaker.state`), and cache size/evictions (`opa.cache.size`).

---

## When to Choose Which

### Choose the Built-in Plugin (`access-control.name=opa`) if:
- You want a lightweight setup with zero external JARs or dependencies to manage on the coordinator.
- Your query volume is modest, and query planning latency is not a critical concern.
- OPA is deployed as a local sidecar daemon on the coordinator where network latency is negligible.
- Your security policies are limited to simple table/catalog allow/deny rules that do not require row filtering, column masking, or complex descriptor rendering.

### Choose this Custom Plugin (`access-control.name=opa-access-control`) if:
- **You run high-concurrency or latency-sensitive Trino clusters:** Decision caching is essential to maintain low planning latency and protect OPA.
- **You require defense-in-depth SQL security:** Safe mode guarantees that policy authors cannot accidentally or maliciously introduce SQL injection vulnerabilities.
- **You need high operational availability:** Circuit breakers, jittered retries, and negative caching protect Trino from coordinator thread pool exhaustion during OPA degradation.
- **You practice GitOps for policy deployment:** You need an automated CI gate (`conformance-cli`) to test Rego policies against the real Java contract before releasing bundles to production.
- **You have enterprise audit and observability requirements:** You need `decision_id` correlation between Trino and OPA logs and production metrics for Prometheus/Datadog.

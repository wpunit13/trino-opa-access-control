package io.opa.trino;

import com.fasterxml.jackson.databind.JsonNode;
import io.opa.trino.cache.CacheKeyCalculator;
import io.opa.trino.cache.DecisionCache;
import io.opa.trino.client.OpaClientException;
import io.opa.trino.client.OpaHttpClient;
import io.opa.trino.client.OpaResponseParser;
import io.opa.trino.config.OpaConfig;
import io.opa.trino.marshal.OpaRequestMarshaller;
import io.opa.trino.marshal.OpaAction;
import io.opa.trino.marshal.OpaRequestContext;
import io.opa.trino.metrics.DecisionLogger;
import io.opa.trino.metrics.OpaMetrics;
import io.opa.trino.sql.DescriptorRenderer;
import io.opa.trino.sql.SqlExpressionValidator;
import io.trino.spi.QueryId;
import io.trino.spi.connector.CatalogSchemaName;
import io.trino.spi.connector.CatalogSchemaRoutineName;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.connector.SchemaTableName;
import io.trino.spi.security.AccessDeniedException;
import io.trino.spi.security.Identity;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemSecurityContext;
import io.trino.spi.security.TrinoPrincipal;
import io.trino.spi.security.ViewExpression;
import io.trino.spi.type.Type;

import java.net.http.HttpTimeoutException;
import java.security.Principal;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The plugin core. This class performs NO policy logic (ARCHITECTURE.md §2):
 * it marshals SPI context to JSON, caches, calls OPA, validates responses and SQL,
 * and translates results into SPI return types. Every failure mode fails closed
 * with {@link AccessDeniedException} (ARCHITECTURE.md §7).
 *
 * SPI coverage (§5): methods not routed to OPA are explicitly default-deny via
 * {@link #denyByDefault} (auditable, not accidental) — see SPI-COVERAGE.md.
 *
 * Observability (§8.4): every decision is logged with its decision_id and
 * recorded in Micrometer metrics (latency cache hit/miss, allow/deny,
 * fail-closed count, error kinds).
 */
public final class OpaAccessControl
        implements SystemAccessControl
{
    private final OpaConfig config;
    private final OpaHttpClient client;
    private final OpaResponseParser responseParser;
    private final OpaRequestMarshaller marshaller;
    private final CacheKeyCalculator cacheKeyCalculator;
    private final DecisionCache decisionCache;
    private final SqlExpressionValidator sqlValidator;
    private final OpaMetrics metrics;
    private final DecisionLogger decisionLogger;
    private final DescriptorRenderer descriptorRenderer;

    public OpaAccessControl(
            OpaConfig config,
            OpaHttpClient client,
            OpaResponseParser responseParser,
            OpaRequestMarshaller marshaller,
            CacheKeyCalculator cacheKeyCalculator,
            DecisionCache decisionCache,
            SqlExpressionValidator sqlValidator,
            OpaMetrics metrics,
            DecisionLogger decisionLogger)
    {
        this.config = config;
        this.client = client;
        this.responseParser = responseParser;
        this.marshaller = marshaller;
        this.cacheKeyCalculator = cacheKeyCalculator;
        this.decisionCache = decisionCache;
        this.sqlValidator = sqlValidator;
        this.metrics = metrics;
        this.decisionLogger = decisionLogger;
        this.descriptorRenderer = new DescriptorRenderer(config.getMaxInClauseSize());
    }

    /** Caller identity + query id, normalized across the two SPI context shapes. */
    private record CallerCtx(Identity identity, Optional<String> queryId)
    {
        static CallerCtx of(SystemSecurityContext context)
        {
            return new CallerCtx(
                    context.getIdentity(),
                    Optional.ofNullable(context.getQueryId()).map(QueryId::getId));
        }
    }

    // ==================================================================
    // Session / identity (§5)
    // ==================================================================

    @Override
    public void checkCanSetUser(Optional<Principal> principal, String userName)
    {
        // The target user is the subject of the decision; no caller identity is
        // provided by this SPI method. The target is carried in resource.columns.
        String decisionId = marshaller.newDecisionId();
        boolean allowed = evaluateBoolean(OpaAction.SET_USER, new CallerCtx(Identity.ofUser(userName), Optional.empty()),
                null, null, null, List.of(userName), decisionId);
        logDecision(OpaAction.SET_USER, userName, decisionId, allowed, false);
        if (!allowed) {
            throw new AccessDeniedException("Access denied: SET USER " + userName);
        }
    }

    @Override
    public void checkCanImpersonateUser(Identity identity, String userName)
    {
        String decisionId = marshaller.newDecisionId();
        boolean allowed = evaluateBoolean(OpaAction.IMPERSONATE_USER, new CallerCtx(identity, Optional.empty()),
                null, null, null, List.of(userName), decisionId);
        logDecision(OpaAction.IMPERSONATE_USER, identity.getUser(), decisionId, allowed, false);
        if (!allowed) {
            throw new AccessDeniedException("Access denied: impersonate user " + userName);
        }
    }

    @Override
    public void checkCanSetSystemSessionProperty(Identity identity, QueryId queryId, String propertyName)
    {
        String decisionId = marshaller.newDecisionId();
        boolean allowed = evaluateBoolean(OpaAction.SET_SYSTEM_SESSION_PROPERTY, new CallerCtx(identity, Optional.ofNullable(queryId).map(QueryId::getId)),
                null, null, null, List.of(propertyName), decisionId);
        logDecision(OpaAction.SET_SYSTEM_SESSION_PROPERTY, identity.getUser(), decisionId, allowed, false);
        if (!allowed) {
            throw new AccessDeniedException("Access denied: set system session property " + propertyName);
        }
    }

    @Override
    public void checkCanSetCatalogSessionProperty(SystemSecurityContext context, String catalogName, String propertyName)
    {
        String decisionId = marshaller.newDecisionId();
        boolean allowed = evaluateBoolean(OpaAction.SET_CATALOG_SESSION_PROPERTY, CallerCtx.of(context),
                catalogName, null, null, List.of(propertyName), decisionId);
        logDecision(OpaAction.SET_CATALOG_SESSION_PROPERTY, context.getIdentity().getUser(), decisionId, allowed, false);
        if (!allowed) {
            throw new AccessDeniedException("Access denied: set catalog session property " + catalogName + "." + propertyName);
        }
    }

    // ==================================================================
    // Catalog (§5)
    // ==================================================================

    @Override
    public boolean canAccessCatalog(SystemSecurityContext context, String catalogName)
    {
        String decisionId = marshaller.newDecisionId();
        boolean allowed = evaluateBoolean(OpaAction.ACCESS_CATALOG, CallerCtx.of(context),
                catalogName, null, null, null, decisionId);
        logDecision(OpaAction.ACCESS_CATALOG, context.getIdentity().getUser(), decisionId, allowed, false);
        return allowed;
    }

    // ==================================================================
    // Schema (§5)
    // ==================================================================

    @Override
    public void checkCanCreateSchema(SystemSecurityContext context, CatalogSchemaName schemaName, Map<String, Object> properties)
    {
        requireAllowed(OpaAction.CREATE_SCHEMA, context, schemaName.getCatalogName(), schemaName.getSchemaName(), null);
    }

    @Override
    public void checkCanDropSchema(SystemSecurityContext context, CatalogSchemaName schemaName)
    {
        requireAllowed(OpaAction.DROP_SCHEMA, context, schemaName.getCatalogName(), schemaName.getSchemaName(), null);
    }

    @Override
    public void checkCanRenameSchema(SystemSecurityContext context, CatalogSchemaName schemaName, String newSchemaName)
    {
        requireAllowed(OpaAction.RENAME_SCHEMA, context, schemaName.getCatalogName(), schemaName.getSchemaName(), List.of(newSchemaName));
    }

    @Override
    public void checkCanSetSchemaAuthorization(SystemSecurityContext context, CatalogSchemaName schemaName, TrinoPrincipal principal)
    {
        requireAllowed(OpaAction.SET_SCHEMA_AUTHORIZATION, context, schemaName.getCatalogName(), schemaName.getSchemaName(), List.of(principal.getName()));
    }

    @Override
    public void checkCanShowSchemas(SystemSecurityContext context, String catalogName)
    {
        requireAllowed(OpaAction.SHOW_SCHEMAS, context, catalogName, null, null);
    }

    // ==================================================================
    // Table / column (§5)
    // ==================================================================

    @Override
    public void checkCanCreateTable(SystemSecurityContext context, CatalogSchemaTableName table, Map<String, Object> properties)
    {
        requireAllowed(OpaAction.CREATE_TABLE, context, table, null);
    }

    @Override
    public void checkCanDropTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireAllowed(OpaAction.DROP_TABLE, context, table, null);
    }

    @Override
    public void checkCanRenameTable(SystemSecurityContext context, CatalogSchemaTableName table, CatalogSchemaTableName newTable)
    {
        requireAllowed(OpaAction.RENAME_TABLE, context, table, List.of(newTableName(newTable)));
    }

    @Override
    public void checkCanAddColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireAllowed(OpaAction.ADD_COLUMN, context, table, null);
    }

    @Override
    public void checkCanDropColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireAllowed(OpaAction.DROP_COLUMN, context, table, null);
    }

    @Override
    public void checkCanRenameColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireAllowed(OpaAction.RENAME_COLUMN, context, table, null);
    }

    @Override
    public void checkCanShowTables(SystemSecurityContext context, CatalogSchemaName schemaName)
    {
        requireAllowed(OpaAction.SHOW_TABLES, context, schemaName.getCatalogName(), schemaName.getSchemaName(), null);
    }

    @Override
    public void checkCanShowColumns(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        requireAllowed(OpaAction.SHOW_COLUMNS, context, table, null);
    }

    /**
     * §5 note: OPA may answer with a single boolean for the whole set or a
     * per-column allow/deny map; both are supported. A column absent from the map
     * or explicitly false denies the whole check (fail closed).
     */
    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        String decisionId = marshaller.newDecisionId();
        List<String> sortedColumns = sorted(columns);
        EvalOutcome outcome = evaluateDecision(OpaAction.SELECT_FROM_COLUMNS, CallerCtx.of(context),
                table.getCatalogName(), table.getSchemaTableName().getSchemaName(), table.getSchemaTableName().getTableName(),
                sortedColumns, decisionId);
        boolean allowed;
        if (outcome.decision() instanceof Boolean all) {
            allowed = all;
        }
        else {
            @SuppressWarnings("unchecked")
            Map<String, Boolean> perColumn = (Map<String, Boolean>) outcome.decision();
            Set<String> denied = new LinkedHashSet<>();
            for (String column : sortedColumns) {
                Boolean columnDecision = perColumn.get(column);
                if (columnDecision == null || !columnDecision) {
                    denied.add(column);
                }
            }
            allowed = denied.isEmpty();
        }
        metrics.recordDecision(OpaAction.SELECT_FROM_COLUMNS.wireName(), allowed, outcome.cacheHit(), outcome.latencyNanos());
        lastCacheHit = outcome.cacheHit();
        logDecision(OpaAction.SELECT_FROM_COLUMNS, context.getIdentity().getUser(), decisionId, allowed, outcome.cacheHit());
        if (!allowed) {
            throw new AccessDeniedException("Access denied: SELECT on columns " + columns + " of " + table);
        }
    }

    // ==================================================================
    // View / materialized view (§5)
    // ==================================================================

    @Override
    public void checkCanCreateView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireAllowed(OpaAction.CREATE_VIEW, context, view, null);
    }

    @Override
    public void checkCanDropView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireAllowed(OpaAction.DROP_VIEW, context, view, null);
    }

    @Override
    public void checkCanRenameView(SystemSecurityContext context, CatalogSchemaTableName view, CatalogSchemaTableName newView)
    {
        requireAllowed(OpaAction.RENAME_VIEW, context, view, List.of(newTableName(newView)));
    }

    @Override
    public void checkCanCreateMaterializedView(SystemSecurityContext context, CatalogSchemaTableName view, Map<String, Object> properties)
    {
        requireAllowed(OpaAction.CREATE_MATERIALIZED_VIEW, context, view, null);
    }

    @Override
    public void checkCanRefreshMaterializedView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireAllowed(OpaAction.REFRESH_MATERIALIZED_VIEW, context, view, null);
    }

    @Override
    public void checkCanDropMaterializedView(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        requireAllowed(OpaAction.DROP_MATERIALIZED_VIEW, context, view, null);
    }

    @Override
    public void checkCanRenameMaterializedView(SystemSecurityContext context, CatalogSchemaTableName view, CatalogSchemaTableName newView)
    {
        requireAllowed(OpaAction.RENAME_MATERIALIZED_VIEW, context, view, List.of(newTableName(newView)));
    }

    // ==================================================================
    // Privileges / roles (§5)
    // ==================================================================

    @Override
    public void checkCanGrantTablePrivilege(SystemSecurityContext context, io.trino.spi.security.Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal principal, boolean grantOption)
    {
        requireAllowed(OpaAction.GRANT_TABLE_PRIVILEGE, context, table, List.of(principal.getName() + ":" + privilege.name()));
    }

    @Override
    public void checkCanRevokeTablePrivilege(SystemSecurityContext context, io.trino.spi.security.Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal principal, boolean grantOption)
    {
        requireAllowed(OpaAction.REVOKE_TABLE_PRIVILEGE, context, table, List.of(principal.getName() + ":" + privilege.name()));
    }

    @Override
    public void checkCanCreateRole(SystemSecurityContext context, String role, Optional<TrinoPrincipal> grantor)
    {
        requireAllowed(OpaAction.CREATE_ROLE, context, null, null, null, List.of(role));
    }

    @Override
    public void checkCanDropRole(SystemSecurityContext context, String role)
    {
        requireAllowed(OpaAction.DROP_ROLE, context, null, null, null, List.of(role));
    }

    @Override
    public void checkCanGrantRoles(SystemSecurityContext context, Set<String> roles, Set<TrinoPrincipal> principals, boolean grantOption, Optional<TrinoPrincipal> grantor)
    {
        requireAllowed(OpaAction.GRANT_ROLES, context, null, null, null, sortedRolesAndPrincipals(roles, principals));
    }

    @Override
    public void checkCanRevokeRoles(SystemSecurityContext context, Set<String> roles, Set<TrinoPrincipal> principals, boolean grantOption, Optional<TrinoPrincipal> grantor)
    {
        requireAllowed(OpaAction.REVOKE_ROLES, context, null, null, null, sortedRolesAndPrincipals(roles, principals));
    }

    // ==================================================================
    // Query lifecycle (§5)
    // ==================================================================

    @Override
    public void checkCanExecuteQuery(Identity identity, QueryId queryId)
    {
        String decisionId = marshaller.newDecisionId();
        boolean allowed = evaluateBoolean(OpaAction.EXECUTE_QUERY, new CallerCtx(identity, Optional.ofNullable(queryId).map(QueryId::getId)),
                null, null, null, null, decisionId);
        logDecision(OpaAction.EXECUTE_QUERY, identity.getUser(), decisionId, allowed, false);
        if (!allowed) {
            throw new AccessDeniedException("Access denied: execute query");
        }
    }

    @Override
    public void checkCanViewQueryOwnedBy(Identity identity, Identity queryOwner)
    {
        String decisionId = marshaller.newDecisionId();
        boolean allowed = evaluateBoolean(OpaAction.VIEW_QUERY_OWNED_BY, new CallerCtx(identity, Optional.empty()),
                null, null, null, List.of(queryOwner.getUser()), decisionId);
        logDecision(OpaAction.VIEW_QUERY_OWNED_BY, identity.getUser(), decisionId, allowed, false);
        if (!allowed) {
            throw new AccessDeniedException("Access denied: view query owned by " + queryOwner.getUser());
        }
    }

    @Override
    public void checkCanKillQueryOwnedBy(Identity identity, Identity queryOwner)
    {
        String decisionId = marshaller.newDecisionId();
        boolean allowed = evaluateBoolean(OpaAction.KILL_QUERY_OWNED_BY, new CallerCtx(identity, Optional.empty()),
                null, null, null, List.of(queryOwner.getUser()), decisionId);
        logDecision(OpaAction.KILL_QUERY_OWNED_BY, identity.getUser(), decisionId, allowed, false);
        if (!allowed) {
            throw new AccessDeniedException("Access denied: kill query owned by " + queryOwner.getUser());
        }
    }

    // ==================================================================
    // Procedures (§5)
    // ==================================================================

    @Override
    public void checkCanExecuteProcedure(SystemSecurityContext context, CatalogSchemaRoutineName routine)
    {
        requireAllowed(OpaAction.EXECUTE_PROCEDURE, context, routine.getCatalogName(), routine.getSchemaName(), List.of(routine.getRoutineName()));
    }

    @Override
    public void checkCanExecuteTableProcedure(SystemSecurityContext context, CatalogSchemaTableName table, String procedure)
    {
        requireAllowed(OpaAction.EXECUTE_TABLE_PROCEDURE, context, table, List.of(procedure));
    }

    // ==================================================================
    // Explicit default deny (§5/§7). Methods without an OPA mapping are denied
    // INTENTIONALLY here — never by relying on SPI default inheritance — and
    // every default denial is audited with a decision_id. See SPI-COVERAGE.md.
    // ==================================================================

    private void denyByDefault(String spiMethod, String target)
    {
        String decisionId = marshaller.newDecisionId();
        metrics.recordFailClosed("DEFAULT_DENY");
        decisionLogger.log(String.format(Locale.ROOT,
                "decision action=%s decision_id=%s result=default_deny target=%s",
                spiMethod, decisionId, target == null ? "" : target));
        throw new AccessDeniedException("Access denied (default deny: no OPA rule mapped): " + spiMethod
                + (target == null || target.isEmpty() ? "" : " on " + target));
    }

    @Override
    public void checkCanCreateCatalog(SystemSecurityContext context, String catalogName)
    {
        denyByDefault("checkCanCreateCatalog", catalogName);
    }

    @Override
    public void checkCanDropCatalog(SystemSecurityContext context, String catalogName)
    {
        denyByDefault("checkCanDropCatalog", catalogName);
    }

    @Override
    public void checkCanShowCreateSchema(SystemSecurityContext context, CatalogSchemaName schemaName)
    {
        denyByDefault("checkCanShowCreateSchema", schemaName.toString());
    }

    @Override
    public void checkCanShowCreateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        denyByDefault("checkCanShowCreateTable", table.toString());
    }

    @Override
    public void checkCanSetTableProperties(SystemSecurityContext context, CatalogSchemaTableName table, Map<String, java.util.Optional<Object>> properties)
    {
        denyByDefault("checkCanSetTableProperties", table.toString());
    }

    @Override
    public void checkCanSetTableComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        denyByDefault("checkCanSetTableComment", table.toString());
    }

    @Override
    public void checkCanSetViewComment(SystemSecurityContext context, CatalogSchemaTableName view)
    {
        denyByDefault("checkCanSetViewComment", view.toString());
    }

    @Override
    public void checkCanSetColumnComment(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        denyByDefault("checkCanSetColumnComment", table.toString());
    }

    @Override
    public void checkCanAlterColumn(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        denyByDefault("checkCanAlterColumn", table.toString());
    }

    @Override
    public void checkCanSetTableAuthorization(SystemSecurityContext context, CatalogSchemaTableName table, TrinoPrincipal principal)
    {
        denyByDefault("checkCanSetTableAuthorization", table + ":" + principal.getName());
    }

    @Override
    public void checkCanSetViewAuthorization(SystemSecurityContext context, CatalogSchemaTableName view, TrinoPrincipal principal)
    {
        denyByDefault("checkCanSetViewAuthorization", view + ":" + principal.getName());
    }

    @Override
    public void checkCanInsertIntoTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        denyByDefault("checkCanInsertIntoTable", table.toString());
    }

    @Override
    public void checkCanDeleteFromTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        denyByDefault("checkCanDeleteFromTable", table.toString());
    }

    @Override
    public void checkCanTruncateTable(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        denyByDefault("checkCanTruncateTable", table.toString());
    }

    @Override
    public void checkCanUpdateTableColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        denyByDefault("checkCanUpdateTableColumns", table + ":" + columns);
    }

    @Override
    public void checkCanDenyTablePrivilege(SystemSecurityContext context, io.trino.spi.security.Privilege privilege, CatalogSchemaTableName table, TrinoPrincipal principal)
    {
        denyByDefault("checkCanDenyTablePrivilege", table + ":" + privilege.name());
    }

    @Override
    public void checkCanGrantSchemaPrivilege(SystemSecurityContext context, io.trino.spi.security.Privilege privilege, CatalogSchemaName schemaName, TrinoPrincipal principal, boolean grantOption)
    {
        denyByDefault("checkCanGrantSchemaPrivilege", schemaName + ":" + privilege.name());
    }

    @Override
    public void checkCanDenySchemaPrivilege(SystemSecurityContext context, io.trino.spi.security.Privilege privilege, CatalogSchemaName schemaName, TrinoPrincipal principal)
    {
        denyByDefault("checkCanDenySchemaPrivilege", schemaName + ":" + privilege.name());
    }

    @Override
    public void checkCanRevokeSchemaPrivilege(SystemSecurityContext context, io.trino.spi.security.Privilege privilege, CatalogSchemaName schemaName, TrinoPrincipal principal, boolean grantOption)
    {
        denyByDefault("checkCanRevokeSchemaPrivilege", schemaName + ":" + privilege.name());
    }

    @Override
    public void checkCanGrantEntityPrivilege(SystemSecurityContext context, io.trino.spi.connector.EntityPrivilege privilege, io.trino.spi.connector.EntityKindAndName entity, TrinoPrincipal principal, boolean grantOption)
    {
        denyByDefault("checkCanGrantEntityPrivilege", entity + ":" + privilege.name());
    }

    @Override
    public void checkCanDenyEntityPrivilege(SystemSecurityContext context, io.trino.spi.connector.EntityPrivilege privilege, io.trino.spi.connector.EntityKindAndName entity, TrinoPrincipal principal)
    {
        denyByDefault("checkCanDenyEntityPrivilege", entity + ":" + privilege.name());
    }

    @Override
    public void checkCanRevokeEntityPrivilege(SystemSecurityContext context, io.trino.spi.connector.EntityPrivilege privilege, io.trino.spi.connector.EntityKindAndName entity, TrinoPrincipal principal, boolean grantOption)
    {
        denyByDefault("checkCanRevokeEntityPrivilege", entity + ":" + privilege.name());
    }

    @Override
    public void checkCanSetMaterializedViewProperties(SystemSecurityContext context, CatalogSchemaTableName view, Map<String, java.util.Optional<Object>> properties)
    {
        denyByDefault("checkCanSetMaterializedViewProperties", view.toString());
    }

    @Override
    public void checkCanShowRoles(SystemSecurityContext context)
    {
        denyByDefault("checkCanShowRoles", "");
    }

    @Override
    public void checkCanShowCurrentRoles(SystemSecurityContext context)
    {
        denyByDefault("checkCanShowCurrentRoles", "");
    }

    @Override
    public void checkCanShowRoleGrants(SystemSecurityContext context)
    {
        denyByDefault("checkCanShowRoleGrants", "");
    }

    @Override
    public void checkCanReadSystemInformation(Identity identity)
    {
        denyByDefault("checkCanReadSystemInformation", identity.getUser());
    }

    @Override
    public java.util.Collection<Identity> filterViewQueryOwnedBy(Identity identity, java.util.Collection<Identity> ownedIdentities)
    {
        // SPI default is pass-through (allow-all): override to fail closed.
        String decisionId = marshaller.newDecisionId();
        metrics.recordFailClosed("DEFAULT_DENY");
        decisionLogger.log(String.format(Locale.ROOT,
                "decision action=filterViewQueryOwnedBy decision_id=%s result=default_deny (empty)", decisionId));
        return java.util.List.of();
    }

    @Override
    public void checkCanWriteSystemInformation(Identity identity)
    {
        denyByDefault("checkCanWriteSystemInformation", identity.getUser());
    }

    @Override
    public void checkCanShowFunctions(SystemSecurityContext context, CatalogSchemaName schemaName)
    {
        denyByDefault("checkCanShowFunctions", schemaName.toString());
    }

    @Override
    public java.util.Set<io.trino.spi.function.SchemaFunctionName> filterFunctions(SystemSecurityContext context, String catalogName, java.util.Set<io.trino.spi.function.SchemaFunctionName> functionNames)
    {
        String decisionId = marshaller.newDecisionId();
        metrics.recordFailClosed("DEFAULT_DENY");
        decisionLogger.log(String.format(Locale.ROOT,
                "decision action=filterFunctions decision_id=%s result=default_deny (empty)", decisionId));
        return java.util.Set.of(); // allow nothing
    }

    @Override
    public java.util.Map<SchemaTableName, java.util.Set<String>> filterColumns(SystemSecurityContext context, String catalogName, java.util.Map<SchemaTableName, java.util.Set<String>> columns)
    {
        String decisionId = marshaller.newDecisionId();
        metrics.recordFailClosed("DEFAULT_DENY");
        decisionLogger.log(String.format(Locale.ROOT,
                "decision action=filterColumns(bulk) decision_id=%s result=default_deny (empty)", decisionId));
        return Map.of(); // allow nothing
    }

    @Override
    public boolean canExecuteFunction(SystemSecurityContext context, CatalogSchemaRoutineName routine)
    {
        String decisionId = marshaller.newDecisionId();
        metrics.recordFailClosed("DEFAULT_DENY");
        decisionLogger.log(String.format(Locale.ROOT,
                "decision action=canExecuteFunction decision_id=%s result=default_deny", decisionId));
        return false;
    }

    @Override
    public boolean canCreateViewWithExecuteFunction(SystemSecurityContext context, CatalogSchemaRoutineName routine)
    {
        String decisionId = marshaller.newDecisionId();
        metrics.recordFailClosed("DEFAULT_DENY");
        decisionLogger.log(String.format(Locale.ROOT,
                "decision action=canCreateViewWithExecuteFunction decision_id=%s result=default_deny", decisionId));
        return false;
    }

    @Override
    public void checkCanCreateFunction(SystemSecurityContext context, CatalogSchemaRoutineName routine)
    {
        denyByDefault("checkCanCreateFunction", routine.toString());
    }

    @Override
    public void checkCanDropFunction(SystemSecurityContext context, CatalogSchemaRoutineName routine)
    {
        denyByDefault("checkCanDropFunction", routine.toString());
    }

    @Override
    public void checkCanShowCreateFunction(SystemSecurityContext context, CatalogSchemaRoutineName routine)
    {
        denyByDefault("checkCanShowCreateFunction", routine.toString());
    }

    // ==================================================================
    // Filtering methods (§3.2.D): bulk evaluation, one OPA call per invocation
    // ==================================================================

    @Override
    public Set<String> filterCatalogs(SystemSecurityContext context, Set<String> catalogs)
    {
        String decisionId = marshaller.newDecisionId();
        List<String> allowed = evaluateFilter(OpaAction.FILTER_CATALOGS, CallerCtx.of(context), null, null, null, sorted(catalogs), decisionId);
        logDecision(OpaAction.FILTER_CATALOGS, context.getIdentity().getUser(), decisionId, true, lastCacheHit);
        return new java.util.HashSet<>(allowed);
    }

    @Override
    public Set<String> filterSchemas(SystemSecurityContext context, String catalogName, Set<String> schemaNames)
    {
        String decisionId = marshaller.newDecisionId();
        List<String> allowed = evaluateFilter(OpaAction.FILTER_SCHEMAS, CallerCtx.of(context), catalogName, null, null, sorted(schemaNames), decisionId);
        logDecision(OpaAction.FILTER_SCHEMAS, context.getIdentity().getUser(), decisionId, true, lastCacheHit);
        return new java.util.HashSet<>(allowed);
    }

    @Override
    public Set<SchemaTableName> filterTables(
            SystemSecurityContext context, String catalogName, Set<SchemaTableName> tableNames)
    {
        String decisionId = marshaller.newDecisionId();
        // Candidates are marshaled as "schema.table" strings and mapped back after the call.
        List<String> candidates = tableNames.stream()
                .map(name -> name.getSchemaName() + "." + name.getTableName())
                .sorted()
                .toList();
        List<String> allowed = evaluateFilter(OpaAction.FILTER_TABLES, CallerCtx.of(context), catalogName, null, null, candidates, decisionId);
        logDecision(OpaAction.FILTER_TABLES, context.getIdentity().getUser(), decisionId, true, lastCacheHit);
        return allowed.stream()
                .map(candidate -> {
                    int dot = candidate.indexOf('.');
                    if (dot <= 0 || dot == candidate.length() - 1) {
                        throw failClosedGlobal(new RuntimeException("OPA filter result entry is not schema.table: " + candidate), OpaAction.FILTER_TABLES, decisionId);
                    }
                    return new SchemaTableName(candidate.substring(0, dot), candidate.substring(dot + 1));
                })
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
    }

    @Override
    public Set<String> filterColumns(
            SystemSecurityContext context, CatalogSchemaTableName tableName, Set<String> columnNames)
    {
        String decisionId = marshaller.newDecisionId();
        List<String> allowed = evaluateFilter(
                OpaAction.FILTER_COLUMNS, CallerCtx.of(context),
                tableName.getCatalogName(),
                tableName.getSchemaTableName().getSchemaName(),
                tableName.getSchemaTableName().getTableName(),
                sorted(columnNames), decisionId);
        logDecision(OpaAction.FILTER_COLUMNS, context.getIdentity().getUser(), decisionId, true, lastCacheHit);
        return new java.util.HashSet<>(allowed);
    }

    // ==================================================================
    // Row-level security / column masking
    // ==================================================================

    @Override
    public List<ViewExpression> getRowFilters(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        String decisionId = marshaller.newDecisionId();
        OpaRequestContext requestContext = toRequestContext(
                OpaAction.GET_ROW_FILTERS, CallerCtx.of(context),
                table.getCatalogName(),
                table.getSchemaTableName().getSchemaName(),
                table.getSchemaTableName().getTableName(),
                null);
        Map<String, Object> input = marshaller.marshal(requestContext, decisionId);
        String key = cacheKey(input);
        boolean volatileDecision = decisionCache.isVolatileDecision(input);

        List<String> filters;
        boolean cacheHit;
        Object cached = checkNegativeThenGet(key, volatileDecision, OpaAction.GET_ROW_FILTERS, decisionId);
        if (cached instanceof List<?> list) {
            filters = (List<String>) list;
            cacheHit = true;
        }
        else {
            long start = System.nanoTime();
            try {
                JsonNode response = client.query(pathFor(OpaAction.GET_ROW_FILTERS), input);
                if (isSafeMode()) {
                    // §3.4 mode 2: OPA emits descriptors; the plugin renders (and
                    // therefore owns quoting/escaping of) all SQL.
                    filters = responseParser.parseRowFilterDescriptors(response).stream()
                            .map(descriptorRenderer::render)
                            .toList();
                }
                else {
                    filters = responseParser.parseRowFilters(response);
                }
            }
            catch (RuntimeException e) {
                throw failClosed(key, e, OpaAction.GET_ROW_FILTERS, decisionId);
            }
            metrics.recordDecision(OpaAction.GET_ROW_FILTERS.wireName(), true, false, System.nanoTime() - start);
            cacheHit = false;
            decisionCache.put(key, filters, volatileDecision);
        }
        logDecision(OpaAction.GET_ROW_FILTERS, context.getIdentity().getUser(), decisionId, true, cacheHit);

        // Contract 4: never inject unvalidated SQL (fail closed on invalid SQL).
        try {
            for (String filter : filters) {
                sqlValidator.validate(filter, targetString(table), Set.of());
            }
        }
        catch (RuntimeException e) {
            throw failClosed(key, e, OpaAction.GET_ROW_FILTERS, decisionId);
        }
        return filters.stream()
                .map(filter -> toViewExpression(table, filter))
                .toList();
    }

    @Override
    public Optional<ViewExpression> getColumnMask(SystemSecurityContext context, CatalogSchemaTableName table, String columnName, Type type)
    {
        String decisionId = marshaller.newDecisionId();
        OpaRequestContext requestContext = toRequestContext(
                OpaAction.GET_COLUMN_MASKS, CallerCtx.of(context),
                table.getCatalogName(),
                table.getSchemaTableName().getSchemaName(),
                table.getSchemaTableName().getTableName(),
                List.of(columnName));
        Map<String, Object> input = marshaller.marshal(requestContext, decisionId);
        String key = cacheKey(input);
        boolean volatileDecision = decisionCache.isVolatileDecision(input);

        Optional<String> mask;
        boolean cacheHit;
        Object cached = checkNegativeThenGet(key, volatileDecision, OpaAction.GET_COLUMN_MASKS, decisionId);
        if (cached instanceof Optional<?> optional) {
            mask = (Optional<String>) optional;
            cacheHit = true;
        }
        else {
            long start = System.nanoTime();
            try {
                JsonNode response = client.query(pathFor(OpaAction.GET_COLUMN_MASKS), input);
                if (isSafeMode()) {
                    // §3.4 mode 2: descriptor → plugin-rendered SQL (escaped here).
                    io.opa.trino.client.OpaFilterDescriptor descriptor = responseParser.parseColumnMaskDescriptor(response);
                    mask = Optional.ofNullable(descriptor).map(descriptorRenderer::render);
                }
                else {
                    mask = Optional.ofNullable(responseParser.parseColumnMask(response));
                }
            }
            catch (RuntimeException e) {
                throw failClosed(key, e, OpaAction.GET_COLUMN_MASKS, decisionId);
            }
            metrics.recordDecision(OpaAction.GET_COLUMN_MASKS.wireName(), true, false, System.nanoTime() - start);
            cacheHit = false;
            decisionCache.put(key, mask, volatileDecision);
        }
        logDecision(OpaAction.GET_COLUMN_MASKS, context.getIdentity().getUser(), decisionId, true, cacheHit);

        try {
            return mask.map(sql -> {
                // Contract 4: never inject unvalidated SQL (fail closed on invalid SQL).
                sqlValidator.validate(sql, targetString(table), Set.of(columnName));
                return toViewExpression(table, sql);
            });
        }
        catch (RuntimeException e) {
            throw failClosed(key, e, OpaAction.GET_COLUMN_MASKS, decisionId);
        }
    }

    // ==================================================================
    // Decision plumbing
    // ==================================================================

    /** Tracks the cache hit/miss of the most recent evaluation for logging. */
    private boolean lastCacheHit;

    private void requireAllowed(OpaAction action, SystemSecurityContext context, CatalogSchemaTableName table, List<String> columns)
    {
        requireAllowed(action, context, table.getCatalogName(), table.getSchemaTableName().getSchemaName(),
                table.getSchemaTableName().getTableName(), columns);
    }

    private void requireAllowed(OpaAction action, SystemSecurityContext context, String catalog, String schema, List<String> columns)
    {
        requireAllowed(action, context, catalog, schema, null, columns);
    }

    private void requireAllowed(OpaAction action, SystemSecurityContext context, String catalog, String schema, String table, List<String> columns)
    {
        String decisionId = marshaller.newDecisionId();
        EvalOutcome outcome = evaluateDecision(action, CallerCtx.of(context), catalog, schema, table, columns, decisionId);
        boolean allowed = (Boolean) outcome.decision();
        metrics.recordDecision(action.wireName(), allowed, outcome.cacheHit(), outcome.latencyNanos());
        lastCacheHit = outcome.cacheHit();
        logDecision(action, context.getIdentity().getUser(), decisionId, allowed, outcome.cacheHit());
        if (!allowed) {
            throw new AccessDeniedException("Access denied: " + action.wireName()
                    + (catalog != null ? " on " + catalog : "")
                    + (schema != null ? "." + schema : "")
                    + (table != null ? "." + table : "")
                    + (columns != null && !columns.isEmpty() ? " (" + columns + ")" : ""));
        }
    }

    private boolean isSafeMode()
    {
        return "safe".equalsIgnoreCase(config.getSqlMode());
    }

    private boolean evaluateBoolean(OpaAction action, CallerCtx ctx, String catalog, String schema, String table, List<String> columns, String decisionId)
    {
        return (Boolean) evaluateDecision(action, ctx, catalog, schema, table, columns, decisionId).decision();
    }

    private record EvalOutcome(Object decision, boolean cacheHit, long latencyNanos) {}

    /**
     * Cache lookup per invariant #2: key = canonical hash of the full marshaled input
     * excluding volatile fields. Also consults the short-TTL negative cache.
     * Returns the raw decision (Boolean or per-column Map) plus cache-hit/latency info;
     * METRICS ARE NOT RECORDED HERE — callers record exactly once with the final outcome.
     */
    private EvalOutcome evaluateDecision(OpaAction action, CallerCtx ctx, String catalog, String schema, String table, List<String> columns, String decisionId)
    {
        OpaRequestContext requestContext = toRequestContext(action, ctx, catalog, schema, table, columns);
        Map<String, Object> input = marshaller.marshal(requestContext, decisionId);
        String key = cacheKey(input);
        boolean volatileDecision = decisionCache.isVolatileDecision(input);

        Object cached = checkNegativeThenGet(key, volatileDecision, action, decisionId);
        if (cached != null) {
            return new EvalOutcome(cached, true, 0);
        }

        long start = System.nanoTime();
        Object decision;
        try {
            JsonNode response = client.query(pathFor(action), input);
            decision = responseParser.parseBooleanOrColumnMap(response);
        }
        catch (RuntimeException e) {
            throw failClosed(key, e, action, decisionId);
        }
        decisionCache.put(key, decision, volatileDecision);
        return new EvalOutcome(decision, false, System.nanoTime() - start);
    }

    /** Bulk filter evaluation
    /**
     * Bulk filter evaluation (§3.2.D): the candidate list is marshaled into
     * {@code input.resource.columns} and OPA returns the allow-listed subset in a
     * SINGLE round-trip. Empty result = allow nothing; absent result = error →
     * fail closed (§7).
     */
    private List<String> evaluateFilter(
            OpaAction action, CallerCtx ctx, String catalog, String schema, String table, List<String> candidates, String decisionId)
    {
        OpaRequestContext requestContext = toRequestContext(action, ctx, catalog, schema, table, candidates);
        Map<String, Object> input = marshaller.marshal(requestContext, decisionId);
        String key = cacheKey(input);
        boolean volatileDecision = decisionCache.isVolatileDecision(input);

        Object cached = checkNegativeThenGet(key, volatileDecision, action, decisionId);
        if (cached instanceof List<?> list) {
            lastCacheHit = true;
            return (List<String>) list;
        }
        lastCacheHit = false;

        long start = System.nanoTime();
        List<String> allowed;
        try {
            JsonNode response = client.query(pathFor(action), input);
            allowed = responseParser.parseFilterResult(response);
        }
        catch (RuntimeException e) {
            throw failClosed(key, e, action, decisionId);
        }
        metrics.recordDecision(action.wireName(), true, false, System.nanoTime() - start);
        decisionCache.put(key, allowed, volatileDecision);
        return allowed;
    }

    private Object checkNegativeThenGet(String key, boolean volatileDecision, OpaAction action, String decisionId)
    {
        decisionCache.getNegative(key).ifPresent(negative -> {
            throw failClosed(key, new RuntimeException("Cached OPA failure: " + negative.reason()), action, decisionId);
        });
        return decisionCache.get(key, volatileDecision).orElse(null);
    }

    private String pathFor(OpaAction action)
    {
        return action.pathFor(config.getAllowPath(), config.getRowFiltersPath(), config.getColumnMasksPath(), config.getFilterPath());
    }

    private String cacheKey(Map<String, Object> input)
    {
        return cacheKeyCalculator.cacheKey(input);
    }

    /**
     * Centralized fail-closed translation (ARCHITECTURE.md §7): ANY failure mode
     * (transport, HTTP status, timeout, malformed response, unsupported schema
     * version, invalid SQL) becomes an AccessDeniedException, is recorded in the
     * short-TTL negative cache, and is counted/correlated via decision_id.
     */
    private AccessDeniedException failClosed(String cacheKey, RuntimeException e, OpaAction action, String decisionId)
    {
        decisionCache.putNegative(cacheKey, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        metrics.recordFailClosed(action.wireName());
        metrics.recordError(errorKind(e));
        logFailClosed(action, decisionId, e);
        String message = "OPA authorization failed (fail closed): " + e.getMessage();
        return new AccessDeniedException(message);
    }

    /** Fail-closed translation for failures that occur before a cache key exists. */
    private AccessDeniedException failClosedGlobal(RuntimeException e, OpaAction action, String decisionId)
    {
        metrics.recordFailClosed(action.wireName());
        metrics.recordError(OpaMetrics.ErrorKind.OTHER);
        logFailClosed(action, decisionId, e);
        return new AccessDeniedException("OPA authorization failed (fail closed): " + e.getMessage());
    }

    private OpaMetrics.ErrorKind errorKind(RuntimeException e)
    {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof HttpTimeoutException) {
                return OpaMetrics.ErrorKind.TIMEOUT;
            }
            if (cause instanceof java.net.ConnectException || cause instanceof java.io.IOException) {
                return OpaMetrics.ErrorKind.TRANSPORT;
            }
            cause = cause.getCause();
        }
        String message = e.getMessage() == null ? "" : e.getMessage();
        if (message.contains("non-200")) {
            return OpaMetrics.ErrorKind.HTTP_STATUS;
        }
        if (message.contains("malformed") || message.contains("schema_version") || message.contains("must be")) {
            return OpaMetrics.ErrorKind.MALFORMED;
        }
        return OpaMetrics.ErrorKind.OTHER;
    }

    private void logDecision(OpaAction action, String user, String decisionId, boolean allowed, boolean cacheHit)
    {
        decisionLogger.log(String.format(Locale.ROOT,
                "decision action=%s decision_id=%s user=%s result=%s cache=%s",
                action.wireName(), decisionId, user, allowed ? "allow" : "deny", cacheHit ? "hit" : "miss"));
    }

    private void logFailClosed(OpaAction action, String decisionId, RuntimeException e)
    {
        decisionLogger.log(String.format(Locale.ROOT,
                "decision action=%s decision_id=%s result=fail_closed reason=%s",
                action.wireName(), decisionId, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()));
    }

    // ==================================================================
    // SPI → context mapping
    // ==================================================================

    private OpaRequestContext toRequestContext(OpaAction action, CallerCtx ctx, String catalog, String schema, String table, List<String> columns)
    {
        Map<String, List<String>> roles = new HashMap<>();
        roles.put("system", List.copyOf(ctx.identity().getEnabledRoles()));
        ctx.identity().getCatalogRoles().forEach((catalogName, selectedRole) -> {
            if (selectedRole != null && selectedRole.getRole().isPresent()) {
                roles.put(catalogName, List.of(selectedRole.getRole().get()));
            }
        });

        return new OpaRequestContext(
                action,
                ctx.identity().getUser(),
                List.copyOf(ctx.identity().getGroups()),
                roles,
                List.of(),          // client tags are not exposed by the system access control SPI
                Optional.empty(),   // source ip is not exposed by the system access control SPI
                ctx.queryId(),
                Optional.empty(),   // query type is not exposed by the system access control SPI
                Map.of(),
                catalog,
                schema,
                table,
                columns);
    }

    private static List<String> sortedRolesAndPrincipals(Set<String> roles, Set<TrinoPrincipal> principals)
    {
        List<String> values = new java.util.ArrayList<>();
        roles.stream().sorted().forEach(role -> values.add("role:" + role));
        principals.stream()
                .map(TrinoPrincipal::getName)
                .sorted()
                .forEach(principal -> values.add("principal:" + principal));
        return values;
    }

    private static List<String> sorted(Set<String> values)
    {
        return values.stream().sorted().toList();
    }

    private static String newTableName(CatalogSchemaTableName table)
    {
        return table.getSchemaTableName().getSchemaName() + "." + table.getSchemaTableName().getTableName();
    }

    private static String targetString(CatalogSchemaTableName table)
    {
        return (table.getCatalogName() + "."
                + table.getSchemaTableName().getSchemaName() + "."
                + table.getSchemaTableName().getTableName()).toLowerCase(Locale.ROOT);
    }

    private static ViewExpression toViewExpression(CatalogSchemaTableName table, String expression)
    {
        return ViewExpression.builder()
                .catalog(table.getCatalogName())
                .schema(table.getSchemaTableName().getSchemaName())
                .expression(expression)
                .build();
    }
}

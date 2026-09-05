package io.opa.trino;

import com.fasterxml.jackson.databind.JsonNode;
import io.opa.trino.cache.CacheKeyCalculator;
import io.opa.trino.cache.DecisionCache;
import io.opa.trino.client.OpaHttpClient;
import io.opa.trino.client.OpaResponseParser;
import io.opa.trino.config.OpaConfig;
import io.opa.trino.marshal.OpaAction;
import io.opa.trino.marshal.OpaRequestContext;
import io.opa.trino.marshal.OpaRequestMarshaller;
import io.opa.trino.sql.SqlExpressionValidator;
import io.trino.spi.connector.CatalogSchemaTableName;
import io.trino.spi.security.AccessDeniedException;
import io.trino.spi.security.SystemAccessControl;
import io.trino.spi.security.SystemSecurityContext;
import io.trino.spi.security.ViewExpression;
import io.trino.spi.type.Type;

import java.util.HashMap;
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

    public OpaAccessControl(
            OpaConfig config,
            OpaHttpClient client,
            OpaResponseParser responseParser,
            OpaRequestMarshaller marshaller,
            CacheKeyCalculator cacheKeyCalculator,
            DecisionCache decisionCache,
            SqlExpressionValidator sqlValidator)
    {
        this.config = config;
        this.client = client;
        this.responseParser = responseParser;
        this.marshaller = marshaller;
        this.cacheKeyCalculator = cacheKeyCalculator;
        this.decisionCache = decisionCache;
        this.sqlValidator = sqlValidator;
    }

    // ------------------------------------------------------------------
    // Boolean authorization checks
    // ------------------------------------------------------------------

    @Override
    public void checkCanSelectFromColumns(SystemSecurityContext context, CatalogSchemaTableName table, Set<String> columns)
    {
        if (!evaluateBoolean(OpaAction.SELECT_FROM_COLUMNS, context, table, sorted(columns))) {
            throw new AccessDeniedException("Access denied: SELECT on columns " + columns + " of " + table);
        }
    }

    @Override
    public void checkCanCreateTable(SystemSecurityContext context, CatalogSchemaTableName table, Map<String, Object> properties)
    {
        if (!evaluateBoolean(OpaAction.CREATE_TABLE, context, table, null)) {
            throw new AccessDeniedException("Access denied: CREATE TABLE " + table);
        }
    }

    // ------------------------------------------------------------------
    // Row-level security / column masking
    // ------------------------------------------------------------------

    @Override
    public List<ViewExpression> getRowFilters(SystemSecurityContext context, CatalogSchemaTableName table)
    {
        OpaRequestContext requestContext = toRequestContext(
                OpaAction.GET_ROW_FILTERS, context,
                table.getCatalogName(),
                table.getSchemaTableName().getSchemaName(),
                table.getSchemaTableName().getTableName(),
                null);
        Map<String, Object> input = marshaller.marshal(requestContext, marshaller.newDecisionId());
        String key = cacheKey(input);
        boolean volatileDecision = decisionCache.isVolatileDecision(input);

        List<String> filters;
        Object cached = checkNegativeThenGet(key, volatileDecision);
        if (cached instanceof List<?> list) {
            filters = (List<String>) list;
        }
        else {
            try {
                JsonNode response = client.query(pathFor(OpaAction.GET_ROW_FILTERS), input);
                filters = responseParser.parseRowFilters(response);
            }
            catch (RuntimeException e) {
                throw failClosed(key, e);
            }
            decisionCache.put(key, filters, volatileDecision);
        }

        // Contract 4: never inject unvalidated SQL (fail closed on invalid SQL).
        try {
            for (String filter : filters) {
                sqlValidator.validate(filter, targetString(table), Set.of());
            }
        }
        catch (RuntimeException e) {
            throw failClosed(key, e);
        }
        return filters.stream()
                .map(filter -> toViewExpression(table, filter))
                .toList();
    }

    @Override
    public Optional<ViewExpression> getColumnMask(SystemSecurityContext context, CatalogSchemaTableName table, String columnName, Type type)
    {
        OpaRequestContext requestContext = toRequestContext(
                OpaAction.GET_COLUMN_MASKS, context,
                table.getCatalogName(),
                table.getSchemaTableName().getSchemaName(),
                table.getSchemaTableName().getTableName(),
                List.of(columnName));
        Map<String, Object> input = marshaller.marshal(requestContext, marshaller.newDecisionId());
        String key = cacheKey(input);
        boolean volatileDecision = decisionCache.isVolatileDecision(input);

        Optional<String> mask;
        Object cached = checkNegativeThenGet(key, volatileDecision);
        if (cached instanceof Optional<?> optional) {
            mask = (Optional<String>) optional;
        }
        else {
            try {
                JsonNode response = client.query(pathFor(OpaAction.GET_COLUMN_MASKS), input);
                mask = Optional.ofNullable(responseParser.parseColumnMask(response));
            }
            catch (RuntimeException e) {
                throw failClosed(key, e);
            }
            decisionCache.put(key, mask, volatileDecision);
        }

        try {
            return mask.map(sql -> {
                // Contract 4: never inject unvalidated SQL (fail closed on invalid SQL).
                sqlValidator.validate(sql, targetString(table), Set.of(columnName));
                return toViewExpression(table, sql);
            });
        }
        catch (RuntimeException e) {
            throw failClosed(key, e);
        }
    }

    // ------------------------------------------------------------------
    // Filtering methods (§3.2.D): bulk evaluation, one OPA call per invocation
    // ------------------------------------------------------------------

    @Override
    public Set<String> filterCatalogs(SystemSecurityContext context, Set<String> catalogs)
    {
        List<String> allowed = evaluateFilter(OpaAction.FILTER_CATALOGS, context, null, null, null, sorted(catalogs));
        return new java.util.HashSet<>(allowed);
    }

    @Override
    public Set<String> filterSchemas(SystemSecurityContext context, String catalogName, Set<String> schemaNames)
    {
        List<String> allowed = evaluateFilter(OpaAction.FILTER_SCHEMAS, context, catalogName, null, null, sorted(schemaNames));
        return new java.util.HashSet<>(allowed);
    }

    @Override
    public Set<io.trino.spi.connector.SchemaTableName> filterTables(
            SystemSecurityContext context, String catalogName, Set<io.trino.spi.connector.SchemaTableName> tableNames)
    {
        // Candidates are marshaled as "schema.table" strings and mapped back after the call.
        List<String> candidates = tableNames.stream()
                .map(name -> name.getSchemaName() + "." + name.getTableName())
                .sorted()
                .toList();
        List<String> allowed = evaluateFilter(OpaAction.FILTER_TABLES, context, catalogName, null, null, candidates);
        return allowed.stream()
                .map(candidate -> {
                    int dot = candidate.indexOf('.');
                    if (dot <= 0 || dot == candidate.length() - 1) {
                        throw failClosedGlobal(new RuntimeException("OPA filter result entry is not schema.table: " + candidate));
                    }
                    return new io.trino.spi.connector.SchemaTableName(candidate.substring(0, dot), candidate.substring(dot + 1));
                })
                .collect(java.util.stream.Collectors.toCollection(java.util.HashSet::new));
    }

    @Override
    public Set<String> filterColumns(
            SystemSecurityContext context, CatalogSchemaTableName tableName, Set<String> columnNames)
    {
        List<String> allowed = evaluateFilter(
                OpaAction.FILTER_COLUMNS, context,
                tableName.getCatalogName(),
                tableName.getSchemaTableName().getSchemaName(),
                tableName.getSchemaTableName().getTableName(),
                sorted(columnNames));
        return new java.util.HashSet<>(allowed);
    }

    // ------------------------------------------------------------------
    // Decision plumbing
    // ------------------------------------------------------------------

    /**
     * Bulk filter evaluation (§3.2.D): the candidate list is marshaled into
     * {@code input.resource.columns} and OPA returns the allow-listed subset in a
     * SINGLE round-trip. Empty result = allow nothing; absent result = error →
     * fail closed (§7).
     */
    private List<String> evaluateFilter(
            OpaAction action, SystemSecurityContext context, String catalog, String schema, String table, List<String> candidates)
    {
        OpaRequestContext requestContext = toRequestContext(action, context, catalog, schema, table, candidates);
        Map<String, Object> input = marshaller.marshal(requestContext, marshaller.newDecisionId());
        String key = cacheKey(input);
        boolean volatileDecision = decisionCache.isVolatileDecision(input);

        Object cached = checkNegativeThenGet(key, volatileDecision);
        if (cached instanceof List<?> list) {
            return (List<String>) list;
        }

        List<String> allowed;
        try {
            JsonNode response = client.query(pathFor(action), input);
            allowed = responseParser.parseFilterResult(response);
        }
        catch (RuntimeException e) {
            throw failClosed(key, e);
        }
        decisionCache.put(key, allowed, volatileDecision);
        return allowed;
    }

    private boolean evaluateBoolean(OpaAction action, SystemSecurityContext context, CatalogSchemaTableName table, List<String> columns)
    {
        OpaRequestContext requestContext = toRequestContext(
                action, context,
                table.getCatalogName(),
                table.getSchemaTableName().getSchemaName(),
                table.getSchemaTableName().getTableName(),
                columns);
        Map<String, Object> input = marshaller.marshal(requestContext, marshaller.newDecisionId());
        String key = cacheKey(input);
        boolean volatileDecision = decisionCache.isVolatileDecision(input);

        Object cached = checkNegativeThenGet(key, volatileDecision);
        if (cached instanceof Boolean decision) {
            return decision;
        }

        boolean decision;
        try {
            JsonNode response = client.query(pathFor(action), input);
            decision = responseParser.parseBoolean(response);
        }
        catch (RuntimeException e) {
            throw failClosed(key, e);
        }
        decisionCache.put(key, decision, volatileDecision);
        return decision;
    }

    /**
     * Cache lookup per invariant #2: key = canonical hash of the full marshaled input
     * excluding volatile fields. Also consults the short-TTL negative cache.
     */
    private Object checkNegativeThenGet(String key, boolean volatileDecision)
    {
        decisionCache.getNegative(key).ifPresent(negative -> {
            throw failClosed(key, new RuntimeException("Cached OPA failure: " + negative.reason()));
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
     * version, invalid SQL) becomes an AccessDeniedException and is recorded in the
     * short-TTL negative cache.
     */
    private AccessDeniedException failClosed(String cacheKey, RuntimeException e)
    {
        decisionCache.putNegative(cacheKey, e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        String message = "OPA authorization failed (fail closed): " + e.getMessage();
        return new AccessDeniedException(message);
    }

    /** Fail-closed translation for failures that occur before a cache key exists. */
    private AccessDeniedException failClosedGlobal(RuntimeException e)
    {
        String message = "OPA authorization failed (fail closed): " + e.getMessage();
        return new AccessDeniedException(message);
    }

    // ------------------------------------------------------------------
    // SPI → context mapping
    // ------------------------------------------------------------------

    private OpaRequestContext toRequestContext(
            OpaAction action, SystemSecurityContext context, String catalog, String schema, String table, List<String> columns)
    {
        Map<String, List<String>> roles = new HashMap<>();
        roles.put("system", List.copyOf(context.getIdentity().getEnabledRoles()));
        context.getIdentity().getCatalogRoles().forEach((catalogName, selectedRole) -> {
            if (selectedRole != null && selectedRole.getRole().isPresent()) {
                roles.put(catalogName, List.of(selectedRole.getRole().get()));
            }
        });

        return new OpaRequestContext(
                action,
                context.getIdentity().getUser(),
                List.copyOf(context.getIdentity().getGroups()),
                roles,
                List.of(),          // client tags are not exposed by SystemSecurityContext
                Optional.empty(),   // source ip is not exposed by SystemSecurityContext
                Optional.ofNullable(context.getQueryId()).map(Object::toString),
                Optional.empty(),   // query type is not exposed by SystemSecurityContext
                Map.of(),
                catalog,
                schema,
                table,
                columns);
    }

    private static List<String> sorted(Set<String> values)
    {
        return values.stream().sorted().toList();
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

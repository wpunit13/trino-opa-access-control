package io.github.wpunit13.trino.marshal;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Contract 1 (ARCHITECTURE.md §3.1): marshals SPI context into the exact JSON shape
 * OPA policies receive as {@code input}. Field insertion order is fixed so the
 * marshaled object serializes deterministically.
 */
public final class OpaRequestMarshaller
{
    public static final int SCHEMA_VERSION = 1;

    /** Marshals the full input, including the volatile decision_id. */
    public Map<String, Object> marshal(OpaRequestContext ctx, String decisionId)
    {
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("user", ctx.user());
        identity.put("groups", new ArrayList<>(sorted(ctx.groups())));

        Map<String, Object> roles = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : new TreeMap<>(ctx.roles()).entrySet()) {
            roles.put(entry.getKey(), new ArrayList<>(sorted(entry.getValue())));
        }
        identity.put("roles", roles);
        identity.put("client_tags", new ArrayList<>(sorted(ctx.clientTags())));
        identity.put("source_ip", ctx.sourceIp().orElse(null));

        Map<String, Object> resource = new LinkedHashMap<>();
        resource.put("catalog", ctx.catalog());
        resource.put("schema", ctx.schema());
        resource.put("table", ctx.table());
        resource.put("columns", ctx.columns() == null ? null : new ArrayList<>(sorted(ctx.columns())));

        Map<String, Object> session = new LinkedHashMap<>();
        session.put("query_id", ctx.queryId().orElse(null));
        session.put("query_type", ctx.queryType().orElse(null));
        session.put("catalog_session_properties", new TreeMap<>(ctx.catalogSessionProperties()));

        Map<String, Object> input = new LinkedHashMap<>();
        input.put("schema_version", SCHEMA_VERSION);
        input.put("action", ctx.action().wireName());
        input.put("decision_id", decisionId);
        input.put("identity", identity);
        input.put("resource", resource);
        input.put("session", session);
        return input;
    }

    public String newDecisionId()
    {
        return UUID.randomUUID().toString();
    }

    private static List<String> sorted(List<String> values)
    {
        return values.stream().sorted().toList();
    }
}

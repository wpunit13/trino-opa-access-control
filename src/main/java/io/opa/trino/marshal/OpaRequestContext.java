package io.opa.trino.marshal;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Fully-decision-relevant context gathered from the Trino SPI before marshaling.
 * This is a plain carrier so marshaling (and cache-key computation) can be unit tested
 * without a live Trino session.
 */
public record OpaRequestContext(
        OpaAction action,
        String user,
        List<String> groups,
        Map<String, List<String>> roles, // "system" -> system roles, catalog name -> enabled role
        List<String> clientTags,
        Optional<String> sourceIp,
        Optional<String> queryId,
        Optional<String> queryType,
        Map<String, String> catalogSessionProperties,
        String catalog,
        String schema,
        String table,
        List<String> columns) // null for whole-table / non-column operations
{
    public OpaRequestContext
    {
        groups = groups == null ? List.of() : List.copyOf(groups);
        clientTags = clientTags == null ? List.of() : List.copyOf(clientTags);
        catalogSessionProperties = catalogSessionProperties == null ? Map.of() : Map.copyOf(catalogSessionProperties);
        columns = columns == null ? null : List.copyOf(columns);
        roles = roles == null ? Map.of() : Map.copyOf(roles);
    }
}

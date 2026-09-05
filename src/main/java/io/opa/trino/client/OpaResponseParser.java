package io.opa.trino.client;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Contract 2 (ARCHITECTURE.md §3.2) + Contract 5 (schema versioning):
 * parses OPA responses into plugin-side decision values, validating the envelope and
 * failing closed (via {@link OpaResponseException}) on any malformed/unsupported shape.
 *
 * Real OPA wraps the policy output in an outer {@code {"result": ...}} document. The
 * policy output itself is expected to be {@code {"schema_version": 1, "result": ...}}
 * per the contract; for compatibility, the parser accepts the contract shape at the
 * top level as well (e.g. when a reverse proxy or test stub emits it directly).
 */
public final class OpaResponseParser
{
    private final int supportedSchemaVersion;

    public OpaResponseParser(int supportedSchemaVersion)
    {
        this.supportedSchemaVersion = supportedSchemaVersion;
    }

    /** Authorization check (§3.2.A). Undefined rule → deny (not an error). */
    public Boolean parseBoolean(JsonNode root)
    {
        // An undefined rule yields OPA's bare document (no result, no schema_version):
        // default deny per §7 without flagging a malformed response.
        boolean bareDocument = root != null && root.isObject()
                && !root.has("schema_version")
                && (!root.has("result") || root.get("result").isNull());
        if (bareDocument) {
            return false;
        }
        JsonNode result = unwrap(root).get("result");
        if (result == null || result.isNull()) {
            // No matching OPA rule → default deny per §7.
            return false;
        }
        if (!result.isBoolean()) {
            throw new OpaResponseException("OPA allow response 'result' must be a boolean");
        }
        return result.asBoolean();
    }

    /** Row filters (§3.2.B): a list of SQL predicate strings; empty list = no filter. */
    public List<String> parseRowFilters(JsonNode root)
    {
        JsonNode result = unwrap(root).get("result");
        if (result == null || result.isNull()) {
            // An absent result on a list contract is an error, not "no filters" (§3.2.D).
            throw new OpaResponseException("OPA row_filters response is missing 'result'");
        }
        if (!result.isArray()) {
            throw new OpaResponseException("OPA row_filters 'result' must be an array");
        }
        List<String> filters = new ArrayList<>();
        for (JsonNode item : result) {
            if (!item.isTextual()) {
                throw new OpaResponseException("OPA row_filters entries must be strings");
            }
            filters.add(item.asText());
        }
        return filters;
    }

    /**
     * Column mask (§3.2.C): a single SQL projection string, or absent/null when the
     * column is unmasked. Returns null for "no mask".
     */
    public String parseColumnMask(JsonNode root)
    {
        JsonNode node = unwrap(root);
        if (!node.has("result")) {
            throw new OpaResponseException("OPA column_masks response is missing 'result'");
        }
        JsonNode result = node.get("result");
        if (result == null || result.isNull()) {
            return null;
        }
        if (!result.isTextual()) {
            throw new OpaResponseException("OPA column_masks 'result' must be a string or null");
        }
        return result.asText();
    }

    /** Unwraps the contract envelope and validates schema_version (fail closed). */
    private JsonNode unwrap(JsonNode root)
    {
        if (root == null || root.isNull() || !root.isObject()) {
            throw new OpaResponseException("OPA response must be a JSON object");
        }
        JsonNode node = root;
        JsonNode inner = node.get("result");
        if (inner != null && inner.isObject() && inner.has("schema_version")) {
            node = inner;
        }
        JsonNode version = node.get("schema_version");
        if (version == null || !version.isIntegralNumber()) {
            throw new OpaResponseException("OPA response is missing an integer 'schema_version'");
        }
        if (version.asInt() != supportedSchemaVersion) {
            // Unknown version → fail closed rather than guessing (§3.5).
            throw new OpaResponseException("Unsupported OPA schema_version " + version.asInt()
                    + " (supported: " + supportedSchemaVersion + ")");
        }
        return node;
    }
}

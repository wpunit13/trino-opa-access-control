package io.github.wpunit13.trino.client;

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
    private static final String RESULT = "result";
    private static final String SCHEMA_VERSION = "schema_version";

    private final int supportedSchemaVersion;

    public OpaResponseParser(int supportedSchemaVersion)
    {
        this.supportedSchemaVersion = supportedSchemaVersion;
    }

    /** Authorization check (§3.2.A). Undefined rule → deny (not an error). */
    public Boolean parseBoolean(JsonNode root)
    {
        Object decision = parseBooleanOrColumnMap(root);
        return (Boolean) decision;
    }

    /**
     * Authorization check with per-column support (§5 note: "single boolean for the
     * whole set, or a per-column allow/deny map"). Returns either a Boolean or a
     * Map<String, Boolean> (column name → allowed).
     */
    public Object parseBooleanOrColumnMap(JsonNode root)
    {
        // An undefined rule yields OPA's bare document (no result, no schema_version):
        // default deny per §7 without flagging a malformed response.
        boolean bareDocument = root != null && root.isObject()
                && !root.has(SCHEMA_VERSION)
                && (!root.has(RESULT) || root.get(RESULT).isNull());
        if (bareDocument) {
            return false;
        }
        JsonNode result = unwrap(root).get(RESULT);
        if (result == null || result.isNull()) {
            // No matching OPA rule → default deny per §7.
            return false;
        }
        if (result.isBoolean()) {
            return result.asBoolean();
        }
        if (result.isObject()) {
            java.util.Map<String, Boolean> perColumn = new java.util.LinkedHashMap<>();
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = result.fields();
            while (fields.hasNext()) {
                var field = fields.next();
                if (!field.getValue().isBoolean()) {
                    throw new OpaResponseException("OPA per-column result values must be booleans: " + field.getKey());
                }
                perColumn.put(field.getKey(), field.getValue().asBoolean());
            }
            return perColumn;
        }
        throw new OpaResponseException("OPA allow response 'result' must be a boolean or per-column object");
    }

    /** Row filters (§3.2.B): a list of SQL predicate strings; empty list = no filter. */
    public List<String> parseRowFilters(JsonNode root)
    {
        return parseStringList(root, "row_filters");
    }

    /**
     * Filtering methods (§3.2.D): a list of allow-listed candidate names.
     * Empty list = allow nothing. An absent/missing result is an ERROR → fail closed
     * (distinct from the boolean contract, where an absent result is a deny).
     */
    public List<String> parseFilterResult(JsonNode root)
    {
        return parseStringList(root, "filter");
    }

    // ---- Safe mode (§3.4): structured descriptors instead of raw SQL ----

    /** Row filters in safe mode: a list of descriptors; empty list = no filter. */
    public List<OpaFilterDescriptor> parseRowFilterDescriptors(JsonNode root)
    {
        JsonNode result = unwrap(root).get(RESULT);
        if (result == null || result.isNull()) {
            throw new OpaResponseException("OPA row_filters response is missing 'result'");
        }
        if (!result.isArray()) {
            throw new OpaResponseException("OPA row_filters 'result' must be an array of descriptors");
        }
        List<OpaFilterDescriptor> descriptors = new ArrayList<>();
        for (JsonNode item : result) {
            descriptors.add(parseDescriptor(item, "row_filters"));
        }
        return descriptors;
    }

    /** Column mask in safe mode: a single descriptor or null (unmasked). */
    public OpaFilterDescriptor parseColumnMaskDescriptor(JsonNode root)
    {
        JsonNode node = unwrap(root);
        if (!node.has(RESULT)) {
            throw new OpaResponseException("OPA column_masks response is missing 'result'");
        }
        JsonNode result = node.get(RESULT);
        if (result == null || result.isNull()) {
            return null;
        }
        return parseDescriptor(result, "column_masks");
    }

    /** Validates the descriptor schema; anything malformed fails closed. */
    private OpaFilterDescriptor parseDescriptor(JsonNode item, String contractName)
    {
        if (item == null || !item.isObject()) {
            throw new OpaResponseException("OPA " + contractName + " safe-mode entries must be descriptor objects");
        }
        JsonNode op = item.get("op");
        JsonNode column = item.get("column");
        if (op == null || !op.isTextual() || !OpaFilterDescriptor.SUPPORTED_OPS.contains(op.asText())) {
            throw new OpaResponseException("OPA " + contractName + " descriptor has an unsupported 'op': "
                    + (op == null ? "missing" : op.asText()) + " (supported: " + OpaFilterDescriptor.SUPPORTED_OPS + ")");
        }
        if (column == null || !column.isTextual() || column.asText().isBlank()) {
            throw new OpaResponseException("OPA " + contractName + " descriptor is missing a 'column' string");
        }
        String opName = op.asText();
        List<Object> values = parseValues(item, contractName, opName);
        return new OpaFilterDescriptor(opName, column.asText(), values);
    }

    private List<Object> parseValues(JsonNode item, String contractName, String opName)
    {
        boolean valueOp = opName.equals("in") || opName.equals("eq") || opName.equals("neq");
        if (!valueOp) {
            return new ArrayList<>();
        }
        JsonNode valuesNode = item.get("values");
        if (valuesNode == null || !valuesNode.isArray()) {
            throw new OpaResponseException("OPA " + contractName + " descriptor op='" + opName + "' requires a 'values' array");
        }
        List<Object> values = new ArrayList<>();
        for (JsonNode value : valuesNode) {
            if (!value.isValueNode() || value.isNull()) {
                throw new OpaResponseException("OPA " + contractName + " descriptor values must be non-null scalars");
            }
            if (value.isTextual()) {
                values.add(value.asText());
            }
            else if (value.isNumber()) {
                values.add(value.decimalValue());
            }
            else if (value.isBoolean()) {
                values.add(value.asBoolean());
            }
            else {
                throw new OpaResponseException("OPA " + contractName + " descriptor values must be scalars");
            }
        }
        return values;
    }

    private List<String> parseStringList(JsonNode root, String contractName)
    {
        JsonNode result = unwrap(root).get(RESULT);
        if (result == null || result.isNull()) {
            // An absent result on a list contract is an error, not "no filters" (§3.2.D).
            throw new OpaResponseException("OPA " + contractName + " response is missing 'result'");
        }
        if (!result.isArray()) {
            throw new OpaResponseException("OPA " + contractName + " 'result' must be an array");
        }
        List<String> values = new ArrayList<>();
        for (JsonNode item : result) {
            if (!item.isTextual()) {
                throw new OpaResponseException("OPA " + contractName + " entries must be strings");
            }
            values.add(item.asText());
        }
        return values;
    }

    /**
     * Column mask (§3.2.C): a single SQL projection string, or absent/null when the
     * column is unmasked. Returns null for "no mask".
     */
    public String parseColumnMask(JsonNode root)
    {
        JsonNode node = unwrap(root);
        if (!node.has(RESULT)) {
            throw new OpaResponseException("OPA column_masks response is missing 'result'");
        }
        JsonNode result = node.get(RESULT);
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
        JsonNode inner = node.get(RESULT);
        if (inner != null && inner.isObject() && inner.has(SCHEMA_VERSION)) {
            node = inner;
        }
        JsonNode version = node.get(SCHEMA_VERSION);
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
package io.opa.trino.sql;

import io.opa.trino.client.OpaFilterDescriptor;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Safe-mode SQL renderer (ARCHITECTURE.md §3.4, mode 2). The plugin owns ALL
 * quoting/escaping — policies can never inject raw SQL because they never emit
 * strings that reach the query; only descriptors are accepted, and every element
 * is rendered here:
 *
 *  - identifiers must match a strict [A-Za-z_][A-Za-z0-9_]* pattern (no quoting
 *    tricks possible);
 *  - string values are single-quote escaped ('' doubling) and always rendered
 *    as quoted literals;
 *  - IN (...) clauses are bounded by opa.sql.max-in-clause-size (fail closed).
 */
public final class DescriptorRenderer
{
    private static final Pattern SAFE_IDENTIFIER = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");

    private final int maxInClauseSize;

    public DescriptorRenderer(int maxInClauseSize)
    {
        this.maxInClauseSize = maxInClauseSize;
    }

    public String render(OpaFilterDescriptor descriptor)
    {
        String column = validatedIdentifier(descriptor.column());
        return switch (descriptor.op()) {
            case "in" -> renderIn(column, descriptor.values());
            case "eq" -> column + " = " + renderValue(singleValue(descriptor));
            case "neq" -> column + " <> " + renderValue(singleValue(descriptor));
            case "is_null" -> column + " IS NULL";
            case "is_not_null" -> column + " IS NOT NULL";
            default -> throw new SqlValidationException("Unsupported descriptor op: " + descriptor.op());
        };
    }

    private String renderIn(String column, List<Object> values)
    {
        // An empty IN list is invalid SQL; an empty allow-set means "allow nothing".
        if (values.isEmpty()) {
            return "false";
        }
        if (values.size() > maxInClauseSize) {
            throw new SqlValidationException("Descriptor IN clause exceeds opa.sql.max-in-clause-size ("
                    + values.size() + " > " + maxInClauseSize + ")");
        }
        StringBuilder sb = new StringBuilder(column).append(" IN (");
        for (int i = 0; i < values.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append(renderValue(values.get(i)));
        }
        return sb.append(')').toString();
    }

    /** Single-quote escaping: ' → '' (SQL standard), then wrap in quotes. */
    private String renderValue(Object value)
    {
        if (value instanceof String s) {
            return "'" + s.replace("'", "''") + "'";
        }
        if (value instanceof Boolean b) {
            return b.toString();
        }
        if (value instanceof Number n) {
            return n.toString();
        }
        throw new SqlValidationException("Descriptor value type not renderable: "
                + (value == null ? "null" : value.getClass().getSimpleName()));
    }

    private Object singleValue(OpaFilterDescriptor descriptor)
    {
        List<Object> values = descriptor.values();
        if (values.size() != 1) {
            throw new SqlValidationException("Descriptor op='" + descriptor.op() + "' requires exactly one value (got "
                    + values.size() + ")");
        }
        return values.get(0);
    }

    /** Identifiers are accepted only in canonical form — no quoting tricks possible. */
    private String validatedIdentifier(String identifier)
    {
        if (identifier == null || !SAFE_IDENTIFIER.matcher(identifier).matches()) {
            throw new SqlValidationException("Descriptor column is not a safe identifier: "
                    + (identifier == null ? "null" : "'" + identifier + "'"));
        }
        return identifier.toLowerCase(Locale.ROOT);
    }
}

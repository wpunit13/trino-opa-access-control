package io.github.wpunit13.trino.client;

import java.util.List;

/**
 * Safe-mode structured descriptor (ARCHITECTURE.md §3.4, mode 2): OPA emits
 * semantic descriptors instead of raw SQL; the plugin renders (and therefore
 * owns quoting/escaping of) the SQL. Supported ops:
 *
 *   in           values: list of scalars   → column IN ('v1', 'v2', ...)
 *   eq / neq     values: single scalar     → column = 'v' / column <> 'v'
 *   is_null / is_not_null   values: none   → column IS NULL / column IS NOT NULL
 */
public record OpaFilterDescriptor(String op, String column, List<Object> values)
{
    public static final java.util.Set<String> SUPPORTED_OPS =
            java.util.Set.of("in", "eq", "neq", "is_null", "is_not_null");
}

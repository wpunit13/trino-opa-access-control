package io.opa.trino.marshal;

import java.util.List;

/**
 * Canonical action strings and OPA data-document paths (ARCHITECTURE.md §5).
 */
public enum OpaAction
{
    SELECT_FROM_COLUMNS("SELECT_FROM_COLUMNS", PathKind.ALLOW),
    CREATE_TABLE("CREATE_TABLE", PathKind.ALLOW),
    GET_ROW_FILTERS("GET_ROW_FILTERS", PathKind.ROW_FILTERS),
    GET_COLUMN_MASKS("GET_COLUMN_MASKS", PathKind.COLUMN_MASKS);

    public enum PathKind { ALLOW, ROW_FILTERS, COLUMN_MASKS, FILTER }

    private final String wireName;
    private final PathKind pathKind;

    OpaAction(String wireName, PathKind pathKind)
    {
        this.wireName = wireName;
        this.pathKind = pathKind;
    }

    public String wireName()
    {
        return wireName;
    }

    public PathKind pathKind()
    {
        return pathKind;
    }

    public String path(String allowPath, String rowFiltersPath, String columnMasksPath, String filterPath)
    {
        return switch (pathKind) {
            case ALLOW -> allowPath;
            case ROW_FILTERS -> rowFiltersPath;
            case COLUMN_MASKS -> columnMasksPath;
            case FILTER -> filterPath;
        };
    }

    /** Convenience overload used by the plugin. */
    public String pathFor(String allowPath, String rowFiltersPath, String columnMasksPath)
    {
        return path(allowPath, rowFiltersPath, columnMasksPath, "/v1/data/trino/filter");
    }

}

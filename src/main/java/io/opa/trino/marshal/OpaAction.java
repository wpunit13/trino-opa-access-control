package io.opa.trino.marshal;

/**
 * Canonical action strings and OPA data-document paths (ARCHITECTURE.md §5).
 */
public enum OpaAction
{
    SELECT_FROM_COLUMNS("SELECT_FROM_COLUMNS", PathKind.ALLOW),
    CREATE_TABLE("CREATE_TABLE", PathKind.ALLOW),
    GET_ROW_FILTERS("GET_ROW_FILTERS", PathKind.ROW_FILTERS),
    GET_COLUMN_MASKS("GET_COLUMN_MASKS", PathKind.COLUMN_MASKS),
    FILTER_CATALOGS("FILTER_CATALOGS", PathKind.FILTER),
    FILTER_SCHEMAS("FILTER_SCHEMAS", PathKind.FILTER),
    FILTER_TABLES("FILTER_TABLES", PathKind.FILTER),
    FILTER_COLUMNS("FILTER_COLUMNS", PathKind.FILTER);

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

    public String pathFor(String allowPath, String rowFiltersPath, String columnMasksPath, String filterPath)
    {
        return switch (pathKind) {
            case ALLOW -> allowPath;
            case ROW_FILTERS -> rowFiltersPath;
            case COLUMN_MASKS -> columnMasksPath;
            case FILTER -> filterPath;
        };
    }
}

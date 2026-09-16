package io.github.wpunit13.trino.marshal;

/**
 * Canonical action strings and OPA data-document paths (ARCHITECTURE.md §5).
 * Every SPI method implemented via OPA maps 1:1 to one of these actions.
 */
public enum OpaAction
{
    // Session / identity
    SET_USER("SET_USER", PathKind.ALLOW),
    IMPERSONATE_USER("IMPERSONATE_USER", PathKind.ALLOW),
    SET_SYSTEM_SESSION_PROPERTY("SET_SYSTEM_SESSION_PROPERTY", PathKind.ALLOW),
    SET_CATALOG_SESSION_PROPERTY("SET_CATALOG_SESSION_PROPERTY", PathKind.ALLOW),
    // Catalog
    ACCESS_CATALOG("ACCESS_CATALOG", PathKind.ALLOW),
    FILTER_CATALOGS("FILTER_CATALOGS", PathKind.FILTER),
    // Schema
    CREATE_SCHEMA("CREATE_SCHEMA", PathKind.ALLOW),
    DROP_SCHEMA("DROP_SCHEMA", PathKind.ALLOW),
    RENAME_SCHEMA("RENAME_SCHEMA", PathKind.ALLOW),
    SET_SCHEMA_AUTHORIZATION("SET_SCHEMA_AUTHORIZATION", PathKind.ALLOW),
    SHOW_SCHEMAS("SHOW_SCHEMAS", PathKind.ALLOW),
    FILTER_SCHEMAS("FILTER_SCHEMAS", PathKind.FILTER),
    // Table / column
    CREATE_TABLE("CREATE_TABLE", PathKind.ALLOW),
    DROP_TABLE("DROP_TABLE", PathKind.ALLOW),
    RENAME_TABLE("RENAME_TABLE", PathKind.ALLOW),
    ADD_COLUMN("ADD_COLUMN", PathKind.ALLOW),
    DROP_COLUMN("DROP_COLUMN", PathKind.ALLOW),
    RENAME_COLUMN("RENAME_COLUMN", PathKind.ALLOW),
    SELECT_FROM_COLUMNS("SELECT_FROM_COLUMNS", PathKind.ALLOW),
    SHOW_TABLES("SHOW_TABLES", PathKind.ALLOW),
    FILTER_TABLES("FILTER_TABLES", PathKind.FILTER),
    SHOW_COLUMNS("SHOW_COLUMNS", PathKind.ALLOW),
    FILTER_COLUMNS("FILTER_COLUMNS", PathKind.FILTER),
    // View / materialized view
    CREATE_VIEW("CREATE_VIEW", PathKind.ALLOW),
    DROP_VIEW("DROP_VIEW", PathKind.ALLOW),
    RENAME_VIEW("RENAME_VIEW", PathKind.ALLOW),
    CREATE_MATERIALIZED_VIEW("CREATE_MATERIALIZED_VIEW", PathKind.ALLOW),
    DROP_MATERIALIZED_VIEW("DROP_MATERIALIZED_VIEW", PathKind.ALLOW),
    REFRESH_MATERIALIZED_VIEW("REFRESH_MATERIALIZED_VIEW", PathKind.ALLOW),
    RENAME_MATERIALIZED_VIEW("RENAME_MATERIALIZED_VIEW", PathKind.ALLOW),
    // Privileges / roles
    GRANT_TABLE_PRIVILEGE("GRANT_TABLE_PRIVILEGE", PathKind.ALLOW),
    REVOKE_TABLE_PRIVILEGE("REVOKE_TABLE_PRIVILEGE", PathKind.ALLOW),
    CREATE_ROLE("CREATE_ROLE", PathKind.ALLOW),
    DROP_ROLE("DROP_ROLE", PathKind.ALLOW),
    GRANT_ROLES("GRANT_ROLES", PathKind.ALLOW),
    REVOKE_ROLES("REVOKE_ROLES", PathKind.ALLOW),
    // Query lifecycle
    EXECUTE_QUERY("EXECUTE_QUERY", PathKind.ALLOW),
    VIEW_QUERY_OWNED_BY("VIEW_QUERY_OWNED_BY", PathKind.ALLOW),
    KILL_QUERY_OWNED_BY("KILL_QUERY_OWNED_BY", PathKind.ALLOW),
    // Procedures
    EXECUTE_PROCEDURE("EXECUTE_PROCEDURE", PathKind.ALLOW),
    EXECUTE_TABLE_PROCEDURE("EXECUTE_TABLE_PROCEDURE", PathKind.ALLOW),
    // Row-level security / masking
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

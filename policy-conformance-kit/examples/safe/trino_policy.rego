package trino

import rego.v1

# Example: SAFE mode. Row filters and masks are structured descriptors; the
# plugin renders all SQL (quoting/escaping is the plugin's job — notice there
# is no string concatenation anywhere below, and the apostrophe in
# "O'Brien Holdings" needs no special handling).
# Same response envelope and undefined-rule semantics as the passthrough example.

default allow := {"schema_version": 1, "result": false}

allow := {"schema_version": 1, "result": true} if {
	input.action == "CREATE_TABLE"
	count(input.identity.groups) > 0
}

allow := {"schema_version": 1, "result": permitted} if {
	input.action == "SELECT_FROM_COLUMNS"
	permitted := {c: true | some c in input.resource.columns; c in data.access.column_allowlist}
}

row_filters := {"schema_version": 1, "result": []} if {
	input.action == "GET_ROW_FILTERS"
	count(input.identity.groups) == 0
}

row_filters := {"schema_version": 1, "result": filters} if {
	input.action == "GET_ROW_FILTERS"
	count(input.identity.groups) > 0
	entities := {e | some g in input.identity.groups; e := data.access.entity_scope[g][_]}
	count(entities) > 0
	filters := [{"op": "in", "column": "legal_entity_code", "values": [e | some e in sort(entities)]}]
}

default column_masks := {"schema_version": 1, "result": null}

column_masks := {"schema_version": 1, "result": {"op": "is_null", "column": "ssn"}} if {
	input.action == "GET_COLUMN_MASKS"
	not "pii_admin" in input.identity.groups
}

filter := {"schema_version": 1, "result": visible} if {
	input.action in {"FILTER_SCHEMAS", "FILTER_CATALOGS", "FILTER_TABLES", "FILTER_COLUMNS"}
	visible := sort([c | some c in input.resource.columns; c in data.access.schema_allowlist])
}

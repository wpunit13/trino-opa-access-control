package trino

import rego.v1

# Example: passthrough mode. Row filters and masks are raw Trino SQL strings.
# NOTE on escaping: single quotes inside values MUST be doubled ('') — see
# the entity "O'Brien Holdings" in data.json. If you cannot guarantee clean
# reference data, prefer safe mode, where the plugin renders the SQL for you.
# Response envelope: every rule returns {"schema_version": 1, "result": ...}.
# An undefined rule is a conforming DENY for `allow`, but an ERROR for
# row_filters / column_masks / filter — those must always return a response.

default allow := {"schema_version": 1, "result": false}

allow := {"schema_version": 1, "result": true} if {
	input.action == "CREATE_TABLE"
	count(input.identity.groups) > 0
}

# Per-column response: allowed columns are true; omitted columns are DENIED
# by the plugin (missing = false).
allow := {"schema_version": 1, "result": permitted} if {
	input.action == "SELECT_FROM_COLUMNS"
	permitted := {c: true | some c in input.resource.columns; c in data.access.column_allowlist}
}

# Unknown users get an explicit empty filter list ("allow nothing").
row_filters := {"schema_version": 1, "result": []} if {
	input.action == "GET_ROW_FILTERS"
	count(input.identity.groups) == 0
}

row_filters := {"schema_version": 1, "result": filters} if {
	input.action == "GET_ROW_FILTERS"
	count(input.identity.groups) > 0
	entities := {e | some g in input.identity.groups; e := data.access.entity_scope[g][_]}
	count(entities) > 0
	# Escape single quotes ('') — the plugin structurally validates this SQL,
	# but well-formed SQL with the wrong meaning is still your responsibility.
	quoted := [sprintf("'%s'", [replace(e, "'", "''")]) | some e in sort(entities)]
	filters := [sprintf("legal_entity_code IN (%s)", [concat(", ", quoted)])]
}

default column_masks := {"schema_version": 1, "result": null}

# NOTE: a passthrough mask must be a structurally valid Trino expression over the
# target column only (the plugin runs SqlExpressionValidator with the target
# table + the masked column as the allowed column set). Policy constructs such
# as `input.identity.groups` do not exist in SQL — an earlier version of this
# example emitted `CASE WHEN 'pii_admin' IN input.identity.groups ...`, which
# the plugin correctly rejects (fail closed). Group-based masking decisions
# belong in the Rego (as below); the SQL then only transforms the column.
column_masks := {"schema_version": 1, "result": "CASE WHEN ssn IS NULL THEN NULL ELSE '***-**-' || substr(ssn, 8) END"} if {
	input.action == "GET_COLUMN_MASKS"
	not "pii_admin" in input.identity.groups
}

filter := {"schema_version": 1, "result": visible} if {
	input.action in {"FILTER_SCHEMAS", "FILTER_CATALOGS", "FILTER_TABLES", "FILTER_COLUMNS"}
	visible := sort([c | some c in input.resource.columns; c in data.access.schema_allowlist])
}

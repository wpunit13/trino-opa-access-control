package conformance

import rego.v1

# Contract-2 shape validators — the Rego re-statement of what the plugin's
# OpaResponseParser / DescriptorRenderer accept. Milestone 6's CLI runner checks
# against the Java implementation itself; this kit is the fast authoring loop.
#
# Envelope rule (Contract 2 / §3.5): every response object must carry
# "schema_version": 1.

supported_version := 1

valid_envelope(resp) if {
	is_object(resp)
	resp.schema_version == supported_version
}

# ---- allow (§3.2.A): boolean, or per-column map (missing/false column denies) ----

is_bool_map(x) if {
	is_object(x)
	every k, v in x {
		is_string(k)
		is_boolean(v)
	}
}

allow_result_ok(result) if is_boolean(result)

allow_result_ok(result) if is_bool_map(result)

allow_valid(resp) if {
	valid_envelope(resp)
	allow_result_ok(resp.result)
}

# allow is special: an undefined rule is a DENY, not an error (§7).
allow_response(doc) := resp if {
	resp := data.trino.allow with input as doc
}

allow_conforms(doc) if {
	not allow_response(doc)
}

allow_conforms(doc) if {
	allow_valid(allow_response(doc))
}

# ---- safe-mode descriptors (§3.4) ----

is_scalar(x) if is_string(x)

is_scalar(x) if is_number(x)

is_scalar(x) if is_boolean(x)

safe_identifier(x) if regex.match(`^[A-Za-z_][A-Za-z0-9_]*$`, x)

descriptor_valid(d) if descriptor_valid_max(d, data.conformance_config.max_in_clause_size)

descriptor_valid_max(d, max_in) if {
	d.op == "in"
	safe_identifier(d.column)
	is_array(d.values)
	count(d.values) <= max_in
	every v in d.values {
		is_scalar(v)
	}
}

descriptor_valid_max(d, max_in) if {
	d.op in {"eq", "neq"}
	safe_identifier(d.column)
	is_array(d.values)
	count(d.values) == 1
	is_scalar(d.values[0])
}

descriptor_valid_max(d, max_in) if {
	d.op in {"is_null", "is_not_null"}
	safe_identifier(d.column)
}

# ---- row_filters (§3.2.B) ----

row_filters_result_ok(result) if {
	data.conformance_config.mode == "passthrough"
	is_array(result)
	every e in result {
		is_string(e)
	}
}

row_filters_result_ok(result) if {
	data.conformance_config.mode == "safe"
	is_array(result)
	every e in result {
		is_object(e)
		descriptor_valid(e)
	}
}

row_filters_valid(resp) if {
	valid_envelope(resp)
	is_array(resp.result)
	row_filters_result_ok(resp.result)
}

row_filters_response(doc) := resp if {
	resp := data.trino.row_filters with input as doc
}

# List/mask contracts: an undefined rule is an ERROR → fail closed (§3.2.D).
row_filters_conforms(doc) if {
	row_filters_valid(row_filters_response(doc))
}

# ---- column_masks (§3.2.C): string (passthrough) / descriptor (safe) / null ----

mask_result_ok(result) if is_null(result)

mask_result_ok(result) if {
	data.conformance_config.mode == "passthrough"
	is_string(result)
}

mask_result_ok(result) if {
	data.conformance_config.mode == "safe"
	is_object(result)
	descriptor_valid(result)
}

column_masks_valid(resp) if {
	valid_envelope(resp)
	mask_result_ok(resp.result)
}

column_masks_response(doc) := resp if {
	resp := data.trino.column_masks with input as doc
}

column_masks_conforms(doc) if {
	column_masks_valid(column_masks_response(doc))
}

# ---- filter (§3.2.D): array of strings; empty = allow nothing; absent = error ----

filter_valid(resp) if {
	valid_envelope(resp)
	is_array(resp.result)
	every e in resp.result {
		is_string(e)
	}
}

filter_response(doc) := resp if {
	resp := data.trino.filter with input as doc
}

filter_conforms(doc) if {
	filter_valid(filter_response(doc))
}

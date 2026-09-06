package conformance

import rego.v1

import data.conformance_broken

# Self-tests: the shape validators must REJECT every deliberately-broken response
# in fixtures/broken/broken.rego. If one of these fails, the kit's validators are
# wrong — do not weaken the validators to fix it.

test_rejects_allow_non_boolean if {
	not allow_valid(conformance_broken.allow_non_boolean)
}

test_rejects_allow_unsupported_schema_version if {
	not allow_valid(conformance_broken.allow_bad_version)
}

test_rejects_allow_missing_schema_version if {
	not allow_valid(conformance_broken.allow_missing_version)
}

test_rejects_per_column_map_non_boolean if {
	not allow_valid(conformance_broken.allow_per_column_non_boolean)
}

test_rejects_row_filters_non_string_entries if {
	data.conformance_config.mode == "passthrough"
	not row_filters_result_ok(conformance_broken.row_filters_non_string_entries)
}

test_rejects_row_filters_missing_result if {
	not row_filters_valid(conformance_broken.row_filters_missing_result)
}

test_rejects_row_filters_non_array if {
	not row_filters_valid(conformance_broken.row_filters_non_array)
}

test_rejects_mask_non_string_in_passthrough if {
	data.conformance_config.mode == "passthrough"
	not mask_result_ok(conformance_broken.mask_non_string)
}

test_rejects_mask_missing_result if {
	not column_masks_valid(conformance_broken.mask_missing_result)
}

test_rejects_filter_missing_result if {
	not filter_valid(conformance_broken.filter_missing_result)
}

test_rejects_filter_non_string_entries if {
	not filter_valid(conformance_broken.filter_non_string_entries)
}

test_rejects_descriptor_unsupported_op if {
	not descriptor_valid_max(conformance_broken.descriptor_unsupported_op, 10)
}

test_rejects_descriptor_unsafe_column if {
	not descriptor_valid_max(conformance_broken.descriptor_unsafe_column, 10)
}

test_rejects_descriptor_missing_values if {
	not descriptor_valid_max(conformance_broken.descriptor_missing_values, 10)
}

test_rejects_descriptor_non_scalar_value if {
	not descriptor_valid_max(conformance_broken.descriptor_non_scalar_value, 10)
}

test_rejects_descriptor_eq_with_wrong_arity if {
	not descriptor_valid_max(conformance_broken.descriptor_eq_wrong_arity, 10)
}

test_rejects_descriptor_oversized_in if {
	not descriptor_valid_max(conformance_broken.descriptor_oversized_in, 10)
}

test_accepts_descriptor_in_at_boundary if {
	descriptor_valid_max(conformance_broken.descriptor_in_at_limit, 10)
}

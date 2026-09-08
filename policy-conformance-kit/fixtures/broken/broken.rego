package conformance_broken

import rego.v1

# Deliberately NON-CONFORMING responses. The conformance kit's self-tests assert
# that every one of these is REJECTED by the shape validators. Never use these
# patterns in real policies.

allow_non_boolean := {"schema_version": 1, "result": "yes"}

allow_bad_version := {"schema_version": 2, "result": true}

allow_missing_version := {"result": true}

allow_per_column_non_boolean := {"schema_version": 1, "result": {"ssn": "yes"}}

row_filters_non_string_entries := {"schema_version": 1, "result": [42]}

row_filters_missing_result := {"schema_version": 1}

row_filters_non_array := {"schema_version": 1, "result": "tenant_id = 'x'"}

mask_non_string := {"schema_version": 1, "result": 7}

mask_missing_result := {"schema_version": 1}

filter_missing_result := {"schema_version": 1}

filter_non_string_entries := {"schema_version": 1, "result": [{"name": "finance"}]}

descriptor_unsupported_op := {"op": "like", "column": "name", "values": ["%admin%"]}

descriptor_unsafe_column := {"op": "eq", "column": "1=1 OR true --", "values": ["x"]}

descriptor_missing_values := {"op": "in", "column": "org_unit_id"}

descriptor_non_scalar_value := {"op": "in", "column": "org_unit_id", "values": [{"nested": "object"}]}

descriptor_eq_wrong_arity := {"op": "eq", "column": "country_iso", "values": ["DE", "FR"]}

descriptor_oversized_in := {"op": "in", "column": "org_unit_id", "values": [sprintf("v%d", [i]) | some i in numbers.range(1, 11)]}

descriptor_in_at_limit := {"op": "in", "column": "org_unit_id", "values": [sprintf("v%d", [i]) | some i in numbers.range(1, 10)]}

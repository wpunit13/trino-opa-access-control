package conformance

import rego.v1

# Author-facing conformance tests: evaluate the policy under test (data.trino.*)
# against every generated Contract-1 input fixture and assert each response
# conforms to Contract 2 as the plugin parses it.
#
# This file is loaded by run.sh ONLY when a policy directory is provided.
# Allow is special: an undefined rule is a conforming DENY. For row_filters /
# column_masks / filter, the policy MUST return a response (undefined = error
# → fail closed at runtime).

test_allow_conforms_create_table_alice if {
	allow_conforms(data.inputs.create_table_alice)
}

test_allow_conforms_select_columns_per_column_map if {
	allow_conforms(data.inputs.select_columns_alice)
}

test_allow_conforms_undefined_rule_is_deny if {
	allow_conforms(data.inputs.create_table_nobody)
}

test_row_filters_conforms_alice if {
	row_filters_conforms(data.inputs.row_filters_alice)
}

test_row_filters_conforms_empty_for_unknown_user if {
	row_filters_conforms(data.inputs.row_filters_nobody)
}

test_column_masks_conforms_ssn if {
	column_masks_conforms(data.inputs.column_masks_ssn)
}

test_filter_conforms_schemas if {
	filter_conforms(data.inputs.filter_schemas)
}

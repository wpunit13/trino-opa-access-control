package trino

import rego.v1

import data.conformance_broken

# Milestone 6: mock policy for the CLI conformance runner's NEGATIVE coverage.
# Serves each deliberately-broken response from broken.rego at the plugin's data
# paths, selected by input.decision_id == "case-<constant-name>". Never load this
# file together with a real policy — it always produces non-conforming responses.
#
# The descriptor_* constants are raw descriptors (no envelope): they are wrapped
# into row_filters / column_masks results the way a safe-mode policy would emit
# them. descriptor_in_at_limit is the positive control (10 values at a max-in of
# 10) — the CLI must ACCEPT that case.

keys := object.keys(conformance_broken)

selected(name) if input.decision_id == sprintf("case-%s", [name])

default allow := {"schema_version": 1, "result": false}

allow := conformance_broken[name] if {
	some name in keys
	startswith(name, "allow_")
	selected(name)
}

row_filters := conformance_broken[name] if {
	some name in keys
	startswith(name, "row_filters_")
	selected(name)
}

row_filters := {"schema_version": 1, "result": [conformance_broken[name]]} if {
	some name in keys
	startswith(name, "descriptor_")
	selected(name)
}

default column_masks := {"schema_version": 1, "result": null}

column_masks := conformance_broken[name] if {
	some name in keys
	startswith(name, "mask_")
	selected(name)
}

column_masks := {"schema_version": 1, "result": conformance_broken[name]} if {
	some name in keys
	startswith(name, "descriptor_")
	selected(name)
}

filter := conformance_broken[name] if {
	some name in keys
	startswith(name, "filter_")
	selected(name)
}

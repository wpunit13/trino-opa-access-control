package trino

import rego.v1

# DEMO policy for connecting a SQL client (DBeaver) to the demo coordinator.
# The demo coordinator has no GroupProvider, so identities carry NO groups —
# the M5 example policy (group-based) would deny everything. This variant grants
# the user "admin" access with a TABLE-LEVEL demonstration:
#
#   tpch.tiny.nation    -> ALLOWED (SELECT)
#   tpch.tiny.customer  -> DENIED  (default deny -> Access Denied in the client)
#   everything else     -> metadata browsing allowed, SELECT denied
#
# Safe mode: row filters / masks are descriptors or null; the plugin renders all SQL.
# Swap ../policy-conformance-kit/examples/safe back into docker-compose.yml to see
# the strict, group-based example (every DBeaver query will then be denied).

# ---- SELECT: only on the demo table -----------------------------------------

select_allowed if {
	input.identity.user == "admin"
	input.action == "SELECT_FROM_COLUMNS"
	input.resource.catalog == "tpch"
	input.resource.schema == "tiny"
	input.resource.table == "nation"
}

allow := {"schema_version": 1, "result": true} if select_allowed

# ---- Everything else the admin does (browsing, EXECUTE_QUERY, ...) ----------

allow := {"schema_version": 1, "result": true} if {
	input.identity.user == "admin"
	input.action != "SELECT_FROM_COLUMNS"
}

# ---- Row filters / masks / bulk filters: none for anyone in this demo --------
# (Unconditional, so the bundle is also gate-clean: undefined list responses
# are plugin errors, so every user must always get a well-formed response.)

row_filters := {"schema_version": 1, "result": []}

default column_masks := {"schema_version": 1, "result": null}

filter := {"schema_version": 1, "result": input.resource.columns}

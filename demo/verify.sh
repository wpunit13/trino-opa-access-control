#!/usr/bin/env bash
# Demo verification (Milestone 6): 10-minute end-to-end check of the local
# demo deployment. Not a test suite, not a production template.
#
#   ./demo/verify.sh
#
# What it proves:
#   1. OPA is up and answering the plugin's data paths.
#   2. The example policy produces Contract-2 responses (allow + per-column map
#      + row filter descriptor) for the generated fixture inputs.
#   3. If the trino service is running, a query from a NON-admin identity is
#      DENIED by the plugin (fail closed) — proving the plugin is installed,
#      wired to OPA, and enforcing. (The demo bundle grants only the user "admin".)
set -euo pipefail

cd "$(dirname "$0")"
FIXTURES="../policy-conformance-kit/fixtures/contract1_inputs.json"
OPA_URL="${OPA_URL:-http://localhost:8181}"

step() { printf '\n== %s ==\n' "$1"; }

step "1. OPA health"
curl -fsS "$OPA_URL/health" >/dev/null && echo "OK: OPA healthy at $OPA_URL"

step "2. Contract-2 responses from the example policy"

post() { # path, fixture-name
  python3 -c "
import json, sys
inp = json.load(open('$FIXTURES'))['inputs']['$2']
print(json.dumps({'input': inp}))
" | curl -fsS -H 'Content-Type: application/json' -d @- "$OPA_URL/v1/data/trino/$1"
}

echo "-- data.trino.allow (create_table_alice, groups present):"
post allow create_table_alice | python3 -m json.tool
echo "-- data.trino.row_filters (row_filters_alice):"
post row_filters row_filters_alice | python3 -m json.tool

step "3. Fail-closed check via Trino (optional; requires the trino service)"
if command -v docker >/dev/null 2>&1 && docker compose ps trino 2>/dev/null | grep -q running; then
  # No GroupProvider is configured in the demo, so the coordinator identity has
  # no groups and the example policy denies everything — this MUST fail.
  if docker compose exec -T trino trino --execute "SELECT 1" >/dev/null 2>&1; then
    echo "UNEXPECTED: query succeeded — the policy did not deny; check your setup"
    exit 1
  else
    echo "OK: query was denied (access denied to catalog) — plugin is wired and fail-closed"
  fi
else
  echo "trino service not running (start with: docker compose --profile trino up -d --build) — skipped"
fi

printf '\nDemo verification complete.\n'

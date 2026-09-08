#!/usr/bin/env bash
# Contract-2 conformance runner (Milestone 5).
#
# Usage:
#   ./run.sh <policy-dir>            # conformance-test your policy directory
#   ./run.sh examples/passthrough    # verify the passthrough example
#   MODE=safe ./run.sh examples/safe # verify the safe-mode example
#   ./run.sh                         # self-test only (validators vs broken fixtures)
#
# Requires: opa >= 1.0 on PATH. Exits non-zero on any conformance violation.
set -euo pipefail
cd "$(dirname "$0")"

MODE="${MODE:-passthrough}"
MAX_IN="${MAX_IN:-1000}"
printf '{"conformance_config": {"mode": "%s", "max_in_clause_size": %s}}\n' "$MODE" "$MAX_IN" > conformance_config.json

if ! command -v opa >/dev/null 2>&1; then
  echo "ERROR: 'opa' binary not found on PATH (need >= 1.0). Install from https://www.openpolicyagent.org" >&2
  exit 2
fi

if [ $# -eq 0 ]; then
  echo "== self-test: validators vs broken fixtures (mode=$MODE) =="
  exec opa test conformance_test.rego contract_negative_test.rego conformance_config.json fixtures/broken
fi

echo "== conformance: policy dir '$1' (mode=$MODE) =="
exec opa test conformance_test.rego author_conformance_test.rego conformance_config.json fixtures "$1"

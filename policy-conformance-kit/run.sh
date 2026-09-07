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

MODE="${MODE:-safe}"
MAX_IN="${MAX_IN:-1000}"

if ! command -v opa >/dev/null 2>&1; then
  echo "ERROR: 'opa' binary not found on PATH (need >= 1.0). Install from https://www.openpolicyagent.org" >&2
  exit 2
fi

if [ $# -eq 0 ]; then
  # Self-test: the validators must reject every broken response. Two negative
  # cases (row_filters non-string, mask non-string) are passthrough-specific and
  # require mode=passthrough, so the self-test ALWAYS runs in passthrough mode
  # (the descriptor cases are mode-independent and still run).
  printf '{"conformance_config": {"mode": "passthrough", "max_in_clause_size": %s}}\n' "$MAX_IN" > conformance_config.json
  echo "== self-test: validators vs broken fixtures (mode=passthrough) =="
  exec opa test conformance_test.rego contract_negative_test.rego conformance_config.json fixtures/broken
fi

printf '{"conformance_config": {"mode": "%s", "max_in_clause_size": %s}}\n' "$MODE" "$MAX_IN" > conformance_config.json

echo "== conformance: policy dir '$1' (mode=$MODE) =="
exec opa test conformance_test.rego author_conformance_test.rego conformance_config.json fixtures "$1"

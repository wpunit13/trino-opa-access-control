#!/usr/bin/env bash
# Non-determinism scanner (Milestone 7 / D4).
#
# WARNING-level check, never a hard failure. Reports Rego builtins that make a
# decision non-deterministic:
#   time.*     — time.now_ns, time.parse_ns, ... (result varies per evaluation)
#   http.send  — network call (result varies)
#   rand.*     — rand.intn, ... (result varies)
#   opa.runtime — runtime/env introspection (varies per host)
#
# Why it matters: the plugin's decision cache keys on the full marshaled input
# minus volatile fields. A rule that consults the clock / network / RNG can
# return DIFFERENT results for the SAME cache key, so the cached decision may
# be stale or wrong. The plugin cannot detect this — it only sees well-formed
# responses. Teams that genuinely need time-based rules may acknowledge the
# warnings and accept degraded caching.
#
# This is a heuristic source scan (grep over .rego files), so it can produce
# false positives (e.g. a comment mentioning "time.now_ns"). Because it is
# warning-level and exits 0, that is acceptable; it is a tripwire, not a gate.
#
# Usage:
#   ./scan.sh <policy-dir> [<policy-dir> ...]
#
# Exits 0 always (warnings only). Requires opa >= 1.0 on PATH (for parity with
# the rest of the kit; the scan itself is plain grep).
set -uo pipefail
cd "$(dirname "$0")"

if [ $# -eq 0 ]; then
  echo "usage: $0 <policy-dir> [<policy-dir> ...]" >&2
  exit 2
fi

if ! command -v opa >/dev/null 2>&1; then
  echo "ERROR: 'opa' binary not found on PATH (need >= 1.0)." >&2
  exit 2
fi

# Display name | grep -E pattern. Keep the pattern tight enough to avoid
# matching unrelated identifiers (e.g. "timeout" has no dot after "time").
RULES=(
  "time.*|time\\."
  "http.send|http\\.send"
  "rand.*|rand\\."
  "opa.runtime|opa\\.runtime"
)

warnings=0
for dir in "$@"; do
  if [ ! -d "$dir" ]; then
    echo "WARNING: policy dir '$dir' does not exist; skipping" >&2
    continue
  fi
  while IFS= read -r file; do
    for rule in "${RULES[@]}"; do
      name="${rule%%|*}"
      pat="${rule##*|}"
      # grep -nHE -> "path:line:content"; -E for extended regex.
      while IFS= read -r match; do
        [ -z "$match" ] && continue
        echo "WARNING: $file uses non-deterministic builtin '$name' (degrades decision caching): $match"
        warnings=$((warnings + 1))
      done < <(grep -nHE "$pat" "$file" || true)
    done
  done < <(find "$dir" -name '*.rego' -type f)
done

if [ "$warnings" -gt 0 ]; then
  echo ""
  echo "== $warnings non-determinism warning(s) found. WARNING only, not a failure. =="
  echo "   Teams that need time-based rules may acknowledge and accept degraded caching."
else
  echo "== no non-deterministic builtins found =="
fi
exit 0
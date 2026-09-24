#!/usr/bin/env bash
#
# Re-send every dataset in a list to the index, one at a time.
#
# Used after a change to how records are indexed, when the fix only reaches
# the index once the records are sent again (#3548, #3595).
#
# Two things this guards against, both learned the hard way:
#
#   A dataset sitting in "retry" or "error" -- usually behind a failed harvest
#   -- accepts the resend call and does nothing. The endpoint still answers
#   {"saveStarted": true}, so the run looks clean while one dataset silently
#   keeps its old content. Hence the clear-error first.
#
#   Waiting for the phase to return to "idle" is not proof that anything was
#   sent; a dataset that never started is idle throughout. So this compares
#   stateSaved before and after and reports a dataset that did not move, which
#   is the difference between "351/351, 0 errors" and the truth.
#
# Usage: staggered-resend.sh <specs-file> [pause-seconds]
set -uo pipefail

BASE="${NARTHEX_BASE:-https://ingestion.brabantcloud.acpt.delving.io/narthex/app}"
SPECS_FILE="${1:?usage: staggered-resend.sh <specs-file> [pause-seconds]}"
PAUSE="${2:-20}"
MAX_WAIT=1800   # per dataset

field() {
  # field <spec> <json-key> -- reads one scalar from the dataset info endpoint
  curl -s --max-time 30 "$BASE/dataset/$1/info" 2>/dev/null | python3 -c "
import json, sys
key = sys.argv[1]
try:
    doc = json.load(sys.stdin)
except Exception:
    print(''); raise SystemExit
def walk(o):
    if isinstance(o, dict):
        for k, v in o.items():
            if k == key and not isinstance(v, (dict, list)):
                return v
            found = walk(v)
            if found is not None:
                return found
    elif isinstance(o, list):
        for v in o:
            found = walk(v)
            if found is not None:
                return found
    return None
print(walk(doc) or '')
" "$2"
}

phase_of() {
  curl -s --compressed "$BASE/dataset-list-light" 2>/dev/null | python3 -c "
import json, sys
spec = sys.argv[1]
try:
    doc = json.load(sys.stdin)
except Exception:
    print('?'); raise SystemExit
rows = doc if isinstance(doc, list) else doc.get('datasets', [])
match = [r for r in rows if r.get('spec') == spec]
print(match[0].get('phase', '?') if match else '?')
" "$1"
}

total=$(grep -cve '^[[:space:]]*$' "$SPECS_FILE")
done_count=0
declare -a failed=() stalled=()

while IFS= read -r spec; do
  [ -z "$spec" ] && continue
  done_count=$((done_count + 1))

  before=$(field "$spec" stateSaved)

  # A dataset parked in retry/error will not save. Clearing is harmless when
  # there is nothing to clear.
  phase=$(phase_of "$spec")
  if [ "$phase" != "idle" ]; then
    curl -s --max-time 40 "$BASE/dataset/$spec/command/clear%20error" >/dev/null 2>&1
    sleep 2
    phase=$(phase_of "$spec")
  fi

  resp=$(curl -s --max-time 60 -X POST "$BASE/dataset/$spec/resend")
  marked=$(echo "$resp" | python3 -c 'import json,sys; print(json.load(sys.stdin).get("marked","?"))' 2>/dev/null || echo "?")

  waited=0
  while [ $waited -lt $MAX_WAIT ]; do
    sleep 15
    waited=$((waited + 15))
    phase=$(phase_of "$spec")
    [ "$phase" = "idle" ] || [ "$phase" = "error" ] && break
  done

  after=$(field "$spec" stateSaved)

  if [ "$phase" = "error" ]; then
    failed+=("$spec (phase=error, marked=$marked)")
    echo "FOUT     $spec — eindigt op error [$done_count/$total]"
  elif [ -n "$before" ] && [ "$before" = "$after" ]; then
    # The whole point of this check: the call succeeded and nothing happened.
    stalled+=("$spec (stateSaved bleef $before, marked=$marked)")
    echo "NIET GESTUURD  $spec — stateSaved onveranderd [$done_count/$total]"
  fi

  if [ $((done_count % 25)) -eq 0 ]; then
    echo "voortgang: $done_count/$total (fouten: ${#failed[@]}, niet gestuurd: ${#stalled[@]})"
  fi

  sleep "$PAUSE"
done < "$SPECS_FILE"

echo
echo "KLAAR: $done_count/$total datasets"
echo "  fouten:        ${#failed[@]}"
echo "  niet gestuurd: ${#stalled[@]}"
for f in "${failed[@]}";  do echo "   FOUT          $f"; done
for s in "${stalled[@]}"; do echo "   NIET GESTUURD $s"; done

[ ${#failed[@]} -eq 0 ] && [ ${#stalled[@]} -eq 0 ]

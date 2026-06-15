#!/usr/bin/env sh
# impact.sh — refresh step 5: per-project PR impact against the combined graph.
# Writes OUT_DIR/<project>.impact.json (+ <project>.pulls.json / <project>.pulls/).
# Needs _combined.json (run 3-merge.sh first) and `gh`/git PR history per project.
# Usage: ./scripts/impact.sh [extra impact flags... e.g. --max 50 --depth 3]
set -e
. "$(dirname "$0")/_common.sh"

GRAPH="$OUT_DIR/_combined.json"
[ -f "$GRAPH" ] || { echo "missing $GRAPH — run ./scripts/3-merge.sh first"; exit 1; }

projects | while read -r p; do
  name=$(basename "$p")
  [ -d "$p/.git" ] || { echo "  · $name: skip (not a standalone git repo)"; continue; }
  "$BIN" impact --git "$p" --graph "$GRAPH" \
    --out "$OUT_DIR/$name.impact.json" --pull-files "$OUT_DIR" "$@" \
    || echo "  · $name: skip (no PR source / impact failed)"
done

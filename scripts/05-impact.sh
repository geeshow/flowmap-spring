#!/usr/bin/env sh
# impact.sh — refresh step 5: per-project PR impact against the combined graph, written into
# OUT_DIR/projects/<name>/<name>.impact.json (+ <name>.pulls.json / <name>.pulls/). Wallga-aware:
# a monorepo's sub-projects (+ shared modules) each get their own impact, attributed by build.path.
# Needs _combined.json (run 03-merge.sh first) and `gh`/git PR history. Extra flags pass through.
# Usage: ./scripts/impact.sh [extra impact flags... e.g. --max 50]
set -e
. "$(dirname "$0")/_common.sh"

GRAPH="$OUT_DIR/_combined.json"
[ -f "$GRAPH" ] || { echo "missing $GRAPH — run ./scripts/03-merge.sh first"; exit 1; }

exec "$BIN" impact --repo "$REPO" --graph "$GRAPH" --out-dir "$OUT_DIR" "$@"

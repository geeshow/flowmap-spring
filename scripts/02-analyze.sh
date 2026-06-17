#!/usr/bin/env sh
# analyze.sh — refresh step 2: build a call graph per project -> OUT_DIR/projects/<name>/<name>.json
# Wallga-aware: a `wallga.yml` monorepo under REPO is analyzed ONCE and split into its
# sub-projects (+ a stand-alone project per shared module) — the same engine as `refresh`,
# so cross-module calls resolve and nothing is dropped. Extra flags (e.g. --public-only
# --include-other --profile p) are passed through.
# Usage: ./scripts/analyze.sh [extra analyze flags...]
set -e
. "$(dirname "$0")/_common.sh"
mkdir -p "$OUT_DIR"

exec "$BIN" analyze --repo "$REPO" --out-dir "$OUT_DIR" "$@"

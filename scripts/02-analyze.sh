#!/usr/bin/env sh
# analyze.sh — refresh step 2: build a call graph per project -> OUT_DIR/<project>.json
# Extra flags (e.g. --public-only --include-other --profile p) are passed through.
# Usage: ./scripts/analyze.sh [extra analyze flags...]
set -e
. "$(dirname "$0")/_common.sh"
mkdir -p "$OUT_DIR"

projects | while read -r p; do
  name=$(basename "$p")
  "$BIN" analyze --repo "$REPO" --project "$name" --out "$OUT_DIR/$name.json" "$@" \
    || echo "  ! $name: analyze failed"
done

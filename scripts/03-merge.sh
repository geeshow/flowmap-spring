#!/usr/bin/env sh
# merge.sh — refresh step 3: merge the per-project graphs (OUT_DIR/<project>.json)
# into a single cross-service _combined.json and refresh _manifest.json.
set -e
. "$(dirname "$0")/_common.sh"

exec "$BIN" combine --dir "$OUT_DIR" --out "$OUT_DIR/_combined.json"

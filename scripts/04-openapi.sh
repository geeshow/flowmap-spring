#!/usr/bin/env sh
# openapi.sh — refresh step 4: OpenAPI per project -> OUT_DIR/projects/<name>/<name>.openapi.json,
# plus a repo-wide OUT_DIR/_openapi.json. Wallga-aware (same sub-project split as analyze).
# Extra flags are passed through.
# Usage: ./scripts/openapi.sh [extra openapi flags...]
set -e
. "$(dirname "$0")/_common.sh"
mkdir -p "$OUT_DIR"

exec "$BIN" openapi --repo "$REPO" --out-dir "$OUT_DIR" --title flowmap-all "$@"

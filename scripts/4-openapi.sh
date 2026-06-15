#!/usr/bin/env sh
# openapi.sh — refresh step 4: OpenAPI per project -> OUT_DIR/<project>.openapi.json,
# plus a repo-wide OUT_DIR/_openapi.json. Extra flags are passed through.
# Usage: ./scripts/openapi.sh [extra openapi flags...]
set -e
. "$(dirname "$0")/_common.sh"
mkdir -p "$OUT_DIR"

projects | while read -r p; do
  name=$(basename "$p")
  "$BIN" openapi --repo "$REPO" --project "$name" --out "$OUT_DIR/$name.openapi.json" "$@" \
    || echo "  ! $name: openapi failed"
done

# repo-wide document (re-analyzes the whole repo)
"$BIN" openapi --repo "$REPO" --title flowmap-all --out "$OUT_DIR/_openapi.json" "$@" \
  || echo "  ! repo-wide openapi failed"

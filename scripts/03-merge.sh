#!/usr/bin/env sh
# merge.sh — refresh step 3: merge the per-project graphs (OUT_DIR/<project>.json)
# into a single cross-service _combined.json and refresh _manifest.json.
#
# --repo lets combine AUTO-DISCOVER Spring Cloud Gateway routes from the source tree
# (spring.cloud.gateway.routes) so it wires `gateway` edges into _combined.json AND emits
# each gateway project's route table (<name>.gateway.json) for the web front→backend join.
set -e
. "$(dirname "$0")/_common.sh"

exec "$BIN" combine --dir "$OUT_DIR" --repo "$REPO" --out "$OUT_DIR/_combined.json"

#!/usr/bin/env sh
# sync.sh — refresh step 6: assemble the web app data dir from OUT_DIR (+ FRONTEND_DIR)
# into SYNC_DIR and (re)write the app-facing manifest.json.
set -e
. "$(dirname "$0")/_common.sh"

[ -n "$SYNC_DIR" ] || { echo "SYNC_DIR not set (flowmap.config or env)"; exit 1; }

set -- sync --out-dir "$OUT_DIR" --sync-dir "$SYNC_DIR"
[ -n "$FRONTEND_DIR" ] && set -- "$@" --frontend-dir "$FRONTEND_DIR"
exec "$BIN" "$@"

# _common.sh — sourced by every step script. Resolves paths/config and the CLI.
#
# Config (REPO / OUT_DIR / SYNC_DIR / FRONTEND_DIR) is read from flowmap.config when
# present, then overridden by any matching environment variable, then defaults.
# Each step is independently runnable; the usual order is:
#   01-pull.sh -> 02-analyze.sh -> 03-merge.sh -> 04-openapi.sh -> 05-impact.sh -> 06-sync.sh

ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"

# value of KEY=VALUE in flowmap.config (ignores # comment lines), or empty
cfg() { grep -E "^$1=" flowmap.config 2>/dev/null | head -1 | cut -d= -f2-; }

REPO="${REPO:-$(cfg REPO)}";             REPO="${REPO:-.repo}"
OUT_DIR="${OUT_DIR:-$(cfg OUT_DIR)}";     OUT_DIR="${OUT_DIR:-json}"
SYNC_DIR="${SYNC_DIR:-$(cfg SYNC_DIR)}"
FRONTEND_DIR="${FRONTEND_DIR:-$(cfg FRONTEND_DIR)}"

BIN="build/install/flowmap-spring/bin/flowmap-spring"
# (re)build the CLI. ALWAYS run installDist — Gradle is incremental, so it recompiles
# only when sources changed and is a near-instant no-op otherwise. Guarding on
# `[ -x "$BIN" ]` would skip the rebuild whenever an old install exists, so source
# fixes never take effect and the pipeline keeps using stale compiled classes.
./gradlew -q installDist

# project directories under REPO (one per line; excludes hidden dirs)
projects() { find "$REPO" -mindepth 1 -maxdepth 1 -type d ! -name '.*' | sort; }

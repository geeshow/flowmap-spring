#!/usr/bin/env sh
# pull.sh — refresh step 1: fast-forward pull each project that is a git repo.
set -e
. "$(dirname "$0")/_common.sh"

projects | while read -r p; do
  name=$(basename "$p")
  if [ -d "$p/.git" ]; then
    printf '  - %s: ' "$name"
    git -C "$p" pull --ff-only 2>&1 | tail -1
  else
    echo "  - $name: skip (not a standalone git repo)"
  fi
done

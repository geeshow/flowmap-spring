#!/usr/bin/env sh
# pull.sh — refresh step 1: switch each git project to master, then fast-forward pull.
set -e
. "$(dirname "$0")/_common.sh"

projects | while read -r p; do
  name=$(basename "$p")
  if [ -d "$p/.git" ]; then
    printf '  - %s: ' "$name"
    # 분석은 항상 master 기준 — pull 전에 master 로 전환(이미 master 면 무영향, 없거나 전환 실패 시 현재 브랜치 유지).
    if ! git -C "$p" checkout master >/dev/null 2>&1; then printf '(master 전환 실패) '; fi
    git -C "$p" pull --ff-only 2>&1 | tail -1
  else
    echo "  - $name: skip (not a standalone git repo)"
  fi
done

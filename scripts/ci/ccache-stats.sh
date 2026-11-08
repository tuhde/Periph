#!/usr/bin/env bash
# Usage: scripts/ci/ccache-stats.sh [-z]
#
# Shows ccache statistics for every per-platform cache that scripts/ci/run.sh
# keeps under $PERIPH_CI_CACHE (default ~/.cache/periph-ci/ccache-*).
# -z  zero the statistics of each cache (entries are kept).
set -euo pipefail

ROOT="${PERIPH_CI_CACHE:-$HOME/.cache/periph-ci}"
ZERO=0
[ "${1:-}" = "-z" ] && ZERO=1

shopt -s nullglob
dirs=("$ROOT"/ccache-*/)
[ "${#dirs[@]}" -gt 0 ] || { echo "no ccache directories under $ROOT" >&2; exit 1; }

for d in "${dirs[@]}"; do
    d="${d%/}"
    export CCACHE_DIR="$d"
    echo "== ${d##*/ccache-}"
    if [ "$ZERO" -eq 1 ]; then
        ccache -z
    else
        ccache -s | grep -E 'Hits|Misses|Cache size'  | awk '!seen[$0]++' || true
    fi
done

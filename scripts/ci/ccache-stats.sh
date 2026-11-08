#!/usr/bin/env bash
# Usage: scripts/ci/ccache-stats.sh [-z]
#
# Shows ccache statistics for every per-platform cache that scripts/ci/run.sh
# keeps under $PERIPH_CCACHE_ROOT (default /var/cache/ccache/<platform>).
# -z  zero the statistics of each cache (entries are kept).
set -euo pipefail

ROOT="${PERIPH_CCACHE_ROOT:-/var/cache/ccache}"
ZERO=0
[ "${1:-}" = "-z" ] && ZERO=1

shopt -s nullglob
dirs=("$ROOT"/*/)
[ "${#dirs[@]}" -gt 0 ] || { echo "no ccache directories under $ROOT" >&2; exit 1; }

for d in "${dirs[@]}"; do
    d="${d%/}"
    export CCACHE_DIR="$d"
    echo "== ${d##*/}"
    if [ "$ZERO" -eq 1 ]; then
        ccache -z
    else
        ccache -s | grep -E 'Hits|Misses|Cache size'  | awk '!seen[$0]++' || true
    fi
done

#!/usr/bin/env bash
# Regenerate every UIFlow 2 .m5b file from its <chip>.json manifest, using
# uiflow-custom-block-generator. Output is deterministic (no timestamps or
# random content), so the committed .m5b files must always match what this
# script produces — CI runs it in --check mode to catch drift after a
# manifest or block .py file changes without regenerating.
#
# Usage:  ./generate.sh          # regenerate all .m5b files in place
#         ./generate.sh --check  # exit 1 if any .m5b is missing or stale; changes nothing

# Pinned to a commit rather than the PyPI release (0.0.5) — PyPI is behind
# upstream and produces byte-different output from the git version this repo's
# committed .m5b files were generated with.
GENERATOR_COMMIT=28c084fe81eaa9acc9a7530ac56feea6650d23f4

set -euo pipefail
cd "$(dirname "$0")"   # python/uiflow/

python3 -m pip install --quiet "git+https://github.com/3110/uiflow-custom-block-generator@${GENERATOR_COMMIT}"

if [ "${1:-}" = "--check" ]; then
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    STATUS=0
    for manifest in */*/*.json; do
        name="$(basename "$manifest" .json)"
        expected="$(dirname "$manifest")/$name.m5b"
        python3 -m uiflow_custom_block_generator "$manifest" --target-dir "$TMP" >/dev/null
        if ! diff -q "$expected" "$TMP/$name.m5b" >/dev/null 2>&1; then
            echo "STALE (run python/uiflow/generate.sh): $expected" >&2
            STATUS=1
        fi
        rm -f "$TMP/$name.m5b"
    done
    exit "$STATUS"
fi

for manifest in */*/*.json; do
    python3 -m uiflow_custom_block_generator "$manifest"
done

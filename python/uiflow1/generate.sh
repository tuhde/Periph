#!/usr/bin/env bash
# Regenerate every UIFlow 1 .m5b file from its <chip>.json manifest, using
# uiflow-custom-block-generator. Output is deterministic (no timestamps or
# random content), so the committed .m5b files must always match what this
# script produces — CI runs it in --check mode to catch drift after a
# manifest or block .py file changes without regenerating.
#
# Installs into a self-managed venv (python/uiflow1/.generator, created here if
# missing) rather than the caller's system Python — Debian/Ubuntu's PEP 668
# "externally-managed-environment" blocks a bare `pip install` outside a venv,
# and this keeps the pinned generator version isolated regardless. The venv
# self-ignores via its own .gitignore, so it's never accidentally committed.
#
# Usage:  ./generate.sh          # regenerate all .m5b files in place
#         ./generate.sh --check  # exit 1 if any .m5b is missing or stale; changes nothing

# Pinned to a commit rather than the PyPI release (0.0.5) — PyPI is behind
# upstream and produces byte-different output from the git version this repo's
# committed .m5b files were generated with.
GENERATOR_COMMIT=28c084fe81eaa9acc9a7530ac56feea6650d23f4

set -euo pipefail
cd "$(dirname "$0")"   # python/uiflow1/

VENV=.generator
[ -d "$VENV" ] || python3 -m venv "$VENV"
"$VENV/bin/pip" install --quiet "git+https://github.com/3110/uiflow-custom-block-generator@${GENERATOR_COMMIT}"
GENERATOR=("$VENV/bin/python" -m uiflow_custom_block_generator)

if [ "${1:-}" = "--check" ]; then
    TMP="$(mktemp -d)"
    trap 'rm -rf "$TMP"' EXIT
    STATUS=0
    for manifest in */*/*.json; do
        name="$(basename "$manifest" .json)"
        expected="$(dirname "$manifest")/$name.m5b"
        "${GENERATOR[@]}" "$manifest" --target-dir "$TMP" >/dev/null
        if ! diff -q "$expected" "$TMP/$name.m5b" >/dev/null 2>&1; then
            echo "STALE (run python/uiflow1/generate.sh): $expected" >&2
            STATUS=1
        fi
        rm -f "$TMP/$name.m5b"
    done
    exit "$STATUS"
fi

for manifest in */*/*.json; do
    "${GENERATOR[@]}" "$manifest"
done

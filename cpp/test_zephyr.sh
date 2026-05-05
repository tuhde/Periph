#!/usr/bin/env bash
# Usage: test_zephyr.sh [--compile-only] <category/chip>
# Example: test_zephyr.sh power/ina226
# Example: test_zephyr.sh --compile-only power/ina226
#
# Requires: west, ZEPHYR_BASE set (or a west workspace initialised)
# Config:   cpp/testconfig_zephyr (copy from testconfig_zephyr.example)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

COMPILE_ONLY=false
if [[ "${1:-}" == "--compile-only" ]]; then
    COMPILE_ONLY=true; shift
fi

TARGET="${1:?Usage: $0 [--compile-only] <category/chip>}"
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

TESTCONFIG="$SCRIPT_DIR/testconfig_zephyr"
if [[ -f "$TESTCONFIG" ]]; then
    # shellcheck source=/dev/null
    source "$TESTCONFIG"
fi

BOARD="${ZEPHYR_BOARD:-}"
if [[ -z "$BOARD" && "$COMPILE_ONLY" == false ]]; then
    echo "Error: ZEPHYR_BOARD not set in testconfig_zephyr" >&2; exit 2
fi

TEST_APP="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_zephyr"
if [[ ! -d "$TEST_APP" ]]; then
    echo "Error: test app not found: $TEST_APP" >&2; exit 2
fi

BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

BUILD_ARGS=(-d "$BUILD_DIR" "$TEST_APP")
[[ -n "$BOARD" ]] && BUILD_ARGS+=(-b "$BOARD")

echo "Building $TARGET..."
west build "${BUILD_ARGS[@]}"

if [[ "$COMPILE_ONLY" == true ]]; then
    echo "Compile-only: done."; exit 0
fi

PORT="${ZEPHYR_PORT:-}"
if [[ -z "$PORT" ]]; then
    echo "Error: ZEPHYR_PORT not set in testconfig_zephyr" >&2; exit 2
fi

echo "Flashing..."
west flash -d "$BUILD_DIR" --esp-device "$PORT"

# read_serial_zephyr.py opens the port then resets via RTS so we catch output
# from the very start of boot, regardless of how fast the board comes up.
echo "Reading output..."
python3 "$SCRIPT_DIR/read_serial_zephyr.py" "$PORT" 115200 "${SERIAL_TIMEOUT:-20}"

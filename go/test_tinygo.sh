#!/usr/bin/env bash
# Usage: test_tinygo.sh [--compile-only] <category/chip>
# Example: test_tinygo.sh environmental/aht21
#
# Requires: tinygo (>= 0.41), picotool (or the Pico W in BOOTSEL mode mounted
#           as a USB drive), pyserial
# Config:   go/testconfig_tinygo (copy from testconfig_tinygo.example)
#
# The runner builds the test for the Raspberry Pi Pico W target, flashes
# the resulting UF2 to the board, then reads serial output until the
# ===DONE: ... === line appears. Exits 0 on full pass, 1 on any
# failure, 2 on timeout.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

COMPILE_ONLY=false
if [[ "${1:-}" == "--compile-only" ]]; then
    COMPILE_ONLY=true; shift
fi

TARGET="${1:?Usage: $0 [--compile-only] <category/chip>}"
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

TESTCONFIG="$SCRIPT_DIR/testconfig_tinygo"
if [[ -f "$TESTCONFIG" ]]; then
    # shellcheck source=/dev/null
    source "$TESTCONFIG"
fi

UF2_MOUNT="${UF2_MOUNT:-/media/$USER/RPI-RP2}"
SERIAL_PORT="${SERIAL_PORT:-/dev/ttyACM0}"
SERIAL_TIMEOUT="${SERIAL_TIMEOUT:-20}"

TEST_DIR="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_tinygo"
if [[ ! -d "$TEST_DIR" ]]; then
    echo "Error: test not found: $TEST_DIR" >&2; exit 2
fi

UF2="$(mktemp --suffix=.uf2)"
trap 'rm -f "$UF2"' EXIT

echo "Building $TARGET for pico-w..."
(cd "$SCRIPT_DIR" && tinygo build -target=pico-w -o "$UF2" "./tests/$CATEGORY/${CHIP}_test_tinygo")

if [[ "$COMPILE_ONLY" == true ]]; then
    echo "Compile-only: done."; exit 0
fi

if [[ ! -d "$UF2_MOUNT" ]]; then
    echo "Error: Pico W UF2 mount not found at $UF2_MOUNT (hold BOOTSEL while plugging in)" >&2
    exit 2
fi

echo "Flashing..."
cp "$UF2" "$UF2_MOUNT/"

echo "Reading output..."
python3 "$SCRIPT_DIR/read_serial_tinygo.py" "$SERIAL_PORT" 115200 "$SERIAL_TIMEOUT"

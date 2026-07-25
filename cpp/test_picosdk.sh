#!/usr/bin/env bash
# Usage: test_picosdk.sh [--compile-only] <category/chip>
# Example: test_picosdk.sh power/ina226
# Example: test_picosdk.sh --compile-only power/ina226
#
# Builds a bare-metal pico-sdk test app, flashes it via picotool, and
# reads the USB-CDC serial output. Requires:
#   - PICO_SDK_PATH set (or pointed at from testconfig_picosdk)
#   - picotool on PATH
#   - A Raspberry Pi Pico / Pico W in BOOTSEL or running picotool-enabled
#     UF2 firmware
#
# Config:   cpp/testconfig_picosdk (copy from testconfig_picosdk.example)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

COMPILE_ONLY=false
if [[ "${1:-}" == "--compile-only" ]]; then
    COMPILE_ONLY=true; shift
fi

TARGET="${1:?Usage: $0 [--compile-only] <category/chip>}"
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

TESTCONFIG="$SCRIPT_DIR/testconfig_picosdk"
if [[ -f "$TESTCONFIG" ]]; then
    # shellcheck source=/dev/null
    source "$TESTCONFIG"
fi

if [[ -z "${PICO_SDK_PATH:-}" && -n "${PICO_SDK_FETCH:-}" ]]; then
    : # User has not set PICO_SDK_PATH; will be discovered by pico_sdk_init.cmake
fi

TEST_APP="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_picosdk"
if [[ ! -d "$TEST_APP" ]]; then
    echo "Error: test app not found: $TEST_APP" >&2; exit 2
fi

# --- pick board -----------------------------------------------------------
# Default to pico (non-W); PICO_BOARD in testconfig_picosdk overrides.
export PICO_BOARD="${PICO_BOARD:-pico}"

BUILD_DIR="$TEST_APP/build"
rm -rf "$BUILD_DIR"
echo "Building $TARGET for $PICO_BOARD..."
cmake -S "$TEST_APP" -B "$BUILD_DIR" -DPICO_BOARD="$PICO_BOARD"
cmake --build "$BUILD_DIR" -- -j"$(nproc 2>/dev/null || echo 2)"

if [[ "$COMPILE_ONLY" == true ]]; then
    echo "Compile-only: done."; exit 0
fi

UF2="$BUILD_DIR/${CHIP}_test_picosdk.uf2"
if [[ ! -f "$UF2" ]]; then
    echo "Error: UF2 not found at $UF2" >&2; exit 2
fi

PORT="${PICOSDK_PORT:-}"
if [[ -z "$PORT" ]]; then
    echo "Error: PICOSDK_PORT not set in testconfig_picosdk" >&2; exit 2
fi

echo "Flashing $UF2 via picotool on $PORT..."
# picotool load -x flashes the UF2 and reboots the chip into the new firmware
picotool load -x -t bin "$BUILD_DIR/${CHIP}_test_picosdk.elf" 2>/dev/null \
    || picotool load -x "$UF2"

# read_serial_picosdk.py opens the port, waits for the board to enumerate
# as a USB CDC device after the picotool-triggered reset, and reads until
# ===DONE=== or timeout.
echo "Reading output..."
python3 "$SCRIPT_DIR/read_serial_picosdk.py" "$PORT" "${SERIAL_TIMEOUT:-20}"

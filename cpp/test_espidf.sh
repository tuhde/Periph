#!/usr/bin/env bash
# Usage: test_espidf.sh [--compile-only] <category/chip>
# Example: test_espidf.sh power/ina226
# Example: test_espidf.sh --compile-only power/ina226
#
# Builds a native ESP-IDF test app, flashes it via idf.py, and reads
# the USB-CDC serial output. Requires:
#   - IDF_PATH set (or export the relevant ESP-IDF environment)
#   - idf.py on PATH
#   - An ESP32 / ESP32-S3 / ESP32-S2 board in flash mode (USB)
#
# Config:   cpp/testconfig_espidf (copy from testconfig_espidf.example)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

COMPILE_ONLY=false
if [[ "${1:-}" == "--compile-only" ]]; then
    COMPILE_ONLY=true; shift
fi

TARGET="${1:?Usage: $0 [--compile-only] <category/chip>}"
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

TESTCONFIG="$SCRIPT_DIR/testconfig_espidf"
if [[ -f "$TESTCONFIG" ]]; then
    # shellcheck source=/dev/null
    source "$TESTCONFIG"
fi

if [[ -z "${IDF_PATH:-}" && "$COMPILE_ONLY" == false ]]; then
    echo "Error: IDF_PATH not set in testconfig_espidf" >&2; exit 2
fi

TEST_APP="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_espidf"
if [[ ! -d "$TEST_APP" ]]; then
    echo "Error: test app not found: $TEST_APP" >&2; exit 2
fi

# --- pick target ----------------------------------------------------------
# Default to esp32; ESPIDF_TARGET in testconfig_espidf overrides.
export IDF_TARGET="${ESPIDF_TARGET:-esp32}"

BUILD_DIR="$TEST_APP/build"
rm -rf "$BUILD_DIR"
echo "Building $TARGET for $IDF_TARGET..."
( cd "$TEST_APP" && idf.py -B "$BUILD_DIR" build )

if [[ "$COMPILE_ONLY" == true ]]; then
    echo "Compile-only: done."; exit 0
fi

PORT="${ESPIDF_PORT:-}"
if [[ -z "$PORT" ]]; then
    echo "Error: ESPIDF_PORT not set in testconfig_espidf" >&2; exit 2
fi

echo "Flashing to $PORT..."
( cd "$TEST_APP" && idf.py -B "$BUILD_DIR" -p "$PORT" flash )

# read_serial.py opens the port, waits for the board to enumerate as
# USB CDC after the idf.py-triggered reset, and reads until ===DONE===
# or timeout. ESP-IDF's USB-CDC port usually needs a moment after the
# reset; the same script the Arduino runner uses handles that retry.
echo "Reading output..."
python3 "$SCRIPT_DIR/read_serial.py" "$PORT" "${SERIAL_TIMEOUT:-20}"

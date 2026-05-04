#!/usr/bin/env bash
# Usage:
#   ./test.sh <category>/<chip>          # compile + upload + run
#   ./test.sh --compile-only <cat>/<chip> # compile only (no hardware needed)
#
# Reads testconfig from the same directory if present.
# Copy testconfig.example to testconfig and fill in your board's values.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# --- load local config ---------------------------------------------------
CONFIG="$SCRIPT_DIR/testconfig"
if [ -f "$CONFIG" ]; then
    # shellcheck source=/dev/null
    source "$CONFIG"
else
    echo "WARNING: $CONFIG not found. Using defaults — copy testconfig.example to testconfig."
fi

FQBN="${FQBN:-esp32:esp32:esp32s3}"
PORT="${PORT:-/dev/ttyACM0}"
UPLOAD_SPEED="${UPLOAD_SPEED:-}"
SERIAL_TIMEOUT="${SERIAL_TIMEOUT:-15}"
I2C_SDA="${I2C_SDA:-}"
I2C_SCL="${I2C_SCL:-}"
I2C_FREQ="${I2C_FREQ:-400000}"

# --- parse args ----------------------------------------------------------
COMPILE_ONLY=0
if [ "${1:-}" = "--compile-only" ]; then
    COMPILE_ONLY=1
    shift
fi

TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    echo "Usage: $0 [--compile-only] <category>/<chip>"
    echo "  e.g. $0 power/ina226"
    exit 1
fi

SKETCH="$SCRIPT_DIR/tests/$TARGET/${TARGET##*/}_test"

if [ ! -d "$SKETCH" ]; then
    echo "ERROR: sketch not found: $SKETCH"
    exit 1
fi

# --- build-properties for pin defines ------------------------------------
BUILD_PROPS=()
[ -n "$I2C_SDA"  ] && BUILD_PROPS+=(--build-property "compiler.cpp.extra_flags=-DTEST_SDA=$I2C_SDA")
[ -n "$I2C_SCL"  ] && BUILD_PROPS+=( --build-property "compiler.cpp.extra_flags=-DTEST_SCL=$I2C_SCL")
[ -n "$I2C_FREQ" ] && BUILD_PROPS+=(--build-property "compiler.cpp.extra_flags=-DTEST_I2C_FREQ=$I2C_FREQ")

# --- compile -------------------------------------------------------------
echo "=== Compiling $TARGET for $FQBN ==="
arduino-cli compile --fqbn "$FQBN" "${BUILD_PROPS[@]}" "$SKETCH"
echo "Compile OK"

[ "$COMPILE_ONLY" -eq 1 ] && exit 0

# --- validate hardware config -------------------------------------------
if [ -z "$I2C_SDA" ] || [ -z "$I2C_SCL" ]; then
    echo "ERROR: I2C_SDA and I2C_SCL must be set in testconfig for hardware tests."
    exit 1
fi

# --- upload --------------------------------------------------------------
echo "=== Uploading to $PORT ==="
SPEED_OPT=""
[ -n "$UPLOAD_SPEED" ] && SPEED_OPT="--upload-field speed=$UPLOAD_SPEED"
# shellcheck disable=SC2086
arduino-cli upload --fqbn "$FQBN" -p "$PORT" $SPEED_OPT "$SKETCH"
echo "Upload OK"

# --- read serial output --------------------------------------------------
echo "=== Running tests (timeout ${SERIAL_TIMEOUT}s) ==="
python3 "$SCRIPT_DIR/read_serial.py" "$PORT" "$SERIAL_TIMEOUT"

#!/usr/bin/env bash
# Usage:
#   ./test.sh <category>/<chip>          # run on MicroPython board
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

MP_PORT="${MP_PORT:-auto}"
MP_I2C_ID="${MP_I2C_ID:-0}"
MP_SDA="${MP_SDA:-}"
MP_SCL="${MP_SCL:-}"
MP_I2C_FREQ="${MP_I2C_FREQ:-400000}"
I2C_ADDR="${I2C_ADDR:-0x40}"

# --- parse args ----------------------------------------------------------
TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    echo "Usage: $0 <category>/<chip>"
    echo "  e.g. $0 power/ina226"
    exit 1
fi

CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"
TEST_FILE="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test.py"

if [ ! -f "$TEST_FILE" ]; then
    echo "ERROR: test file not found: $TEST_FILE"
    exit 1
fi

# --- validate config -----------------------------------------------------
if [ -z "$MP_SDA" ] || [ -z "$MP_SCL" ]; then
    echo "ERROR: MP_SDA and MP_SCL must be set in testconfig."
    exit 1
fi

# --- generate _testconfig.py (imported by the test script on the board) --
cat > "$SCRIPT_DIR/_testconfig.py" << EOF
I2C_ID = $MP_I2C_ID
SDA    = $MP_SDA
SCL    = $MP_SCL
FREQ   = $MP_I2C_FREQ
ADDR   = $I2C_ADDR
EOF

# --- run -----------------------------------------------------------------
echo "=== Running $TARGET on MicroPython ($MP_PORT) ==="
OUTPUT=$(mpremote connect "$MP_PORT" mount "$SCRIPT_DIR" run "$TEST_FILE" 2>&1)
echo "$OUTPUT"

PASSED=$(echo "$OUTPUT" | grep -c '^PASS ' || true)
FAILED=$(echo "$OUTPUT" | grep -c '^FAIL ' || true)

if echo "$OUTPUT" | grep -q '===DONE'; then
    [ "$FAILED" -eq 0 ] && exit 0 || exit 1
else
    echo "ERROR: test did not complete (===DONE not seen)"
    exit 2
fi

#!/usr/bin/env bash
# Usage:
#   ./test.sh <category>/<chip>
#
# Runs a Node.js hardware test on the host using the i2c-bus package.
# Reads testconfig from the same directory if present.

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

I2C_BUS="${I2C_BUS:-1}"
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
TEST_FILE="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test.js"

if [ ! -f "$TEST_FILE" ]; then
    echo "ERROR: test file not found: $TEST_FILE"
    exit 1
fi

# --- run -----------------------------------------------------------------
echo "=== Running $TARGET on Node.js (I2C bus $I2C_BUS) ==="
I2C_BUS="$I2C_BUS" I2C_ADDR="$I2C_ADDR" node "$TEST_FILE"

#!/usr/bin/env bash
# Usage:
#   ./test_linux.sh <category>/<chip>
#
# Runs a Linux kernel I2C test directly on the host using smbus2.

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

LINUX_I2C_BUS="${LINUX_I2C_BUS:-1}"
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
TEST_FILE="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_linux.py"

if [ ! -f "$TEST_FILE" ]; then
    echo "ERROR: test file not found: $TEST_FILE"
    exit 1
fi

# --- run -----------------------------------------------------------------
echo "=== Running $TARGET on Linux I2C bus $LINUX_I2C_BUS ==="
PYTHONPATH="$SCRIPT_DIR" LINUX_I2C_BUS="$LINUX_I2C_BUS" I2C_ADDR="$I2C_ADDR" python3 "$TEST_FILE"

#!/usr/bin/env bash
# Usage:
#   ./test_linux.sh [--compile-only] <category>/<chip>
#
# Builds the Linux GCC test with g++ and runs it on the host.
# No hardware upload — the binary talks to /dev/i2c-N directly.

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
COMPILE_ONLY=0
if [ "${1:-}" = "--compile-only" ]; then
    COMPILE_ONLY=1; shift
fi

TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    echo "Usage: $0 [--compile-only] <category>/<chip>"
    echo "  e.g. $0 power/ina226"
    exit 1
fi

CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

TEST_SRC="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_linux/${CHIP}_test_linux.cpp"
if [ ! -f "$TEST_SRC" ]; then
    echo "ERROR: test source not found: $TEST_SRC"
    exit 1
fi

SRC_DIR="$SCRIPT_DIR/src"
TRANSPORT_SRC="$SRC_DIR/transport/I2CTransportLinux.cpp"

CHIP_SRC=$(find "$SRC_DIR/chips/$CATEGORY" -maxdepth 1 -iname "${CHIP}.cpp" | head -1)
if [ -z "$CHIP_SRC" ]; then
    echo "ERROR: no chip source found for $CATEGORY/$CHIP in $SRC_DIR/chips/$CATEGORY"
    exit 1
fi

BUILD_DIR=$(mktemp -d)
trap 'rm -rf "$BUILD_DIR"' EXIT
BIN="$BUILD_DIR/${CHIP}_test"

# --- compile -------------------------------------------------------------
echo "=== Compiling $TARGET for Linux GCC ==="
g++ -std=c++17 \
    -I"$SRC_DIR/transport" \
    -I"$SRC_DIR/chips/$CATEGORY" \
    -DTEST_I2C_BUS="$LINUX_I2C_BUS" \
    -DTEST_ADDR="$I2C_ADDR" \
    "$TEST_SRC" "$TRANSPORT_SRC" "$CHIP_SRC" \
    -o "$BIN"
echo "Compile OK"

[ "$COMPILE_ONLY" -eq 1 ] && exit 0

# --- run -----------------------------------------------------------------
echo "=== Running tests on /dev/i2c-$LINUX_I2C_BUS ==="
"$BIN"

#!/usr/bin/env bash
# Usage:
#   ./test.sh <category>/<chip> [--lang java|kotlin|groovy]
#
# Runs a JVM (jbang) hardware test on the Pi via Pi4J.
# Reads testconfig from the same directory if present.
# Defaults to Java; pass --lang kotlin or --lang groovy for the other variants.

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

# --- parse args ----------------------------------------------------------
TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    echo "Usage: $0 <category>/<chip> [--lang java|kotlin|groovy]"
    echo "  e.g. $0 adc_dac/mcp4725"
    echo "       $0 adc_dac/mcp4725 --lang kotlin"
    exit 1
fi

LANG_OPT="java"
if [ "${2:-}" = "--lang" ]; then
    LANG_OPT="${3:-java}"
fi

CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

# --- resolve I2C address -------------------------------------------------
if [ -z "${I2C_ADDR:-}" ]; then
    I2C_ADDR=$(awk -v c="$CHIP" '$1==c{print $2; exit}' "$SCRIPT_DIR/../chip_defaults" 2>/dev/null || true)
    if [ -z "${I2C_ADDR:-}" ]; then
        echo "ERROR: I2C_ADDR not set in testconfig and no default found for '$CHIP' in chip_defaults" >&2
        exit 1
    fi
fi

# --- derive class name and extension: mcp4725 → Mcp4725Test --------------
CHIP_CLASS="$(echo "${CHIP:0:1}" | tr '[:lower:]' '[:upper:]')${CHIP:1}Test"

case "$LANG_OPT" in
    kotlin|kt) EXT="kt" ;;
    groovy)    EXT="groovy" ;;
    *)         EXT="java" ;;
esac

TEST_FILE="$SCRIPT_DIR/tests/$CATEGORY/$CHIP/${CHIP_CLASS}.${EXT}"

if [ ! -f "$TEST_FILE" ]; then
    echo "ERROR: test file not found: $TEST_FILE"
    exit 1
fi

# --- run -----------------------------------------------------------------
echo "=== Running $TARGET on JVM/$LANG_OPT / Pi4J (I2C bus $I2C_BUS, addr $I2C_ADDR) ==="
I2C_BUS="$I2C_BUS" I2C_ADDR="$I2C_ADDR" jbang "$TEST_FILE"

#!/usr/bin/env bash
# Usage:
#   ./test.sh <category>/<chip> [--lang java|kotlin|groovy]
#
# Runs a JVM (jbang) hardware test on the Pi via Linux FFM (no Pi4J).
# Reads testconfig from the same directory if present.
# Defaults to Java; pass --lang kotlin or --lang groovy for the other variants.
#
# Transport is detected automatically:
#   I2C     — chip found in ../chip_defaults
#   NeoPixel/SPI — chip found in chip_spi_defaults (same directory as this script)

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
    echo "       $0 led/ws2812b"
    echo "       $0 adc_dac/mcp4725 --lang kotlin"
    exit 1
fi

LANG_OPT="java"
if [ "${2:-}" = "--lang" ]; then
    LANG_OPT="${3:-java}"
fi

CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

# --- detect transport ----------------------------------------------------
I2C_ADDR_DEFAULT=$(awk -v c="$CHIP" '!/^#/ && $1==c{print $2; exit}' \
    "$SCRIPT_DIR/../chip_defaults" 2>/dev/null || true)

if [ -n "$I2C_ADDR_DEFAULT" ]; then
    TRANSPORT="i2c"
    I2C_ADDR="${I2C_ADDR:-$I2C_ADDR_DEFAULT}"
else
    SPI_LINE=$(awk -v c="$CHIP" '!/^#/ && $1==c{print; exit}' \
        "$SCRIPT_DIR/chip_spi_defaults" 2>/dev/null || true)
    if [ -z "$SPI_LINE" ]; then
        echo "ERROR: '$CHIP' not found in ../chip_defaults (I2C) or chip_spi_defaults (SPI)." >&2
        echo "       Set I2C_ADDR in testconfig, or add the chip to chip_spi_defaults." >&2
        exit 1
    fi
    TRANSPORT=$(echo "$SPI_LINE" | awk '{print $2}')
    SPI_BUS="${SPI_BUS:-$(echo "$SPI_LINE" | awk '{print $3}')}"
    SPI_DEVICE="${SPI_DEVICE:-$(echo "$SPI_LINE" | awk '{print $4}')}"
    PIXEL_COUNT="${PIXEL_COUNT:-$(echo "$SPI_LINE" | awk '{print $5}')}"
fi

# --- find test file (glob avoids brittle name-casing logic) --------------
case "$LANG_OPT" in
    kotlin|kt) EXT="kt" ;;
    groovy)    EXT="groovy" ;;
    *)         EXT="java" ;;
esac

TEST_FILE=$(find "$SCRIPT_DIR/tests/$CATEGORY/$CHIP/" -name "*Test.${EXT}" 2>/dev/null | head -1)

if [ -z "$TEST_FILE" ]; then
    echo "ERROR: no *Test.${EXT} file found under tests/$CATEGORY/$CHIP/"
    exit 1
fi

# --- run -----------------------------------------------------------------
if [ "$TRANSPORT" = "i2c" ]; then
    echo "=== Running $TARGET on JVM/$LANG_OPT (I2C bus $I2C_BUS, addr $I2C_ADDR) ==="
    I2C_BUS="$I2C_BUS" I2C_ADDR="$I2C_ADDR" jbang "$TEST_FILE"
else
    echo "=== Running $TARGET on JVM/$LANG_OPT (SPI bus $SPI_BUS, device $SPI_DEVICE, pixels $PIXEL_COUNT) ==="
    SPI_BUS="$SPI_BUS" SPI_DEVICE="$SPI_DEVICE" PIXEL_COUNT="$PIXEL_COUNT" jbang "$TEST_FILE"
fi

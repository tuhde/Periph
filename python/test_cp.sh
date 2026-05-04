#!/usr/bin/env bash
# Usage:
#   ./test_cp.sh <category>/<chip>
#
# Copies the periph library to the CIRCUITPY drive, runs the test via raw REPL,
# then cleans up.  Requires the CIRCUITPY USB drive to be mounted.
#
# Note: ampy is not compatible with CircuitPython 10+ (status bar breaks raw REPL).
# This script uses direct filesystem access + cp_runner.py instead.

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

CP_PORT="${CP_PORT:-auto}"
CP_SDA="${CP_SDA:-board.SDA}"
CP_SCL="${CP_SCL:-board.SCL}"
CP_I2C_FREQ="${CP_I2C_FREQ:-400000}"
I2C_ADDR="${I2C_ADDR:-0x40}"

# Resolve 'auto' to first available ACM/USB port
if [ "$CP_PORT" = "auto" ]; then
    CP_PORT=$(find /dev -maxdepth 1 \( -name 'ttyACM*' -o -name 'ttyUSB*' \) 2>/dev/null | sort | head -1)
    if [ -z "$CP_PORT" ]; then
        echo "ERROR: no serial port found; set CP_PORT in testconfig"
        exit 1
    fi
    echo "Auto-detected port: $CP_PORT"
fi

# --- locate CIRCUITPY mount ----------------------------------------------
CIRCUITPY=$(findmnt -t vfat -n -o TARGET 2>/dev/null | grep -i circuit | head -1 || true)
if [ -z "$CIRCUITPY" ]; then
    echo "ERROR: CIRCUITPY drive not found. Connect a CircuitPython board via USB."
    exit 1
fi
echo "CIRCUITPY mount: $CIRCUITPY"

# --- parse args ----------------------------------------------------------
TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    echo "Usage: $0 <category>/<chip>"
    echo "  e.g. $0 power/ina226"
    exit 1
fi

CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"
TEST_FILE="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_cp.py"

if [ ! -f "$TEST_FILE" ]; then
    echo "ERROR: test file not found: $TEST_FILE"
    exit 1
fi

# --- ensure lib directory exists on board --------------------------------
mkdir -p "$CIRCUITPY/lib"

# --- copy periph library -------------------------------------------------
echo "=== Copying periph library to $CIRCUITPY/lib/periph ==="
rm -rf "$CIRCUITPY/lib/periph"
cp -r "$SCRIPT_DIR/periph" "$CIRCUITPY/lib/periph"
# Remove __pycache__ if any
find "$CIRCUITPY/lib/periph" -name '__pycache__' -exec rm -rf {} + 2>/dev/null || true

# --- generate and copy _testconfig.py ------------------------------------
cat > "$CIRCUITPY/_testconfig.py" << EOF
import board
SDA  = $CP_SDA
SCL  = $CP_SCL
FREQ = $CP_I2C_FREQ
ADDR = $I2C_ADDR
EOF

# Flush writes before accessing via REPL
sync

# --- run test via raw REPL -----------------------------------------------
echo "=== Running $TARGET on CircuitPython ($CP_PORT) ==="
python3 "$SCRIPT_DIR/cp_runner.py" "$CP_PORT" "$TEST_FILE"

# --- cleanup -------------------------------------------------------------
rm -rf "$CIRCUITPY/lib/periph"
rm -f "$CIRCUITPY/_testconfig.py"
sync

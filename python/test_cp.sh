#!/usr/bin/env bash
# Usage:
#   ./test_cp.sh [--board NAME] [--level hil|conformance] <category>/<chip>
#   ./test_cp.sh --board NAME [--level ...]   # self-test every chip on that board
#
# Copies the periph library to the CIRCUITPY drive, runs the test via raw REPL,
# then cleans up.  Requires the CIRCUITPY USB drive to be mounted.
#
# Note: ampy is not compatible with CircuitPython 10+ (status bar breaks raw REPL).
# This script uses direct filesystem access + cp_runner.py instead.
#
# Auto-detects hil vs conformance (sigrok configured + board present ->
# conformance; board present -> hil). There is no unit level here:
# CircuitPython runs the exact same chip-driver source as test_linux.sh's
# host run, so unit coverage lives there once, not duplicated per embedded
# platform.
#
# --board selects a committed, shared board profile (python/boards/<name>.conf)
# instead of the private testconfig free-wire bench config - see
# specs/testing_framework.md, "Test Scenarios: Fixed Board vs Free-Wire
# Bench".

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# --- parse args ------------------------------------------------------------
BOARD=""
LEVEL=""
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --board) BOARD="${2:-}"; shift 2 ;;
        --level) LEVEL="${2:-}"; shift 2 ;;
        *) ARGS+=("$1"); shift ;;
    esac
done
set -- "${ARGS[@]:-}"

TARGET="${1:-}"

# --- --board with no target: self-test every chip on that board ------------
if [ -n "$BOARD" ] && [ -z "$TARGET" ]; then
    BOARD_PROFILE="$SCRIPT_DIR/boards/${BOARD}.conf"
    if [ ! -f "$BOARD_PROFILE" ]; then
        echo "ERROR: board profile not found: $BOARD_PROFILE" >&2
        exit 1
    fi
    BOARD_CHIPS=()
    # shellcheck source=/dev/null
    source "$BOARD_PROFILE"
    if [ "${#BOARD_CHIPS[@]}" -eq 0 ]; then
        echo "ERROR: $BOARD_PROFILE does not declare BOARD_CHIPS" >&2
        exit 1
    fi
    OVERALL=0
    for chip_target in "${BOARD_CHIPS[@]}"; do
        echo "=== Board $BOARD: testing $chip_target ==="
        child_args=(--board "$BOARD")
        [ -n "$LEVEL" ] && child_args+=(--level "$LEVEL")
        child_args+=("$chip_target")
        "$0" "${child_args[@]}" || OVERALL=1
    done
    exit "$OVERALL"
fi

if [ -z "$TARGET" ]; then
    echo "Usage: $0 [--board NAME] [--level hil|conformance] <category>/<chip>"
    echo "  e.g. $0 power/ina226"
    echo "       $0 --board esp32s3-sensor-devkit"
    exit 1
fi

# Parse the target BEFORE sourcing config, so the per-chip wiring/board case
# block (keyed on $CATEGORY/$CHIP) has both variables available when it runs.
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

# --- load local config ---------------------------------------------------
CONFIG="$SCRIPT_DIR/testconfig"
if [ -f "$CONFIG" ]; then
    # shellcheck source=/dev/null
    source "$CONFIG"
else
    echo "WARNING: $CONFIG not found. Using defaults — copy testconfig.example to testconfig."
fi

if [ -n "$BOARD" ]; then
    BOARD_PROFILE="$SCRIPT_DIR/boards/${BOARD}.conf"
    if [ ! -f "$BOARD_PROFILE" ]; then
        echo "ERROR: board profile not found: $BOARD_PROFILE" >&2
        exit 1
    fi
    # shellcheck source=/dev/null
    source "$BOARD_PROFILE"
fi

CP_PORT="${CP_PORT:-auto}"
CP_SDA="${CP_SDA:-board.SDA}"
CP_SCL="${CP_SCL:-board.SCL}"
CP_I2C_FREQ="${CP_I2C_FREQ:-400000}"

# --- resolve I2C address (env/testconfig/board win; chip_defaults falls back)
if [ -z "${I2C_ADDR:-}" ]; then
    I2C_ADDR=$(awk -v c="$CHIP" '!/^#/ && $1==c{print $2; exit}' "$SCRIPT_DIR/../chip_defaults" 2>/dev/null || true)
fi
I2C_ADDR="${I2C_ADDR:-0x40}"

# Resolve 'auto' to first available ACM/USB port
if [ "$CP_PORT" = "auto" ]; then
    CP_PORT=$(find /dev -maxdepth 1 \( -name 'ttyACM*' -o -name 'ttyUSB*' \) 2>/dev/null | sort | head -1)
fi

TEST_FILE="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_cp.py"

if [ ! -f "$TEST_FILE" ]; then
    echo "ERROR: test file not found: $TEST_FILE"
    exit 1
fi

# --- helpers -----------------------------------------------------------------
find_tool() {
    local name="$1"
    if command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
    elif [ -x "/usr/sbin/$name" ]; then
        echo "/usr/sbin/$name"
    fi
}

detect_sigrok() {
    local sigrok_cli
    sigrok_cli=$(find_tool sigrok-cli) || true
    [ -z "$sigrok_cli" ] && return 1
    if [ -n "${SIGROK_DRIVER:-}" ]; then
        "$sigrok_cli" --driver "${SIGROK_DRIVER}${SIGROK_CONN:+:conn=$SIGROK_CONN}" --scan 2>/dev/null | grep -q .
    else
        "$sigrok_cli" --scan 2>/dev/null | grep -q .
    fi
}

# The CIRCUITPY mass-storage mount is the hardware-present signal for
# CircuitPython (it only appears once a CircuitPython board is plugged in and
# has enumerated), mirroring test_arduino.sh's PORT existence check.
detect_board() {
    findmnt -t vfat -n -o TARGET 2>/dev/null | grep -qi circuit
}

# --- detect level (no unit fallback - embedded has nothing to fall back to) -
if [ -n "$LEVEL" ]; then
    EFFECTIVE_LEVEL="$LEVEL"
elif detect_board; then
    if detect_sigrok; then EFFECTIVE_LEVEL="conformance"; else EFFECTIVE_LEVEL="hil"; fi
else
    echo "ERROR: no CircuitPython board detected (CIRCUITPY drive not mounted) and no --level given." >&2
    echo "       Connect a CircuitPython board via USB." >&2
    exit 1
fi

if [ -z "$CP_PORT" ]; then
    echo "ERROR: no serial port found; set CP_PORT in testconfig or --board profile" >&2
    exit 1
fi

# --- locate CIRCUITPY mount ----------------------------------------------
CIRCUITPY=$(findmnt -t vfat -n -o TARGET 2>/dev/null | grep -i circuit | head -1 || true)
if [ -z "$CIRCUITPY" ]; then
    echo "ERROR: CIRCUITPY drive not found. Connect a CircuitPython board via USB."
    exit 1
fi
echo "CIRCUITPY mount: $CIRCUITPY"

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

case "$EFFECTIVE_LEVEL" in
    hil)
        echo "=== [hil] Running $TARGET on CircuitPython ($CP_PORT) ==="
        python3 "$SCRIPT_DIR/cp_runner.py" "$CP_PORT" "$TEST_FILE"
        ;;
    conformance)
        CHECKER="$SCRIPT_DIR/../conformance/$CATEGORY/${CHIP}_conformance.py"
        if [ ! -f "$CHECKER" ]; then
            echo "ERROR: conformance checker not found: $CHECKER" >&2
            echo "       (no conformance implementation yet for $CATEGORY/$CHIP)" >&2
            rm -rf "$CIRCUITPY/lib/periph"
            rm -f "$CIRCUITPY/_testconfig.py"
            exit 1
        fi
        echo "=== [conformance] Running $TARGET via $CHECKER ==="
        SIGROK_DRIVER="${SIGROK_DRIVER:-}" SIGROK_CONN="${SIGROK_CONN:-}" SIGROK_CHANNELS="${SIGROK_CHANNELS:-}" \
            python3 "$CHECKER" --lang circuitpython --cp-port "$CP_PORT" --cp-test "$TEST_FILE"
        ;;
    *)
        echo "ERROR: unknown --level '$EFFECTIVE_LEVEL' (expected hil|conformance)" >&2
        exit 1
        ;;
esac

# --- cleanup -------------------------------------------------------------
rm -rf "$CIRCUITPY/lib/periph"
rm -f "$CIRCUITPY/_testconfig.py"
sync

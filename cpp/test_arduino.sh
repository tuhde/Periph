#!/usr/bin/env bash
# Usage:
#   ./test_arduino.sh [--board NAME] [--level hil|conformance] [--compile-only] <category>/<chip>
#   ./test_arduino.sh --board NAME [--level ...] [--compile-only]   # self-test every chip on that board
#
# Compiles (and, unless --compile-only, uploads + reads serial output for)
# the Arduino test sketch.
#
# Auto-detects hil vs conformance (sigrok configured + board present ->
# conformance; board present -> hil). There is no unit level here: Arduino
# runs the exact same chip-driver source as test_linux.sh's host build, so
# unit coverage lives there once, not duplicated per embedded platform.
#
# --board selects a committed, shared board profile (cpp/boards/<name>.conf)
# instead of the private testconfig/testconfig_wiring free-wire bench config
# - see specs/testing_framework.md, "Test Scenarios: Fixed Board vs
# Free-Wire Bench".

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# --- parse args ------------------------------------------------------------
BOARD=""
LEVEL=""
COMPILE_ONLY=0
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --board) BOARD="${2:-}"; shift 2 ;;
        --level) LEVEL="${2:-}"; shift 2 ;;
        --compile-only) COMPILE_ONLY=1; shift ;;
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
        [ "$COMPILE_ONLY" -eq 1 ] && child_args+=(--compile-only)
        child_args+=("$chip_target")
        "$0" "${child_args[@]}" || OVERALL=1
    done
    exit "$OVERALL"
fi

if [ -z "$TARGET" ]; then
    echo "Usage: $0 [--board NAME] [--level hil|conformance] [--compile-only] <category>/<chip>"
    echo "  e.g. $0 power/ina226"
    echo "       $0 --board esp32s3-sensor-devkit"
    exit 1
fi

# Parse the target BEFORE sourcing config, so the per-chip wiring/board case
# block (keyed on $CATEGORY/$CHIP) has both variables available when it runs.
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

# --- load local config ------------------------------------------------------
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
else
    WIRING="$SCRIPT_DIR/testconfig_wiring"
    if [ -f "$WIRING" ]; then
        # shellcheck source=/dev/null
        source "$WIRING"
    fi
fi

FQBN="${FQBN:-esp32:esp32:esp32s3}"
PORT="${PORT:-/dev/ttyACM0}"
UPLOAD_SPEED="${UPLOAD_SPEED:-}"
SERIAL_TIMEOUT="${SERIAL_TIMEOUT:-15}"
I2C_SDA="${I2C_SDA:-}"
I2C_SCL="${I2C_SCL:-}"
I2C_FREQ="${I2C_FREQ:-400000}"

# --- resolve I2C address (env/testconfig/board win; chip_defaults falls back)
if [ -z "${I2C_ADDR:-}" ]; then
    I2C_ADDR=$(awk -v c="$CHIP" '!/^#/ && $1==c{print $2; exit}' "$SCRIPT_DIR/../chip_defaults" 2>/dev/null || true)
fi

SKETCH="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test"
if [ ! -d "$SKETCH" ]; then
    echo "ERROR: sketch not found: $SKETCH"
    exit 1
fi

# --- build-properties for pin/address defines -------------------------------
EXTRA_FLAGS=""
[ -n "$I2C_SDA"       ] && EXTRA_FLAGS="$EXTRA_FLAGS -DTEST_SDA=$I2C_SDA"
[ -n "$I2C_SCL"       ] && EXTRA_FLAGS="$EXTRA_FLAGS -DTEST_SCL=$I2C_SCL"
[ -n "$I2C_FREQ"      ] && EXTRA_FLAGS="$EXTRA_FLAGS -DTEST_I2C_FREQ=$I2C_FREQ"
[ -n "${I2C_ADDR:-}"  ] && EXTRA_FLAGS="$EXTRA_FLAGS -DTEST_ADDR=$I2C_ADDR"

BUILD_PROPS=()
[ -n "$EXTRA_FLAGS" ] && BUILD_PROPS=(--build-property "compiler.cpp.extra_flags=$EXTRA_FLAGS")

# --- compile -------------------------------------------------------------
echo "=== Compiling $TARGET for $FQBN ==="
arduino-cli compile --fqbn "$FQBN" \
    --library "$SCRIPT_DIR/src/connection" \
    --library "$SCRIPT_DIR/src/chips/$CATEGORY" \
    "${BUILD_PROPS[@]}" "$SKETCH"
echo "Compile OK"

[ "$COMPILE_ONLY" -eq 1 ] && exit 0

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

# --- detect level (no unit fallback - embedded has nothing to fall back to) -
if [ -n "$LEVEL" ]; then
    EFFECTIVE_LEVEL="$LEVEL"
elif [ -e "$PORT" ]; then
    if detect_sigrok; then EFFECTIVE_LEVEL="conformance"; else EFFECTIVE_LEVEL="hil"; fi
else
    echo "ERROR: no board detected on $PORT (and no --level given)." >&2
    echo "       Connect the board, or use --compile-only to just verify the build." >&2
    exit 1
fi

# --- validate hardware config -------------------------------------------
if [ -z "$I2C_SDA" ] || [ -z "$I2C_SCL" ]; then
    echo "ERROR: I2C_SDA and I2C_SCL must be set (testconfig, testconfig_wiring, or --board profile)." >&2
    exit 1
fi

# --- upload --------------------------------------------------------------
echo "=== Uploading to $PORT ==="
SPEED_OPT=""
[ -n "$UPLOAD_SPEED" ] && SPEED_OPT="--upload-field speed=$UPLOAD_SPEED"
# shellcheck disable=SC2086
arduino-cli upload --fqbn "$FQBN" -p "$PORT" $SPEED_OPT "$SKETCH"
echo "Upload OK"

case "$EFFECTIVE_LEVEL" in
    hil)
        echo "=== [hil] Running tests (timeout ${SERIAL_TIMEOUT}s) ==="
        python3 "$SCRIPT_DIR/read_serial.py" "$PORT" "$SERIAL_TIMEOUT"
        ;;
    conformance)
        CHECKER="$SCRIPT_DIR/../conformance/$CATEGORY/${CHIP}_conformance.py"
        if [ ! -f "$CHECKER" ]; then
            echo "ERROR: conformance checker not found: $CHECKER" >&2
            echo "       (no conformance implementation yet for $CATEGORY/$CHIP)" >&2
            exit 1
        fi
        echo "=== [conformance] Running via $CHECKER ==="
        python3 "$CHECKER" --lang cpp-arduino --port "$PORT" --serial-timeout "$SERIAL_TIMEOUT"
        ;;
    *)
        echo "ERROR: unknown --level '$EFFECTIVE_LEVEL' (expected hil|conformance)" >&2
        exit 1
        ;;
esac

#!/usr/bin/env bash
# Usage:
#   ./test_tinygo.sh [--board NAME] [--level hil|conformance] [--compile-only] <category>/<chip>
#   ./test_tinygo.sh --board NAME [--level ...] [--compile-only]   # self-test every chip on that board
#
# Requires: tinygo (>= 0.41), picotool (or the Pico W in BOOTSEL mode mounted
#           as a USB drive), pyserial
# Config:   go/testconfig_tinygo (copy from testconfig_tinygo.example)
#
# The runner builds the test for the Raspberry Pi Pico W target, flashes
# the resulting UF2 to the board, then reads serial output until the
# ===DONE: ... === line appears. Exits 0 on full pass, 1 on any
# failure, 2 on timeout.
#
# Auto-detects hil vs conformance (sigrok configured + board present ->
# conformance; board present -> hil). There is no unit level here: TinyGo
# runs the exact same chip-driver source as test_linux.sh's host build, so
# unit coverage lives there once, not duplicated per embedded platform.
#
# --board selects a committed, shared board profile (go/boards/<name>.conf)
# instead of the private testconfig_tinygo/testconfig_wiring free-wire bench
# config - see specs/testing_framework.md, "Test Scenarios: Fixed Board vs
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

TARGET="${1:?Usage: $0 [--board NAME] [--level hil|conformance] [--compile-only] <category/chip>}"
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

TESTCONFIG="$SCRIPT_DIR/testconfig_tinygo"
if [[ -f "$TESTCONFIG" ]]; then
    # shellcheck source=/dev/null
    source "$TESTCONFIG"
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

UF2_MOUNT="${UF2_MOUNT:-/media/$USER/RPI-RP2}"
SERIAL_PORT="${SERIAL_PORT:-/dev/ttyACM0}"
SERIAL_TIMEOUT="${SERIAL_TIMEOUT:-20}"

TEST_DIR="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_tinygo"
if [[ ! -d "$TEST_DIR" ]]; then
    echo "Error: test not found: $TEST_DIR" >&2; exit 2
fi

UF2="$(mktemp --suffix=.uf2)"
trap 'rm -f "$UF2"' EXIT

echo "Building $TARGET for pico-w..."
(cd "$SCRIPT_DIR" && tinygo build -target=pico-w -o "$UF2" "./tests/$CATEGORY/${CHIP}_test_tinygo")

if [[ "$COMPILE_ONLY" -eq 1 ]]; then
    echo "Compile-only: done."; exit 0
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

# --- detect level (no unit fallback - embedded has nothing to fall back to) -
# A Pico W already running our firmware shows up as $SERIAL_PORT, not the
# BOOTSEL mass-storage mount (that only appears pre-flash) - so the serial
# port is the "board present" signal, matching test_arduino.sh's PORT check.
if [ -n "$LEVEL" ]; then
    EFFECTIVE_LEVEL="$LEVEL"
elif [ -e "$SERIAL_PORT" ] || [ -d "$UF2_MOUNT" ]; then
    if detect_sigrok; then EFFECTIVE_LEVEL="conformance"; else EFFECTIVE_LEVEL="hil"; fi
else
    echo "ERROR: no Pico W detected ($SERIAL_PORT absent, $UF2_MOUNT absent) and no --level given." >&2
    echo "       Connect the board (hold BOOTSEL while plugging in), or use --compile-only." >&2
    exit 1
fi

if [[ ! -d "$UF2_MOUNT" ]]; then
    echo "Error: Pico W UF2 mount not found at $UF2_MOUNT (hold BOOTSEL while plugging in)" >&2
    exit 2
fi

echo "Flashing..."
cp "$UF2" "$UF2_MOUNT/"

case "$EFFECTIVE_LEVEL" in
    hil)
        echo "=== [hil] Reading output ==="
        python3 "$SCRIPT_DIR/read_serial_tinygo.py" "$SERIAL_PORT" 115200 "$SERIAL_TIMEOUT"
        ;;
    conformance)
        CHECKER="$SCRIPT_DIR/../conformance/$CATEGORY/${CHIP}_conformance.py"
        if [ ! -f "$CHECKER" ]; then
            echo "ERROR: conformance checker not found: $CHECKER" >&2
            echo "       (no conformance implementation yet for $CATEGORY/$CHIP)" >&2
            exit 1
        fi
        echo "=== [conformance] Running via $CHECKER ==="
        python3 "$CHECKER" --lang tinygo --port "$SERIAL_PORT" --serial-timeout "$SERIAL_TIMEOUT"
        ;;
    *)
        echo "ERROR: unknown --level '$EFFECTIVE_LEVEL' (expected hil|conformance)" >&2
        exit 1
        ;;
esac

#!/usr/bin/env bash
# Usage:
#   ./test_zephyr.sh [--board NAME] [--level hil|conformance] [--compile-only] <category>/<chip>
#   ./test_zephyr.sh --board NAME [--level ...] [--compile-only]   # self-test every chip on that board
#
# Requires: west, ZEPHYR_BASE set (or a west workspace initialised)
# Config:   cpp/testconfig_zephyr (copy from testconfig_zephyr.example)
#
# Auto-detects hil vs conformance (sigrok configured + board present ->
# conformance; board present -> hil); no unit level - Zephyr runs the exact
# same chip-driver source as test_linux.sh's host build, so unit coverage
# lives there once, not duplicated per embedded platform.
#
# --board selects a committed, shared board profile (cpp/boards/<name>.conf)
# instead of the private testconfig_zephyr/testconfig_wiring free-wire bench
# config - see specs/testing_framework.md, "Test Scenarios".

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
TESTCONFIG="$SCRIPT_DIR/testconfig_zephyr"
if [ -f "$TESTCONFIG" ]; then
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

BOARD_ID="${ZEPHYR_BOARD:-}"
PORT="${ZEPHYR_PORT:-}"

TEST_APP="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_zephyr"
if [ ! -d "$TEST_APP" ]; then
    echo "ERROR: test app not found: $TEST_APP" >&2
    exit 1
fi

# --- resolve I2C address (env/testconfig/board win; chip_defaults falls back)
if [ -z "${I2C_ADDR:-}" ]; then
    I2C_ADDR=$(awk -v c="$CHIP" '!/^#/ && $1==c{print $2; exit}' "$SCRIPT_DIR/../chip_defaults" 2>/dev/null || true)
fi

# --- compile -------------------------------------------------------------
BUILD_DIR="$(mktemp -d)"
trap 'rm -rf "$BUILD_DIR"' EXIT

BUILD_ARGS=(-d "$BUILD_DIR" "$TEST_APP")
[ -n "$BOARD_ID" ] && BUILD_ARGS+=(-b "$BOARD_ID")

# Inject the chip's address via Zephyr's own EXTRA_CFLAGS passthrough - the
# macro name is per-chip (e.g. ENS160_ADDR), matching each test app's
# #ifndef <CHIP>_ADDR guard.
if [ -n "${I2C_ADDR:-}" ]; then
    CHIP_UPPER=$(echo "$CHIP" | tr '[:lower:]' '[:upper:]')
    BUILD_ARGS+=(-- "-DEXTRA_CFLAGS=-D${CHIP_UPPER}_ADDR=${I2C_ADDR}")
fi

echo "=== Building $TARGET for ${BOARD_ID:-<default board>} ==="
west build "${BUILD_ARGS[@]}"
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
elif [ -n "$PORT" ] && [ -e "$PORT" ]; then
    if detect_sigrok; then EFFECTIVE_LEVEL="conformance"; else EFFECTIVE_LEVEL="hil"; fi
else
    echo "ERROR: no board detected on '${PORT:-<unset ZEPHYR_PORT>}' (and no --level given)." >&2
    echo "       Connect the board, or use --compile-only to just verify the build." >&2
    exit 1
fi

# --- flash -----------------------------------------------------------------
echo "=== Flashing ==="
west flash -d "$BUILD_DIR" --esp-device "$PORT"

case "$EFFECTIVE_LEVEL" in
    hil)
        # read_serial_zephyr.py opens the port then resets via RTS so we catch
        # output from the very start of boot, regardless of how fast the
        # board comes up.
        echo "=== [hil] Reading output ==="
        python3 "$SCRIPT_DIR/read_serial_zephyr.py" "$PORT" 115200 "${SERIAL_TIMEOUT:-20}"
        ;;
    conformance)
        CHECKER="$SCRIPT_DIR/../conformance/$CATEGORY/${CHIP}_conformance.py"
        if [ ! -f "$CHECKER" ]; then
            echo "ERROR: conformance checker not found: $CHECKER" >&2
            echo "       (no conformance implementation yet for $CATEGORY/$CHIP)" >&2
            exit 1
        fi
        echo "=== [conformance] Running via $CHECKER ==="
        python3 "$CHECKER" --lang cpp-zephyr --port "$PORT" --serial-timeout "${SERIAL_TIMEOUT:-20}"
        ;;
    *)
        echo "ERROR: unknown --level '$EFFECTIVE_LEVEL' (expected hil|conformance)" >&2
        exit 1
        ;;
esac

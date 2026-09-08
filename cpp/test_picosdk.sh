#!/usr/bin/env bash
# Usage:
#   ./test_picosdk.sh [--board NAME] [--level hil|conformance] [--compile-only] <category>/<chip>
#   ./test_picosdk.sh --board NAME [--level ...] [--compile-only]   # self-test every chip on that board
#
# Builds a bare-metal pico-sdk test app, flashes it via picotool, and
# reads the USB-CDC serial output. Requires:
#   - PICO_SDK_PATH set (or pointed at from testconfig_picosdk)
#   - picotool on PATH
#   - A Raspberry Pi Pico / Pico W in BOOTSEL or running picotool-enabled
#     UF2 firmware
#
# Config:   cpp/testconfig_picosdk (copy from testconfig_picosdk.example)
#
# Auto-detects hil vs conformance (sigrok configured + board present ->
# conformance; board present -> hil); no unit level - the Pico SDK build
# runs the exact same chip-driver source as test_linux.sh's host build, so
# unit coverage lives there once, not duplicated per embedded platform.
#
# --board selects a committed, shared board profile (cpp/boards/<name>.conf)
# instead of the private testconfig_picosdk/testconfig_wiring free-wire
# bench config - see specs/testing_framework.md, "Test Scenarios".

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

TESTCONFIG="$SCRIPT_DIR/testconfig_picosdk"
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

TEST_APP="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_picosdk"
if [[ ! -d "$TEST_APP" ]]; then
    echo "Error: test app not found: $TEST_APP" >&2; exit 2
fi

# --- pick board -----------------------------------------------------------
# Default to pico (non-W); PICO_BOARD in testconfig_picosdk/board profile overrides.
export PICO_BOARD="${PICO_BOARD:-pico}"

BUILD_DIR="$TEST_APP/build"
rm -rf "$BUILD_DIR"
echo "Building $TARGET for $PICO_BOARD..."

# picotool is only needed to sign/embed and flash the UF2; skip requiring it
# for --compile-only runs (e.g. CI) so we don't hit pico-sdk's picotool
# auto-fetch-and-build-from-source path, which needs network access and its
# own working host toolchain setup.
CMAKE_EXTRA_ARGS=()
if [[ "$COMPILE_ONLY" -eq 1 ]]; then
    CMAKE_EXTRA_ARGS+=(-DPICO_NO_PICOTOOL=1)
fi

cmake -S "$TEST_APP" -B "$BUILD_DIR" -DPICO_BOARD="$PICO_BOARD" "${CMAKE_EXTRA_ARGS[@]}"
cmake --build "$BUILD_DIR" -- -j"$(nproc 2>/dev/null || echo 2)"

if [[ "$COMPILE_ONLY" -eq 1 ]]; then
    echo "Compile-only: done."; exit 0
fi

UF2="$BUILD_DIR/${CHIP}_test_picosdk.uf2"
if [[ ! -f "$UF2" ]]; then
    echo "Error: UF2 not found at $UF2" >&2; exit 2
fi

PORT="${PICOSDK_PORT:-}"

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
    echo "ERROR: no board detected (PICOSDK_PORT not set or not present) and no --level given." >&2
    echo "       Connect the board, or use --compile-only to just verify the build." >&2
    exit 1
fi

echo "Flashing $UF2 via picotool on $PORT..."
# picotool load -x flashes the UF2 and reboots the chip into the new firmware
picotool load -x -t bin "$BUILD_DIR/${CHIP}_test_picosdk.elf" 2>/dev/null \
    || picotool load -x "$UF2"

case "$EFFECTIVE_LEVEL" in
    hil)
        # read_serial_picosdk.py opens the port, waits for the board to
        # enumerate as a USB CDC device after the picotool-triggered reset,
        # and reads until ===DONE=== or timeout.
        echo "=== [hil] Reading output ==="
        python3 "$SCRIPT_DIR/read_serial_picosdk.py" "$PORT" "${SERIAL_TIMEOUT:-20}"
        ;;
    conformance)
        CHECKER="$SCRIPT_DIR/../conformance/$CATEGORY/${CHIP}_conformance.py"
        if [ ! -f "$CHECKER" ]; then
            echo "ERROR: conformance checker not found: $CHECKER" >&2
            echo "       (no conformance implementation yet for $CATEGORY/$CHIP)" >&2
            exit 1
        fi
        echo "=== [conformance] Running via $CHECKER ==="
        python3 "$CHECKER" --lang cpp-picosdk --port "$PORT" --serial-timeout "${SERIAL_TIMEOUT:-20}"
        ;;
    *)
        echo "ERROR: unknown --level '$EFFECTIVE_LEVEL' (expected hil|conformance)" >&2
        exit 1
        ;;
esac

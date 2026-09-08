#!/usr/bin/env bash
# Usage:
#   ./test_espidf.sh [--board NAME] [--level hil|conformance] [--compile-only] <category>/<chip>
#   ./test_espidf.sh --board NAME [--level ...] [--compile-only]   # self-test every chip on that board
#
# Builds a native ESP-IDF test app, flashes it via idf.py, and reads
# the USB-CDC serial output. Requires:
#   - IDF_PATH set (or export the relevant ESP-IDF environment)
#   - idf.py on PATH
#   - An ESP32 / ESP32-S3 / ESP32-S2 board in flash mode (USB)
#
# Config:   cpp/testconfig_espidf (copy from testconfig_espidf.example)
#
# Auto-detects hil vs conformance (sigrok configured + board present ->
# conformance; board present -> hil); no unit level - ESP-IDF runs the exact
# same chip-driver source as test_linux.sh's host build, so unit coverage
# lives there once, not duplicated per embedded platform.
#
# --board selects a committed, shared board profile (cpp/boards/<name>.conf)
# instead of the private testconfig_espidf/testconfig_wiring free-wire bench
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

TARGET="${1:?Usage: $0 [--board NAME] [--level hil|conformance] [--compile-only] <category/chip>}"
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

TESTCONFIG="$SCRIPT_DIR/testconfig_espidf"
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

if [[ -z "${IDF_PATH:-}" && "$COMPILE_ONLY" -eq 0 ]]; then
    echo "Error: IDF_PATH not set in testconfig_espidf" >&2; exit 2
fi

TEST_APP="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_espidf"
if [[ ! -d "$TEST_APP" ]]; then
    echo "Error: test app not found: $TEST_APP" >&2; exit 2
fi

# --- pick target ----------------------------------------------------------
# Default to esp32; ESPIDF_TARGET in testconfig_espidf/board profile overrides.
export IDF_TARGET="${ESPIDF_TARGET:-esp32}"

BUILD_DIR="$TEST_APP/build"
rm -rf "$BUILD_DIR"
echo "Building $TARGET for $IDF_TARGET..."
( cd "$TEST_APP" && idf.py -B "$BUILD_DIR" build )

if [[ "$COMPILE_ONLY" -eq 1 ]]; then
    echo "Compile-only: done."; exit 0
fi

PORT="${ESPIDF_PORT:-}"

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
    echo "ERROR: no board detected (ESPIDF_PORT not set or not present) and no --level given." >&2
    echo "       Connect the board, or use --compile-only to just verify the build." >&2
    exit 1
fi

echo "Flashing to $PORT..."
( cd "$TEST_APP" && idf.py -B "$BUILD_DIR" -p "$PORT" flash )

case "$EFFECTIVE_LEVEL" in
    hil)
        # read_serial.py opens the port, waits for the board to enumerate as
        # USB CDC after the idf.py-triggered reset, and reads until ===DONE===
        # or timeout. ESP-IDF's USB-CDC port usually needs a moment after the
        # reset; the same script the Arduino runner uses handles that retry.
        echo "=== [hil] Reading output ==="
        python3 "$SCRIPT_DIR/read_serial.py" "$PORT" "${SERIAL_TIMEOUT:-20}"
        ;;
    conformance)
        CHECKER="$SCRIPT_DIR/../conformance/$CATEGORY/${CHIP}_conformance.py"
        if [ ! -f "$CHECKER" ]; then
            echo "ERROR: conformance checker not found: $CHECKER" >&2
            echo "       (no conformance implementation yet for $CATEGORY/$CHIP)" >&2
            exit 1
        fi
        echo "=== [conformance] Running via $CHECKER ==="
        python3 "$CHECKER" --lang cpp-espidf --port "$PORT" --serial-timeout "${SERIAL_TIMEOUT:-20}"
        ;;
    *)
        echo "ERROR: unknown --level '$EFFECTIVE_LEVEL' (expected hil|conformance)" >&2
        exit 1
        ;;
esac

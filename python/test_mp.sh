#!/usr/bin/env bash
# Usage:
#   ./test_mp.sh [--board NAME] [--level hil|conformance] <category>/<chip>
#   ./test_mp.sh --board NAME [--level ...]   # self-test every chip on that board
#
# Runs the MicroPython test on a real board over mpremote.
#
# Auto-detects hil vs conformance (sigrok configured + board present ->
# conformance; board present -> hil). There is no unit level here:
# MicroPython runs the exact same chip-driver source as test_linux.sh's host
# run, so unit coverage lives there once, not duplicated per embedded
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

MP_PORT="${MP_PORT:-auto}"
MP_I2C_ID="${MP_I2C_ID:-0}"
MP_SDA="${MP_SDA:-}"
MP_SCL="${MP_SCL:-}"
MP_I2C_FREQ="${MP_I2C_FREQ:-400000}"

# --- resolve I2C address (env/testconfig/board win; chip_defaults falls back)
if [ -z "${I2C_ADDR:-}" ]; then
    I2C_ADDR=$(awk -v c="$CHIP" '!/^#/ && $1==c{print $2; exit}' "$SCRIPT_DIR/../chip_defaults" 2>/dev/null || true)
fi
I2C_ADDR="${I2C_ADDR:-0x40}"

TEST_FILE="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test.py"

if [ ! -f "$TEST_FILE" ]; then
    echo "ERROR: test file not found: $TEST_FILE"
    exit 1
fi

# --- validate config -----------------------------------------------------
if [ -z "$MP_SDA" ] || [ -z "$MP_SCL" ]; then
    echo "ERROR: MP_SDA and MP_SCL must be set (testconfig or --board profile)." >&2
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

# For an explicit port, check it exists (matching test_arduino.sh's PORT
# existence check). For "auto", `mpremote connect list` also lists plain
# non-USB serial ports (ttySN, VID:PID 0000:0000) that are never a real
# board, so require an actual USB VID:PID to count as "board present".
detect_board() {
    if [ "$MP_PORT" = "auto" ]; then
        mpremote connect list 2>/dev/null | awk '{print $3}' | grep -qv '^0000:0000$'
    else
        [ -e "$MP_PORT" ]
    fi
}

# --- detect level (no unit fallback - embedded has nothing to fall back to) -
if [ -n "$LEVEL" ]; then
    EFFECTIVE_LEVEL="$LEVEL"
elif detect_board; then
    if detect_sigrok; then EFFECTIVE_LEVEL="conformance"; else EFFECTIVE_LEVEL="hil"; fi
else
    echo "ERROR: no board detected on $MP_PORT (and no --level given)." >&2
    echo "       Connect the board, or set MP_PORT/testconfig." >&2
    exit 1
fi

# --- generate _testconfig.py (imported by the test script on the board) --
cat > "$SCRIPT_DIR/_testconfig.py" << EOF
I2C_ID = $MP_I2C_ID
SDA    = $MP_SDA
SCL    = $MP_SCL
FREQ   = $MP_I2C_FREQ
ADDR   = $I2C_ADDR
EOF

case "$EFFECTIVE_LEVEL" in
    hil)
        echo "=== [hil] Running $TARGET on MicroPython ($MP_PORT) ==="
        OUTPUT=$(mpremote connect "$MP_PORT" mount "$SCRIPT_DIR" run "$TEST_FILE" 2>&1)
        echo "$OUTPUT"

        PASSED=$(echo "$OUTPUT" | grep -c '^PASS ' || true)
        FAILED=$(echo "$OUTPUT" | grep -c '^FAIL ' || true)

        if echo "$OUTPUT" | grep -q '===DONE'; then
            [ "$FAILED" -eq 0 ] && exit 0 || exit 1
        else
            echo "ERROR: test did not complete (===DONE not seen)"
            exit 2
        fi
        ;;
    conformance)
        CHECKER="$SCRIPT_DIR/../conformance/$CATEGORY/${CHIP}_conformance.py"
        if [ ! -f "$CHECKER" ]; then
            echo "ERROR: conformance checker not found: $CHECKER" >&2
            echo "       (no conformance implementation yet for $CATEGORY/$CHIP)" >&2
            exit 1
        fi
        echo "=== [conformance] Running $TARGET via $CHECKER ==="
        python3 "$CHECKER" --lang micropython --mp-port "$MP_PORT" --mp-mount "$SCRIPT_DIR" --mp-test "$TEST_FILE"
        ;;
    *)
        echo "ERROR: unknown --level '$EFFECTIVE_LEVEL' (expected hil|conformance)" >&2
        exit 1
        ;;
esac

#!/usr/bin/env bash
# Usage:
#   ./test_linux_java.sh [--level unit|hil|conformance] <category>/<chip>
#
# Runs a JVM (jbang) hardware test on the Pi via Linux FFM (no Pi4J), for the
# Java driver variant. Replaces `test.sh --lang java`.
# Reads testconfig from the same directory if present.
#
# Transport is detected automatically:
#   I2C          — chip found in ../chip_defaults
#   NeoPixel/SPI — chip found in chip_spi_defaults (same directory as this script)
#
# Auto-detects the deepest test level the environment supports (I2C chips
# only — see detect_hardware below):
#   conformance - sigrok analyzer configured (SIGROK_DRIVER/sigrok-cli --scan)
#                 AND hardware present
#   hil         - hardware present (bus + chip respond), no sigrok
#   unit        - neither — mocked, no hardware needed at all
# Override the auto-detected level with --level.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
MODULE="periph-java"
TEST_SUFFIX="Test"

# --- parse args ------------------------------------------------------------
LEVEL=""
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --level) LEVEL="${2:-}"; shift 2 ;;
        *) ARGS+=("$1"); shift ;;
    esac
done
set -- "${ARGS[@]:-}"

TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    echo "Usage: $0 [--level unit|hil|conformance] <category>/<chip>"
    echo "  e.g. $0 adc_dac/mcp4725"
    echo "       $0 led/ws2812b"
    exit 1
fi

# Parse the target BEFORE sourcing config, so the per-chip wiring case block
# (keyed on $CATEGORY/$CHIP) has both variables available when it runs.
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"
CHIP_PASCAL="${CHIP^}"

# --- load local config ----------------------------------------------------
CONFIG="$SCRIPT_DIR/testconfig"
if [ -f "$CONFIG" ]; then
    # shellcheck source=/dev/null
    source "$CONFIG"
else
    echo "WARNING: $CONFIG not found. Using defaults — copy testconfig.example to testconfig."
fi

I2C_BUS="${I2C_BUS:-1}"

# --- detect transport ------------------------------------------------------
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

# --- helpers -----------------------------------------------------------------

# find_tool NAME: print the first match of NAME on PATH, else /usr/sbin/NAME
# if that exists (i2c-tools commonly lands there without being on PATH),
# else nothing.
find_tool() {
    local name="$1"
    if command -v "$name" >/dev/null 2>&1; then
        command -v "$name"
    elif [ -x "/usr/sbin/$name" ]; then
        echo "/usr/sbin/$name"
    fi
}

# detect_hardware: 0 (true) if the configured I2C bus exists and the chip
# responds at its address; 1 (false) otherwise. SPI/NeoPixel chips have no
# equivalent simple probe yet, so they always report "not present" and fall
# through to hil, same as before this rework.
detect_hardware() {
    [ "$TRANSPORT" = "i2c" ] || return 1
    [ -e "/dev/i2c-$I2C_BUS" ] || return 1
    [ -z "${I2C_ADDR:-}" ] && return 1
    local i2cget_bin
    i2cget_bin=$(find_tool i2cget) || true
    if [ -n "$i2cget_bin" ]; then
        "$i2cget_bin" -y "$I2C_BUS" "$I2C_ADDR" >/dev/null 2>&1
    else
        # i2c-tools unavailable: the device node existing is the best signal we have.
        return 0
    fi
}

# detect_sigrok: 0 (true) if a logic analyzer is configured/reachable.
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

# detect_level: print the effective test level (honors --level override).
detect_level() {
    if [ -n "$LEVEL" ]; then
        echo "$LEVEL"
        return
    fi
    if detect_hardware; then
        if detect_sigrok; then
            echo "conformance"
        else
            echo "hil"
        fi
    elif [ -f "$SCRIPT_DIR/$MODULE/src/test/java/it/uhde/periph/chips/$CATEGORY/${CHIP_PASCAL}${TEST_SUFFIX}.java" ]; then
        echo "unit"
    else
        # No hardware and no unit test for this chip yet (most chips, until
        # backfilled per specs/testing_framework.md Rollout Scope) - fall
        # through to hil so this behaves exactly as it always has: attempt
        # the real thing and fail with the familiar "no hardware" error,
        # rather than a confusing "no unit test" dead end.
        echo "hil"
    fi
}

# --- unit level: mocked, no hardware, no testconfig needed ------------------
run_unit() {
    echo "=== [unit] Running $TARGET (mocked, no hardware) ==="
    (cd "$SCRIPT_DIR" && mvn -pl "$MODULE" -am test \
        -Dtest="${CHIP_PASCAL}${TEST_SUFFIX}" -Dsurefire.failIfNoSpecifiedTests=false)
}

# --- find hil/conformance test file (glob avoids brittle name-casing logic) -
find_test_file() {
    find "$SCRIPT_DIR/tests/$CATEGORY/$CHIP/" -name "*Test.java" 2>/dev/null | head -1
}

# --- hil level: real hardware, value checks ---------------------------------
run_hil() {
    local test_file
    test_file=$(find_test_file)
    if [ -z "$test_file" ]; then
        echo "ERROR: no *Test.java file found under tests/$CATEGORY/$CHIP/" >&2
        exit 1
    fi
    if [ "$TRANSPORT" = "i2c" ]; then
        echo "=== [hil] Running $TARGET on JVM/java (I2C bus $I2C_BUS, addr $I2C_ADDR) ==="
        I2C_BUS="$I2C_BUS" I2C_ADDR="$I2C_ADDR" jbang "$test_file"
    else
        echo "=== [hil] Running $TARGET on JVM/java (SPI bus $SPI_BUS, device $SPI_DEVICE, pixels $PIXEL_COUNT) ==="
        SPI_BUS="$SPI_BUS" SPI_DEVICE="$SPI_DEVICE" PIXEL_COUNT="$PIXEL_COUNT" jbang "$test_file"
    fi
}

# --- conformance level: real hardware, timing checks via sigrok -------------
run_conformance() {
    local checker="$SCRIPT_DIR/../conformance/$CATEGORY/${CHIP}_conformance.py"
    if [ ! -f "$checker" ]; then
        echo "ERROR: conformance checker not found: $checker" >&2
        echo "       (no conformance implementation yet for $CATEGORY/$CHIP)" >&2
        exit 1
    fi
    local test_file
    test_file=$(find_test_file)
    echo "=== [conformance] Running $TARGET via $checker ==="
    I2C_BUS="$I2C_BUS" I2C_ADDR="${I2C_ADDR:-}" python3 "$checker" --lang jvm-java --jbang-test "$test_file"
}

# --- dispatch ----------------------------------------------------------------
EFFECTIVE_LEVEL=$(detect_level)
case "$EFFECTIVE_LEVEL" in
    unit)        run_unit ;;
    hil)         run_hil ;;
    conformance) run_conformance ;;
    *)
        echo "ERROR: unknown --level '$EFFECTIVE_LEVEL' (expected unit|hil|conformance)" >&2
        exit 1
        ;;
esac

#!/usr/bin/env bash
# Usage:
#   ./test_linux.sh [--level unit|hil|conformance] [--compile-only] <category>/<chip>
#
# Builds the Linux GCC test with g++ and runs it on the host.
#
# Auto-detects the deepest test level the environment supports:
#   conformance - sigrok analyzer configured (SIGROK_DRIVER/sigrok-cli --scan)
#                 AND hardware present
#   hil         - hardware present (bus + chip respond), no sigrok
#   unit        - neither — mocked, no hardware needed at all
# Override the auto-detected level with --level.

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# --- parse args ------------------------------------------------------------
LEVEL=""
COMPILE_ONLY=0
ARGS=()
while [ $# -gt 0 ]; do
    case "$1" in
        --level) LEVEL="${2:-}"; shift 2 ;;
        --compile-only) COMPILE_ONLY=1; shift ;;
        *) ARGS+=("$1"); shift ;;
    esac
done
set -- "${ARGS[@]:-}"

TARGET="${1:-}"
if [ -z "$TARGET" ]; then
    echo "Usage: $0 [--level unit|hil|conformance] [--compile-only] <category>/<chip>"
    echo "  e.g. $0 power/ina226"
    exit 1
fi

# Parse the target BEFORE sourcing config, so the per-chip wiring case block
# (keyed on $CATEGORY/$CHIP) has both variables available when it runs.
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

# Shared per-chip wiring/sigrok overrides for a rig with several chips wired
# at once (see specs/testing_framework.md, "Chip Wiring & Sigrok Configuration").
WIRING="$SCRIPT_DIR/testconfig_wiring"
if [ -f "$WIRING" ]; then
    # shellcheck source=/dev/null
    source "$WIRING"
fi

LINUX_I2C_BUS="${LINUX_I2C_BUS:-1}"

SRC_DIR="$SCRIPT_DIR/src"
CONNECTION_SRC="$SRC_DIR/connection/I2CConnectionLinux.cpp"
CHIP_SRC=$(find "$SRC_DIR/chips/$CATEGORY" -maxdepth 1 -iname "${CHIP}.cpp" | head -1)
if [ -z "$CHIP_SRC" ]; then
    echo "ERROR: no chip source found for $CATEGORY/$CHIP in $SRC_DIR/chips/$CATEGORY"
    exit 1
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

# detect_addr: print I2C_ADDR if resolvable (env, testconfig, or chip_defaults),
# else print nothing. Never fails — used during auto-detection where an
# unresolved address just means "can't be hardware", not a hard error.
detect_addr() {
    if [ -n "${I2C_ADDR:-}" ]; then
        echo "$I2C_ADDR"
        return
    fi
    awk -v c="$CHIP" '!/^#/ && $1==c{print $2; exit}' "$SCRIPT_DIR/../chip_defaults" 2>/dev/null || true
}

# resolve_addr: like detect_addr, but hard-fails with a clear message when a
# real run (hil/conformance) needs an address and none can be found.
resolve_addr() {
    I2C_ADDR=$(detect_addr)
    if [ -z "$I2C_ADDR" ]; then
        echo "ERROR: I2C_ADDR not set in testconfig and no default found for '$CHIP' in chip_defaults" >&2
        exit 1
    fi
}

# detect_hardware: 0 (true) if the configured bus exists and the chip
# responds at its address; 1 (false) otherwise. Never touches anything
# destructive - a single read probe only.
detect_hardware() {
    [ -e "/dev/i2c-$LINUX_I2C_BUS" ] || return 1
    local addr
    addr=$(detect_addr)
    [ -z "$addr" ] && return 1
    local i2cget_bin
    i2cget_bin=$(find_tool i2cget) || true
    if [ -n "$i2cget_bin" ]; then
        "$i2cget_bin" -y "$LINUX_I2C_BUS" "$addr" >/dev/null 2>&1
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
    elif [ -f "$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_unit/${CHIP}_test_unit.cpp" ]; then
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
    local unit_src="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_unit/${CHIP}_test_unit.cpp"
    if [ ! -f "$unit_src" ]; then
        echo "ERROR: unit test source not found: $unit_src" >&2
        exit 1
    fi
    build_dir=$(mktemp -d)
    trap 'rm -rf "$build_dir"' EXIT
    bin="$build_dir/${CHIP}_test_unit"

    echo "=== [unit] Compiling $TARGET for Linux GCC ==="
    g++ -std=c++17 \
        -I"$SRC_DIR/connection" \
        -I"$SRC_DIR/chips/$CATEGORY" \
        "$unit_src" "$CHIP_SRC" \
        -o "$bin"
    echo "Compile OK"

    [ "$COMPILE_ONLY" -eq 1 ] && return 0

    echo "=== [unit] Running (mocked, no hardware) ==="
    "$bin"
}

# --- hil level: real hardware, value checks ---------------------------------
run_hil() {
    resolve_addr
    local test_src="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_linux/${CHIP}_test_linux.cpp"
    if [ ! -f "$test_src" ]; then
        echo "ERROR: test source not found: $test_src" >&2
        exit 1
    fi
    build_dir=$(mktemp -d)
    trap 'rm -rf "$build_dir"' EXIT
    bin="$build_dir/${CHIP}_test"

    echo "=== [hil] Compiling $TARGET for Linux GCC ==="
    g++ -std=c++17 \
        -I"$SRC_DIR/connection" \
        -I"$SRC_DIR/chips/$CATEGORY" \
        -DTEST_I2C_BUS="$LINUX_I2C_BUS" \
        -DTEST_ADDR="$I2C_ADDR" \
        "$test_src" "$CONNECTION_SRC" "$CHIP_SRC" \
        -o "$bin"
    echo "Compile OK"

    [ "$COMPILE_ONLY" -eq 1 ] && return 0

    echo "=== [hil] Running on /dev/i2c-$LINUX_I2C_BUS ==="
    "$bin"
}

# --- conformance level: real hardware, timing checks via sigrok -------------
run_conformance() {
    resolve_addr
    local test_src="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_linux/${CHIP}_test_linux.cpp"
    if [ ! -f "$test_src" ]; then
        echo "ERROR: test source not found: $test_src" >&2
        exit 1
    fi
    local checker="$SCRIPT_DIR/../conformance/$CATEGORY/${CHIP}_conformance.py"
    if [ ! -f "$checker" ]; then
        echo "ERROR: conformance checker not found: $checker" >&2
        echo "       (no conformance implementation yet for $CATEGORY/$CHIP)" >&2
        exit 1
    fi
    build_dir=$(mktemp -d)
    trap 'rm -rf "$build_dir"' EXIT
    bin="$build_dir/${CHIP}_test"

    echo "=== [conformance] Compiling $TARGET for Linux GCC ==="
    g++ -std=c++17 \
        -I"$SRC_DIR/connection" \
        -I"$SRC_DIR/chips/$CATEGORY" \
        -DTEST_I2C_BUS="$LINUX_I2C_BUS" \
        -DTEST_ADDR="$I2C_ADDR" \
        "$test_src" "$CONNECTION_SRC" "$CHIP_SRC" \
        -o "$bin"
    echo "Compile OK"

    [ "$COMPILE_ONLY" -eq 1 ] && return 0

    echo "=== [conformance] Running via $checker ==="
    python3 "$checker" --lang cpp --binary "$bin"
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

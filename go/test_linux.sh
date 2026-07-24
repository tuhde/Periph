#!/usr/bin/env bash
# Usage: test_linux.sh [--compile-only] <category/chip>
# Example: test_linux.sh environmental/aht21
#
# Requires: go (>= 1.24), /dev/i2c-N kernel driver
# Config:   go/testconfig (copy from testconfig.example)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

COMPILE_ONLY=false
if [[ "${1:-}" == "--compile-only" ]]; then
    COMPILE_ONLY=true; shift
fi

TARGET="${1:?Usage: $0 [--compile-only] <category/chip>}"
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

TESTCONFIG="$SCRIPT_DIR/testconfig"
if [[ -f "$TESTCONFIG" ]]; then
    # shellcheck source=/dev/null
    source "$TESTCONFIG"
fi

TEST_DIR="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test"
if [[ ! -d "$TEST_DIR" ]]; then
    echo "Error: test not found: $TEST_DIR" >&2; exit 2
fi

BIN_DIR="$(mktemp -d)"
BIN="$BIN_DIR/${CHIP}_test"
trap 'rm -rf "$BIN_DIR"' EXIT

echo "Building $TARGET..."
(cd "$SCRIPT_DIR" && go build -o "$BIN" "./tests/$CATEGORY/${CHIP}_test")

if [[ "$COMPILE_ONLY" == true ]]; then
    echo "Compile-only: done."; exit 0
fi

I2C_BUS="${I2C_BUS:-1}"

if [[ -z "${I2C_ADDR:-}" ]]; then
    I2C_ADDR=$(awk -v c="$CHIP" '$1==c{print $2; exit}' "$SCRIPT_DIR/../chip_defaults" 2>/dev/null || true)
    if [[ -z "${I2C_ADDR:-}" ]]; then
        echo "Error: I2C_ADDR not set in testconfig and no default found for '$CHIP' in chip_defaults" >&2
        exit 1
    fi
fi

echo "Running $TARGET..."
I2C_BUS="$I2C_BUS" I2C_ADDR="$I2C_ADDR" "$BIN"

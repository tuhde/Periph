#!/usr/bin/env bash
# Usage: test_linux.sh [--compile-only] <category/chip>
# Example: test_linux.sh power/ina226
#
# Requires: cargo, /dev/i2c-N kernel driver
# Config:   rust/testconfig (copy from testconfig.example)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

COMPILE_ONLY=false
if [[ "${1:-}" == "--compile-only" ]]; then
    COMPILE_ONLY=true; shift
fi

TARGET="${1:?Usage: $0 [--compile-only] <category/chip>}"
CHIP="${TARGET##*/}"

TESTCONFIG="$SCRIPT_DIR/testconfig"
if [[ -f "$TESTCONFIG" ]]; then
    # shellcheck source=/dev/null
    source "$TESTCONFIG"
fi

CATEGORY="${TARGET%/*}"
TEST_DIR="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test"
if [[ ! -d "$TEST_DIR" ]]; then
    echo "Error: test not found: $TEST_DIR" >&2; exit 2
fi

echo "Building $TARGET..."
cargo build --manifest-path "$SCRIPT_DIR/Cargo.toml" --bin "${CHIP}_test" --release

if [[ "$COMPILE_ONLY" == true ]]; then
    echo "Compile-only: done."; exit 0
fi

I2C_BUS="${I2C_BUS:-1}"
I2C_ADDR="${I2C_ADDR:-0x40}"

echo "Running $TARGET..."
I2C_BUS="$I2C_BUS" I2C_ADDR="$I2C_ADDR" \
    "$SCRIPT_DIR/target/release/${CHIP}_test"

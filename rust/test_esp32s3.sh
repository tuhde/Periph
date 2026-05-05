#!/usr/bin/env bash
# Usage: test_esp32s3.sh [--compile-only] <category/chip>
# Example: test_esp32s3.sh power/ina226
#
# Requires: rustup with 'esp' toolchain (espup install), cargo-espflash, pyserial
# Config:   rust/testconfig_esp32s3 (copy from testconfig_esp32s3.example)

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

# Use rustup's cargo (required for rust-toolchain.toml / esp toolchain resolution)
export PATH="$HOME/.cargo/bin:$PATH"

COMPILE_ONLY=false
if [[ "${1:-}" == "--compile-only" ]]; then
    COMPILE_ONLY=true; shift
fi

TARGET="${1:?Usage: $0 [--compile-only] <category/chip>}"
CHIP="${TARGET##*/}"
CATEGORY="${TARGET%/*}"

TESTCONFIG="$SCRIPT_DIR/testconfig_esp32s3"
if [[ -f "$TESTCONFIG" ]]; then
    # shellcheck source=/dev/null
    source "$TESTCONFIG"
fi

PORT="${ESP32S3_PORT:-}"
if [[ -z "$PORT" && "$COMPILE_ONLY" == false ]]; then
    echo "Error: ESP32S3_PORT not set in testconfig_esp32s3" >&2; exit 2
fi

TEST_DIR="$SCRIPT_DIR/tests/$CATEGORY/${CHIP}_test_esp32s3"
if [[ ! -d "$TEST_DIR" ]]; then
    echo "Error: test not found: $TEST_DIR" >&2; exit 2
fi

echo "Building $TARGET for ESP32-S3..."
(cd "$TEST_DIR" && cargo build --release)

if [[ "$COMPILE_ONLY" == true ]]; then
    echo "Compile-only: done."; exit 0
fi

echo "Flashing..."
(cd "$TEST_DIR" && cargo espflash flash --chip esp32s3 --port "$PORT" --release --after watchdog-reset)

echo "Reading output..."
python3 "$SCRIPT_DIR/read_serial_esp32s3.py" "$PORT" 115200 "${SERIAL_TIMEOUT:-20}"

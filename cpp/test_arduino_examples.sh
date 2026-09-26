#!/usr/bin/env bash
# Usage:
#   ./test_arduino_examples.sh [--fqbn FQBN] [--tests] [--shard I/N] [filter]
#
# Compiles every Arduino example (cpp/examples/arduino/**/*.ino) against cpp/
# as a single library -- the same layout the Library Manager ships in
# tuhde/Periph-Arduino (library.properties + src/Periph.h). The Arduino build system
# compiles every .cpp in the library for every sketch, so one driver that
# doesn't build on a core breaks every sketch on that core; this catches that.
#
# Every example is copied into the same sketch folder and built with one shared
# build path: arduino-cli wipes the build path whenever the sketch location
# changes, so this is what lets it compile the library objects once per run
# instead of once per example (minutes per example on ESP32).
#
# --tests: also compile every test sketch (cpp/tests/<category>/<chip>_test/*.ino).
# They target the ESP32-S3 test rig (Wire.begin(sda, scl, freq) etc.), so only
# pass this with an ESP32 FQBN.
#
# --shard I/N  compile only every N-th sketch starting at the I-th (1-based),
#              so CI can split a slow core across parallel jobs.
#
# filter: optional substring of the example path (e.g. "temperature/" or "BME280").
#
# Examples:
#   ./test_arduino_examples.sh                              # ESP32-S3
#   ./test_arduino_examples.sh --fqbn arduino:avr:mega      # AVR, C++11
#   ./test_arduino_examples.sh --fqbn arduino:avr:mega BME280
#   ./test_arduino_examples.sh --shard 1/4                  # first of 4 shards

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

FQBN="esp32:esp32:esp32s3"
FILTER=""
TESTS=0
SHARD_I=1
SHARD_N=1
while [ $# -gt 0 ]; do
    case "$1" in
        --fqbn) FQBN="${2:-}"; shift 2 ;;
        --tests) TESTS=1; shift ;;
        --shard) SHARD_I="${2%/*}"; SHARD_N="${2#*/}"; shift 2 ;;
        *) FILTER="$1"; shift ;;
    esac
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BUILD_PATH="$WORK/build"
mkdir -p "$BUILD_PATH"

OK=0
FAILED=()
n=0
while IFS= read -r ino; do
    case "$ino" in *"$FILTER"*) ;; *) continue ;; esac
    n=$((n + 1))
    [ $(( (n - 1) % SHARD_N + 1 )) -eq "$SHARD_I" ] || continue
    case "$ino" in
        "$SCRIPT_DIR/tests/"*) sketch="$(basename "$ino" .ino)" ;;
        *)  chip="$(basename "$(dirname "$(dirname "$ino")")")"
            tier="$(basename "$(dirname "$ino")")"
            sketch="${chip}_${tier^}" ;;
    esac
    mkdir -p "$WORK/Example"
    cp "$ino" "$WORK/Example/Example.ino"
    if arduino-cli compile --fqbn "$FQBN" --library "$SCRIPT_DIR" \
            --build-path "$BUILD_PATH" "$WORK/Example" > "$WORK/$sketch.log" 2>&1; then
        OK=$((OK + 1))
    else
        FAILED+=("$sketch")
        echo "=== FAIL: $sketch ($ino) ==="
        grep -E 'error|undefined reference|overflow|too big' "$WORK/$sketch.log" | head -20 || tail -20 "$WORK/$sketch.log"
    fi
done < <({ find "$SCRIPT_DIR/examples/arduino" -name '*.ino'
            [ "$TESTS" -eq 1 ] && find "$SCRIPT_DIR/tests" -path '*_test/*.ino'; } | sort)

echo "=== $FQBN (shard $SHARD_I/$SHARD_N): $OK compiled, ${#FAILED[@]} failed ==="
[ "${#FAILED[@]}" -eq 0 ] || { printf '  %s\n' "${FAILED[@]}"; exit 1; }

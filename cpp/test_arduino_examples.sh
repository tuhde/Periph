#!/usr/bin/env bash
# Usage:
#   ./test_arduino_examples.sh [--fqbn FQBN] [filter]
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
# filter: optional substring of the example path (e.g. "temperature/" or "BME280").
#
# Examples:
#   ./test_arduino_examples.sh                              # ESP32-S3
#   ./test_arduino_examples.sh --fqbn arduino:avr:mega      # AVR, C++11
#   ./test_arduino_examples.sh --fqbn arduino:avr:mega BME280

set -euo pipefail
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

FQBN="esp32:esp32:esp32s3"
FILTER=""
while [ $# -gt 0 ]; do
    case "$1" in
        --fqbn) FQBN="${2:-}"; shift 2 ;;
        *) FILTER="$1"; shift ;;
    esac
done

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
BUILD_PATH="$WORK/build"
mkdir -p "$BUILD_PATH"

OK=0
FAILED=()
while IFS= read -r ino; do
    case "$ino" in *"$FILTER"*) ;; *) continue ;; esac
    chip="$(basename "$(dirname "$(dirname "$ino")")")"
    tier="$(basename "$(dirname "$ino")")"
    sketch="${chip}_${tier^}"
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
done < <(find "$SCRIPT_DIR/examples/arduino" -name '*.ino' | sort)

echo "=== $FQBN: $OK compiled, ${#FAILED[@]} failed ==="
[ "${#FAILED[@]}" -eq 0 ] || { printf '  %s\n' "${FAILED[@]}"; exit 1; }

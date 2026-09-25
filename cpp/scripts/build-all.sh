#!/usr/bin/env bash
# Usage:
#   cpp/scripts/build-all.sh <platform> [--shard I/N] [filter]
#
# Compile-checks every C++ app of one platform: all examples and all test
# apps (and, for Linux, builds and runs every unit test). Prints one line per
# app and a summary, and exits non-zero if anything failed. CI runs this;
# it works the same locally once the platform's toolchain is set up (see
# TESTING.md).
#
# Platforms:
#   linux    g++ + libgpiod v2: cpp/examples/linux, cpp/tests/*/*_test_linux,
#            cpp/tests/*/*_test_unit (built and run)
#   picosdk  PICO_SDK_PATH set: cpp/examples/picosdk, cpp/tests/*/*_test_picosdk
#   espidf   ESP-IDF environment active (idf.py on PATH):
#            cpp/examples/espidf, cpp/tests/*/*_test_espidf
#   zephyr   Zephyr environment (ZEPHYR_BASE, west): cpp/examples/zephyr,
#            cpp/tests/*/*_test_zephyr, built for ZEPHYR_BOARD
#            (default rpi_pico2/rp2350a/m33) with the matching overlay from
#            cpp/boards/zephyr/ if there is one
#   Arduino has its own script: cpp/test_arduino_examples.sh.
#
# --shard I/N  build only every N-th app starting at the I-th (1-based), so
#              CI can split a slow platform across parallel jobs.
# filter       optional substring of the app path (e.g. "pressure/").
#
# Build output goes to a temporary directory; nothing is written into the
# source tree. Set KEEP_LOGS=<dir> to keep each app's build log.
set -uo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
CPP_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

PLATFORM="${1:-}"
shift || true
SHARD_I=1
SHARD_N=1
FILTER=""
while [ $# -gt 0 ]; do
    case "$1" in
        --shard) SHARD_I="${2%/*}"; SHARD_N="${2#*/}"; shift 2 ;;
        *) FILTER="$1"; shift ;;
    esac
done

list_apps() {
    case "$PLATFORM" in
        linux)
            find "$CPP_DIR/examples/linux" -mindepth 3 -maxdepth 3 -type d
            find "$CPP_DIR/tests" -mindepth 2 -maxdepth 2 -type d \( -name '*_test_linux' -o -name '*_test_unit' \) ;;
        picosdk)
            find "$CPP_DIR/examples/picosdk" -mindepth 3 -maxdepth 3 -type d
            find "$CPP_DIR/tests" -mindepth 2 -maxdepth 2 -type d -name '*_test_picosdk' ;;
        espidf)
            find "$CPP_DIR/examples/espidf" -mindepth 3 -maxdepth 3 -type d
            find "$CPP_DIR/tests" -mindepth 2 -maxdepth 2 -type d -name '*_test_espidf' ;;
        zephyr)
            find "$CPP_DIR/examples/zephyr" -mindepth 3 -maxdepth 3 -type d
            find "$CPP_DIR/tests" -mindepth 2 -maxdepth 2 -type d -name '*_test_zephyr' ;;
        *)
            echo "Usage: $0 <linux|picosdk|espidf|zephyr> [--shard I/N] [filter]" >&2
            exit 2 ;;
    esac
}

# Linux apps have no build files; linux-sources.py works out what to compile.
LINUX_INCLUDES=(-I"$CPP_DIR/src/connection")
for d in "$CPP_DIR"/src/chips/*/; do LINUX_INCLUDES+=(-I"$d"); done

build_linux() {
    local app="$1" out="$2" srcs
    mapfile -t srcs < <(python3 "$SCRIPT_DIR/linux-sources.py" "$app")
    g++ -std=c++17 -Wall -Wextra -Werror -O1 -I"$app" "${LINUX_INCLUDES[@]}" \
        "${srcs[@]}" -o "$out/app" -lgpiod -lpthread -lm || return 1
    # Unit tests run against mocks, so run them too.
    if [[ "$app" == *_test_unit ]]; then
        "$out/app" || return 1
    fi
}

build_picosdk() {
    local app="$1" out="$2"
    cmake -S "$app" -B "$out/b" -DPICO_BOARD="${PICO_BOARD:-pico}" \
        -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache &&
    cmake --build "$out/b" -- -j"$(nproc)"
}

build_espidf() {
    local app="$1" out="$2" rc
    ( cd "$app" && IDF_TARGET="${ESPIDF_TARGET:-esp32}" IDF_CCACHE_ENABLE=1 \
        idf.py -B "$out/b" -D SDKCONFIG="$out/b/sdkconfig" build )
    rc=$?
    rm -f "$app/dependencies.lock"
    return $rc
}

ZEPHYR_BOARD="${ZEPHYR_BOARD:-rpi_pico2/rp2350a/m33}"
ZEPHYR_OVERLAY="$CPP_DIR/boards/zephyr/$(echo "$ZEPHYR_BOARD" | tr / _).overlay"

build_zephyr() {
    local app="$1" out="$2" extra=()
    [ -f "$ZEPHYR_OVERLAY" ] && extra=(-- -DEXTRA_DTC_OVERLAY_FILE="$ZEPHYR_OVERLAY")
    west build -p always -b "$ZEPHYR_BOARD" -d "$out/b" "$app" "${extra[@]}"
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
[ -n "${KEEP_LOGS:-}" ] && mkdir -p "$KEEP_LOGS"

OK=0
FAILED=()
n=0
while IFS= read -r app; do
    rel="${app#"$CPP_DIR"/}"
    case "$rel" in *"$FILTER"*) ;; *) continue ;; esac
    n=$((n + 1))
    [ $(( (n - 1) % SHARD_N + 1 )) -eq "$SHARD_I" ] || continue

    out="$WORK/app"
    rm -rf "$out"; mkdir -p "$out"
    log="$WORK/build.log"
    if "build_$PLATFORM" "$app" "$out" > "$log" 2>&1; then
        OK=$((OK + 1))
        echo "ok   $rel"
    else
        FAILED+=("$rel")
        echo "FAIL $rel"
        grep -E 'error|Error|undefined reference|FAIL ' "$log" | head -15 | sed 's/^/     /'
    fi
    [ -n "${KEEP_LOGS:-}" ] && cp "$log" "$KEEP_LOGS/$(echo "$rel" | tr / _).log"
done < <(list_apps | sort)

echo "=== $PLATFORM (shard $SHARD_I/$SHARD_N): $OK built, ${#FAILED[@]} failed ==="
[ "${#FAILED[@]}" -eq 0 ] || { printf '  %s\n' "${FAILED[@]}"; exit 1; }

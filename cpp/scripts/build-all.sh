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
#   stm32cube  arm-none-eabi-gcc + STM32CUBE_FW_PATH set: cpp/examples/stm32cube,
#            cpp/tests/*/*_test_stm32cube, built for NUCLEO-F411RE via
#            cpp/boards/stm32cube/toolchain-arm-none-eabi.cmake
#   Arduino has its own script: cpp/test_arduino_examples.sh.
#
# --shard I/N  build only every N-th app starting at the I-th (1-based), so
#              CI can split a slow platform across parallel jobs.
# -j N, --jobs N  build N apps at the same time (env: BUILD_JOBS). Default:
#              nproc for linux, 2 for the toolchain-heavy platforms (their
#              own build tool already runs in parallel inside each app).
#              Each app builds in its own directory; results print as they
#              finish, so lines may come out of order.
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
JOBS="${BUILD_JOBS:-}"
while [ $# -gt 0 ]; do
    case "$1" in
        --shard) SHARD_I="${2%/*}"; SHARD_N="${2#*/}"; shift 2 ;;
        -j|--jobs) JOBS="$2"; shift 2 ;;
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
        stm32cube)
            find "$CPP_DIR/examples/stm32cube" -mindepth 3 -maxdepth 3 -type d
            find "$CPP_DIR/tests" -mindepth 2 -maxdepth 2 -type d -name '*_test_stm32cube' ;;
        *)
            echo "Usage: $0 <linux|picosdk|espidf|zephyr|stm32cube> [--shard I/N] [filter]" >&2
            exit 2 ;;
    esac
}

NPROC="$(nproc)"
if [ -z "$JOBS" ]; then
    case "$PLATFORM" in linux) JOBS="$NPROC" ;; *) JOBS=2 ;; esac
fi
[ "$JOBS" -ge 1 ] 2>/dev/null || { echo "invalid --jobs: $JOBS" >&2; exit 2; }
# CPU threads each app's own build tool may use, so the total stays near nproc.
INNER_JOBS=$(( NPROC / JOBS )); [ "$INNER_JOBS" -ge 1 ] || INNER_JOBS=1

# Linux apps have no build files; linux-sources.py works out what to compile.
LINUX_INCLUDES=(-I"$CPP_DIR/src/connection" -I"$CPP_DIR/src/discovery" -I"$CPP_DIR/src/storage")
for d in "$CPP_DIR"/src/chips/*/; do LINUX_INCLUDES+=(-I"$d"); done

# Compile each source to its own object so ccache can cache it (ccache skips
# compile-and-link commands). Most apps share the same connection and chip
# sources, so after the first app nearly everything is a cache hit.
build_linux() {
    local app="$1" out="$2" srcs objs=() i=0 s cc=(g++)
    command -v ccache >/dev/null 2>&1 && cc=(ccache g++)
    mapfile -t srcs < <(python3 "$SCRIPT_DIR/linux-sources.py" "$app")
    for s in "${srcs[@]}"; do
        objs+=("$out/$((i++)).o")
        "${cc[@]}" -std=c++17 -Wall -Wextra -Werror -O1 -I"$app" "${LINUX_INCLUDES[@]}" \
            -c "$s" -o "${objs[-1]}" || return 1
    done
    g++ "${objs[@]}" -o "$out/app" -lgpiod -lpthread -lm || return 1
    # Unit tests run against mocks, so run them too.
    if [[ "$app" == *_test_unit ]]; then
        "$out/app" || return 1
    fi
}

build_picosdk() {
    local app="$1" out="$2"
    cmake -S "$app" -B "$out/b" -DPICO_BOARD="${PICO_BOARD:-pico}" \
        -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache &&
    cmake --build "$out/b" -- -j"$INNER_JOBS"
}

# Every app recompiles the same ~1,000 ESP-IDF sources. For ccache to share
# them between apps it must ignore the per-project -fmacro-prefix-map and
# hash the specs file ESP-IDF regenerates per build by content, not mtime.
build_espidf() {
    local app="$1" out="$2" rc
    ( cd "$app" && IDF_TARGET="${ESPIDF_TARGET:-esp32}" IDF_CCACHE_ENABLE=1 \
        CCACHE_IGNOREOPTIONS='-fmacro-prefix-map=*' CCACHE_COMPILERCHECK=content \
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

build_stm32cube() {
    local app="$1" out="$2"
    cmake -S "$app" -B "$out/b" \
        -DCMAKE_C_COMPILER_LAUNCHER=ccache -DCMAKE_CXX_COMPILER_LAUNCHER=ccache &&
    cmake --build "$out/b" -- -j"$INNER_JOBS"
}

WORK="$(mktemp -d)"
trap 'rm -rf "$WORK"' EXIT
[ -n "${KEEP_LOGS:-}" ] && mkdir -p "$KEEP_LOGS"

# Select the apps of this shard.
APPS=()
n=0
while IFS= read -r app; do
    rel="${app#"$CPP_DIR"/}"
    case "$rel" in *"$FILTER"*) ;; *) continue ;; esac
    n=$((n + 1))
    [ $(( (n - 1) % SHARD_N + 1 )) -eq "$SHARD_I" ] || continue
    APPS+=("$app")
done < <(list_apps | sort)
echo "=== $PLATFORM: ${#APPS[@]} apps, $JOBS at a time ==="

# Build one app in its own directory. Writes "<idx>.ok" or "<idx>.fail" to
# $WORK and prints its result as a single block, so parallel output stays intact.
build_one() {
    local idx="$1" app="$2" rel t out log msg rc
    rel="${app#"$CPP_DIR"/}"
    out="$WORK/$idx"; log="$WORK/$idx.log"
    mkdir -p "$out"
    t=$SECONDS
    if "build_$PLATFORM" "$app" "$out" > "$log" 2>&1; then rc=0; else rc=1; fi
    if [ $rc -eq 0 ]; then
        printf '[%s] %s ... ok (%ss)\n' "$(date +%H:%M:%S)" "$rel" "$((SECONDS - t))"
        : > "$WORK/$idx.ok"
    else
        msg="$(grep -E 'error|Error|undefined reference|FAIL ' "$log" | head -15 | sed 's/^/     /')"
        printf '[%s] %s ... FAILED (%ss)\n%s\n' "$(date +%H:%M:%S)" "$rel" "$((SECONDS - t))" "$msg"
        echo "$rel" > "$WORK/$idx.fail"
    fi
    [ -n "${KEEP_LOGS:-}" ] && cp "$log" "$KEEP_LOGS/$(echo "$rel" | tr / _).log"
    rm -rf "$out" "$log"
}

idx=0
for app in "${APPS[@]+"${APPS[@]}"}"; do
    while [ "$(jobs -rp | wc -l)" -ge "$JOBS" ]; do wait -n || true; done
    idx=$((idx + 1))
    build_one "$idx" "$app" &
done
wait

OK=$(find "$WORK" -maxdepth 1 -name '*.ok' | wc -l)
mapfile -t FAILED < <(cat "$WORK"/*.fail 2>/dev/null | sort)
echo "=== $PLATFORM (shard $SHARD_I/$SHARD_N): $OK built, ${#FAILED[@]} failed ==="
[ "${#FAILED[@]}" -eq 0 ] || { printf '  %s\n' "${FAILED[@]}"; exit 1; }

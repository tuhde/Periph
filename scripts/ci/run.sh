#!/usr/bin/env bash
# Usage: scripts/ci/run.sh <job> [args passed to the build script, e.g. --shard 1/4]
#
# One entry point per CI job, shared by .github/workflows/ci.yml and
# .gitlab-ci.yml so the two pipelines cannot drift apart. Both run on a
# self-hosted runner whose host has the toolchains preinstalled (setup:
# TOOLCHAINS.md, plus the runner notes in CONTRIBUTING.md); nothing is
# installed or downloaded here except the pinned Arduino board cores.
#
# Jobs: arduino-esp32  arduino-avr  arduino-checks  linux-gcc  pico-sdk
#       stm32cube  python  registry  nodejs  java  esp-idf  zephyr  rust
#       go-linux  go-tinygo
#
# Scope: when PERIPH_CI_BASE is set (CI sets it to the PR base / previous push)
# and PERIPH_CI_SCOPE is not "full", scripts/ci/changed.sh skips jobs the change
# cannot affect and narrows the C++ jobs to the touched chips plus a smoke set
# (scripts/ci/smoke.txt). Without PERIPH_CI_BASE (local runs) everything runs.
# The release gate is a full run: workflow_dispatch / GitLab "web" pipeline.
#
# Persistent state lives under $PERIPH_CI_CACHE (default ~/.cache/periph-ci):
# the cargo target dir; ccache lives under $PERIPH_CCACHE_ROOT (default
# /var/cache/ccache, one subdirectory per platform). Toolchain locations can
# be overridden with PICO_SDK_PATH, PICOTOOL_PREFIX, STM32CUBE_FW_PATH, IDF_PATH, ZEPHYR_DIR
# and ZEPHYR_VENV.
set -euo pipefail

JOB="${1:-}"
[ -n "$JOB" ] || { sed -n '2,16p' "$0" | sed 's/^# \{0,1\}//'; exit 2; }
shift

cd "$(git rev-parse --show-toplevel)"

# Service-launched runners don't read ~/.bashrc, so add the usual tool dirs.
for d in "$HOME/.cargo/bin" "$HOME/.local/bin" /usr/local/go/bin "$HOME/go/bin"; do
    [ -d "$d" ] && PATH="$d:$PATH"
done
export PATH

# --- progress output -------------------------------------------------------
# Every command runs through `step`, which prints a timestamped header first
# and its duration afterwards. On failure the exit trap names the step.
T0=$SECONDS
CURRENT_STEP=""
CLEANUP=()
log() { printf '\n==> [%s] %s\n' "$(date +%H:%M:%S)" "$*"; }
step() { # <description> <command...>
    local desc="$1" t=$SECONDS; shift
    CURRENT_STEP="$desc"
    log "$desc"
    "$@"
    printf '    done in %ss\n' "$((SECONDS - t))"
    CURRENT_STEP=""
}
at_exit() { CLEANUP+=("$*"); }
finish() {
    local rc=$?
    for c in "${CLEANUP[@]+"${CLEANUP[@]}"}"; do eval "$c" || true; done
    if [ $rc -ne 0 ]; then
        log "FAILED: ${CURRENT_STEP:-job $JOB} (exit $rc) after $((SECONDS - T0))s"
    else
        log "job $JOB finished OK in $((SECONDS - T0))s"
    fi
}
trap finish EXIT
log "job $JOB on $(hostname)"

# --- scope: skip or narrow the job to what the change touches ----------------
eval "$(scripts/ci/changed.sh "$JOB")"
log "scope: $CI_REASON"
if [ "$CI_RUN" != 1 ]; then
    log "skipped: nothing this job checks has changed"
    exit 0
fi
[ -z "$CI_FILTER" ] || set -- "$@" "$CI_FILTER"

CACHE_ROOT="${PERIPH_CI_CACHE:-$HOME/.cache/periph-ci}"
mkdir -p "$CACHE_ROOT"
CCACHE_ROOT="${PERIPH_CCACHE_ROOT:-/var/cache/ccache}"

# Pinned so the board-package set only changes when the core does.
ESP32_CORE_VERSION="${ESP32_CORE_VERSION:-3.3.12}"
AVR_CORE_VERSION="${AVR_CORE_VERSION:-1.8.8}"

# ccache for a platform: persistent, shared by all shards, no hash of the
# (temporary) build dir so entries hit across runs.
use_ccache() {
    export CCACHE_DIR="$CCACHE_ROOT/$1"
    export CCACHE_MAXSIZE="${CCACHE_MAXSIZE:-2G}"
    mkdir -p "$CCACHE_DIR" || { echo "cannot create $CCACHE_DIR (make $CCACHE_ROOT writable by $(id -un))" >&2; exit 1; }
}
ccache_stats() { log "ccache stats"; ccache -s || true; }

need_dir() { # <dir> <what>
    [ -d "$1" ] || { echo "ERROR: $2 not found at $1 (see TOOLCHAINS.md)" >&2; exit 1; }
}

# Sourcing the SDK environment scripts trips `set -u`.
source_relaxed() { set +u; . "$1"; set -u; }

case "$JOB" in
    arduino-esp32)
        step "Configure arduino-cli" arduino-cli config init --overwrite
        step "Add ESP32 board index" arduino-cli config add board_manager.additional_urls \
            https://raw.githubusercontent.com/espressif/arduino-esp32/gh-pages/package_esp32_index.json
        step "Update board index" arduino-cli core update-index
        step "Install ESP32 core $ESP32_CORE_VERSION" arduino-cli core install "esp32:esp32@$ESP32_CORE_VERSION"
        # Every example and test sketch against cpp/ as one library, the layout
        # the Library Manager ships.
        step "Compile ESP32-S3 examples and test sketches" cpp/test_arduino_examples.sh --fqbn esp32:esp32:esp32s3 --tests "$@"
        ;;

    arduino-avr)
        step "Configure arduino-cli" arduino-cli config init --overwrite
        step "Update board index" arduino-cli core update-index
        step "Install AVR core $AVR_CORE_VERSION" arduino-cli core install "arduino:avr@$AVR_CORE_VERSION"
        step "Compile AVR examples (Mega 2560)" cpp/test_arduino_examples.sh --fqbn arduino:avr:mega "$@"
        ;;

    arduino-checks)
        step "Check Periph.h" node cpp/scripts/generate-periph-header.js --check
        stage="$(mktemp -d)"
        at_exit 'rm -rf "$stage"'
        mkdir -p "$stage/Periph"
        step "Stage Library Manager layout" cpp/scripts/stage-arduino-library.sh "$stage/Periph"
        # "update": Periph is registered (arduino/library-registry#9201), so the
        # name must stay the same and each release needs a new version.
        step "Arduino Lint" arduino-lint --compliance strict --library-manager update \
            --project-type library --recursive=false "$stage/Periph"
        step "Check cpp/README.md" node cpp/scripts/generate-readme.js --check
        step "Check keywords.txt" node cpp/scripts/generate-keywords.js --check
        ;;

    linux-gcc)
        # libgpiod v2 (Debian trixie); Ubuntu 24.04 still ships v1.
        log "libgpiod $(pkg-config --modversion libgpiod)"
        use_ccache linux
        at_exit ccache_stats
        step "Build all apps, run unit tests" cpp/scripts/build-all.sh linux "$@"
        ;;

    pico-sdk)
        export PICO_SDK_PATH="${PICO_SDK_PATH:-$HOME/pico-sdk}"
        need_dir "$PICO_SDK_PATH/lib/tinyusb" "Pico SDK (with submodules)"
        # Without an installed picotool the SDK clones and builds it from GitHub
        # in every app's configure (~25 s each, and needs the network).
        PICOTOOL_PREFIX="${PICOTOOL_PREFIX:-$HOME/picotool}"
        if [ -f "$PICOTOOL_PREFIX/picotool/picotoolConfig.cmake" ]; then
            export CMAKE_PREFIX_PATH="$PICOTOOL_PREFIX${CMAKE_PREFIX_PATH:+:$CMAKE_PREFIX_PATH}"
        else
            echo "WARNING: no picotool in $PICOTOOL_PREFIX; every app will download and build it (slow, needs network) -- see TOOLCHAINS.md" >&2
        fi
        export PICO_BOARD=pico
        export CCACHE_BASEDIR=/tmp CCACHE_NOHASHDIR=1
        use_ccache pico
        at_exit ccache_stats
        step "Build all Pico SDK apps" cpp/scripts/build-all.sh picosdk "$@"
        ;;

    stm32cube)
        export STM32CUBE_FW_PATH="${STM32CUBE_FW_PATH:-$HOME/STM32CubeF4}"
        need_dir "$STM32CUBE_FW_PATH/Drivers/STM32F4xx_HAL_Driver" "STM32CubeF4 HAL driver"
        need_dir "$STM32CUBE_FW_PATH/Middlewares/Third_Party/FatFs" "STM32CubeF4 FatFs middleware"
        export CCACHE_BASEDIR=/tmp CCACHE_NOHASHDIR=1
        use_ccache stm32cube
        at_exit ccache_stats
        step "Build all STM32Cube apps" cpp/scripts/build-all.sh stm32cube "$@"
        ;;

    esp-idf)
        export IDF_PATH="${IDF_PATH:-$HOME/esp-idf}"
        source_relaxed "$IDF_PATH/export.sh" >/dev/null
        export ESPIDF_TARGET=esp32
        # Without these the random per-app build path ends up in every cache key
        # and a second run gets no hits at all.
        export CCACHE_BASEDIR=/tmp CCACHE_NOHASHDIR=1
        use_ccache espidf
        at_exit ccache_stats
        step "Build all ESP-IDF apps" cpp/scripts/build-all.sh espidf "$@"
        ;;

    zephyr)
        ZEPHYR_DIR="${ZEPHYR_DIR:-$HOME/zephyrproject}"
        need_dir "$ZEPHYR_DIR/zephyr" "Zephyr workspace"
        ZEPHYR_VENV="${ZEPHYR_VENV:-$HOME/.venvs/zephyr}"
        [ -f "$ZEPHYR_VENV/bin/activate" ] && source_relaxed "$ZEPHYR_VENV/bin/activate"
        export ZEPHYR_BOARD=rpi_pico2/rp2350a/m33
        export CCACHE_BASEDIR=/tmp CCACHE_NOHASHDIR=1
        use_ccache zephyr
        at_exit ccache_stats
        root="$PWD"
        cd "$ZEPHYR_DIR"
        source_relaxed zephyr/zephyr-env.sh
        step "Build all Zephyr apps" "$root/cpp/scripts/build-all.sh" zephyr "$@"
        ;;

    python)
        step "Python syntax check" bash -c "find python/periph python/tests python/examples -name '*.py' | xargs python3 -m py_compile"
        step "Check UIFlow 1 .m5b files" python/uiflow1/generate.sh --check
        step "Check python/README.md" node python/scripts/generate-readme.js --check
        step "Check mip packages" python3 python/scripts/generate-mip-packages.py --check
        ;;

    registry)
        step "Validator tests" node registry/scripts/validate_test.js
        step "Validate registry" node registry/scripts/validate.js --check
        step "Check generated discovery tables" node registry/scripts/generate.js --check
        # smbus2 comes from apt (python3-smbus2): Debian blocks bare pip installs.
        PYTHONPATH=python step "Python discovery unit test" python3 python/tests/discovery/discovery_test_unit.py
        ;;

    nodejs)
        log "node $(node --version)"
        step "npm ci" bash -c 'cd nodejs && npm ci'
        step "Syntax check" bash -c "find nodejs/packages/periph/src nodejs/tests -name '*.js' | xargs -I{} node --check {}"
        step "Verify chip drivers load" node -e "const m = require('./nodejs/packages/periph/src/chips/power/ina226'); \
                 if (!m.INA226Minimal || !m.INA226Full) process.exit(1)"
        step "Check package READMEs" node nodejs/scripts/generate-readmes.js --check
        step "I2C discovery unit test" node nodejs/tests/discovery/discovery_test_unit.js
        ;;

    java)
        step "Java version" java -version
        step "Compile JVM modules" bash -c 'cd jvm && mvn install --batch-mode --no-transfer-progress -DskipTests -T 1C'
        ;;

    rust)
        step "Install Rust stable" rustup toolchain install stable --profile minimal --no-self-update
        export CARGO_TARGET_DIR="$CACHE_ROOT/cargo-target"
        step "Compile workspace" cargo build --manifest-path rust/Cargo.toml
        step "Check rust README" node rust/scripts/generate-readme.js --check
        step "I2C discovery unit tests" cargo test --manifest-path rust/Cargo.toml -p periph --features std discovery
        ;;

    go-linux)
        cd go
        step "go build" go build ./...
        step "go vet" go vet ./...
        step "I2C discovery unit tests" go test ./periph/discovery/
        ;;

    go-tinygo)
        cd go
        out="$(mktemp -d)"
        at_exit 'rm -rf "$out"'
        # Pico W, one example per transport type. The builds are independent,
        # so run them side by side (each writes its own .uf2 and log).
        tinygo_build() { # <label> <example dir>
            tinygo build -target=pico-w -o "$out/$(basename "$2").uf2" "$2" >"$out/$(basename "$2").log" 2>&1
        }
        tinygo_examples=(
            "I2C (power/ina226)|./examples/tinygo/power/ina226/minimal"
            "NeoPixel (led/ws2812b)|./examples/tinygo/led/ws2812b/minimal"
            "DHTxx (humidity/dht11)|./examples/tinygo/humidity/dht11/minimal"
            "HX711 (adc_dac/hx711)|./examples/tinygo/adc_dac/hx711/minimal"
            "SPI (rfid/mfrc522)|./examples/tinygo/rfid/mfrc522/minimal"
            "UART (gnss/neo6)|./examples/tinygo/gnss/neo6/minimal"
        )
        log "TinyGo: building ${#tinygo_examples[@]} examples in parallel"
        pids=()
        for e in "${tinygo_examples[@]}"; do
            tinygo_build "${e%%|*}" "${e#*|}" &
            pids+=($!)
        done
        fail=0
        for k in "${!pids[@]}"; do
            e="${tinygo_examples[$k]}"
            if wait "${pids[$k]}"; then
                printf '    ok     TinyGo %s\n' "${e%%|*}"
            else
                printf '    FAILED TinyGo %s\n' "${e%%|*}"
                tail -20 "$out/$(basename "${e#*|}").log" | sed 's/^/        /'
                fail=1
            fi
        done
        CURRENT_STEP="TinyGo builds"
        [ "$fail" -eq 0 ]
        ;;

    *)
        echo "unknown job: $JOB" >&2
        exit 2
        ;;
esac

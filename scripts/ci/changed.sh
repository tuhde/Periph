#!/usr/bin/env bash
# Usage: scripts/ci/changed.sh <job>
#
# Decides how much of CI job <job> a change needs, so a PR touching one chip
# does not rebuild all ~1,500 C++ apps. Called by scripts/ci/run.sh (so GitHub
# Actions and GitLab behave identically); prints eval-able assignments:
#
#   CI_RUN=1|0        run the job (1) or skip it (0)
#   CI_FILTER=...     C++ platform jobs: comma-separated app filter for
#                     cpp/scripts/build-all.sh / cpp/test_arduino_examples.sh;
#                     empty = build everything
#   CI_REASON=...     one line for the log
#
# Scope:
#   PERIPH_CI_SCOPE=full   everything (release gate: workflow_dispatch / GitLab "web")
#   PERIPH_CI_BASE=<sha>   reduced scope: only what differs from this commit
#   neither set            everything (local runs, unknown base)
#   PERIPH_CI_HEAD=<rev>   compare against this instead of HEAD (testing)
# Anything unrecognised, and changes to shared code or CI itself, also run
# everything -- skipping is only done for paths we positively know.
set -euo pipefail

JOB="${1:?usage: changed.sh <job>}"
cd "$(git rev-parse --show-toplevel)"

CPP_JOBS=(arduino-avr linux-gcc pico-sdk stm32cube esp-idf zephyr)
OTHER_JOBS=(arduino-checks python registry nodejs java rust go-linux go-tinygo)

emit() { # <run 0|1> <filter> <reason>
    printf 'CI_RUN=%s\nCI_FILTER=%q\nCI_REASON=%q\n' "$1" "$2" "$3"
    exit 0
}
everything() { emit 1 "" "full scope: $1"; }

[ "${PERIPH_CI_SCOPE:-auto}" = full ] && everything "requested"
base="${PERIPH_CI_BASE:-}"
[ -n "$base" ] || everything "no base commit given"
case "$base" in *[!0]*) ;; *) everything "new branch (all-zero base)" ;; esac
if ! git cat-file -e "$base^{commit}" 2>/dev/null; then
    git fetch --no-tags --depth=1 origin "$base" >&2 2>/dev/null || true
    git cat-file -e "$base^{commit}" 2>/dev/null || everything "base $base unavailable"
fi

declare -A need=()
declare -A chip_seen=()
chips=()
cpp_everything=0
add_chip() { # <category> <name>; the name is a case-insensitive substring
    local k="$1/$2"
    [[ "$k" =~ ^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]*$ ]] || { cpp_everything=1; return; }
    [ -n "${chip_seen[$k]:-}" ] || { chip_seen[$k]=1; chips+=("$k"); }
}
mark() { local j; for j in "$@"; do need[$j]=1; done; }
plat_jobs() { # <cpp platform dir name> -> jobs
    case "$1" in
        arduino)   echo arduino-avr ;;
        linux)     echo linux-gcc ;;
        picosdk)   echo pico-sdk ;;
        espidf)    echo esp-idf ;;
        zephyr)    echo zephyr ;;
        stm32cube) echo stm32cube ;;
        *)         echo "${CPP_JOBS[@]}" ;;
    esac
}

while IFS= read -r f; do
    [ -n "$f" ] || continue
    case "$f" in
        # --- CI itself and the tooling it runs ------------------------------
        scripts/ci/*|.github/workflows/ci.yml|.gitlab-ci.yml)
            everything "CI definition changed ($f)" ;;

        # --- language trees (each has a cheap whole-tree job) ---------------
        python/*) mark python ;;
        nodejs/*) mark nodejs ;;
        jvm/*)    mark java ;;
        rust/*)   mark rust ;;
        go/*)     mark go-linux go-tinygo ;;

        # --- I2C registry: generated tables in every language ----------------
        registry/*)
            mark "${OTHER_JOBS[@]}" "${CPP_JOBS[@]}"; add_chip discovery "" ;;

        # --- C++ ------------------------------------------------------------
        cpp/src/chips/*/*)
            rest="${f#cpp/src/chips/}"; cat="${rest%%/*}"; name="${rest#*/}"
            mark arduino-checks "${CPP_JOBS[@]}"; add_chip "$cat" "${name%%.*}" ;;
        cpp/src/discovery/*)
            mark arduino-checks "${CPP_JOBS[@]}"; add_chip discovery "" ;;
        cpp/README.md|cpp/keywords.txt|cpp/src/Periph.h|cpp/library.properties)
            mark arduino-checks ;;
        cpp/examples/*/*/*/*)
            rest="${f#cpp/examples/}"; plat="${rest%%/*}"; rest="${rest#*/}"
            cat="${rest%%/*}"; rest="${rest#*/}"; name="${rest%%/*}"
            # shellcheck disable=SC2046
            mark $(plat_jobs "$plat"); add_chip "$cat" "$name" ;;
        cpp/tests/*/*/*)
            rest="${f#cpp/tests/}"; cat="${rest%%/*}"; rest="${rest#*/}"; dir="${rest%%/*}"
            case "$dir" in
                *_test_linux|*_test_unit) mark linux-gcc ;;
                *_test_picosdk)   mark pico-sdk ;;
                *_test_espidf)    mark esp-idf ;;
                *_test_zephyr)    mark zephyr ;;
                *_test_stm32cube) mark stm32cube ;;
                *_test)           ;;  # ESP32 hardware sketches; CI has no ESP32 core
                *)                mark "${CPP_JOBS[@]}" ;;
            esac
            add_chip "$cat" "${dir%%_test*}" ;;
        cpp/tests/*/*)  # shared header inside a test category dir
            rest="${f#cpp/tests/}"; mark "${CPP_JOBS[@]}"; add_chip "${rest%%/*}" "" ;;
        cpp/src/connection/*|cpp/src/storage/*|cpp/CMakeLists.txt|cpp/zephyr/*|cpp/boards/*|cpp/scripts/*|cpp/test_arduino_examples.sh)
            mark arduino-checks "${CPP_JOBS[@]}"; cpp_everything=1 ;;
        cpp/*)  # manual hardware scripts, probes, configs: nothing to build
            mark arduino-checks ;;

        # --- known not to affect CI -----------------------------------------
        specs/*|datasheets/*|wiki|wiki/*|sigrok/*|.github/*|*.md|LICENSE*|.gitignore|.gitmodules) ;;

        *) everything "unrecognised path $f" ;;
    esac
done < <(git diff --name-only --no-renames "$base" "${PERIPH_CI_HEAD:-HEAD}")

if printf '%s\n' "${CPP_JOBS[@]}" | grep -qx -- "$JOB"; then
    [ -n "${need[$JOB]:-}" ] || emit 0 "" "no C++ change relevant to $JOB"
    if [ "$cpp_everything" = 1 ]; then everything "shared C++ code changed"; fi
    while IFS= read -r line; do
        line="${line%%#*}"; line="${line//[[:space:]]/}"
        [ -n "$line" ] && add_chip "${line%%/*}" "${line#*/}"
    done < scripts/ci/smoke.txt
    [ "$cpp_everything" = 0 ] || everything "unusable chip name"
    filter="$(IFS=,; echo "${chips[*]}")"
    emit 1 "$filter" "reduced scope: $filter"
fi

[ -n "${need[$JOB]:-}" ] || emit 0 "" "no change relevant to $JOB"
emit 1 "" "changed files affect $JOB"

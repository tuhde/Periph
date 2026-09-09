# HIL / Conformance Verification Checklist — ENS160 & AHT21

Tracks working through real hardware for every platform's test script, one MCU at a time, for the two reference chips built out under issue #73 (`specs/testing_framework.md`).

## Before you start

- **Every script for every language is now reworked** (unit/hil/conformance detection cascade, `--level` override, `--board` support where applicable). Nothing is blocked on script work anymore — what's left is exercising `hil`/`conformance` against real boards and a real logic analyzer, one MCU at a time.
- **The conformance checker now exists** (`conformance/_sigrok_conformance.py` + `conformance/gas/ens160_conformance.py` + `conformance/environmental/aht21_conformance.py` + `specs/gas/ens160_timing.conf` + `specs/environmental/aht21_timing.conf`). `--level conformance` no longer fails fast with "conformance checker not found" on any platform. It has **not** been run against a real chip + real sigrok analyzer yet, though — the capture/decode/compare plumbing was verified end-to-end against `sigrok-cli`'s builtin `demo` driver (confirms every subprocess invocation, the annotation regex, and the bound-comparison math are correct), but no real ENS160/AHT21 traffic has been decoded. Treat every Conformance cell below as needing a first real run, not just a retry.
- Both chips: ENS160 at `0x53`, AHT21 at `0x38` (confirmed via `i2cdetect` on this bench).
- `--compile-only`/toolchain checks below were run in a sandboxed dev environment with real ESP-IDF, Pico SDK, and TinyGo toolchains installed but **no attached boards** — they confirm the build is clean, not that the board actually runs correctly. HIL/Conformance cells still require a physical run.

## cpp

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux GCC | `test_linux.sh` | ✅ reworked | ⚠️ partial | ⬜ | ✅ done | ⬜ | AHT21: 12/12, verified twice on real hardware (bus 15). ENS160: driver had a real timeout bug on Linux (fixed, `e521a65d`); last attempt got through init+status before the USB-I2C adapter dropped mid-run — retry once the adapter's back. Unit level (mocked): ENS160 21/21, AHT21 14/14, both verified in this pass. |
| Arduino | `test_arduino.sh` | ✅ reworked, `--board` added | ⬜ | ⬜ | ⬜ | ⬜ | Compiles clean for `esp32:esp32:esp32s3:CDCOnBoot=cdc` (ENS160 needed 2 real bugs fixed first — wrong include paths + missing `<Arduino.h>`, `1d25298c`). Not yet flashed to a physical board. |
| Zephyr | `test_zephyr.sh` | ✅ reworked, `--board` added | ⬜ | ⬜ | ⬜ | ⬜ | Compiles clean for `esp32s3_devkitc/esp32s3/procpu` (needed an i2c0-enable overlay + `CONFIG_REQUIRES_FULL_LIBCPP` + a real cross-chip `CONFIG_ZEPHYR`→`__ZEPHYR__` macro bug fixed, `deb37292`). Not yet flashed. |
| ESP-IDF | `test_espidf.sh` | ✅ reworked, `--board` added | ⬜ | ⬜ | ⬜ | ⬜ | Now compiles clean for `esp32s3` via `--board esp32s3-sensor-devkit --compile-only`, verified against a real ESP-IDF v6.0.2 workspace. Needed two real bugs fixed first: `REQUIRES driver` alone no longer pulls in `driver/i2c_master.h` on ESP-IDF ≥6.0 (moved to `esp_driver_i2c`); `i2c_master_bus_config_t`/`i2c_device_config_t` designated initializers were missing several fields ESP-IDF v6.0.2 added (harmless at runtime - aggregate init zero-fills them - but fatal under this repo's `-Werror -Wmissing-field-initializers`). Not yet flashed. |
| Pico SDK | `test_picosdk.sh` | ✅ reworked, `--board` added | ⬜ | ⬜ | ⬜ | ⬜ | Now compiles clean (`PICO_BOARD=pico`, RP2040) against a real Pico SDK checkout. ENS160's test app needed a real bug fixed: it called `sensor.data_ready()`/`.tvoc()`/`.eco2()`, none of which exist on `ENS160Full` (real API: `status()`, `read_tvoc()`, `read_eco2()`) - this had apparently never been compiled before this rework exercised it. Not yet flashed. |

## python

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux (CPython) | `test_linux.sh` | ✅ reworked | ⚠️ partial (pre-rework) | ⬜ | ✅ done (pre-rework) | ⬜ | Both run today via `i2c_auto`'s Linux fallback, ahead of the script rework. AHT21: 12/12. ENS160: warm-up didn't complete in the 8-minute budget, then an `OSError: [Errno 95]` on a plain write — retry once the adapter's stable. `test_linux.sh`'s `run_hil()` looked only for `<chip>_test_linux.py`, but ENS160/AHT21 share one `i2c_auto`-based file with `test_mp.sh` (`<chip>_test.py`) instead - fixed to fall back to that name. Unit level (mocked): ENS160 21/21, AHT21 14/14, both verified in this pass. |
| MicroPython | `test_mp.sh` | ✅ reworked, `--board` added | ⬜ | ⬜ | ⬜ | ⬜ | "Auto" port detection now requires a real USB VID:PID (filters out plain `ttySN` ports, which otherwise always false-positive as "board present"). Not yet run against a real board in this pass. |
| CircuitPython | `test_cp.sh` | ✅ reworked, `--board` added | ⬜ | ⬜ | ⬜ | ⬜ | Not yet run against a real board in this pass. |

## nodejs

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux | `test.sh` → `test_linux.sh` | ✅ reworked (renamed) | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. ENS160 hit the same adapter drop as every other language during the multi-language sweep — not language-specific. Unit level (mocked): ENS160 21/21, AHT21 14/14, both verified in this pass. |

## rust

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux | `test_linux.sh` | ✅ reworked | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. Unit level (`cargo test -p periph --features std`, mocked via `embedded-hal-mock`): ENS160 2/2, AHT21 1/1, both verified in this pass - `std` must be passed explicitly since the crate is `no_std` by default. |
| ESP32-S3 | `test_esp32s3.sh` | ✅ reworked, `--board` added | ⬜ | ⬜ | ⬜ | ⬜ | `--board`/detection plumbing verified up to the real build step; the build itself currently hits a pre-existing, unrelated bug (`rust/periph/src/connection/dhtxx.rs` uses `std::thread::sleep` unconditionally, breaking any `no_std` target) already on `main` - out of scope here, tracked separately. |

## go

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux | `test_linux.sh` | ✅ reworked | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. Unit level (`go test`, mocked): both verified passing in this pass, filtered via a case-insensitive `-run` regex since Go test function names don't follow the chip's lowercase package casing. |
| TinyGo (Pico W) | `test_tinygo.sh` | ✅ reworked, `--board` added | ⬜ | ⬜ | ⬜ | ⬜ | Now compiles clean for `pico-w` via `--board pico-w-sensor-devkit --compile-only`, verified against a real TinyGo 0.41.1 toolchain. Not yet flashed. |

## jvm

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Java (Linux) | `test.sh --lang java` → `test_linux_java.sh` | ✅ reworked (split) | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. Unit level (`mvn test -Dtest=<Chip>Test`): ENS160 verified passing in this pass. |
| Kotlin (Linux) | `test.sh --lang kotlin` → `test_linux_kotlin.sh` | ✅ reworked (split) | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. Unit level: AHT21 verified passing in this pass. |
| Groovy (Linux) | `test.sh --lang groovy` → `test_linux_groovy.sh` | ✅ reworked (split) | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. Unit level (`mvn test -Dtest=<Chip>Spec` - Spock's naming convention): ENS160 verified passing in this pass. |

## Still not started (needs a follow-up pass with real hardware + a logic analyzer)

- Flashing/running `hil` on every embedded platform above marked "not yet flashed" or "not yet run" (Arduino, Zephyr, ESP-IDF, Pico SDK, MicroPython, CircuitPython, Rust ESP32-S3, TinyGo).
- Retrying the "retry pending" host-language HIL rows once the flaky USB-I2C adapter is stable.
- Every `conformance` cell, on every platform, for both chips - the checker exists and its plumbing is verified against `sigrok-cli`'s `demo` driver, but it has never decoded a real capture. The first real run may need small adjustments to the annotation-text matchers in `conformance/gas/ens160_conformance.py`/`conformance/environmental/aht21_conformance.py`, or to the samplerate/capture-window values in the two `_timing.conf` files.
- `ens160_test_espidf`/`aht21_test_espidf` and `ens160_test_picosdk`/`aht21_test_picosdk`: compiled clean but never flashed - the runtime behavior (does the sensor actually respond correctly through the real bus config) is unverified.

## Legend

- ✅ done — verified passing on real hardware
- ⚠️ partial — attempted, incomplete (see notes)
- ⬜ — not yet attempted

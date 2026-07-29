# HIL / Conformance Verification Checklist — ENS160 & AHT21

Tracks working through real hardware for every platform's test script, one MCU at a time, for the two reference chips built out under issue #73 (`specs/testing_framework.md`).

## Before you start

- **Conformance is blocked on every platform** until the conformance checker itself exists (`conformance/<category>/<chip>_conformance.py` + `specs/<category>/<chip>_timing.conf` — tracked separately, not yet implemented). Every `--level conformance` run will fail fast with "conformance checker not found" regardless of which platform script you use. Leave those boxes unchecked until that lands; this checklist is ready for them in the meantime.
- **HIL is testable per platform as soon as that platform's script is reworked** (see Script status column). Untested rows below either need script work first or are already reworked and just waiting on a board.
- Both chips: ENS160 at `0x53`, AHT21 at `0x38` (confirmed via `i2cdetect` on this bench).

## cpp

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux GCC | `test_linux.sh` | ✅ reworked | ⚠️ partial | ⬜ | ✅ done | ⬜ | AHT21: 12/12, verified twice on real hardware (bus 15). ENS160: driver had a real timeout bug on Linux (fixed, `e521a65d`); last attempt got through init+status before the USB-I2C adapter dropped mid-run — retry once the adapter's back. |
| Arduino | `test_arduino.sh` | ✅ reworked | ⬜ | ⬜ | ⬜ | ⬜ | Compiles clean for `esp32:esp32:esp32s3:CDCOnBoot=cdc` (ENS160 needed 2 real bugs fixed first — wrong include paths + missing `<Arduino.h>`, `1d25298c`). Not yet flashed to a physical board. |
| Zephyr | `test_zephyr.sh` | ✅ reworked | ⬜ | ⬜ | ⬜ | ⬜ | Compiles clean for `esp32s3_devkitc/esp32s3/procpu` (needed an i2c0-enable overlay + `CONFIG_REQUIRES_FULL_LIBCPP` + a real cross-chip `CONFIG_ZEPHYR`→`__ZEPHYR__` macro bug fixed, `deb37292`). Not yet flashed. |
| ESP-IDF | `test_espidf.sh` | ⬜ not yet reworked | ⬜ | ⬜ | ⬜ | ⬜ | Toolchain confirmed present and working (`/opt/espressif/v6.0.2/esp-idf`). |
| Pico SDK | `test_picosdk.sh` | ⬜ not yet reworked | ⬜ | ⬜ | ⬜ | ⬜ | Toolchain confirmed present (`/usr/src/pico-sdk`, `picotool`). |

## python

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux (CPython) | `test_linux.sh` | ⬜ not yet reworked | ⚠️ partial (pre-rework) | ⬜ | ✅ done (pre-rework) | ⬜ | Both run today via `i2c_auto`'s Linux fallback, ahead of the script rework. AHT21: 12/12. ENS160: warm-up didn't complete in the 8-minute budget, then an `OSError: [Errno 95]` on a plain write — retry once the adapter's stable. |
| MicroPython | `test_mp.sh` | ⬜ not yet reworked | ⬜ | ⬜ | ⬜ | ⬜ | |
| CircuitPython | `test_cp.sh` | ⬜ not yet reworked | ⬜ | ⬜ | ⬜ | ⬜ | |

## nodejs

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux | `test.sh` → `test_linux.sh` | ⬜ not yet reworked (rename pending) | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. ENS160 hit the same adapter drop as every other language during the multi-language sweep — not language-specific. |

## rust

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux | `test_linux.sh` | ⬜ not yet reworked | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. |
| ESP32-S3 | `test_esp32s3.sh` | ⬜ not yet reworked | ⬜ | ⬜ | ⬜ | ⬜ | |

## go

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Linux | `test_linux.sh` | ⬜ not yet reworked | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. |
| TinyGo (Pico W) | `test_tinygo.sh` | ⬜ not yet reworked | ⬜ | ⬜ | ⬜ | ⬜ | |

## jvm

| Platform | Script | Script status | ENS160 HIL | ENS160 Conformance | AHT21 HIL | AHT21 Conformance | Notes |
|---|---|---|---|---|---|---|---|
| Java (Linux) | `test.sh --lang java` → `test_linux_java.sh` | ⬜ not yet reworked (split pending) | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. |
| Kotlin (Linux) | `test.sh --lang kotlin` → `test_linux_kotlin.sh` | ⬜ not yet reworked (split pending) | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. |
| Groovy (Linux) | `test.sh --lang groovy` → `test_linux_groovy.sh` | ⬜ not yet reworked (split pending) | ⬜ retry pending | ⬜ | ✅ done (pre-rework) | ⬜ | AHT21: 12/12. |

## Legend

- ✅ done — verified passing on real hardware
- ⚠️ partial — attempted, incomplete (see notes)
- ⬜ — not yet attempted

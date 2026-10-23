# Feature Design: Mbed CE Platform

**Status:** Draft — not yet implemented
**Branch:** `feature/mbed-ce`
**Scope:** C++ only — a seventh platform target (connection layer, chip-driver delay branch,
examples, tests, docs, CI) alongside Arduino, Linux GCC, Zephyr RTOS, ESP-IDF, Raspberry Pi Pico
SDK, and STM32Cube. No other language is affected.
**Origin:** GitHub issue — to be filed ("Mbed CE platform integration"); fill in the number here
once it exists.

This document follows the shape of `specs/feature_stm32cube_platform.md`; read that first. Where
this document says "same as STM32Cube" it means the same decision, file layout, and rollout
mechanics apply unchanged.

---

## 1. Problem Statement

[Mbed CE](https://github.com/mbed-ce/mbed-os) (Community Edition) is the community continuation
of Arm Mbed OS: a C++ embedded framework with a uniform peripheral API (`I2C`, `SPI`,
`DigitalIn`/`DigitalOut`, `InterruptIn`, `BufferedSerial`, `Timer`, `ThisThread`) across several
hundred boards from many vendors, built with CMake. Existing Mbed codebases (and boards whose
vendor support is Mbed-only) have no path to this library's chip drivers today. Arduino reaches
only the few Mbed-based Arduino cores, and those expose Arduino's API, not Mbed's.

## 2. Design Goals

1. Match the shape of the existing vendor-SDK C++ platforms exactly — one connection class per
   platform per transport, the same `minimal`/`complete`/`demo` example tiers, one standalone
   CMake project per example/test, compile-only CI.
2. **Native Mbed CE API only** — no Arduino compatibility layer. Mbed OS ships an RTOS in its
   default profile; the connections use the blocking drivers and must work with both the RTOS and
   the bare-metal profile (`"target.application-profile": "bare-metal"`).
3. **Headless, CI-buildable** — CMake + `arm-none-eabi-gcc` only; no Mbed Studio, no `mbed-cli`
   v1, no Keil/IAR.
4. **One reference board:** **NUCLEO-F411RE** (`NUCLEO_F411RE` in Mbed CE) — the same hardware
   as the STM32Cube HIL rig, so no new hardware is needed and the two platforms can be compared
   on identical silicon.
5. Every existing C++ chip driver gets a Mbed CE example + test, and every new chip spec requires
   it going forward, per `AGENTS.md`'s "every chip is implemented across all supported platforms
   within each language."

**Non-goals:** Mbed OS (Arm's original, `ARMmbed/mbed-os`) and Mbed CLI 1 / `mbed-tools` build
flows; other boards (addable later by changing the `MBED_TARGET` setting once F411RE is proven
out); BLE/Wi-Fi/network stacks; DMA or asynchronous (`transfer()` with callbacks) transfers —
blocking calls only, as on every other platform; Mbed Studio project files.

## 3. Reference Board & Toolchain

- **Board:** NUCLEO-F411RE (STM32F411RET6, Cortex-M4 @ 100 MHz, on-board ST-LINK). Mbed CE
  provides the target definition, clock setup, linker script, and startup code, so unlike
  STM32Cube **no `cpp/boards/mbed/<board>/` linker/startup/HAL-conf files are committed.**
- **Framework source:** a new `MBED_CE_PATH` env var points at a local clone of
  `mbed-ce/mbed-os` (with its submodules), mirroring `PICO_SDK_PATH` / `ZEPHYR_BASE` /
  `STM32CUBE_FW_PATH`. Not vendored into this repo.
- **Toolchain:** `arm-none-eabi-gcc` (same install as the STM32Cube job). Mbed CE ships its own
  CMake toolchain handling, so no project-local toolchain file is needed.
- **Build:** one standalone CMake project per example/test, using Mbed CE's
  `app.cmake` / `mbed_add_executable()` flow, with a per-app `mbed_app.json5` (`target_overrides`
  selecting `NUCLEO_F411RE`, and `platform.stdio-baud-rate`). Layout and relative-path convention
  (`CPP_DIR`, `MBED_CE_PATH`) match the Pico SDK / STM32Cube example projects.

**To verify in Phase 1** (written from memory, not from a test build): exact `app.cmake` include
path and `mbed_project_init()` call; how to point a project at an out-of-tree mbed-os checkout
(`MBED_OS_PATH`-style variable vs. `add_subdirectory`); the `upload_*` target for ST-LINK;
which Mbed CE release tag to pin.

## 4. Platform-Detection Macro

The shared delay chain in the chip `.cpp` files (see `specs/feature_stm32cube_platform.md` §4)
gains a branch keyed on `__MBED__`, which every Mbed build defines:

```cpp
#elif defined(__MBED__)
#include "mbed.h"
static inline void delay(unsigned long ms) { ThisThread::sleep_for(std::chrono::milliseconds(ms)); }
```

`ThisThread::sleep_for` works in both the RTOS and the bare-metal profile. `wait_us()` is used
where microsecond delays are needed.

**Ordering matters:** the Arduino core for Mbed-based boards (Nano 33 BLE, Portenta, ...) also
defines `__MBED__`. The branch must therefore sit **after** `#ifdef ARDUINO` so those builds keep
using the Arduino path. It must also come before the `__has_include(<pico/time.h>)` and the
`usleep` fallback only if Mbed's headers could satisfy them — verify in Phase 1; placing it
directly after the Arduino branch is the safe default.

Drivers with their own time helpers (MCP2515, RFM9x, ADE7953) get a Mbed branch in the same
way as they did for STM32Cube. Count of files to touch: the 35 `.cpp` files that currently carry
the `__has_include(<stm32f4xx_hal.h>)` branch.

## 5. Connection Layer

New header-only files in `cpp/src/connection/`, one Mbed CE variant per existing connection
class (`*MbedCE.h`), following the `*PicoSDK.h` / `*STM32Cube.h` style. The caller constructs the
Mbed peripheral object (pins, frequency, format); the connection wraps a reference to it —
same division of responsibility as the other platforms.

| New file | Wraps | Notes |
|---|---|---|
| `I2CConnectionMbedCE.h` | `mbed::I2C` (`write`/`read` with `repeated` flag) | Mbed takes the **8-bit** address (`addr << 1`) and returns **0 on success** (inverted vs. the other platforms' success checks). `write_read` = `write(..., repeated=true)` + `read`. |
| `SMBusConnectionMbedCE.h` | the above + software CRC-8 (PEC) | same pattern as the other `SMBusConnection*` |
| `SPIConnectionMbedCE.h` | `mbed::SPI` (`format`, `frequency`, `write(tx, txlen, rx, rxlen)`) + CS `DigitalOut` | CS pin owned by the connection and configured in the constructor (lesson from STM32Cube §12) |
| `UARTConnectionMbedCE.h` | `mbed::BufferedSerial` | read timeouts need `set_blocking(false)` + polling or `poll()`; RS-485 DE as `DigitalOut` |
| `NeoPixelConnectionMbedCE.h` | SPI bit-encoding at 2.4 MHz, mode 0 | `SPI::frequency()` is best-effort on Mbed — the achievable rate depends on the target's clock tree; document the actual rate on F411RE (expect the same 3.125 MHz limitation as STM32Cube) |
| `HX711ConnectionMbedCE.h` | `DigitalIn`/`DigitalOut` bit-bang + `wait_us` | |
| `SiPoConnectionMbedCE.h` | hardware SPI or bit-bang `DigitalOut` | |
| `DHTxxConnectionMbedCE.h` | `DigitalInOut` + `Timer` (`us_ticker`) | single-wire timing; critical sections via `CriticalSectionLock` |
| `InputPinMbedCE.h` | `mbed::InterruptIn` | Mbed delivers callbacks directly, so **no fixed-size handler fan-out / trampoline** is needed (unlike Zephyr/Pico/STM32Cube) |
| `OutputPinMbedCE.h` | `mbed::DigitalOut` | |

No `.cpp` companions — header-only, like Zephyr/ESP-IDF/Pico SDK/STM32Cube.

Pin identification uses Mbed's `PinName` (e.g. `PB_8`); the `Pin` type used by the constructors
is `PinName`, passed through unchanged.

## 6. Build & Test Infrastructure

- `cpp/examples/mbedce/<category>/<Chip>/<tier>/{CMakeLists.txt,mbed_app.json5,main.cpp}`
- `cpp/tests/<category>/<chip>_test_mbedce/{CMakeLists.txt,mbed_app.json5,main.cpp}`
- `cpp/test_mbedce.sh` + `cpp/testconfig_mbedce.example` — mirrors `test_stm32cube.sh`:
  `hil`/`conformance` levels, `--board`, `--compile-only`, flashing via the Mbed CE `flash`
  target / `st-flash`
- `cpp/scripts/build-all.sh` — new `mbedce` case, requiring `MBED_CE_PATH`
- `cpp/scripts/generate_mbedce_examples.py` — derives the examples and tests from the existing
  STM32Cube ones (only the connection construction and the stdio differ), like
  `generate_picosdk_examples.py`
- `scripts/generate_stats.py` — add the platform to its three platform maps

The directory name is `mbedce` (no hyphen/underscore), consistent with `picosdk`, `espidf`,
`stm32cube`.

## 7. Continuous Integration

New compile-only job in `.github/workflows/ci.yml`, same posture as the other embedded platforms
(no Mbed hardware in CI; HIL stays manual):

1. Install `gcc-arm-none-eabi`, `cmake`, `ninja`; cache a pinned `mbed-ce/mbed-os` checkout
   (keyed on the pin).
2. `cpp/scripts/build-all.sh mbedce`, sharded.

**Main risk — build time.** Every standalone Mbed CE project compiles the whole mbed-os tree
(HAL, drivers, platform, optionally RTOS) for its target. With ~275 apps that is far more than
STM32Cube, which compiles only a handful of HAL files per app. Options, to decide in Phase 1
after measuring a clean single-app build:

| Option | Trade-off |
|---|---|
| A. Plain per-app projects, ccache, more shards (≥8) | Matches repo convention; CI cost high; ccache hit rate depends on identical flags/config across apps (keep every `mbed_app.json5` identical except stdio) |
| B. **CI-only superproject**: `build-all.sh mbedce` generates one top-level CMake project that `add_subdirectory`s mbed-os once and every app as an extra `mbed_add_executable` | mbed-os built once; far faster; deviates from "one project per app" for CI only — example projects stay standalone for users |
| C. Bare-metal profile + a minimal `mbed_app.json5` | Shrinks the OS build in either option; combine with A or B |

Recommendation: start with A + C on the pilot, measure, and switch to B if a full run exceeds
the other platforms' wall time by more than ~2×.

## 8. Documentation Changes

Mechanical updates, following the STM32Cube rollout (`specs/feature_stm32cube_platform.md` §8):

- `CLAUDE.md` — C++ platform list and repository layout (`cpp/examples/mbedce/`); `AGENTS.md` —
  "six supported targets" → seven, connection-class table, "Where things go" table, Tests
  table, commit-label table (`C++/MbedCE`), the "Embedded builds are mandatory" list
- `TESTING.md` — new "Mbed CE (`cpp/test_mbedce.sh`)" section; `TOOLCHAINS.md` — install and
  `MBED_CE_PATH` setup
- `README.md` — C++ platform list; `EXAMPLES.md`; `specs/hil_conformance_checklist.md`
- Chip spec templates (`specs/_template_chip*.md`) — list the Mbed CE examples and test
- `specs/transport_{i2c,spi,smbus,uart,neopixel,hx711,sipo,dhtxx}.md` — add a Mbed CE
  platform-notes subsection each
- Wiki — chip pages' platform matrices get an Mbed CE column as each chip's support lands

## 9. Rollout Plan

1. **Phase 1 — Plumbing.** All ten connection headers, the 35-file delay-branch insertion,
   `test_mbedce.sh`, `build-all.sh` case, CI job skeleton — proven end-to-end against the pilot
   chip **ADXL345** (I²C) on a real NUCLEO-F411RE, plus one SPI chip to cover the 8-bit-address /
   return-code inversion differences early. Measure clean and cached build times and settle the
   CI option from §7.
2. **Phase 2 — Docs.** §8, landed together once Phase 1 compiles clean and the pilot passes HIL.
3. **Phase 3 — Full retrofit.** Examples + tests for the remaining existing chips (~270 apps),
   generated with `generate_mbedce_examples.py` and landed in batches of ~19 chips, tracked as
   sub-issues like #428–#484.
4. **Phase 4 — Make it routine.** Chip spec templates and `AGENTS.md` list Mbed CE alongside the
   other platforms so every new chip includes it with no separate tracking.

## 10. Design Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Mbed OS or Mbed CE? | Mbed CE only — CMake-based, community-maintained, headless-friendly |
| 2 | Reference board? | NUCLEO-F411RE, shared with STM32Cube HIL rig |
| 3 | RTOS or bare-metal? | Code must work in both profiles; CI builds bare-metal if it measurably shortens builds |
| 4 | Build system? | Standalone CMake per example/test (Mbed CE `app.cmake`) + `MBED_CE_PATH`; CI may use a generated superproject (§7 option B) |
| 5 | Platform-detection macro? | `defined(__MBED__)`, placed after `ARDUINO` |
| 6 | Pin type? | `PinName` passed through |
| 7 | Rollout? | Plumbing + pilot, docs, then generated retrofit in batches (same as STM32Cube) |

## 11. Open Questions

1. **Project longevity.** Arm has announced the end of life of Mbed OS; Mbed CE continues it as a
   community fork. Confirm the project's current maintenance status and release cadence
   before committing to ~275 apps. If the answer is unfavourable, stop after Phase 2 (pilot only)
   rather than doing the full retrofit.
2. **mbed-os checkout size / CI fetch time.** Measure a sparse/shallow clone with only the
   `NUCLEO_F411RE` target's submodules.
3. **Which Mbed CE tag to pin**, and whether `app.cmake` supports an out-of-tree mbed-os path
   without a git submodule in every example directory.
4. **NeoPixel rate on F411RE** via `SPI::frequency()`; document the same way as STM32Cube §12.
5. **UART read-timeout semantics** of `BufferedSerial` across the RTOS and bare-metal profiles.

## 12. Implementation Checklist

### Build & test scripts
- [ ] `cpp/scripts/build-all.sh` `mbedce` case
- [ ] `cpp/test_mbedce.sh`, `cpp/testconfig_mbedce.example`
- [ ] `cpp/scripts/generate_mbedce_examples.py`
- [ ] `scripts/generate_stats.py` platform maps

### Platform detection
- [ ] `defined(__MBED__)` delay branch in the 35 chip `.cpp` files (after `ARDUINO`)

### Connection layer
- [ ] `I2CConnectionMbedCE.h`, `SMBusConnectionMbedCE.h`, `SPIConnectionMbedCE.h`,
      `UARTConnectionMbedCE.h`, `NeoPixelConnectionMbedCE.h`, `HX711ConnectionMbedCE.h`,
      `SiPoConnectionMbedCE.h`, `DHTxxConnectionMbedCE.h`, `InputPinMbedCE.h`,
      `OutputPinMbedCE.h`

### CI
- [ ] Compile-only job in `ci.yml`; build-time measurement and option decision (§7)

### Docs
- [ ] `CLAUDE.md`, `AGENTS.md`, `TESTING.md`, `TOOLCHAINS.md`, `README.md`, `EXAMPLES.md`,
      `specs/hil_conformance_checklist.md`, chip spec templates, the eight `specs/transport_*.md`

### Pilot chip (ADXL345) + one SPI chip
- [ ] `cpp/examples/mbedce/accelerometer/ADXL345/{minimal,complete,demo}/`
- [ ] `cpp/tests/accelerometer/adxl345_test_mbedce/`
- [ ] Compiles clean against a real `mbed-ce/mbed-os` checkout
- [ ] Flashed to a NUCLEO-F411RE and HIL test passes

### Full retrofit (Phase 3 — tracked separately)
- [ ] Examples + tests for all remaining C++ chips, in batches

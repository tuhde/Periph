# Feature Design: STM32Cube Platform

**Status:** Implemented — Phases 1–3 merged (PRs #427, #485, #487, #488); Phase 4 done in this change
**Branch:** `feature/stm32cube`
**Scope:** C++ only — a sixth platform target (connection layer, chip-driver delay branch,
examples, tests, docs, CI) alongside Arduino, Linux GCC, Zephyr RTOS, ESP-IDF, and Raspberry
Pi Pico SDK. No other language is affected.
**Origin:** GitHub issue #426 ("STM32Cube platform integration")

---

## 1. Problem Statement

STM32 is one of the most widely used MCU families in embedded and hobbyist projects, but this
repo's C++ implementation has no native STM32Cube (HAL, bare-metal) target. Arduino covers some
STM32 boards only through a community core with inconsistent peripheral coverage; Zephyr covers
STM32 silicon too, but forces devicetree and RTOS overhead on users who just want
STM32CubeMX-generated HAL code. There is no direct path for a plain CubeMX/HAL project to use
this library's chip drivers today.

## 2. Design Goals

1. Match the shape of the four existing bare-metal-or-vendor-SDK C++ platforms exactly — one
   connection class per platform per transport, same example tier structure
   (`minimal`/`complete`/`demo`), same CMake-project-per-example layout, same compile-only CI
   approach.
2. **Bare-metal HAL only** — "STM32Cube" here means "STM32CubeMX-generated HAL drivers used
   directly," the same niche Pico SDK and ESP-IDF occupy today, not "ST HAL + FreeRTOS." Zephyr
   already covers the RTOS niche on STM32 silicon.
3. **Headless, CI-buildable** — a plain CMake project per example/test, no STM32CubeIDE
   `.project`/`.cproject` Eclipse files (CubeIDE has no documented headless CLI build suitable
   for GitHub Actions).
4. **One reference board** for examples, tests, and CI — matching how Pico SDK standardized on
   `pico` and ESP-IDF on `esp32s3-sensor-devkit`: **NUCLEO-F411RE**.
5. Every existing C++ chip driver eventually gets an STM32Cube example + test — a full retrofit
   at the same scale as the original Pico SDK rollout (`714252c4`) — and every new chip spec
   requires it going forward, per `AGENTS.md`'s "every chip is implemented across all six
   languages and every supported platform within each language."

**Non-goals:** FreeRTOS integration; STM32CubeIDE project files; other STM32 families/boards
(F1/F7/G0/H7/L4/...) — addable later the same way Zephyr adds board overlays, once F4 is
proven out; DMA or interrupt-driven transfers — blocking HAL calls only, matching every other
platform's blocking style; a Zephyr-module-style external package registry (STM32Cube/CubeMX has
no such system — consuming projects add `cpp/src/connection` and the relevant
`cpp/src/chips/<category>` as include dirs directly, the same manual wiring ESP-IDF and Pico SDK
examples already use).

## 3. Reference Board & Toolchain

- **Board:** NUCLEO-F411RE (STM32F411RET6, Cortex-M4 @ 100 MHz, hardware FPU, on-board ST-LINK).
  Cheapest, most ubiquitous Nucleo-64 board, well-trodden HAL package (STM32CubeF4) — same
  "cheap, available, well-documented" reasoning behind the Pico and ESP32-S3-devkit picks.
- **HAL source:** a new `STM32CUBE_FW_PATH` env var points at a locally cloned `STM32CubeF4`
  firmware repo (HAL drivers + CMSIS device headers + startup files), exactly mirroring
  `PICO_SDK_PATH` / `ZEPHYR_BASE` / `IDF_PATH`. Not vendored into this repo.
- **Toolchain:** `arm-none-eabi-gcc` + a project-local CMake toolchain file,
  `cpp/boards/stm32cube/toolchain-arm-none-eabi.cmake`, plus a linker script (`STM32F411RETX_FLASH.ld`)
  and startup file (`startup_stm32f411xe.s`) checked into `cpp/boards/stm32cube/nucleo-f411re/`
  (pulled from the CubeF4 package's board example at first use, then committed — same reasoning
  as Zephyr's committed board overlay).
- **Build:** one standalone CMake project per example/test, same layout as Pico SDK
  (`CMakeLists.txt` + `src/main.cpp` / `Core/Src/main.cpp`), not a CubeIDE project.

## 4. Platform-Detection Macro

Thirty chip driver `.cpp` files have a shared delay/platform `#if` chain (e.g.
`cpp/src/chips/accelerometer/ADXL345.cpp:5-20`):

```cpp
#ifdef ARDUINO
#include <Arduino.h>
#elif defined(__ZEPHYR__)
#include <zephyr/kernel.h>
static inline void delay(unsigned long ms) { k_sleep(K_MSEC(ms)); }
#elif defined(ESP_PLATFORM)
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>
static inline void delay(unsigned long ms) { vTaskDelay(pdMS_TO_TICKS(ms)); }
#elif __has_include(<pico/time.h>)
#include <pico/time.h>
static inline void delay(unsigned long ms) { sleep_ms(ms); }
#else
#include <unistd.h>
static inline void delay(unsigned long ms) { usleep(ms * 1000UL); }
#endif
```

Pico SDK is detected by **header availability** (`__has_include(<pico/time.h>)`), not a vendor
macro — there is no single `PICO_SDK` define either. STM32Cube gets the same treatment, added as
its own branch before the Pico SDK one (order doesn't matter for correctness — the `__has_include`
targets never coexist — but keeps platforms in rollout order):

```cpp
#elif __has_include(<stm32f4xx_hal.h>)
#include <stm32f4xx_hal.h>
static inline void delay(unsigned long ms) { HAL_Delay(ms); }
```

`HAL_Delay` is provided directly by the STM32 HAL (backed by `SysTick`), no extra include needed.

**Future multi-family note:** once a second STM32 family is added, this becomes
`__has_include(<stm32f4xx_hal.h>) || __has_include(<stm32f1xx_hal.h>) || ...` — out of scope here,
called out so the next platform-family addition doesn't have to rediscover the pattern.

All 30 files need this branch inserted; it is shared driver code (counted once, not per-platform,
per `STATS.md`'s convention) and is part of the connection-layer work in §9 Phase 1, not the
per-chip example/test retrofit in Phase 3.

## 5. Connection Layer

New header-only files in `cpp/src/connection/`, one STM32Cube variant per existing connection
class, following the `*ESPIDF.h` / `*PicoSDK.h` style (constructor takes an already-configured HAL
handle; caller owns `MX_I2C1_Init()`/`MX_SPI1_Init()`/etc., same division of responsibility as
ESP-IDF's "caller creates the bus, constructor just wraps the handle"):

| New file | Wraps |
|---|---|
| `I2CConnectionSTM32Cube.h` | `HAL_I2C_Master_Transmit`/`_Receive` on an `I2C_HandleTypeDef*`; `write_read` via `HAL_I2C_Mem_Read`/`Write` where a register convention applies |
| `SMBusConnectionSTM32Cube.h` | wraps the above + software CRC-8 (PEC), same pattern as `SMBusConnectionESPIDF.h` |
| `SPIConnectionSTM32Cube.h` | `HAL_SPI_Transmit`/`_Receive` on `SPI_HandleTypeDef*` + manual CS GPIO via `HAL_GPIO_WritePin` |
| `UARTConnectionSTM32Cube.h` | `HAL_UART_Transmit`/`_Receive` on `UART_HandleTypeDef*`; GPIO DE pin for RS-485 |
| `NeoPixelConnectionSTM32Cube.h` | same SPI bit-encoding trick as every other platform (2.4 MHz, mode 0) via `HAL_SPI_Transmit` — keeps WS2812B timing identical everywhere, no TIM+DMA special-case |
| `HX711ConnectionSTM32Cube.h` | `HAL_GPIO_ReadPin`/`WritePin` bit-bang |
| `SiPoConnectionSTM32Cube.h` | hardware SPI or bit-bang GPIO |
| `DHTxxConnectionSTM32Cube.h` | single-wire bit-bang on a GPIO pin, `HAL_GPIO_ReadPin`/`WritePin` + `DWT`-cycle-counter microsecond timing (HAL has no built-in `micros()`; `DWT->CYCCNT` is the standard Cortex-M technique, same role Pico SDK's `time_us_32()` plays) |
| `InputPinSTM32Cube.h` | EXTI line + `HAL_GPIO_EXTI_Callback` trampoline, same fixed-size handler fan-out as `InputPinZephyr.h`/`InputPinPicoSDK.h` |
| `OutputPinSTM32Cube.h` | `HAL_GPIO_WritePin` |

No `.cpp` companion files needed — same reasoning as Zephyr/ESP-IDF/Pico SDK's header-only
connections (no compiled sources beyond what each example already builds).

## 6. Build & Test Infrastructure

- `cpp/boards/stm32cube/toolchain-arm-none-eabi.cmake` — standard ARM GCC CMake toolchain file
  (compiler paths, `-mcpu=cortex-m4 -mfpu=fpv4-sp-d16 -mfloat-abi=hard`, etc.)
- `cpp/boards/stm32cube/nucleo-f411re/{STM32F411RETX_FLASH.ld,startup_stm32f411xe.s,Core/Inc/stm32f4xx_hal_conf.h}`
- `cpp/examples/stm32cube/<category>/<Chip>/<tier>/{CMakeLists.txt,Core/Src/main.cpp}` — one
  standalone CMake project per example, same `CPP_DIR` relative-path convention as Pico SDK's
  example `CMakeLists.txt`
- `cpp/tests/<category>/<chip>_test_stm32cube/{CMakeLists.txt,Core/Src/main.cpp}`
- `cpp/test_stm32cube.sh` + `cpp/testconfig_stm32cube.example` — new runner mirroring
  `test_picosdk.sh`: `hil`/`conformance` levels, `--board`, `--compile-only`, ST-LINK flashing via
  `st-flash` or `STM32_Programmer_CLI`
- `cpp/scripts/build-all.sh` — add an `stm32cube` case next to `picosdk`/`espidf`/`zephyr`,
  requiring `STM32CUBE_FW_PATH`

## 7. Continuous Integration

New compile-only job in `.github/workflows/ci.yml`, same posture as the other three embedded
platforms today (no self-hosted STM32 hardware in CI — HIL stays manual, per
`specs/hil_conformance_checklist.md`'s "not yet flashed" precedent for Zephyr/ESP-IDF/Pico SDK):

1. Install `gcc-arm-none-eabi` (apt) or the Arm GNU Toolchain release used by `TOOLCHAINS.md`.
2. Clone/cache a pinned `STM32CubeF4` tag (cache keyed on the pin, same pattern as the Arduino
   ESP32/AVR core caches already in `ci.yml`).
3. `cpp/scripts/build-all.sh stm32cube`, sharded the same way Arduino/Zephyr/ESP-IDF/Pico SDK are.

## 8. Documentation Changes

Mechanical updates, following exactly how the Pico SDK rollout touched these same files
(`273bd524`, `6c52c770`/`66d9fa3d`, `02de616b`):

- `CLAUDE.md` — C++ platform list: add STM32Cube
- `AGENTS.md` — "five supported targets" → six (§"C++ conventions"); the per-connection-class
  table (§"Connection implementations"); the "Where things go" table (new STM32Cube examples
  row); the Tests table; the commit-label table (`C++/STM32Cube`)
- `TESTING.md` — new "STM32Cube (`cpp/test_stm32cube.sh`)" section mirroring the Pico SDK section
  (prerequisites, config, `--compile-only`, `--board`), plus a test-app CMake/main.cpp template
- `TOOLCHAINS.md` — new "STM32Cube" subsection (toolchain install, `STM32CUBE_FW_PATH` setup)
- `README.md` — C++ row's platform list
- `EXAMPLES.md`, `specs/hil_conformance_checklist.md` — add STM32Cube rows
- `specs/transport_i2c.md`, `transport_spi.md`, `transport_smbus.md`, `transport_uart.md`,
  `transport_neopixel.md`, `transport_hx711.md`, `transport_sipo.md`, `transport_dhtxx.md` — add an STM32Cube
  platform-notes subsection to each
- Wiki — no page-by-page edits required; `<ChipName>.md` platform matrices only need a new column
  once that chip's STM32Cube support actually lands (handled per-chip during Phase 3, not here)

## 9. Rollout Plan

1. **Phase 1 — Plumbing.** Toolchain file, board files, all ten connection headers (§5), the
   30-file delay-branch insertion (§4), `test_stm32cube.sh`, `build-all.sh` case, CI job skeleton —
   all proved end-to-end against **one pilot chip** (recommend `ADXL345`: I²C, already has the
   delay chain, simplest register convention) compiled locally against a real `STM32CubeF4`
   checkout before anything else lands.
2. **Phase 2 — Docs.** §8, landed together once Phase 1 compiles clean.
3. **Phase 3 — Full retrofit.** Examples + tests for the remaining ~58 existing chips. This is
   the bulk of the work (comparable in size to Pico SDK's own rollout, ~11k lines per
   `STATS.md`), tracked as its own batch of implementation work (per-chip `backlog.md` entries or
   issues) once Phases 1-2 are merged — not enumerated chip-by-chip in this spec.
4. **Phase 4 — Make it required.** Once Phase 3 lands, the CI job moves from informational to a
   required check, and every new chip spec lists STM32Cube under Stages/Checklist like the other
   five platforms, no separate tracking needed. *Done:* the chip spec templates now list the
   STM32Cube examples and test. `main` has no branch protection, so no CI job is a *required*
   GitHub check; the `STM32Cube compile (n/4)` shards simply run in CI like every other platform's.

## 10. Design Decisions

| # | Question | Decision |
|---|---|---|
| 1 | Reference board/family? | NUCLEO-F411RE / STM32F4, chosen for cost + ubiquity + HAL maturity (§3) |
| 2 | RTOS or bare-metal? | Bare-metal HAL only — Zephyr already covers STM32 + RTOS |
| 3 | Build system? | Standalone CMake per example/test + `STM32CUBE_FW_PATH` env var, **not** STM32CubeIDE project files — CubeIDE has no CI-friendly headless build |
| 4 | Module/package-registry equivalent (like Zephyr's `west`)? | None — no such system exists for CubeMX/HAL projects; consumers add include dirs directly, same as ESP-IDF/Pico SDK examples do today |
| 5 | Platform-detection macro for the shared delay `#if` chain? | `__has_include(<stm32f4xx_hal.h>)`, matching the existing header-availability idiom used for Pico SDK detection (§4) |
| 6 | Scope of initial rollout vs. full retrofit? | Plumbing + one pilot chip first (Phase 1-2); full retrofit across all existing chips is follow-up work tracked separately (Phase 3), matching how Pico SDK itself shipped in two stages |

## 11. Implementation Checklist

### Toolchain & board files
- [x] `cpp/boards/stm32cube/toolchain-arm-none-eabi.cmake`
- [x] `cpp/boards/stm32cube/nucleo-f411re/{STM32F411RETX_FLASH.ld,startup_stm32f411xe.s,Core/Inc/stm32f4xx_hal_conf.h}`

### Platform detection
- [x] `__has_include(<stm32f4xx_hal.h>)` delay branch added to all 30 chip `.cpp` files currently
      carrying the Arduino/Zephyr/ESP-IDF/Pico SDK chain (§4). Three more drivers carried their own
      time helpers and got a branch in Phase 3: MCP2515, RFM9x, ADE7953

### Connection layer
- [x] `I2CConnectionSTM32Cube.h`, `SMBusConnectionSTM32Cube.h`, `SPIConnectionSTM32Cube.h`,
      `UARTConnectionSTM32Cube.h`, `NeoPixelConnectionSTM32Cube.h`, `HX711ConnectionSTM32Cube.h`,
      `SiPoConnectionSTM32Cube.h`, `DHTxxConnectionSTM32Cube.h`, `InputPinSTM32Cube.h`,
      `OutputPinSTM32Cube.h` (§5)

### Build & test scripts
- [x] `cpp/test_stm32cube.sh`, `cpp/testconfig_stm32cube.example`
- [x] `cpp/scripts/build-all.sh` `stm32cube` case

### CI
- [x] New compile-only job in `.github/workflows/ci.yml` (§7); now sharded 4 ways with a bounded setup (PR #486)

### Docs
- [x] `CLAUDE.md`, `AGENTS.md`, `TESTING.md`, `TOOLCHAINS.md`, `README.md`, `EXAMPLES.md`,
      `specs/hil_conformance_checklist.md`, the eight `specs/transport_*.md` files (§8)

### Pilot chip (ADXL345)
- [x] `cpp/examples/stm32cube/accelerometer/ADXL345/{minimal,complete,demo}/`
- [x] `cpp/tests/accelerometer/adxl345_test_stm32cube/`
- [x] Compiles clean locally against a real `STM32CubeF4` checkout with `arm-none-eabi-gcc`

### Hardware-in-loop
- [x] ADXL345 flashed to a real NUCLEO-F411RE via ST-LINK and confirmed reading plausible
      acceleration values over the on-board I²C pins (the HIL test, which also checks DEVID and HAL
      errors, passed 6/6 and the demo streamed ~1.00 g at rest; the minimal example itself was not
      separately flashed)

### Full retrofit (Phase 3 — tracked separately, not part of this spec's checklist)
- [x] Examples + tests for the remaining 57 existing C++ chips (58 with the pilot), done as sub-issues
      #428–#484 in three batches (PRs #485, #487, #488)

## 12. Open Follow-Up

Tracked as GitHub issue #426 (https://github.com/tuhde/Periph/issues/426). All four phases are done.

Known follow-ups (not yet verified or fixed):
- Only the I²C connection has run on real hardware (ADXL345); the SPI, UART, NeoPixel, HX711, SiPo,
  DHTxx and SMBus STM32Cube connections are compile-checked and, for some tests, boot-checked only.
- NeoPixel runs SPI1 at 3.125 MHz (APB2 / 32), not the specified 2.4 MHz: SPI1 cannot produce 2.4 MHz
  from the 100 MHz APB2 clock. Bit timing is slightly off the WS2812B datasheet and the 16-byte reset
  pad is ~41 µs rather than >50 µs.
- `SPIConnectionSTM32Cube` writes its CS pin but never configures it, so every SPI example sets CS up
  itself.
- Several Pico SDK tests the STM32Cube ones were ported from are stubs that only print `PASS probe`
  (HX711, HX710A/B, DHT11, NEO6, WS2812B, SK6812RGBW, WS2814).
- The NOP busy-loop in `ADE7953::_delayMs` still serves the non-STM32Cube platforms and is not
  wall-clock accurate.

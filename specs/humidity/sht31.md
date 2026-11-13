# Chip Spec: SHT31

**Manufacturer:** Sensirion  
**Datasheet:** `datasheets/humidity/sht31.pdf` (SHT3x-DIS, Version 0.93, May 2015)  
**Category:** humidity  
**Transports:** I²C

> **Note:** The SHT31 measures relative humidity and temperature (no pressure); it sits in `humidity` next to the SHTC3 and HDC1080. The datasheet covers the whole SHT3x-DIS family (SHT30/31/35, differing only in accuracy grade); the same driver applies. The committed datasheet is a preliminary revision (watermarked).

## Overview

The SHT31 is a digital humidity and temperature sensor with 16-bit results (typ. accuracy ±2 %RH and ±0.2 °C), a supply range of 2.4–5.5 V and I²C up to 1 MHz. Measurements are started with 16-bit commands in **single shot** mode (with or without clock stretching, three repeatability levels) or in **periodic** mode (0.5, 1, 2, 4 or 10 measurements per second, plus the 4 Hz ART mode). A heater, a status register, a CRC-8 on every word, an alert output and a reset pin are provided. Typical uses: HVAC, weather stations, data loggers.

## Transport Configuration

### I²C
- **Address:** `0x44` (ADDR pin low, default) or `0x45` (ADDR pin high); the ADDR pin must not float.
- **Max clock:** 1 MHz (fast-mode plus); clock stretching supported in single-shot mode
- **Supply:** 2.4–5.5 V (3.3 V typ.); decouple with 100 nF
- **Command protocol:** 16-bit commands, MSB first; no register pointer. Results are 16-bit words each followed by a CRC-8.
- **Identity register:** none exposed as a fixed value (the status register at `0xF32D` has a default of `0x8010`, but it varies with state); `id_probe: null` in `registry/chips.json`.
- **Probe safety:** `write_sensitive` — any 2-byte write is interpreted as a command (possibly starting a measurement or the heater).

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| SDA, SCL | — | I²C; external pull-ups |
| ADDR | — | selects `0x44` (low) / `0x45` (high) |
| ALERT | high | alert output (limit alerts configured through commands not documented in this datasheet revision); leave open if unused |
| nRESET | low | hardware reset; internal pull-up; ≥ 350 ns pulse; leave open if unused |
| VDD, VSS | — | 2.4–5.5 V |

## Command Set

All commands are 2 bytes, MSB first.

### Single shot

| Repeatability | Clock stretching enabled | Clock stretching disabled |
|---------------|--------------------------|---------------------------|
| High | `0x2C06` | `0x2400` |
| Medium | `0x2C0D` | `0x240B` |
| Low | `0x2C10` | `0x2416` |

Readout: 6 bytes — T MSB, T LSB, CRC, RH MSB, RH LSB, CRC. With stretching disabled the read header is NACKed until the measurement is done; with stretching enabled the sensor ACKs and holds SCL low until done.

### Periodic (clock stretching not available)

| Repeatability | 0.5 mps | 1 mps | 2 mps | 4 mps | 10 mps |
|---------------|---------|-------|-------|-------|--------|
| High | `0x2032` | `0x2130` | `0x2236` | `0x2334` | `0x2737` |
| Medium | `0x2024` | `0x2126` | `0x2220` | `0x2322` | `0x2721` |
| Low | `0x202F` | `0x212D` | `0x222B` | `0x2329` | `0x272A` |

- **ART** (accelerated response time, 4 mps): `0x2B32`.
- **Fetch data:** `0xE000` — then read 6 bytes (same layout); the read header is NACKed if no data is available.
- **Break (stop periodic mode):** `0x3093` (back to single shot mode; ≤ 1 ms recommended before the next command).

### Other commands

| Command | Code | Description |
|---------|------|-------------|
| Soft reset | `0x30A2` | reset system controller, reload calibration; sensor idle after ≤ 1 ms |
| Heater enable | `0x306D` | heater on (≈ 5–25 mW) |
| Heater disable | `0x3066` | heater off (default after reset) |
| Read status register | `0xF32D` | 2 bytes + CRC |
| Clear status register | `0x3041` | clears bits 15, 11, 10, 4 |
| General call reset | `0x00` `0x06` | resets **all** general-call devices on the bus |

### Status register (`0xF32D`)

| Bit | Name | Description |
|-----|------|-------------|
| 15 | Alert pending | `1` = at least one pending alert (default `1`) |
| 13 | Heater status | `1` = heater on |
| 11 | RH tracking alert | `1` = alert |
| 10 | T tracking alert | `1` = alert |
| 4 | System reset detected | `1` since the last clear (default `1` after reset) |
| 1 | Command status | `1` = last command not processed (invalid or failed its command checksum) |
| 0 | Write data checksum status | `1` = checksum of last write transfer failed |

Default after reset `0x8010`.

## Initialization Sequence

1. Power up (VDD ≥ VPOR); wait tPU ≤ 1 ms for the sensor to reach idle.
2. Send soft reset `0x30A2`; wait ≤ 1 ms.
3. Optionally read the status register (`0xF32D`), verify its CRC and clear it (`0x3041`).
4. Trigger a single-shot measurement (`0x2400` high repeatability, no stretching), wait the conversion time (≤ 15 ms) or poll the read header until ACKed, read 6 bytes and verify both CRCs.

## Interrupt

Level 0 — the ALERT pin and its limit registers are not part of this driver (the datasheet revision covers alert mode only in a separate application note).

## Implementation Stages

### Minimal

Goal: read temperature and humidity with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Waits 1 ms, soft reset, waits 1 ms |
| `read` | — | `(temperature_c, humidity_pct)` | Single shot, high repeatability, clock stretching disabled (`0x2400`); waits 15 ms; reads 6 bytes; verifies both CRCs (raises on mismatch after one retry) |

**Sensible defaults:** high repeatability, no clock stretching, fixed 15 ms wait (polls if the read is NACKed), CRC checked, heater off.

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_temperature` | — | `float` | °C (same cycle) |
| `read_humidity` | — | `float` | %RH |
| `read_raw` | — | `(int, int)` | Raw 16-bit (temperature, humidity) |
| `set_repeatability` | `level: str` | — | `'low'`, `'medium'`, `'high'` for later single-shot reads (waits 4 / 6 / 15 ms) |
| `set_clock_stretching` | `on: bool` | — | Use the stretching command set (needs controller support); the wait is skipped |
| `start_periodic` | `rate: float`, `repeatability: str='high'`, `art: bool=False` | — | `rate` ∈ {0.5, 1, 2, 4, 10} mps, or ART (4 mps) |
| `fetch` | — | `(temperature_c, humidity_pct)` or `None` | `0xE000`; `None` when the read header is NACKed (no new data) |
| `stop_periodic` | — | — | Break command `0x3093`, wait 1 ms |
| `set_heater` | `on: bool` | — | `0x306D` / `0x3066` |
| `read_status` | — | `int` | Status register (CRC verified) |
| `clear_status` | — | — | `0x3041` |
| `soft_reset` | — | — | `0x30A2`, 1 ms; leaves single-shot mode |
| `dew_point` | — | `float` | °C from one measurement (Magnus formula) |

**Additional configuration options:** repeatability (3), clock stretching, periodic rate (5 + ART), heater.

## Data Conversion

```
S_T  = (MSB << 8) | LSB                     # first word  (temperature)
S_RH = (MSB << 8) | LSB                     # second word (humidity)
RH_percent = 100.0 * S_RH / 65535.0
T_celsius  = -45.0 + 175.0 * S_T / 65535.0
```

CRC-8 per word: polynomial `0x31` (x⁸ + x⁵ + x⁴ + 1), initial value `0xFF`, no reflection, final XOR `0x00`, over the two data bytes. Example: `0xBEEF` → `0x92`.

### Dew point

Magnus formula (same constants as `specs/humidity/shtc3.md`):

```
gamma = ln(RH/100) + (17.62 * T) / (243.12 + T)
Td    = 243.12 * gamma / (17.62 - gamma)
```

## Node-RED

Node name: `periph-sht31`  
Package: `node-red-contrib-periph-humidity`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature: float, humidity: float }` | °C and %RH |

Config panel fields: I²C bus number (default `1`), I²C address (`0x44`/`0x45`, default `0x44`), repeatability (default high).

### Demo flow

Inject node (repeat every 2 s) → `periph-sht31` → Function node (format `"%.2f °C, %.1f %%RH"`) → Debug node.

## Examples

### Demo

Humidity logger with periodic mode and heater test. Soft reset, print the status register, take three single-shot readings at low, medium and high repeatability (print values and the elapsed time), then start periodic mode at 2 mps, `fetch` ten samples (waiting while `fetch` returns `None`), stop it, enable the heater for 10 s printing the temperature rise and heater bit, switch it off, clear the status register and print the dew point. Exercises `init`, `read`, `read_temperature`, `read_humidity`, `read_raw`, `set_repeatability`, `set_clock_stretching`, `start_periodic`, `fetch`, `stop_periodic`, `set_heater`, `read_status`, `clear_status`, `soft_reset`, `dew_point`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Power-up (tPU) | ≤ 1 ms (0.5 ms typ.) |
| Soft reset (tSR) | ≤ 1 ms (0.5 ms typ.) |
| Measurement, low / medium / high repeatability | ≤ 4 / 6 / 15 ms (2.5 / 4.5 / 12.5 ms typ.) |
| Reset pulse (nRESET) | ≥ 350 ns |
| Max I²C clock | 1 MHz |

**Conformance-checked constraint:** the maximum single-shot measurement time (15 ms, high repeatability) via the `conversion_start` / `conversion_done` annotation pair (STOP after a single-shot command → first ACKed read header); see `sht31_timing.conf`.

## Implementation Notes

- **Command writes are 2 bytes, MSB first;** the sensor NACKs an invalid command (status bit 1).
- **Clock stretching off by default:** works on every bus layer; the NACK of the read header means "not ready".
- **Periodic mode:** send `0x3093` (break) before issuing any other command, otherwise it may be ignored.
- **Per-word CRC:** init `0xFF`, polynomial `0x31`; a read can be aborted after the first CRC byte with a NACK.
- **General call reset resets every device** that supports it on the bus; the driver uses the soft-reset command instead.
- **Heater:** ≈ 5–25 mW depending on VDD; for plausibility checks only, readings are unusable while it is on.
- **Address overlap:** `0x44`/`0x45` also host other chips (e.g. INA2xx at `0x40`–`0x4F`); the SHT31 has no fixed identity value, so discovery cannot identify it.
- **Interface reset:** toggling SCL nine or more times with SDA high resets the interface only; the status register keeps its content.

## Sigrok Decoder

Decoder id `sht31`, input `['i2c']`. Matches I²C addresses `0x44` and `0x45`. Annotates the 16-bit commands by name (single shot variants, periodic rates, fetch, break, ART, heater, status, soft reset), decodes the 6-byte result into °C / %RH with per-word CRC check, decodes the status register bits, and annotates polling (read header with a missing ACK). It emits the conformance pair `conversion_start` (STOP after a single-shot command) and `conversion_done` (first ACKed read header).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/humidity/sht31.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/humidity/sht31/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/humidity/sht31/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/humidity/sht31/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/humidity/sht31_test.py` (MicroPython)
- [ ] Tests `python/tests/humidity/sht31_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/humidity/sht31_test_linux.py` (Linux)
- [ ] Unit test `python/tests/humidity/sht31_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/humidity/sht31/sht31.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/humidity/sht31/sht31_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/humidity/sht31/sht31.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/humidity/sht31/SHT31.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/humidity/sht31/SHT31.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/humidity/SHT31.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/humidity/SHT31.cpp`
- [ ] Examples `cpp/examples/arduino/humidity/SHT31/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/humidity/SHT31/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/humidity/SHT31/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/humidity/SHT31/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/humidity/SHT31/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/humidity/SHT31/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/humidity/SHT31/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/humidity/SHT31/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/humidity/SHT31/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/humidity/SHT31/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/humidity/SHT31/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/humidity/SHT31/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/humidity/SHT31/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/humidity/SHT31/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/humidity/SHT31/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/humidity/SHT31/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/humidity/SHT31/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/humidity/SHT31/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/humidity/sht31_test/sht31_test.ino` (Arduino)
- [ ] Tests `cpp/tests/humidity/sht31_test_linux/sht31_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/humidity/sht31_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/humidity/sht31_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/humidity/sht31_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/humidity/sht31_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/humidity/sht31_test_unit/sht31_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/humidity/sht31.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/humidity/sht31/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/humidity/sht31/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/humidity/sht31/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/humidity/sht31_test.js`
- [ ] Unit test `nodejs/tests/humidity/sht31_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-humidity/nodes/sht31/sht31.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-humidity/nodes/sht31/sht31.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-humidity/examples/sht31/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/humidity/sht31.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/humidity/sht31/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/humidity/sht31/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/humidity/sht31/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/humidity/sht31_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/humidity/sht31_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/humidity/sht31.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/humidity/sht31.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/humidity/sht31/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/humidity/sht31/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/humidity/sht31/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/humidity/sht31/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/sht31/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/sht31/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/humidity/sht31_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/humidity/sht31_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/humidity/sht31_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/SHT31Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/SHT31Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/SHT31Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/SHT31Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/SHT31Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/SHT31Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/humidity/sht31/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/humidity/sht31/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/humidity/sht31/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/humidity/sht31/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/humidity/sht31/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/humidity/sht31/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/humidity/sht31/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/humidity/sht31/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/humidity/sht31/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/humidity/sht31/SHT31Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/humidity/SHT31Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/humidity/SHT31Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/humidity/SHT31Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/sht31/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/sht31/pd.py` — annotates commands, results with CRC check and the status register per the Sigrok Decoder section — for the conformance-checked Timing Constraints above, emits the named `conversion_start` / `conversion_done` annotation pair

### Conformance
- [ ] Checker `conformance/humidity/sht31_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/humidity/sht31_timing.conf`
- [ ] Decoder `sigrok/sht31/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

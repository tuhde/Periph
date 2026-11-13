# Chip Spec: SHTC3

**Manufacturer:** Sensirion  
**Datasheet:** `datasheets/humidity/shtc3.pdf` (SHTC3 datasheet, Version 1.1, January 2019)  
**Category:** humidity  
**Transports:** I²C

> **Note:** The SHTC3 measures relative humidity and temperature (no pressure); it is placed in `humidity` next to the DHT11.

## Overview

The SHTC3 is an ultra-low-power digital humidity and temperature sensor in a 2 × 2 × 0.75 mm DFN package: relative humidity 0–100 %RH (typical ±2 %RH) and temperature −40…+125 °C (typical ±0.2 °C), 1.62–3.6 V supply, I²C up to Fast Mode Plus. Every measurement command returns **both** values, each as a 16-bit word followed by a CRC-8. A **normal mode** measurement takes 10.8 ms typ., a **low-power mode** measurement 0.7 ms typ.; between measurements the chip can be put into sleep mode (0.3 µA) and must be woken with a dedicated command. Commands are 16-bit, there are no addressable registers (except the ID read command). It is the successor of the SHTC1 (same protocol, different ID and no sleep requirement).

## Transport Configuration

### I²C
- **Address:** `0x70` (7-bit, fixed; address byte `1110 000 R/W`)
- **Max clock:** 1 MHz (Fast Mode Plus); 100 kHz and 400 kHz modes supported; drivers use ≤ 400 kHz
- **Identity register:** read-ID command `0xEFC8` returns a 16-bit ID + CRC; the SHTC3 product code is in bits 11 and 5:0 (`xxxx 1xxx xx00 0111`): mask `0x083F`, expected `0x0807` (the other bits vary per sensor); feeds `registry/chips.json`. A sleeping sensor does not respond — wake it first (the registry probe does not, so a sleeping SHTC3 may be missed)
- **Probe safety:** `write_sensitive` — a write is a command (measurement, soft reset, sleep, wake-up); there is no register pointer
- **Protocol:** every command is two bytes, MSB first, sent after the write header and ended with STOP; results are read with a separate read header: `[addr+R] [MSB] [LSB] [CRC] [MSB] [LSB] [CRC]` (first word / second word depends on the command). The drivers use the plain `Connection` (`write(cmd_bytes)` and `read(n)`), not `RegisterConnection` — the command and the result are separate transactions, and the read may be NACKed until the measurement finishes

## Pin Configuration

| Pin | Notes |
|-----|-------|
| SDA, SCL | I²C; external pull-ups (≈ 10 kΩ), SDA/SCL are bidirectional |
| VDD, VSS | 1.62–3.6 V supply; 100 nF decoupling close to the pin |

No interrupt or enable pins. The center pad is connected to VSS and gives the best thermal contact to the PCB.

## Command Set

| Command | Code | Description |
|---------|------|-------------|
| Sleep | `0xB098` | Enter sleep mode (0.3 µA) |
| Wake-up | `0x3517` | Leave sleep mode; required before any other command |
| Soft reset | `0x805D` | Reset to the power-up state without removing power (idle state only) |
| Read ID | `0xEFC8` | Read the 16-bit ID + CRC |
| General-call reset | address `0x00`, data `0x06` | Resets all devices on the bus that support it |
| Measure, clock stretching, T first, normal | `0x7CA2` | |
| Measure, clock stretching, RH first, normal | `0x5C24` | |
| Measure, no stretching, T first, normal | `0x7866` | |
| Measure, no stretching, RH first, normal | `0x58E0` | |
| Measure, clock stretching, T first, low power | `0x6458` | |
| Measure, clock stretching, RH first, low power | `0x44DE` | |
| Measure, no stretching, T first, low power | `0x609C` | |
| Measure, no stretching, RH first, low power | `0x401A` | |

Each measurement command triggers a temperature **and** a humidity measurement. **With clock stretching enabled** the sensor holds SCL low on the read header until the result is ready; **with it disabled** the read header is NACKed while the measurement is running and the master retries. The drivers use the **no-stretching, T-first** codes (portable across I²C controllers) with a fixed wait.

### Measurement frame (6 bytes)

| Byte | Content |
|------|---------|
| 0–1 | First value (T or RH depending on the command), MSB first |
| 2 | CRC-8 of bytes 0–1 |
| 3–4 | Second value |
| 5 | CRC-8 of bytes 3–4 |

The master may stop reading after any byte (NACK) — e.g. read only the first word + CRC.

### CRC-8

Polynomial `0x31` (x⁸ + x⁵ + x⁴ + 1), initial value `0xFF`, no input/output reflection, final XOR `0x00`; computed over the two data bytes of each word. Datasheet examples: CRC(`0xBEEF`) = `0x92`, CRC(`0x00`) = `0xAC`.

## Initialization Sequence

1. Power-up: after VDD ≥ VPOR the sensor enters the idle state within tPU ≤ 240 µs. Wait ≥ 1 ms.
2. The sensor is in idle (not sleep) state after power-up; the datasheet recommends sending it to sleep between measurements.
3. A measurement cycle is four transactions: **wake-up** (`0x3517`), wait, **measurement command**, wait for the measurement, **read** (6 bytes), then **sleep** (`0xB098`).
4. `init` stores the connection, issues a soft reset and a read-ID check (raises on a wrong product code), then puts the sensor to sleep.

## Implementation Stages

### Minimal

Goal: read temperature and humidity with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Soft reset, ID check, sleep |
| `read` | — | `{temperature_c, humidity_pct}` | Full cycle: wake-up, normal-mode T-first measurement (no stretching), 13 ms wait, read 6 bytes, verify both CRCs, sleep |

**Sensible defaults:** normal mode (best accuracy), clock stretching disabled, fixed 13 ms wait, CRC checked (raises on mismatch after one retry), sensor put to sleep after every reading.

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_temperature` | — | float | °C only (same cycle) |
| `read_humidity` | — | float | %RH only |
| `read_raw` | — | `(raw_temperature, raw_humidity)` | Raw 16-bit words |
| `set_low_power` | `on: bool` | — | Use the low-power measurement commands (0.8 ms wait) for later reads |
| `set_clock_stretching` | `on: bool` | — | Use the clock-stretching command set (the read header blocks until ready; requires a controller that supports stretching); the wait is skipped |
| `set_sleep_between` | `on: bool` | — | Whether a read ends with the sleep command (default on) |
| `wake` | — | — | Wake-up command + 1 ms |
| `sleep` | — | — | Sleep command |
| `soft_reset` | — | — | Soft reset command, 1 ms wait; leaves the sensor idle |
| `read_id` | — | int | 16-bit ID (CRC verified); `(id & 0x083F) == 0x0807` for the SHTC3 |
| `dew_point` | — | float | °C from one measurement (Magnus formula) |

**Additional configuration options:** normal vs low-power mode, clock stretching on/off, sleep between measurements.

## Data Conversion

```
RH  [%RH] = 100 * S_RH / 2^16                 # S_RH = 16-bit humidity word
T   [°C]  = -45 + 175 * S_T / 2^16            # S_T  = 16-bit temperature word
crc8(data) : poly 0x31, init 0xFF, MSB first, no reflect, xor-out 0x00
Magnus     : gamma = ln(RH/100) + 17.62*T/(243.12+T);  Td = 243.12*gamma/(17.62 - gamma)
```

Examples: `S_RH = 0x6000` → 37.5 %RH; `S_T = 0x6666` → 25.0 °C (24.9989…); the raw values are clamped to the physical range 0–100 %RH.

## Node-RED

Node name: `periph-shtc3`  
Package: `node-red-contrib-periph-humidity`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature_c, humidity_pct }` | One measurement per input message |
| `msg.low_power = true` | as above | Low-power mode for this reading |

Config panel fields: I²C bus number, address (`0x70`), low-power mode.

### Demo flow

Inject node (every 5 s) → `periph-shtc3` → function node formatting `"25.0 °C / 37.5 %RH"` → debug node and gauge widgets.

## Examples

### Demo

**Battery-friendly climate logger.** With the Full driver, print the ID and then read temperature and humidity every 10 s for a minute in normal mode (sleep between readings), switch to low-power mode and read again to compare the values and durations, print the dew point, show `read_raw()` and finish with `sleep()`.

## Timing Constraints

- **Power-up:** tPU ≤ 240 µs (180 µs typ.) from VDD ≥ VPOR to idle; the datasheet advertises "power-up and measurement within 1 ms".
- **Soft reset:** tSR ≤ 240 µs after the ACK of the soft-reset command.
- **Wake-up:** the datasheet refers to "Table 5" for the wake-up time but does not tabulate a separate value; the drivers wait 1 ms after the wake-up command (derived, conservative).
- **Measurement duration:** normal mode 10.8 ms typ. (12.1 ms max), low-power mode 0.7 ms typ. (0.8 ms max); the drivers wait 13 ms / 1 ms. With clock stretching disabled a read header before completion is NACKed (the driver could poll instead of waiting).
- **Power:** idle 70 µA max, measuring 570 µA, average 900 µA (during the measurement set), sleep 0.3 µA typ. (0.6 µA max); energy per measurement < 1 µJ.
- **I²C:** per mode (standard / fast / fast-mode-plus) — clock-rate properties, not event-to-event delays.
- **Conformance:** the measurement duration and the power-up/reset times have only typical/maximum limits, no datasheet minimum to check, so `specs/humidity/shtc3_timing.conf` defines no checks.

## Implementation Notes

- **Plain Connection:** commands and results are separate transactions; do **not** use a repeated-START register read — after the measurement command the sensor is busy and the result is read with a fresh read header (STOP between).
- **Sleep discipline:** a sleeping chip ignores every command except wake-up; the drivers wake before every cycle and (by default) sleep afterwards. Without sleeping the supply current stays at the idle level.
- **CRC:** verify both words; a bad CRC raises (after one retry); the CRC byte may be skipped by NACKing the read early, but the drivers always read 6 bytes.
- **Clock stretching:** the stretching command set needs an I²C controller that tolerates a slave holding SCL low during the read header (Linux `i2c-dev` usually does, some bit-banged hosts do not) — hence the default is no-stretching.
- **Read order:** T-first vs RH-first only changes the order of the two words; the conversion formulas are the same.
- **Self-heating:** low-power mode reduces measurement time and heating but has slightly higher noise; the drivers default to normal mode.
- **Address:** `0x70` is fixed; it is shared with the SHTC1 (same command set apart from the ID/sleep) and with TCA9548/PCA9548-type I²C muxes (`0x70`–`0x77`), whose probe would respond to a plain write.
- **Datasheet:** Sensirion SHTC3 Version 1.1 (January 2019), downloaded from `https://www.waveshare.com/w/upload/3/33/SHTC3_Datasheet.pdf` (a Waveshare-hosted copy of the Sensirion document).

## Sigrok Decoder

Decoder id `shtc3`, input `['i2c']`. Matches I²C address `0x70`. Decodes the 16-bit commands by name (wake-up, sleep, soft reset, read ID and the eight measurement commands with their mode/stretching/order), reads as T/RH words converted to °C and %RH (in the order of the last measurement command) with the CRC-8 verified, and the ID word with the SHTC3 product code check (`0x083F`/`0x0807`); warns on a CRC mismatch, on a command while the sensor is asleep (no wake-up seen), and on an unknown command code. No timing annotation pair is emitted.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/humidity/shtc3.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/humidity/shtc3/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/humidity/shtc3/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/humidity/shtc3/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/humidity/shtc3_test.py` (MicroPython)
- [ ] Tests `python/tests/humidity/shtc3_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/humidity/shtc3_test_linux.py` (Linux)
- [ ] Unit test `python/tests/humidity/shtc3_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/humidity/shtc3/shtc3.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/humidity/shtc3/shtc3_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/humidity/shtc3/shtc3.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/humidity/shtc3/SHTC3.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/humidity/shtc3/SHTC3.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/humidity/SHTC3.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/humidity/SHTC3.cpp`
- [ ] Examples `cpp/examples/arduino/humidity/SHTC3/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/humidity/SHTC3/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/humidity/SHTC3/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/humidity/SHTC3/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/humidity/SHTC3/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/humidity/SHTC3/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/humidity/SHTC3/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/humidity/SHTC3/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/humidity/SHTC3/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/humidity/SHTC3/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/humidity/SHTC3/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/humidity/SHTC3/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/humidity/SHTC3/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/humidity/SHTC3/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/humidity/SHTC3/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/humidity/SHTC3/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/humidity/SHTC3/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/humidity/SHTC3/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/humidity/shtc3_test/shtc3_test.ino` (Arduino)
- [ ] Tests `cpp/tests/humidity/shtc3_test_linux/shtc3_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/humidity/shtc3_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/humidity/shtc3_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/humidity/shtc3_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/humidity/shtc3_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/humidity/shtc3_test_unit/shtc3_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/humidity/shtc3.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/humidity/shtc3/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/humidity/shtc3/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/humidity/shtc3/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/humidity/shtc3_test.js`
- [ ] Unit test `nodejs/tests/humidity/shtc3_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-humidity/nodes/shtc3/shtc3.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-humidity/nodes/shtc3/shtc3.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-humidity/examples/shtc3/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/humidity/shtc3.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/humidity/shtc3/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/humidity/shtc3/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/humidity/shtc3/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/humidity/shtc3_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/humidity/shtc3_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/humidity/shtc3.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/humidity/shtc3.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/humidity/shtc3/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/humidity/shtc3/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/humidity/shtc3/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/humidity/shtc3/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/shtc3/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/shtc3/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/humidity/shtc3_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/humidity/shtc3_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/humidity/shtc3_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/SHTC3Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/SHTC3Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/SHTC3Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/SHTC3Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/SHTC3Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/SHTC3Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/humidity/shtc3/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/humidity/shtc3/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/humidity/shtc3/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/humidity/shtc3/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/humidity/shtc3/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/humidity/shtc3/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/humidity/shtc3/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/humidity/shtc3/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/humidity/shtc3/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/humidity/shtc3/SHTC3Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/humidity/SHTC3Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/humidity/SHTC3Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/humidity/SHTC3Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/shtc3/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/shtc3/pd.py` — annotates every command and result frame per the Sigrok Decoder section (no timing annotation pair: no conformance-checked constraints)

### Conformance
- [ ] Checker `conformance/humidity/shtc3_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/humidity/shtc3_timing.conf` — already committed; defines no checks (see its header)
- [ ] Decoder `sigrok/shtc3/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

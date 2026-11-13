# Chip Spec: AHT10

**Manufacturer:** ASAIR (Guangzhou Aosong Electronics)  
**Datasheet:** `datasheets/environmental/aht10.pdf` (AHT10 Technical Manual V1.1, unofficial English translation, 2019/05)  
**Category:** environmental  
**Transports:** I²C

> **Note:** The AHT10 measures temperature and humidity only (no pressure). It is placed in the `environmental` category as the closest fit for a combined T+RH sensor. The datasheet URL in issue #262 points at the **AHT20** datasheet; the AHT10 manual above was used instead — the two differ in the init command, supply range and CRC (see Implementation Notes).

## Overview

The AHT10 is a fully calibrated digital temperature and humidity sensor in a 4×5×1.6 mm dual-row flat leadless (DFN-6) SMD package. An on-chip ASIC processes the raw MEMS capacitive humidity element and the on-chip temperature element and outputs calibrated 20-bit values directly over I²C — no host-side calibration is needed. Supply 1.8–3.6 V (3.3 V recommended), humidity accuracy ±2 %RH typical, temperature accuracy ±0.3 °C typical, resolution 0.024 %RH / 0.01 °C. It is the predecessor of the AHT20/AHT21 and uses the same measurement frame (without CRC).

## Transport Configuration

### I²C
- **Address:** `0x38` (7-bit; the manual's address byte is `0111 000 R/W`). Some breakout modules expose the `ADR` pin (pin 1) to select `0x39`; the manual's pin table is garbled on this, so `0x39` is **not verified** against the datasheet — the drivers accept the address as a constructor parameter, the registry lists `0x38` only
- **Max clock:** 400 kHz (fast mode); standard 100 kHz also supported. SCL has no minimum frequency
- **Identity register:** none; chip has no ID register (`registry/chips.json` entry has `"id_probe": null`; address `0x38` is shared with the AHT21 / AHT20)
- **Probe safety:** `write_sensitive` — a 1-byte write is interpreted as a command (soft reset `0xBA`, init `0xE1`, trigger `0xAC`); there is no register pointer
- **Protocol:** command-based, no register addresses. Commands are written with `write(data)`; the status byte / result frame is read with `read(n)`. The plain `Connection` is used — see `specs/feature_register_access_design.md`, "no register concept"

## Pin Configuration

| Pin | Notes |
|-----|-------|
| 1 ADR | Address select on some modules (see above); leave per module datasheet |
| 2 SDA | I²C data; external pull-up (≈10 kΩ) |
| 3 SCL | I²C clock; external pull-up |
| 4 VDD | 1.8–3.6 V; 100 nF decoupling close to the pin |
| 5 GND | Ground |
| 6 NC | Not connected |

No interrupt or enable pin. Only a single AHT10 may be on the I²C bus (per the manual).

## Command Set

| Command | Bytes sent | Description |
|---------|------------|-------------|
| Read status | *(read 1 byte)* | Returns the status byte at any time |
| `0xE1` `0x08` `0x00` | 3 | Initialization (calibration enable); the manual names only the `0xE1` command code — the parameter bytes `0x08 0x00` are the sequence used by the manufacturer's sample code and open-source drivers |
| `0xAC` `0x33` `0x00` | 3 | Trigger measurement (`0x33` / `0x00` as given in the manual's Figure) |
| `0xBA` | 1 | Soft reset — restarts the sensor and restores defaults; takes < 20 ms |

### Status byte

| Bit | Name | Description |
|-----|------|-------------|
| 7 | BUSY | `1` = busy (measuring); `0` = idle |
| 6:5 | MODE | `00` NOR, `01` CYC, `1x` CMD mode (the driver uses NOR/CMD one-shot only) |
| 4 | — | Reserved |
| 3 | CAL | `1` = calibrated (calibration enable set); `0` = not calibrated |
| 2:0 | — | Reserved |

`(status & 0x08) != 0` confirms the sensor is calibrated; a freshly initialised, idle sensor reads `0x08` (`0x18` is **not** expected — bit 4 is reserved).

## Initialization Sequence

1. Power on; wait ≥ 20 ms (manual: at most 20 ms until idle with SCL high). The driver waits 40 ms for margin.
2. Send `0xE1 0x08 0x00` (initialization / calibration enable); wait 10 ms.
3. Read the status byte; if `CAL` is still `0`, send soft reset `0xBA`, wait 20 ms and repeat step 2 once; raise an error if still uncalibrated.

## Measurement Sequence

1. Write `0xAC 0x33 0x00`.
2. Wait ≥ 75 ms (the driver waits 80 ms).
3. Read 6 bytes. If byte 0 bit 7 (BUSY) is `1`, wait 10 ms and re-read (timeout 200 ms → error).
4. Decode bytes 1–5 into humidity and temperature.

### Response frame (6 bytes)

| Byte | Content |
|------|---------|
| 0 | Status |
| 1 | RH raw bits [19:12] |
| 2 | RH raw bits [11:4] |
| 3 | RH raw bits [3:0] (upper nibble) \| T raw bits [19:16] (lower nibble) |
| 4 | T raw bits [15:8] |
| 5 | T raw bits [7:0] |

The AHT10 has **no CRC byte** (unlike the AHT20/AHT21).

## Implementation Stages

### Minimal

Goal: trigger a measurement and return temperature and humidity with no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Power-on wait, init command, calibration check (soft reset + retry if needed) |
| `read` | — | `{temperature_c, humidity_pct}` | Triggers one measurement, waits 80 ms, polls BUSY, decodes |

**Sensible defaults baked into Minimal:** one-shot measurement per `read()` call, fixed 80 ms wait followed by BUSY check, no mode selection.

### Full

Goal: expose all device capabilities. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_temperature` | — | float | °C only |
| `read_humidity` | — | float | %RH only |
| `read_raw` | — | `(raw_humidity, raw_temperature)` | 20-bit raw values |
| `read_status` | — | int | Status byte |
| `is_calibrated` | — | bool | `CAL` bit |
| `is_busy` | — | bool | `BUSY` bit |
| `soft_reset` | — | — | Sends `0xBA`, waits 20 ms, re-runs the init command |
| `dew_point` | — | float | °C, Magnus formula from one fresh measurement |

**Additional capabilities:** raw values, status inspection, explicit soft reset, dew point.

## Data Conversion

```
raw_rh = (b1 << 12) | (b2 << 4) | (b3 >> 4)         # 20 bit
raw_t  = ((b3 & 0x0F) << 16) | (b4 << 8) | b5        # 20 bit

humidity_pct  = raw_rh / 2^20 * 100                  # 0..100 %RH (resolution 0.024 %RH)
temperature_c = raw_t  / 2^20 * 200 - 50             # -50..150 °C (specified -40..85 °C)
```

Example: `raw_t = 0x6B700` → 439040 / 1048576 × 200 − 50 = 33.7 °C. Humidity is clamped to 0–100 %RH.

Dew point (Full): `γ = ln(RH/100) + 17.62·T / (243.12 + T)`, `Td = 243.12·γ / (17.62 − γ)`.

## Node-RED

Node name: `periph-aht10`  
Package: `node-red-contrib-periph-environmental`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature_c, humidity_pct }` | One measurement per input message |

Config panel fields: I²C bus number, address (`0x38`).

### Demo flow

Inject node (every 5 s) → `periph-aht10` → function node formatting `"25.3 °C / 48.1 %RH"` → debug node; a second branch feeds temperature and humidity gauge widgets.

## Examples

### Demo

A weather-station logger. Initialize with the Full driver, print the calibration status, then every 5 seconds read temperature and humidity, print them with a timestamp and the dew point from `dew_point`, and keep running min/max. Finish by calling `soft_reset()` after 12 samples and confirming `is_calibrated()` again. Why-comments explain the 75 ms measurement time, the calibration check, and the ≥ 1 s interval (self-heating).

## Timing Constraints

| Event | Time | Notes |
|-------|------|-------|
| Power-on to idle | ≤ 20 ms | Hold SCL high; driver waits 40 ms |
| Init command settle | ~10 ms | Derived (open-source drivers) |
| Trigger to data ready | > 75 ms | The decoder marks the `0xAC` write as `measurement_start` and the first read with BUSY = 0 as `measurement_done`; `aht10_timing.conf` checks ≥ 75 ms |
| Soft reset recovery | < 20 ms | Driver waits 20 ms |
| Minimum measurement interval | ≥ 1 s | More frequent reads self-heat the sensor |
| Response time τ63 | RH ≈ 8 s; T 5–30 s | Depends on air flow / thermal contact |

## Implementation Notes

- **Not an AHT20:** the init command is `0xE1` (AHT20/21 use `0xBE`/`0x1B..`), the supply range is 1.8–3.6 V (AHT20/21: 2.2–5.5 V) and there is no CRC byte; the 6-byte frame and conversion formulas are identical. Do not drive an AHT10 from a 5 V supply or 5 V pull-ups.
- **Init parameter bytes:** the manual gives only the command code `0xE1`; `0x08 0x00` is the manufacturer-sample / community sequence (bit 3 = calibration enable, matching status bit `CAL`).
- **Status check mask:** use `0x08` (CAL). `0x18` as used for the AHT21 would fail on the AHT10.
- **Busy polling:** the 75 ms is the minimum; poll BUSY in 10 ms steps with a ~200 ms guard.
- **Self-heating:** keep the interval ≥ 1 s; do not place the sensor in a sealed air pocket on the PCB.
- **Post-reflow rehydration:** after soldering, store at 20–30 °C / > 75 %RH for 12 hours to restore humidity accuracy (manual §2).
- **Single device:** the manual states only one AHT10 can be on the bus.
- **Datasheet:** downloaded from `https://server4.eca.ir/eshop/AHT10/Aosong_AHT10_en_draft_0c.pdf` (unofficial English translation of the Chinese manual).

## Sigrok Decoder

Decoder id `aht10`, input `['i2c']`. Matches I²C address `0x38`. Decodes the commands `0xE1` (init), `0xAC` (trigger), `0xBA` (soft reset) with their parameter bytes, and the 1-byte status read (BUSY, MODE, CAL) and the 6-byte frame as humidity %RH and temperature °C (the same formulas as above). Timing annotations in the `timing` row (variable-length strings per `specs/sigrok_annotations.md`): `measurement_start` on the `0xAC` write and `measurement_done` on the first following read whose status has BUSY = 0.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/environmental/aht10.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/environmental/aht10/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/environmental/aht10/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/environmental/aht10/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/environmental/aht10_test.py` (MicroPython)
- [ ] Tests `python/tests/environmental/aht10_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/environmental/aht10_test_linux.py` (Linux)
- [ ] Unit test `python/tests/environmental/aht10_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/environmental/aht10/aht10.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/environmental/aht10/aht10_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/environmental/aht10/aht10.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/environmental/aht10/AHT10.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/environmental/aht10/AHT10.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/environmental/AHT10.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/environmental/AHT10.cpp`
- [ ] Examples `cpp/examples/arduino/environmental/AHT10/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/environmental/AHT10/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/environmental/AHT10/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/environmental/AHT10/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/environmental/AHT10/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/environmental/AHT10/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/environmental/AHT10/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/environmental/AHT10/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/environmental/AHT10/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/environmental/AHT10/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/environmental/AHT10/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/environmental/AHT10/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/environmental/AHT10/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/environmental/AHT10/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/environmental/AHT10/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/environmental/AHT10/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/environmental/AHT10/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/environmental/AHT10/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/environmental/aht10_test/aht10_test.ino` (Arduino)
- [ ] Tests `cpp/tests/environmental/aht10_test_linux/aht10_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/environmental/aht10_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/environmental/aht10_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/environmental/aht10_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/environmental/aht10_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/environmental/aht10_test_unit/aht10_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/environmental/aht10.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/environmental/aht10/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/environmental/aht10/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/environmental/aht10/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/environmental/aht10_test.js`
- [ ] Unit test `nodejs/tests/environmental/aht10_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-environmental/nodes/aht10/aht10.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-environmental/nodes/aht10/aht10.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-environmental/examples/aht10/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/environmental/aht10.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/environmental/aht10/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/environmental/aht10/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/environmental/aht10/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/environmental/aht10_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/environmental/aht10_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/environmental/aht10.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/environmental/aht10.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/environmental/aht10/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/environmental/aht10/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/environmental/aht10/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/environmental/aht10/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/environmental/aht10/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/environmental/aht10/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/environmental/aht10_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/environmental/aht10_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/environmental/aht10_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/environmental/AHT10Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/environmental/AHT10Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/environmental/AHT10Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/environmental/AHT10Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/environmental/AHT10Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/environmental/AHT10Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/environmental/aht10/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/environmental/aht10/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/environmental/aht10/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/environmental/aht10/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/environmental/aht10/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/environmental/aht10/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/environmental/aht10/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/environmental/aht10/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/environmental/aht10/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/environmental/aht10/AHT10Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/environmental/AHT10Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/environmental/AHT10Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/environmental/AHT10Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/aht10/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/aht10/pd.py` — annotates the command byte and the 2-byte result per the Sigrok Decoder section — for the conformance-checked Timing Constraints above, emits the named `conversion_start` / `conversion_done` annotation pair

### Conformance
- [ ] Checker `conformance/environmental/aht10_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/environmental/aht10_timing.conf`
- [ ] Decoder `sigrok/aht10/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

# Chip Spec: AHT20

**Manufacturer:** ASAIR (Guangzhou Aosong Electronics)  
**Datasheet:** `datasheets/environmental/aht20.pdf` (Data Sheet AHT20 V1.0, May 2021)  
**Category:** environmental  
**Transports:** I²C

> **Note:** The AHT20 measures temperature and humidity only (no pressure). It is placed in the `environmental` category as the closest fit for a combined T+RH sensor.

## Overview

The AHT20 is a fully calibrated digital temperature and humidity sensor in a 3×3×1.0 mm dual-row flat leadless (DFN-6) SMD package. An on-chip ASIC processes the raw MEMS capacitive humidity element and the on-chip temperature element and outputs calibrated 20-bit values directly over I²C — no host-side calibration is needed. Supply 2.2–5.5 V, humidity accuracy ±2 %RH typical, temperature accuracy ±0.3 °C typical, resolution 0.024 %RH / 0.01 °C, specified −40…85 °C and 0–100 %RH. It is the successor of the AHT10 and, protocol-wise, the sibling of the already-supported AHT21: the measurement command, 6-byte frame (+ optional CRC-8), status byte and conversion formulas are the same, so the AHT20 driver is a sibling of the AHT21 driver (the specs share their command set, differing only in the datasheet limits quoted below).

## Transport Configuration

### I²C
- **Address:** `0x38` (7-bit, fixed — no address pin; address byte `0111 000 R/W`). Only one AHT20 can be on a bus
- **Max clock:** 400 kHz (fast mode); standard 100 kHz also supported; SCL has no minimum frequency
- **Identity register:** none; chip has no ID register (`registry/chips.json` entry has `"id_probe": null`; address `0x38` is shared with the AHT10 and AHT21, which cannot be told apart)
- **Probe safety:** `write_sensitive` — a 1-byte write is interpreted as a command (soft reset `0xBA`, trigger `0xAC`, calibration registers `0x1B`/`0x1C`/`0x1E`); there is no register pointer
- **Protocol:** command-based, no register addresses. Commands are written with `write(data)`; the status byte / result frame is read with `read(n)`. The plain `Connection` is used — see `specs/feature_register_access_design.md`, "no register concept"

## Pin Configuration

| Pin | Notes |
|-----|-------|
| SDA | I²C data; external pull-up (2.0–4.7 kΩ), pulled to VDD |
| SCL | I²C clock; external pull-up |
| VDD | 2.2–5.5 V; decoupling capacitor close to the pin (datasheet recommends 100 nF; 10 µF bulk shown in the application circuit) |
| GND | Ground |

No interrupt, enable or address pins. The pull-up voltage for SCL/SDA must be VDD.

## Command Set

| Command | Bytes sent | Description |
|---------|------------|-------------|
| `0x71` | 1 | Read status word (the datasheet's "get status" command; reading one byte straight after the address gives the same status) |
| `0xAC` `0x33` `0x00` | 3 | Trigger measurement |
| `0xBA` | 1 | Soft reset — restarts the sensor system; wait ≥ 20 ms (not in the AHT20 datasheet text; same as the AHT10/AHT21 family) |
| `0x1B` `0x00` `0x00`, `0x1C` `0x00` `0x00`, `0x1E` `0x00` `0x00` | 3 each | Initialize calibration registers — only when the status check fails; the datasheet refers to the vendor sample code for the values |

### Status byte

| Bit | Name | Description |
|-----|------|-------------|
| 7 | BUSY | `1` = busy (measuring); `0` = idle / hibernation |
| 6:5 | — | Reserved |
| 4 | — | Reserved |
| 3 | CAL | `1` = calibrated; `0` = uncalibrated |
| 2:0 | — | Reserved |

Check: `(status & 0x18) == 0x18` confirms the sensor is idle-ready and calibrated (the datasheet explicitly tests the status word against `0x18`).

## Initialization Sequence

1. Power on; wait ≥ 100 ms (SCL held high) until the sensor is idle.
2. Read the status word (command `0x71`, then read 1 byte).
3. If `(status & 0x18) != 0x18`: soft reset `0xBA`, wait 20 ms, re-check; if still uncalibrated write the calibration init commands `0x1B`, `0x1C`, `0x1E` (each followed by `0x00 0x00`), 10 ms apart, and re-check once; raise an error if still uncalibrated.
4. This check is only required once after power-on, not before every measurement.

## Measurement Sequence

1. Wait 10 ms after init, then write `0xAC 0x33 0x00`.
2. Wait ≥ 80 ms.
3. Read the status; if BUSY (bit 7) is `1` keep waiting (10 ms steps, ~200 ms guard → error); otherwise read 6 bytes (7 to include the CRC).
4. Decode bytes 1–5 into humidity and temperature.

### Response frame (6 bytes, 7 with CRC)

| Byte | Content |
|------|---------|
| 0 | Status |
| 1 | RH raw bits [19:12] |
| 2 | RH raw bits [11:4] |
| 3 | RH raw bits [3:0] (upper nibble) \| T raw bits [19:16] (lower nibble) |
| 4 | T raw bits [15:8] |
| 5 | T raw bits [7:0] |
| 6 | CRC-8 (optional; ACK the 6th byte to receive it, NACK to skip) |

## Implementation Stages

### Minimal

Goal: trigger a measurement and return temperature and humidity with no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Power-on wait, status check, calibration init if needed |
| `read` | — | `{temperature_c, humidity_pct}` | Triggers one measurement, waits 80 ms, polls BUSY, decodes |

**Sensible defaults baked into Minimal:** one-shot measurement per `read()`, fixed 80 ms wait followed by a BUSY check, no CRC verification.

### Full

Goal: expose all device capabilities. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_temperature` | — | float | °C only |
| `read_humidity` | — | float | %RH only |
| `read_raw` | — | `(raw_humidity, raw_temperature)` | 20-bit raw values |
| `read_with_crc` | — | `{temperature_c, humidity_pct, crc_ok}` | Reads 7 bytes; verifies CRC-8 |
| `read_status` | — | int | Status byte |
| `is_calibrated` | — | bool | `CAL` bit |
| `is_busy` | — | bool | `BUSY` bit |
| `soft_reset` | — | — | Sends `0xBA`, waits 20 ms, re-runs the calibration check |
| `dew_point` | — | float | °C, Magnus formula from one fresh measurement |

**Additional capabilities:** CRC verification (polynomial x⁸ + x⁵ + x⁴ + 1 = `0x31`, initial value `0xFF`, over bytes 0–5), raw values, status inspection, soft reset, dew point.

## Data Conversion

```
raw_rh = (b1 << 12) | (b2 << 4) | (b3 >> 4)         # 20 bit
raw_t  = ((b3 & 0x0F) << 16) | (b4 << 8) | b5        # 20 bit

humidity_pct  = raw_rh / 2^20 * 100                  # 0..100 %RH (resolution 0.024 %RH)
temperature_c = raw_t  / 2^20 * 200 - 50             # -50..150 °C (specified -40..85 °C)

CRC-8: polynomial 0x31 (x^8 + x^5 + x^4 + 1), init 0xFF, over bytes 0-5, compared with byte 6
```

Example: `raw_t = 0x6B700` → 439040 / 1048576 × 200 − 50 = 33.7 °C. Humidity is clamped to 0–100 %RH.

Dew point (Full): `γ = ln(RH/100) + 17.62·T / (243.12 + T)`, `Td = 243.12·γ / (17.62 − γ)`.

## Node-RED

Node name: `periph-aht20`  
Package: `node-red-contrib-periph-environmental`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature_c, humidity_pct }` | One measurement per input message |

Config panel fields: I²C bus number, address (`0x38`).

### Demo flow

Inject node (every 5 s) → `periph-aht20` → function node formatting `"25.3 °C / 48.1 %RH"` → debug node; a second branch feeds temperature and humidity gauge widgets.

## Examples

### Demo

A weather-station logger. Initialize with the Full driver, print the calibration status, then every 5 seconds read temperature and humidity, print them with a timestamp and the dew point from `dew_point`, and keep running min/max. Every 10th sample use `read_with_crc` and report `crc_ok`. Finish by calling `soft_reset()` after 12 samples and confirming `is_calibrated()` again. Why-comments explain the 80 ms measurement time, the calibration check and the ≥ 1 s interval (self-heating).

## Timing Constraints

| Event | Time | Notes |
|-------|------|-------|
| Power-on to idle | ≥ 100 ms | Hold SCL high; datasheet §7.1 |
| Init to trigger | 10 ms | Datasheet §7.4 step 2 |
| Trigger to data ready | > 80 ms | The decoder marks the `0xAC` write as `measurement_start` and the first read with BUSY = 0 as `measurement_done`; `aht20_timing.conf` checks ≥ 80 ms |
| Soft reset recovery | ≥ 20 ms | Derived (AHT10/AHT21 family) |
| Minimum measurement interval | ≥ 1 s | More frequent reads self-heat the sensor (datasheet recommends keeping the rise ≤ 0.1 °C) |
| Response time τ63 | RH ≈ 8 s; T 5–30 s | Depends on air flow / thermal contact |

## Implementation Notes

- **Sibling of the AHT21:** same command set, frame and formulas; the AHT20 datasheet specifies 2.2–5.5 V and −40…85 °C, and tests the status word against `0x18` after sending `0x71`.
- **Calibration init bytes:** the datasheet defers to the vendor sample code for the `0x1B/0x1C/0x1E` register initialization; `0x00 0x00` parameters are the commonly used sequence. Most modules ship calibrated and set `CAL` at power-on.
- **Status check runs once:** the calibration check is only needed after power-on, not before each measurement.
- **Busy polling:** 80 ms is the minimum; poll BUSY in 10 ms steps with a ~200 ms guard.
- **6 vs 7 bytes:** reading 6 bytes ends with a NACK; reading the CRC byte needs an ACK after the sixth byte.
- **Self-heating:** keep the interval ≥ 1 s and the I²C clock moderate; do not place the sensor in a sealed air pocket on the PCB.
- **Address overlap:** `0x38` is shared with the AHT10/AHT21 (and the PCF8574A range); wrong-chip access returns meaningless data rather than an error, so the discovery registry marks it `write_sensitive`.
- **Post-reflow rehydration:** after soldering, store the sensor at 20–30 °C / > 75 %RH for 12 hours (datasheet §4) to restore humidity accuracy.
- **Datasheet:** V1.0 (May 2021), downloaded from `https://asairsensors.com/wp-content/uploads/2021/09/Data-Sheet-AHT20-Humidity-and-Temperature-Sensor-ASAIR-V1.0.03.pdf`.

## Sigrok Decoder

Decoder id `aht20`, input `['i2c']`. Matches I²C address `0x38`. Decodes the commands `0x71` (status), `0xAC` (trigger) with its parameter bytes, `0xBA` (soft reset) and the calibration init commands, the 1-byte status read (BUSY, CAL) and the 6/7-byte frame as humidity %RH and temperature °C (verifying the CRC when byte 6 is present). Timing annotations in the `timing` row (variable-length strings per `specs/sigrok_annotations.md`): `measurement_start` on the `0xAC` write and `measurement_done` on the first following read whose status has BUSY = 0.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/environmental/aht20.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/environmental/aht20/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/environmental/aht20/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/environmental/aht20/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/environmental/aht20_test.py` (MicroPython)
- [ ] Tests `python/tests/environmental/aht20_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/environmental/aht20_test_linux.py` (Linux)
- [ ] Unit test `python/tests/environmental/aht20_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/environmental/aht20/aht20.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/environmental/aht20/aht20_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/environmental/aht20/aht20.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/environmental/aht20/AHT20.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/environmental/aht20/AHT20.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/environmental/AHT20.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/environmental/AHT20.cpp`
- [ ] Examples `cpp/examples/arduino/environmental/AHT20/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/environmental/AHT20/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/environmental/AHT20/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/environmental/AHT20/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/environmental/AHT20/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/environmental/AHT20/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/environmental/AHT20/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/environmental/AHT20/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/environmental/AHT20/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/environmental/AHT20/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/environmental/AHT20/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/environmental/AHT20/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/environmental/AHT20/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/environmental/AHT20/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/environmental/AHT20/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/environmental/AHT20/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/environmental/AHT20/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/environmental/AHT20/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/environmental/aht20_test/aht20_test.ino` (Arduino)
- [ ] Tests `cpp/tests/environmental/aht20_test_linux/aht20_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/environmental/aht20_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/environmental/aht20_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/environmental/aht20_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/environmental/aht20_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/environmental/aht20_test_unit/aht20_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/environmental/aht20.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/environmental/aht20/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/environmental/aht20/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/environmental/aht20/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/environmental/aht20_test.js`
- [ ] Unit test `nodejs/tests/environmental/aht20_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-environmental/nodes/aht20/aht20.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-environmental/nodes/aht20/aht20.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-environmental/examples/aht20/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/environmental/aht20.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/environmental/aht20/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/environmental/aht20/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/environmental/aht20/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/environmental/aht20_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/environmental/aht20_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/environmental/aht20.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/environmental/aht20.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/environmental/aht20/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/environmental/aht20/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/environmental/aht20/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/environmental/aht20/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/environmental/aht20/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/environmental/aht20/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/environmental/aht20_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/environmental/aht20_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/environmental/aht20_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/environmental/AHT20Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/environmental/AHT20Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/environmental/AHT20Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/environmental/AHT20Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/environmental/AHT20Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/environmental/AHT20Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/environmental/aht20/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/environmental/aht20/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/environmental/aht20/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/environmental/aht20/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/environmental/aht20/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/environmental/aht20/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/environmental/aht20/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/environmental/aht20/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/environmental/aht20/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/environmental/aht20/AHT20Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/environmental/AHT20Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/environmental/AHT20Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/environmental/AHT20Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/aht20/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/aht20/pd.py` — annotates the command byte and the 2-byte result per the Sigrok Decoder section — for the conformance-checked Timing Constraints above, emits the named `conversion_start` / `conversion_done` annotation pair

### Conformance
- [ ] Checker `conformance/environmental/aht20_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/environmental/aht20_timing.conf`
- [ ] Decoder `sigrok/aht20/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

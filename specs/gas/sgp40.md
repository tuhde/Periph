# Chip Spec: SGP40

**Manufacturer:** Sensirion  
**Datasheet:** `datasheets/gas/sgp40.pdf` (Sensirion SGP40 Indoor Air Quality Sensor for VOC Measurements, version 1.2, February 2022)  
**Category:** gas  
**Transports:** I²C

## Overview

The SGP40 is a MOx (metal-oxide) VOC gas sensor with a temperature-controlled micro hotplate in a 2.44 × 2.44 × 0.85 mm DFN package. It outputs a digital raw signal `SRAW_VOC` (16-bit ticks, proportional to the logarithm of the sensing-layer resistance) that is sensitive to most indoor VOCs (ethanol specified range 0.3–30 ppm, measurement range 0–1000 ppm) and can be humidity-compensated by supplying the current relative humidity and temperature with each measurement command. Sensirion's open-source **Gas Index Algorithm** (separate library, not part of the datasheet) turns the raw signal into a VOC Index (1–500, 100 = average over the learning time) once per second. The sensor needs 1.7–3.6 V (3.3 V typical, VDD and VDDH on the same supply), draws 2.6 mA continuously at 3.3 V (34 µA idle), switches on in < 60 s for reliable VOC event detection and meets the specified accuracy after < 1 h. Interface: I²C (standard and fast mode), 16-bit commands, data words with CRC-8. Typical uses: air purifiers, demand-controlled ventilation, indoor air-quality monitors.

## Transport Configuration

### I²C
- **Address:** `0x59` (7-bit, fixed; one sensor per bus)
- **Max clock:** 400 kHz (fast mode); 100 kHz in standard mode
- **Command format:** 2-byte command, MSB first, written in one transaction; commands **must not** be followed by a CRC (the code contains a 3-bit CRC). Parameter and result data are 16-bit words (MSB first), each followed by a CRC-8 (polynomial `0x31`, init `0xFF`, no reflection, no final XOR; `CRC(0xBE 0xEF) = 0x92`). The sensor NACKs a read header while a measurement is in progress, and data cannot be read more than once (extra reads return high bits)
- **Register addressing:** none (command pointers, no register file); write the command, wait the measurement time, then read the response
- **Identity register:** none; the 48-bit serial number (`0x3682`) is a command. Registered in `registry/chips.json` with `id_probe: null`
- **Probe safety:** `write_sensitive` — every written byte sequence is a command (`0x260F` starts the hotplate, `0x3615` stops it). A probe should only write `0x3682` and read the three-word response

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| SDA, SCL | — | I²C, open-drain, external pull-ups |
| VDD, VDDH | — | 1.7–3.6 V; connect both to the same supply (the hotplate needs the specified peak current) |
| VSS | — | ground |

## Command Set

| Command | Code | Parameters (with CRC) | Response (with CRC) | Duration (typ./max) | Description |
|---------|------|-----------------------|---------------------|---------------------|-------------|
| `sgp40_measure_raw_signal` | `0x26 0x0F` | 6 bytes: RH word + CRC, T word + CRC | 3 bytes (1 word + CRC) | 25 ms / 30 ms | Starts/continues the VOC measurement (hotplate on) and returns `SRAW_VOC`; default compensation `0x80 0x00 0xA2 0x66 0x66 0x93` (50 %RH, 25 °C) |
| `sgp40_execute_self_test` | `0x28 0x0E` | — | 3 bytes | 300 ms / 320 ms | Hotplate and MOX integrity test: `0xD4 xx` = all passed, `0x4B xx` = failed (ignore the second byte) |
| `sgp4x_turn_heater_off` | `0x36 0x15` | — | — | 0.1 ms / 1 ms | Hotplate off, sensor returns to idle |
| `sgp4x_get_serial_number` | `0x36 0x82` | — | 9 bytes (3 words + CRC) | 0.1 ms / 1 ms | 48-bit serial number, MSB first |
| `soft_reset` | `0x00 0x06` to the **general-call address** `0x00` | — | — | tSR 0.4 ms (max 0.6 ms) | Resets *all* devices on the bus that support the general call; the SGP40 restarts in idle mode |

Humidity and temperature inputs to the measurement command: `RH_ticks = RH_percent × 65535 / 100`, `T_ticks = (T_celsius + 45) × 65535 / 175`; minimum `0x0000 + CRC 0x81`, maximum `0xFFFF + CRC 0xAC`.

## Initialization Sequence

1. Power VDD and VDDH (1.7–3.6 V); the sensor is ready after `tPU` ≤ 0.6 ms and is in idle mode.
2. Optionally read the serial number (`0x3682`) as a presence check and run the self-test (`0x280E`, 300 ms).
3. Start measuring: send `0x260F` with the humidity/temperature words (default or live values), wait 30 ms, read 3 bytes, verify the CRC. Repeat once per second for the VOC Index (0.5–10 s for the raw signal).
4. The VOC Index needs ≈ 45–60 s before it is meaningful (datasheet: reliable VOC event detection after < 60 s, specifications met after < 1 h).
5. To stop, send `0x3615` (heater off).

## Implementation Stages

### Minimal

Goal: read the VOC signal with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Stores the plain `Connection`; reads the serial number as a presence check; no measurement is started (the hotplate starts with the first `read`) |
| `read` | — | `{voc_raw, voc_index}` | One measurement with the default compensation (50 %RH, 25 °C): sends `0x260F`, waits 30 ms, reads and CRC-checks `SRAW_VOC`, feeds it to the Gas Index Algorithm and returns the raw ticks and the VOC Index (0 during the algorithm's initial blackout, otherwise 1–500). Call it once per second |

**Sensible defaults:** default humidity/temperature compensation, hotplate on while reading, Gas Index Algorithm with its default tuning (VOC Index 100 = typical, learning time 12 h, as in the Sensirion reference implementation).

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_raw` | `humidity_rh: float \| None = None, temperature_c: float \| None = None` | int | One `SRAW_VOC` measurement; with both arguments the humidity compensation is enabled (inputs converted to ticks with CRC), with neither the defaults are sent; raises if only one is given |
| `read_voc_index` | `humidity_rh=None, temperature_c=None` | int | `read_raw` followed by the Gas Index Algorithm (the algorithm state advances once per call) |
| `reset_algorithm` | — | — | Re-initialises the Gas Index Algorithm state (also drops the learned baseline) |
| `set_compensation` | `humidity_rh: float \| None, temperature_c: float \| None` | — | Stores the values used by later `read`/`read_raw`/`read_voc_index` calls (`None` = defaults) |
| `self_test` | — | bool | `0x280E`, waits 320 ms, True if the result byte is `0xD4` |
| `heater_off` | — | — | `0x3615`; the next measurement restarts the hotplate (allow warm-up) |
| `serial_number` | — | int | `0x3682`, 48-bit |
| `soft_reset` | — | — | General-call reset (`0x00 0x06` written to address `0x00`) — resets every device on the bus; raises if the connection cannot address the general call; the algorithm state is cleared |
| `read_word` / `write_command` | `command: int` / `command: int, data: bytes` | int / — | Raw command access with the CRC handled (diagnostics) |

**Additional configuration options:** humidity and temperature compensation, algorithm reset, heater control.

## Data Conversion

```
# measurement response: 3 bytes = 1 word + CRC-8 (poly 0x31, init 0xFF)
voc_raw = word               # SRAW_VOC ticks, 0..65535, proportional to ln(resistance)

# compensation inputs
rh_ticks = round(rh_percent * 65535 / 100)         # clamp 0..100 %
t_ticks  = round((t_celsius + 45) * 65535 / 175)   # clamp -45..130 C
default  = RH 50 %  -> 0x8000 (CRC 0xA2);   T 25 C -> 0x6666 (CRC 0x93)

# serial number: 3 words, MSB first -> 48 bit integer
serial = word0 << 32 | word1 << 16 | word2

# VOC Index: Sensirion Gas Index Algorithm (open source, BSD-3-Clause), one call per second
voc_index = gas_index_algorithm_process(voc_raw)    # 1..500, 100 = typical indoor air, 0 = not yet valid
```

The sensitivity is −870 ticks (typ., −710 … −1030) for a doubling of an ethanol concentration between 5 and 10 ppm. The VOC Index device-to-device variation is below ±15 index points (or 15 % of the measured value), the repeatability below ±5 (5 %). The datasheet does not describe the algorithm itself; the driver ports the reference implementation and states its source and licence in the file header.

## Node-RED

Node name: `periph-sgp40`  
Package: `node-red-contrib-periph-gas`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ voc_raw, voc_index }` | one measurement; the node expects to be triggered once per second (configurable internal timer) |

Config panel fields: I²C bus number, optional humidity/temperature compensation (static values or `msg.humidity`/`msg.temperature`), sampling interval.

### Demo flow

Inject node (every 1 s) → `periph-sgp40` → function node mapping the VOC Index to an air-quality label (good < 100, moderate 100–200, poor > 200) → chart, gauge and debug nodes.

## Examples

### Demo

**VOC index monitor.** With the Full driver print the serial number, run the self-test, then take a measurement every second for 90 s (printing the raw ticks and the VOC Index — which stays 0 during the initial blackout), apply a live humidity/temperature compensation, show the effect of a VOC event (documented: hold a marker pen or alcohol wipe near the sensor), switch the compensation back to the defaults, turn the heater off and on again, reset the algorithm and show the blackout restarting. Exercises `init`, `read`, `read_raw`, `read_voc_index`, `reset_algorithm`, `set_compensation`, `self_test`, `heater_off`, `serial_number`, `soft_reset` (skipped on buses with other devices), `read_word`, `write_command`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Power-up time | ≤ 0.6 ms (typ. 0.4 ms) |
| Soft reset time | ≤ 0.6 ms (typ. 0.4 ms) |
| Measurement duration (`0x260F`) | 25 ms typ., 30 ms max |
| Self-test duration | 300 ms typ., 320 ms max |
| Heater off / serial number | 0.1 ms typ., 1 ms max |
| Sampling interval | VOC Index: 1 s; raw signal 0.5–10 s |
| Switch-on behaviour | reliable VOC event detection < 60 s; specifications met < 1 h |
| I²C clock | ≤ 400 kHz |

The durations are minimum host waits (the sensor NACKs a read header until the result is ready); there is no bus-decodable start/end pair with a bound the decoder can mark, so `sgp40_timing.conf` defines no checks.

## Implementation Notes

- **Plain Connection, command protocol:** commands are written with `write` and results read in a separate transaction after the measurement time; do not use a register-addressed connection. A NACK on the read header means "not ready yet"; the driver may poll after the typical duration instead of waiting the maximum.
- **No CRC on commands:** append CRC bytes only to data words (the humidity/temperature parameters), never to the command code. Verify the CRC of every word read.
- **One read per measurement:** the result can be read only once; a second read returns a pattern of high bits.
- **Call once per second:** the Gas Index Algorithm assumes one raw sample per second; irregular sampling distorts the VOC Index. The driver does not sleep: the caller owns the 1 s cadence (`read` blocks only for the 30 ms measurement).
- **Humidity compensation:** without humidity/temperature input the sensor assumes 50 %RH/25 °C; supply live values from a humidity sensor for accurate results.
- **Gas Index Algorithm:** not part of the datasheet; it is provided by Sensirion on GitHub (`gas-index-algorithm`, BSD-3-Clause). The port keeps its default tuning (VOC Index 100 average, learning time 12 h) and its initial blackout (the index is reported as 0 for the first samples). Before implementation, confirm the licence and the algorithm parameters against the repository.
- **General-call reset:** `soft_reset` addresses *all* devices on the bus, so call it only when the bus is dedicated to the sensor; most hosts allow it with a write to address `0x00`.
- **Heater:** the hotplate runs from the first `0x260F` until `0x3615` or a reset; after `heater_off` the algorithm needs warm-up again (the first samples after a restart are not reliable).
- **Supply:** 2.6 mA average at 3.3 V (3.5 mA at 1.8 V); VDD and VDDH on one supply; contamination by siloxanes and other gases can degrade the MOX layer (see the datasheet's environmental-stability section).
- **Address:** the fixed `0x59` is shared with some other chips; there is no ID register, so use `serial_number` to confirm presence, and probe with `active=True` only.

## Sigrok Decoder

Decoder id `sgp40`, input `['i2c']`. Matches I²C address `0x59` (and the general-call reset `0x00 0x06`). Annotates every 2-byte command with its name, decodes the measurement parameters (relative humidity and temperature ticks with CRC check and the converted values), the 3-byte raw-signal response, the self-test result (`0xD4` passed / `0x4B` failed) and the serial number; warns on CRC errors, on a CRC byte appended to a command, on a read header sent before the measurement duration has elapsed (NACK expected), on a second read of one result and on measurement commands issued closer than 0.5 s apart. No conformance annotation pair is emitted (no checked timing constraint).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/gas/sgp40.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/gas/sgp40/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/gas/sgp40/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/gas/sgp40/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/gas/sgp40_test.py` (MicroPython)
- [ ] Tests `python/tests/gas/sgp40_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/gas/sgp40_test_linux.py` (Linux)
- [ ] Unit test `python/tests/gas/sgp40_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/gas/sgp40/sgp40.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/gas/sgp40/sgp40_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/gas/sgp40/sgp40.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/gas/sgp40/Sgp40.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/gas/sgp40/Sgp40.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/gas/Sgp40.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/gas/Sgp40.cpp`
- [ ] Examples `cpp/examples/arduino/gas/Sgp40/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/gas/Sgp40/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/gas/Sgp40/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/gas/Sgp40/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/gas/Sgp40/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/gas/Sgp40/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/gas/Sgp40/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/gas/Sgp40/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/gas/Sgp40/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/gas/Sgp40/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/gas/Sgp40/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/gas/Sgp40/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/gas/Sgp40/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/gas/Sgp40/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/gas/Sgp40/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/gas/Sgp40/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/gas/Sgp40/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/gas/Sgp40/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/gas/sgp40_test/sgp40_test.ino` (Arduino)
- [ ] Tests `cpp/tests/gas/sgp40_test_linux/sgp40_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/gas/sgp40_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/gas/sgp40_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/gas/sgp40_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/gas/sgp40_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/gas/sgp40_test_unit/sgp40_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/gas/sgp40.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/gas/sgp40/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/gas/sgp40/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/gas/sgp40/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/gas/sgp40_test.js`
- [ ] Unit test `nodejs/tests/gas/sgp40_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-gas/nodes/sgp40/sgp40.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-gas/nodes/sgp40/sgp40.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-gas/examples/sgp40/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/gas/sgp40.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/gas/sgp40/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/gas/sgp40/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/gas/sgp40/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/gas/sgp40_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/gas/sgp40_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/gas/sgp40.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/gas/sgp40.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/gas/sgp40/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/gas/sgp40/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/gas/sgp40/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/gas/sgp40/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/sgp40/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/sgp40/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/gas/sgp40_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/gas/sgp40_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/gas/sgp40_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Sgp40Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Sgp40Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Sgp40Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Sgp40Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Sgp40Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Sgp40Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/gas/sgp40/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/gas/sgp40/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/gas/sgp40/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/gas/sgp40/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/gas/sgp40/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/gas/sgp40/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/gas/sgp40/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/gas/sgp40/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/gas/sgp40/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/gas/sgp40/Sgp40Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/gas/Sgp40Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/gas/Sgp40Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/gas/Sgp40Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/sgp40/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/sgp40/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [ ] Checker `conformance/gas/sgp40_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/gas/sgp40_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/sgp40/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

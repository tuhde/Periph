# Chip Spec: SGP30

**Manufacturer:** Sensirion  
**Datasheet:** `datasheets/gas/sgp30.pdf` (Sensirion SGP30 Sensirion Gas Platform datasheet, Sensirion_Gas_Sensors_SGP30_Datasheet_EN-1148053)  
**Category:** gas  
**Transports:** I²C

## Overview

The SGP30 is a multi-pixel MOx gas sensor with a micro hotplate in a 2.45 × 2.45 × 0.9 mm DFN package. It measures a total VOC signal (TVOC, 0–60 000 ppb) and a CO₂-equivalent signal (CO₂eq, 400–60 000 ppm) from its on-chip air-quality algorithm with dynamic baseline compensation, and also exposes two raw signals (H₂ and ethanol, 0–1000 ppm) for verification. A measurement must be requested every second for the baseline algorithm to work; the first 15 s after `Init_air_quality` return fixed values (400 ppm / 0 ppb). The baseline can be read out and restored after a power cycle, and an absolute-humidity value (g/m³) from an external sensor enables on-chip humidity compensation. Supply 1.62–1.98 V (1.8 V typical, VDD and VDDH together), 48.2 mA in measurement mode — **this part is a 1.8 V device**. Interface: I²C (standard/fast mode), 16-bit commands, data words with CRC-8. Typical uses: air purifiers, demand-controlled ventilation, indoor air-quality monitors.

## Transport Configuration

### I²C
- **Address:** `0x58` (7-bit, fixed; one sensor per bus)
- **Max clock:** 400 kHz (fast mode); 100 kHz in standard mode
- **Command format:** 16-bit command MSB first; commands with a parameter append words each followed by a CRC-8 (polynomial `0x31`, init `0xFF`, no reflection, no final XOR; `CRC(0xBEEF) = 0x92`). Responses are words (MSB first) each followed by a CRC-8. The sensor NACKs the read header while a measurement is in progress; data can be read only once (further bytes read as 1s)
- **Register addressing:** none (command pointers, no register file); write the command, wait the measurement duration, then read the response
- **Identity register:** none; the 48-bit serial ID (`0x3682`) and the feature-set word (`0x202F`) identify the part but are commands. Registered in `registry/chips.json` with `id_probe: null`
- **Probe safety:** `write_sensitive` — every written byte sequence is a command (`0x2003` starts the algorithm). A probe should only write `0x3682` and read the response (wait 0.5 ms)

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| SDA, SCL | — | I²C, open-drain, external pull-ups |
| VDD, VDDH | — | 1.62–1.98 V (1.8 V typical); connect together |
| VSS | — | ground |

## Command Set

| Command | Code | Parameters (with CRC) | Response (with CRC) | Duration (typ./max) | Description |
|---------|------|-----------------------|---------------------|---------------------|-------------|
| `Init_air_quality` | `0x2003` | — | — | 2 ms / 10 ms | Starts the air-quality algorithm; required after every power-up or soft reset |
| `Measure_air_quality` | `0x2008` | — | 6 bytes: CO₂eq word + CRC, TVOC word + CRC | 10 ms / 12 ms | Must be sent every 1 s |
| `Get_baseline` | `0x2015` | — | 6 bytes: CO₂eq baseline, TVOC baseline | 10 ms / 10 ms | Read the baseline for non-volatile storage |
| `Set_baseline` | `0x201E` | 6 bytes in the order **TVOC, CO₂eq** | — | 10 ms / 10 ms | Restore the baseline (after `Init_air_quality`) |
| `Set_humidity` | `0x2061` | 3 bytes: absolute humidity word (8.8 fixed point, g/m³) + CRC | — | 1 ms / 10 ms | Humidity compensation; `0x0000` = off/default `0x0B92` (11.57 g/m³) |
| `Measure_test` | `0x2032` | — | 3 bytes | 200 ms / 220 ms | Self-test, `0xD400` = passed (production test; not after `Init_air_quality`) |
| `Measure_raw_signals` | `0x2050` | — | 6 bytes: H₂ signal word, ethanol signal word | 20 ms / 25 ms | Raw signals (verification) |
| `Get_feature_set_version` | `0x202F` | — | 3 bytes | not specified in the datasheet table (use 10 ms) | Feature set `0x0020` (product type 0) |
| `Get_serial_id` | `0x3682` | — | 9 bytes (3 words + CRC) | 0.5 ms | 48-bit serial ID, MSB first |
| `Soft reset` | `0x06` to the **general-call address** `0x00` | — | — | — | Resets *all* devices on the bus that support the general call; re-send `Init_air_quality` afterwards |

## Initialization Sequence

1. Power VDD/VDDH (1.62–1.98 V); the sensor reaches idle after `tPU`.
2. Optionally read the serial ID (`0x3682`, wait 0.5 ms) and the feature set (`0x202F`) as a presence check.
3. Send `Init_air_quality` (`0x2003`), wait 10 ms.
4. Optionally `Set_baseline` (restored value), `Set_humidity`.
5. Send `Measure_air_quality` (`0x2008`) every second; wait 12 ms, read 6 bytes, verify the two CRCs. The first 15 s return 400 ppm / 0 ppb.

## Implementation Stages

### Minimal

Goal: read TVOC and CO₂eq with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Stores the plain `Connection`; reads the serial ID as a presence check and sends `Init_air_quality` |
| `read` | — | `{co2eq_ppm, tvoc_ppb}` | Sends `Measure_air_quality`, waits 12 ms, reads and CRC-checks both words. The caller calls it once per second; the first 15 s return 400 / 0 |

**Sensible defaults:** the on-chip humidity default (11.57 g/m³), no baseline restore.

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_co2eq` / `read_tvoc` | — | int | One value (still one measurement) |
| `read_raw` | — | `(h2_signal, ethanol_signal)` | `Measure_raw_signals` |
| `get_baseline` | — | `(co2eq, tvoc)` | `Get_baseline` |
| `set_baseline` | `co2eq: int, tvoc: int` | — | `Set_baseline` (sends TVOC first, then CO₂eq; only valid after `init_air_quality`) |
| `set_humidity` | `absolute_humidity_g_m3: float` | — | `Set_humidity` with `round(g/m³ × 256)` (0 = off, valid 1/256 … 255.996) |
| `set_humidity_from_rh` | `humidity_rh: float, temperature_c: float` | — | Converts relative humidity and temperature to absolute humidity (g/m³) and calls `set_humidity` |
| `init_air_quality` | — | — | `Init_air_quality` (restarts the 15 s initialisation phase) |
| `self_test` | — | bool | `Measure_test`, waits 220 ms, True if the result is `0xD400` |
| `feature_set` | — | int | `0x202F`, e.g. `0x0020` |
| `serial_id` | — | int | `0x3682`, 48-bit |
| `soft_reset` | — | — | General-call reset (`0x06` written to address `0x00`); raises if the connection cannot address the general call; the driver then re-runs `init_air_quality` |
| `read_word` / `write_command` | `command: int` / `command: int, data: bytes` | int / — | Raw command access with the CRC handled (diagnostics) |

**Additional configuration options:** humidity compensation, baseline save/restore.

## Data Conversion

```
# every data word is followed by CRC-8 (poly 0x31, init 0xFF)
co2eq_ppm = word0      # Measure_air_quality, 400 .. 60000 (fixed 400 for the first 15 s)
tvoc_ppb  = word1      # 0 .. 60000

humidity word = round(absolute_humidity_g_m3 * 256)     # 8.8 fixed point, 0x0F80 = 15.5 g/m3
absolute humidity (g/m3) = 216.7 * (RH/100 * 6.112 * exp(17.62 * T / (243.12 + T))) / (273.15 + T)   # RH in %, T in degC

raw signals:  ln(c / c_ref) = (s_ref - s_out) / 512
serial id  = word0 << 32 | word1 << 16 | word2
```

Output resolution: TVOC 1 ppb up to 2008 ppb, 6 ppb to 11110 ppb, 32 ppb above; CO₂eq 1 ppm up to 1479 ppm, 3 ppm to 5144 ppm, 9 ppm to 17597 ppm and coarser above. Set baseline order is (TVOC, CO₂eq) while the get order is (CO₂eq, TVOC).

## Node-RED

Node name: `periph-sgp30`  
Package: `node-red-contrib-periph-gas`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ co2eq_ppm, tvoc_ppb }` | one measurement; the node expects to be triggered once per second; `msg.humidity_g_m3` sets the humidity compensation |

Config panel fields: I²C bus number, humidity compensation, baseline persistence.

### Demo flow

Inject node (every 1 s) → `periph-sgp30` → function node mapping TVOC to an air-quality label → chart, gauge and debug nodes; a context variable stores the baseline hourly.

## Examples

### Demo

**Air-quality monitor with baseline persistence.** With the Full driver print the serial ID and feature set, run the self-test, then `init_air_quality` and measure every second for 60 s (the first 15 s show the fixed 400/0 values), apply humidity compensation from a fixed 50 %RH/25 °C value, read the baseline and write it back, show the raw H₂/ethanol signals and finish with `soft_reset` (skipped on shared buses). Exercises `init`, `read`, `read_co2eq`, `read_tvoc`, `read_raw`, `get_baseline`, `set_baseline`, `set_humidity`, `set_humidity_from_rh`, `init_air_quality`, `self_test`, `feature_set`, `serial_id`, `soft_reset`, `read_word`, `write_command`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| `Measure_air_quality` call interval | 1 s (required by the baseline algorithm) |
| Initialisation phase | 15 s of fixed output after `Init_air_quality` |
| Measurement durations | 12 ms (air quality), 25 ms (raw), 10 ms (baseline), 10 ms (humidity), 220 ms (test) max |
| Serial ID | wait 0.5 ms before the read header |
| I²C clock | ≤ 400 kHz |

The durations are minimum host waits (the sensor NACKs the read header until the result is ready); there is no bus-decodable start/end pair with a datasheet bound, so `sgp30_timing.conf` defines no checks.

## Implementation Notes

- **Plain Connection, command protocol:** write commands with `write`, read results with `read` after the measurement duration; do not use a register-addressed connection.
- **1.8 V device:** VDD and VDDH are 1.62–1.98 V (not 3.3 V); the I²C levels follow VDD. Level shifting is needed on 3.3 V hosts unless the module has a regulator.
- **1 Hz cadence:** `Measure_air_quality` must be called every second for the dynamic baseline compensation; the driver does not sleep, the caller owns the cadence. Irregular calls degrade the TVOC/CO₂eq signals.
- **Init after every reset:** `Init_air_quality` is mandatory after power-up or soft reset; a baseline must be restored *after* it.
- **Baseline storage:** read the baseline periodically and store it in non-volatile memory; the datasheet defers the storage policy (intervals, maximum age) to the SGP30 driver integration guide, which must be consulted before implementing the demo; the driver exposes the values and leaves the storage to the application.
- **Baseline set order:** `Set_baseline` takes (TVOC, CO₂eq) while `Get_baseline` returns (CO₂eq, TVOC); the driver hides the swap.
- **Humidity:** the humidity word is an absolute humidity in g/m³ (8.8 fixed point), not relative humidity; use `set_humidity_from_rh` with a nearby humidity sensor.
- **General-call reset:** `soft_reset` resets every device on the bus supporting the general call; use it only on a dedicated bus.
- **Self-test:** `Measure_test` is for production testing and must not be used after `Init_air_quality`.
- **Address:** `0x58` is fixed; presence check with `get_serial_id`; probe with `active=True` only.

## Sigrok Decoder

Decoder id `sgp30`, input `['i2c']`. Matches I²C address `0x58` (and the general-call reset `0x00 0x06`). Annotates each 16-bit command with its name, decodes the humidity parameter (g/m³) and baseline words with CRC verification, the CO₂eq/TVOC response, baselines, raw signals, the feature set and serial ID, and the self-test result; warns on CRC errors, on a `Measure_air_quality` repeated at an interval other than about 1 s, on a read header before the measurement duration has elapsed, on `Set_baseline` without a preceding `Init_air_quality` and on `Measure_test` after `Init_air_quality`. No conformance annotation pair is emitted (no checked timing constraint).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/gas/sgp30.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/gas/sgp30/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/gas/sgp30/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/gas/sgp30/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/gas/sgp30_test.py` (MicroPython)
- [ ] Tests `python/tests/gas/sgp30_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/gas/sgp30_test_linux.py` (Linux)
- [ ] Unit test `python/tests/gas/sgp30_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/gas/sgp30/sgp30.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/gas/sgp30/sgp30_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/gas/sgp30/sgp30.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/gas/sgp30/Sgp30.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/gas/sgp30/Sgp30.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/gas/Sgp30.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/gas/Sgp30.cpp`
- [ ] Examples `cpp/examples/arduino/gas/Sgp30/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/gas/Sgp30/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/gas/Sgp30/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/gas/Sgp30/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/gas/Sgp30/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/gas/Sgp30/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/gas/Sgp30/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/gas/Sgp30/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/gas/Sgp30/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/gas/Sgp30/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/gas/Sgp30/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/gas/Sgp30/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/gas/Sgp30/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/gas/Sgp30/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/gas/Sgp30/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/gas/Sgp30/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/gas/Sgp30/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/gas/Sgp30/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/gas/sgp30_test/sgp30_test.ino` (Arduino)
- [ ] Tests `cpp/tests/gas/sgp30_test_linux/sgp30_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/gas/sgp30_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/gas/sgp30_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/gas/sgp30_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/gas/sgp30_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/gas/sgp30_test_unit/sgp30_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/gas/sgp30.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/gas/sgp30/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/gas/sgp30/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/gas/sgp30/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/gas/sgp30_test.js`
- [ ] Unit test `nodejs/tests/gas/sgp30_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-gas/nodes/sgp30/sgp30.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-gas/nodes/sgp30/sgp30.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-gas/examples/sgp30/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/gas/sgp30.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/gas/sgp30/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/gas/sgp30/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/gas/sgp30/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/gas/sgp30_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/gas/sgp30_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/gas/sgp30.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/gas/sgp30.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/gas/sgp30/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/gas/sgp30/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/gas/sgp30/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/gas/sgp30/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/sgp30/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/sgp30/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/gas/sgp30_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/gas/sgp30_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/gas/sgp30_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Sgp30Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Sgp30Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Sgp30Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Sgp30Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Sgp30Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Sgp30Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/gas/sgp30/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/gas/sgp30/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/gas/sgp30/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/gas/sgp30/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/gas/sgp30/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/gas/sgp30/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/gas/sgp30/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/gas/sgp30/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/gas/sgp30/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/gas/sgp30/Sgp30Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/gas/Sgp30Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/gas/Sgp30Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/gas/Sgp30Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/sgp30/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/sgp30/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [ ] Checker `conformance/gas/sgp30_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/gas/sgp30_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/sgp30/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

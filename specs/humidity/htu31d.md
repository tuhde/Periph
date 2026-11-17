# Chip Spec: HTU31D

**Manufacturer:** TE Connectivity (Measurement Specialties)  
**Datasheet:** `datasheets/humidity/htu31d.pdf` (HTU31D RH/T sensor IC, version 2, 04/2020)  
**Category:** humidity  
**Transports:** I²C

## Overview

The HTU31D is a digital relative-humidity and temperature sensor in a DFN package with 16-bit outputs (RH ±2 %RH typ. over 0–100 %RH, 0.01 %RH nominal resolution; temperature ±0.2 °C typ. over −40…125 °C, 0.016 °C resolution), AEC-Q100 grade 1 qualification, a 3.0–5.5 V supply (5 V typical) and a built-in heater to drive off condensation. It is command driven (no register pointer): a conversion command with selectable oversampling (OSR 0–3 separately for humidity and temperature) loads a result into the sensor memory, and a separate read command returns the temperature and humidity, each followed by a CRC-8. A diagnostic byte reports out-of-range and NVM errors, and a 24-bit serial number identifies the device. The I²C address is selected by the `IC_Add` pin (`0x40` / `0x41`). The HTU31V is the analog (voltage output) variant and is not covered. Typical uses: appliances, medical, humidifiers, printers, automotive and environmental monitoring.

## Transport Configuration

### I²C
- **Address:** `0x40` (`IC_Add` = GND) or `0x41` (`IC_Add` = VDD); the level must stay constant for the whole transaction. A factory-programmable software address also exists (not covered)
- **Max clock:** the datasheet's timing table lists up to 10 MHz; use ≤ 400 kHz (fast mode) in practice, there is no minimum SCL frequency
- **Identity:** no ID register; the serial-number command (`0x0A`) returns a 24-bit device-specific number with a CRC, which is not a fixed fingerprint. `id_probe` is `null` in `registry/chips.json`. The addresses overlap with the HDC1008/HDC1080/HTU21D/Si7021 (`0x40`) and INA2xx family
- **Probe safety:** `write_sensitive` — every written byte is a command (`0x1E` resets the chip, `0x04`/`0x02` switch the heater, `0x40`–`0x5F` start a conversion); undocumented command bytes must not be sent

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| SDA, SCL | — | I²C; open-drain with external pull-up where the host has none (e.g. 10 kΩ at 100 kHz) |
| IC_Add | — | address select: GND = `0x40`, VDD = `0x41` |
| RST | low | hardware reset; a pulse of ≥ 1 µs (datasheet table: 2 ms typical) resets the chip; leave floating or tie to VDD via ≥ 2 kΩ if unused |
| VDD, GND | — | 3.0–5.5 V (5 V typ.); 100 nF decoupling close to the pins |

## Command Set

Each command is one byte written to the device; bit 0 is a don't-care ("stop") bit that the driver sets to 0.

| Command | Byte | Description |
|---------|------|-------------|
| Reset | `0x1E` | Soft reset (reboot); ready again after ≤ 15 ms; also switches the heater off |
| Heater on | `0x04` | Switch the internal heater on |
| Heater off | `0x02` | Switch the heater off |
| Conversion | `0x40` \| `osr_rh << 3` \| `osr_t << 1` | Start one temperature + humidity conversion with the given oversampling; the result is stored in the sensor memory |
| Read T&RH | `0x00` | Read 6 bytes: temperature MSB, LSB, CRC, humidity MSB, LSB, CRC |
| Read RH | `0x10` | Read 3 bytes: humidity MSB, LSB, CRC |
| Read diagnostic | `0x08` | Read the diagnostic byte |
| Read serial number | `0x0A` | Read 3 serial-number bytes + CRC (repeated 24-bit number with CRC per the datasheet figure) |

The read commands must be ended with a NACK of the last byte and a STOP (otherwise the chip may block SDA); a read may be stopped after the temperature (NACK after the temperature CRC) when humidity is not needed. A measurement is: write the conversion command, wait for the conversion time, write a read command, read the bytes.

### Conversion command (`0x40 | osr_rh << 3 | osr_t << 1`)

| OSR | Humidity resolution / conversion time (typ.) | Temperature resolution / conversion time (typ.) |
|-----|---------------------------------------------|------------------------------------------------|
| 0 | 0.020 %RH / 1.11 ms | 0.040 °C / 1.57 ms |
| 1 | 0.014 %RH / 2.14 ms | 0.025 °C / 3.06 ms |
| 2 | 0.010 %RH / 4.21 ms | 0.016 °C / 6.03 ms |
| 3 | 0.007 %RH / 8.34 ms | 0.012 °C / 11.98 ms |

The conversion time of one command is the sum of the humidity and temperature times of the chosen OSRs (e.g. 20.32 ms typ. for OSR 3 / 3). The datasheet gives only typical values.

### Diagnostic register (`0x08`)

| Bit | Name | Description |
|-----|------|-------------|
| 7 | NVM_ERR | NVM CRC failed |
| 6 | RH_OVERRUN | humidity truncated to 0 or `2^16 − 1` |
| 5 | RH_HIGH_ERR | humidity calculation above 120 %RH |
| 4 | RH_LOW_ERR | humidity calculation below −10 %RH |
| 3 | T_OVERRUN | temperature truncated to 0 or `2^16 − 1` |
| 2 | T_HIGH_ERR | temperature calculation above 150 °C |
| 1 | T_LOW_ERR | temperature calculation below −50 °C |
| 0 | HEATER_ON | the heater is on |

Checksum: CRC-8, polynomial `x⁸ + x⁵ + x⁴ + 1` (`0x31`), initial value `0x00`, no final XOR, over each 2-byte data word (and the serial-number bytes). Test vectors: `0xDC` → `0x79`, `0x683A` → `0x7C`, `0x4E85` → `0x6B`.

## Initialization Sequence

1. Power up VDD (3.0–5.5 V); the chip is ready after ≤ 10 ms.
2. Optionally send the reset command `0x1E` and wait ≥ 15 ms.
3. Measure: write the conversion command (`0x5E` = OSR 3 / 3), wait for the conversion time (≥ 25 ms for OSR 3 / 3), write `0x00`, read 6 bytes (NACK the last), verify both CRCs and convert.

## Implementation Stages

### Minimal

Goal: read humidity and temperature with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Stores the plain `Connection`; sends reset (`0x1E`) and waits 15 ms |
| `read` | — | `{temperature_c, humidity_pct}` | Conversion at the highest resolution (`0x5E`, OSR 3 / 3), waits 25 ms, reads T&RH with both CRCs verified (raises on mismatch); humidity clamped to 0–100 %RH |

**Sensible defaults:** reset at init, highest oversampling (OSR 3 for both), heater off, CRC verified.

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_humidity` | — | float | %RH only (conversion then `0x10`) |
| `read_temperature` | — | float | °C only (conversion then `0x00`, NACK after the temperature CRC) |
| `read_raw` | — | `(t_raw, rh_raw)` | 16-bit codes of one measurement |
| `set_resolution` / `get_resolution` | `osr_rh: int, osr_t: int` (0–3 each) / — | — / `(osr_rh, osr_t)` | OSR used by the following measurements; raises for other values |
| `conversion_time` | — | float | Seconds: sum of the typical humidity and temperature conversion times for the current OSR |
| `start_conversion` | — | — | Writes the conversion command only (non-blocking use) |
| `read_result` | — | `{temperature_c, humidity_pct}` | Reads the last conversion result (`0x00`) |
| `set_heater` | `on: bool` | — | `0x04` / `0x02` |
| `is_heater_on` | — | bool | Diagnostic bit 0 |
| `diagnostic` | — | int | Diagnostic byte |
| `diagnostic_flags` | — | dict | Named flags of the diagnostic byte |
| `serial_number` | — | int | 24-bit serial number (CRC verified when present) |
| `software_reset` | — | — | `0x1E`, waits 15 ms; heater off, defaults |
| `dew_point` | — | float | °C from one measurement (Magnus formula, see Data Conversion) |

**Additional configuration options:** oversampling of humidity and temperature, heater.

## Data Conversion

```
t_raw  = (msb << 8) | lsb               # 16-bit unsigned
rh_raw = (msb << 8) | lsb               # 16-bit unsigned

humidity_pct  = 100 * rh_raw / (2**16 - 1)          # clamp 0 ... 100
temperature_c = -40 + 165 * t_raw / (2**16 - 1)

# dew point (Magnus, over water; T in degC, RH in %)
g = ln(humidity_pct / 100) + 17.62 * T / (243.12 + T)
dew_point_c = 243.12 * g / (17.62 - g)
```

The same formulas apply at every resolution. The Magnus constants are common Sonntag-style values, not part of the datasheet.

## Node-RED

Node name: `periph-htu31d`  
Package: `node-red-contrib-periph-humidity`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature_c, humidity_pct }` | One measurement per input message |

Config panel fields: I²C bus number, address (`0x40` / `0x41`), resolution (OSR), heater.

### Demo flow

Inject node (every 5 s) → `periph-htu31d` → function node formatting `"25.0 °C / 50.0 %RH"` → debug node and gauges.

## Examples

### Demo

**Condensation-aware hygrometer.** With the Full driver print the serial number and the diagnostic flags, read temperature and humidity every 2 s for 30 s and compute the dew point, compare readings and the measured read time at the four resolutions (OSR 0…3), show the heater effect (`set_heater(True)` for 10 s: RH drop and temperature rise, then off, print `is_heater_on` and the diagnostic byte), demonstrate the non-blocking `start_conversion` / `read_result` pair, and finish with a software reset. Exercises `init`, `read`, `read_humidity`, `read_temperature`, `read_raw`, `set_resolution`, `get_resolution`, `conversion_time`, `start_conversion`, `read_result`, `set_heater`, `is_heater_on`, `diagnostic`, `diagnostic_flags`, `serial_number`, `software_reset`, `dew_point`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Power-up time | ≤ 10 ms |
| Soft reset time | ≤ 15 ms |
| Reset pulse (RST pin) | 2 ms typ. (≥ 1 µs reliably triggers) |
| Humidity conversion (OSR 3 / 2 / 1 / 0) | 8.34 / 4.21 / 2.14 / 1.11 ms typ. |
| Temperature conversion (OSR 3 / 2 / 1 / 0) | 11.98 / 6.03 / 3.06 / 1.57 ms typ. |
| I²C clock | datasheet up to 10 MHz; the driver uses ≤ 400 kHz |

The datasheet gives conversion times as typical values only, without a maximum, and the soft-reset/power-up limits are not marked by a bus event pair, so `htu31d_timing.conf` defines no checks.

## Implementation Notes

- **Plain Connection, command protocol:** there is no register pointer; commands are written with `write`, results read in separate transactions. Do not use a register-addressed connection.
- **Conversion wait:** the datasheet does not say whether the chip NACKs a read during a conversion; wait the conversion time (plus margin — the driver uses 25 ms at OSR 3 / 3 and `1.25 × typical` otherwise) before the read command.
- **Always end reads with NACK + STOP:** otherwise the chip may hold SDA low.
- **Read order:** `0x00` returns temperature (2 bytes + CRC) then humidity (2 bytes + CRC); `0x10` returns humidity only. The conversion command updates both values; a read returns the last conversion's result and can be repeated.
- **CRC:** the CRC covers each 16-bit word; verify both CRCs.
- **Heater:** the integrated heater (12.5 mW typical) raises the die temperature; let the sensor cool before trusting readings. The reset command (and a power cycle) switch it off.
- **Address overlap:** `0x40` is shared with HDC1008/HDC1080/HTU21D/Si7021 and the INA2xx family; `0x41` is shared with the INA2xx family. Probe the HTU31D only with `active=True`; it cannot be distinguished from the other chips by an ID register.
- **Serial number format:** the datasheet figure shows the 24-bit number followed by a CRC; check the byte layout against hardware before relying on it.
- **Diagnostic read:** the datasheet describes one diagnostic byte; check against hardware whether a CRC byte follows.
- **Analog variant:** the HTU31V (voltage output) has no I²C interface and is not covered.

## Sigrok Decoder

Decoder id `htu31d`, input `['i2c']`. Matches I²C addresses `0x40`–`0x41`. Annotates every command (reset, heater, conversion with the decoded OSR pair, read T&RH, read RH, read diagnostic, read serial number), decodes the 16-bit temperature and humidity words (with CRC check) as °C and %RH, decodes the diagnostic byte into its flags, and warns on undocumented command bytes and CRC mismatches. No conformance annotation pair is emitted (no checked timing constraint).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/humidity/htu31d.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/humidity/htu31d/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/humidity/htu31d/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/humidity/htu31d/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/humidity/htu31d_test.py` (MicroPython)
- [ ] Tests `python/tests/humidity/htu31d_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/humidity/htu31d_test_linux.py` (Linux)
- [ ] Unit test `python/tests/humidity/htu31d_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/humidity/htu31d/htu31d.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/humidity/htu31d/htu31d_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/humidity/htu31d/htu31d.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/humidity/htu31d/Htu31d.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/humidity/htu31d/Htu31d.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/humidity/Htu31d.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/humidity/Htu31d.cpp`
- [ ] Examples `cpp/examples/arduino/humidity/Htu31d/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/humidity/Htu31d/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/humidity/Htu31d/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/humidity/Htu31d/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/humidity/Htu31d/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/humidity/Htu31d/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/humidity/Htu31d/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/humidity/Htu31d/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/humidity/Htu31d/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/humidity/Htu31d/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/humidity/Htu31d/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/humidity/Htu31d/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/humidity/Htu31d/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/humidity/Htu31d/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/humidity/Htu31d/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/humidity/Htu31d/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/humidity/Htu31d/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/humidity/Htu31d/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/humidity/htu31d_test/htu31d_test.ino` (Arduino)
- [ ] Tests `cpp/tests/humidity/htu31d_test_linux/htu31d_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/humidity/htu31d_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/humidity/htu31d_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/humidity/htu31d_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/humidity/htu31d_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/humidity/htu31d_test_unit/htu31d_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/humidity/htu31d.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/humidity/htu31d/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/humidity/htu31d/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/humidity/htu31d/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/humidity/htu31d_test.js`
- [ ] Unit test `nodejs/tests/humidity/htu31d_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-humidity/nodes/htu31d/htu31d.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-humidity/nodes/htu31d/htu31d.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-humidity/examples/htu31d/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/humidity/htu31d.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/humidity/htu31d/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/humidity/htu31d/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/humidity/htu31d/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/humidity/htu31d_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/humidity/htu31d_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/humidity/htu31d.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/humidity/htu31d.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/humidity/htu31d/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/humidity/htu31d/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/humidity/htu31d/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/humidity/htu31d/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/htu31d/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/htu31d/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/humidity/htu31d_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/humidity/htu31d_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/humidity/htu31d_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/Htu31dMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/Htu31dFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/Htu31dMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/Htu31dFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/Htu31dMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/Htu31dFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/humidity/htu31d/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/humidity/htu31d/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/humidity/htu31d/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/humidity/htu31d/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/humidity/htu31d/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/humidity/htu31d/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/humidity/htu31d/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/humidity/htu31d/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/humidity/htu31d/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/humidity/htu31d/Htu31dTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/humidity/Htu31dTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/humidity/Htu31dTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/humidity/Htu31dSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/htu31d/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/htu31d/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [ ] Checker `conformance/humidity/htu31d_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/humidity/htu31d_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/htu31d/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

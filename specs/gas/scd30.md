# Chip Spec: SCD30

**Manufacturer:** Sensirion  
**Datasheet:** `datasheets/gas/scd30.pdf` (Sensirion SCD30 Sensor Module datasheet, Version 1.0 – D1, May 2020)  
**Category:** gas  
**Transports:** I²C

## Overview

The SCD30 is a NDIR CO₂ sensor module with a built-in Sensirion SHT humidity and temperature sensor. It measures CO₂ over 0–40 000 ppm on I²C (specified accuracy ±(30 ppm + 3 % of the reading) and repeatability ±10 ppm at 400–10 000 ppm), relative humidity (±3 %RH typ.) and temperature (±(0.4 °C + 0.023 × (T − 25 °C))), and delivers all three values as IEEE-754 32-bit floats. Measurements run continuously at a configurable interval (2–1800 s, default 2 s); a data-ready flag (and the `RDY` pin) signals a new sample. The module supports automatic self-calibration (ASC), forced recalibration (FRC) against a known reference concentration, altitude/ambient-pressure compensation and a temperature offset for the on-board RH/T sensor. The command set is the **SCD30 Interface Description** (separate Sensirion document; the datasheet in this repo covers electrical and mechanical data only and refers to it); this spec follows that interface description. Interfaces: I²C (default), Modbus-RTU over UART (`SEL` pin pulled to VDD) and a PWM output of the CO₂ value. Supply 3.3–5.5 V, 19 mA average at a 2 s interval (75 mA peak). Typical uses: indoor air quality, HVAC and demand-controlled ventilation, IoT environment monitors.

## Transport Configuration

### I²C
- **Address:** `0x61` (7-bit, fixed; one module per bus)
- **Max clock:** 100 kHz; the sensor stretches the clock — the master **must support clock stretching** (up to about 150 ms at the start of an access)
- **Interface selection:** the `SEL` pin floating or at GND selects I²C; pulled to VDD (≤ 4 V, use a divider above) selects Modbus
- **Command format:** 16-bit command, MSB first, written in one transaction; commands with an argument append a 16-bit argument and an 8-bit CRC. Reads return 16-bit words, each followed by a CRC byte (CRC-8, polynomial `0x31`, init `0xFF`, no reflection, no final XOR)
- **Register addressing:** none (16-bit command pointers, no register file); for a read, write the command, wait 3 ms (datasheet-independent guideline of the interface description), then issue a read of the response length
- **Identity register:** none; the firmware version command (`0xD100`) returns two bytes. Registered in `registry/chips.json` with `id_probe: null`
- **Probe safety:** `write_sensitive` — every written byte sequence is a command; writing `0xD304` soft-resets, `0x0010`/`0x0104` start/stop the measurement. A probe should only write the read-only command `0xD100` and read the response

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| RDY | high | data-ready output: high when a new measurement can be read |
| PWM | — | PWM output of the CO₂ concentration (not used by the driver) |
| SEL | — | interface select: floating/GND = I²C, VDD (≤ 4 V) = Modbus |
| TX/SCL, RX/SDA | — | I²C pins with internal 45 kΩ pull-ups to 3 V (a level shifter is needed above 3 V logic); Modbus pins in the other mode |
| VDD, GND | — | 3.3–5.5 V |

## Command Set

| Command | Code | Argument | Response | Description |
|---------|------|----------|----------|-------------|
| Trigger continuous measurement | `0x0010` | ambient pressure in mbar (hPa), 0 or 700–1400, + CRC | — | Starts the continuous measurement at the set interval; a non-zero pressure compensates until the next command; 0 deactivates the compensation |
| Stop continuous measurement | `0x0104` | — | — | Stops the measurement |
| Set measurement interval | `0x4600` | seconds (2–1800), + CRC | — | Interval between measurements (default 2 s) |
| Get measurement interval | `0x4600` | — | 1 word + CRC | Read back (write the command, then read 3 bytes) |
| Get data ready status | `0x0202` | — | 1 word + CRC | `0x0001` = data ready, `0x0000` = not ready |
| Read measurement | `0x0300` | — | 18 bytes | CO₂ (float32, 2 words + 2 CRC), temperature (float32), relative humidity (float32); each 16-bit word followed by its CRC |
| (De)activate ASC | `0x5306` | `0x0001` on / `0x0000` off, + CRC | — | Automatic self-calibration |
| Get ASC status | `0x5306` | — | 1 word + CRC | Read back |
| Set forced recalibration value | `0x5204` | CO₂ reference 400–2000 ppm, + CRC | — | FRC against a known concentration; overrides ASC |
| Get forced recalibration value | `0x5204` | — | 1 word + CRC | Read back (default 400 ppm) |
| Set temperature offset | `0x5403` | offset in 0.01 K units, + CRC | — | Compensates self-heating of the module (positive offset lowers the reported temperature) |
| Get temperature offset | `0x5403` | — | 1 word + CRC | Read back |
| Set altitude compensation | `0x5102` | altitude in metres above sea level, + CRC | — | Compensates the ambient pressure (overridden by a non-zero pressure in the trigger command) |
| Get altitude compensation | `0x5102` | — | 1 word + CRC | Read back |
| Read firmware version | `0xD100` | — | 1 word + CRC | firmware version (major byte, minor byte) |
| Soft reset | `0xD304` | — | — | Restarts the sensor module (settings stored in non-volatile memory remain) |

Settings written with the setter commands (interval, ASC, FRC, temperature offset, altitude) are stored in non-volatile memory by the module.

## Initialization Sequence

1. Power up VDD (3.3–5.5 V); wait ≥ 2 s for start-up.
2. Optionally soft-reset (`0xD304`) and wait ≥ 2 s; read the firmware version (`0xD100`).
3. Optionally set interval, ASC, altitude/temperature offset (each argument with its CRC).
4. Start continuous measurement (`0x0010` with pressure 0).
5. Poll the data-ready status (`0x0202`) or watch `RDY`; when ready read the measurement (`0x0300`, 18 bytes), verify the three CRC pairs, convert.

## Implementation Stages

### Minimal

Goal: read CO₂, temperature and humidity with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Stores the plain `Connection`; reads the firmware version (sanity check), starts continuous measurement with the default interval (2 s) and no pressure compensation |
| `read` | — | `{co2_ppm, temperature_c, humidity_rh}` | Waits for the data-ready flag (polls `0x0202`, up to the measurement interval plus margin), reads the 18-byte measurement, verifies the CRCs, converts the three floats |

**Sensible defaults:** continuous mode, 2 s interval, ASC off (the module default), no altitude or pressure compensation, temperature offset 0.

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_co2` / `read_temperature` / `read_humidity` | — | float | One value of a fresh measurement |
| `data_ready` | — | bool | `0x0202` |
| `read_measurement` | — | `(co2_ppm, temperature_c, humidity_rh)` | Reads `0x0300` without waiting (call after `data_ready`) |
| `start` | `pressure_pa: int = 0` | — | `0x0010`; the pressure is given in **pascals** and converted to mbar (`round(pressure_pa / 100)`, range 70 000–140 000 Pa, or 0 = off) |
| `stop` | — | — | `0x0104` |
| `set_interval` / `get_interval` | `seconds: int` (2–1800) / — | — / int | `0x4600` |
| `set_auto_calibration` / `get_auto_calibration` | `enabled: bool` / — | — / bool | `0x5306` |
| `set_forced_calibration` / `get_forced_calibration` | `ppm: int` (400–2000) / — | — / int | `0x5204`; apply after ≥ 2 minutes of stable measurement at the reference concentration |
| `set_temperature_offset` / `get_temperature_offset` | `kelvin: float` / — | — / float | `0x5403` in 0.01 K units (round to the nearest 0.01) |
| `set_altitude` / `get_altitude` | `metres: int` / — | — / int | `0x5102` |
| `firmware_version` | — | `(major, minor)` | `0xD100` |
| `soft_reset` | — | — | `0xD304`, waits 2 s, restarts the measurement if it was running |
| `read_word` / `write_word` | `command: int` / `command: int, value: int` | int / — | Raw command access with the CRC handled (for diagnostics) |

**Additional configuration options:** interval, ASC, FRC reference, temperature offset, altitude and ambient-pressure compensation.

## Data Conversion

```
# measurement response: 18 bytes = 6 words, each word followed by CRC-8 (poly 0x31, init 0xFF)
co2_ppm       = float32_be(w0 << 16 | w1)       # w0, w1 first two words, MSW first
temperature_c = float32_be(w2 << 16 | w3)
humidity_rh   = float32_be(w4 << 16 | w5)       # percent relative humidity

# setter arguments
interval            : uint16 seconds, 2..1800
ambient pressure    : uint16 mbar (= hPa), 0 or 700..1400   (driver input in Pa: mbar = Pa / 100)
altitude            : uint16 metres
temperature offset  : uint16 in units of 0.01 K  (offset_K * 100)
forced calibration  : uint16 ppm, 400..2000
```

CRC-8 example from the interface description: the two bytes `0xBE 0xEF` have CRC `0x92`. The datasheet tolerances: CO₂ ±(30 ppm + 3 %) at 400–10 000 ppm after calibration, temperature ±(0.4 °C + 0.023 × (T − 25 °C)) at 0–50 °C. Units: ppm for CO₂ (a mole fraction; no imperial or non-SI pressure units are used in the API — pascals only).

## Node-RED

Node name: `periph-scd30`  
Package: `node-red-contrib-periph-gas`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ co2_ppm, temperature_c, humidity_rh }` | latest measurement (waits for the next sample if none is ready) |

Config panel fields: I²C bus number, measurement interval, altitude.

### Demo flow

Inject node (every 5 s) → `periph-scd30` → function node classifying the CO₂ level (good < 1000 ppm, poor > 1500 ppm) → gauge and debug nodes.

## Examples

### Demo

**Room air-quality monitor.** With the Full driver print the firmware version and all settings (interval, ASC, FRC value, temperature offset, altitude), read CO₂, temperature and humidity every 2 s for 30 s with a CO₂ classification (good/moderate/poor), apply a 2 K temperature offset and show the effect on temperature/humidity, set altitude compensation and then ambient pressure compensation, enable ASC, show `data_ready`, `stop`/`start`, change the interval to 5 s, demonstrate forced recalibration (documented, skipped unless a reference gas is attached) and finish with `soft_reset`. Exercises `init`, `read`, `read_co2`, `read_temperature`, `read_humidity`, `data_ready`, `read_measurement`, `start`, `stop`, `set_interval`, `get_interval`, `set_auto_calibration`, `get_auto_calibration`, `set_forced_calibration`, `get_forced_calibration`, `set_temperature_offset`, `get_temperature_offset`, `set_altitude`, `get_altitude`, `firmware_version`, `soft_reset`, `read_word`, `write_word`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Measurement interval | 2–1800 s (default 2 s) |
| Time between writing a read command and reading the response | ≥ 3 ms (interface description) |
| Clock stretching | up to about 150 ms (the master must support it) |
| Start-up / after soft reset | ≈ 2 s |
| I²C clock | ≤ 100 kHz |
| FRC | measure at least 2 minutes at the reference concentration before applying |

The interface description gives the delays as minimum host waits; there is no bus-decodable start/end pair with a datasheet bound, so `scd30_timing.conf` defines no checks.

## Implementation Notes

- **Plain Connection, command protocol:** commands are 16-bit and written with `write`; responses are read with `read` in a separate transaction after the 3 ms delay. Do not use a register-addressed connection (no register file).
- **Clock stretching:** the SCD30 stretches SCL; on hosts where this is not supported (e.g. some Raspberry Pi hardware I²C controllers), lower the bus speed to 50 kHz or use a bit-banged bus; the driver documents this.
- **CRC on every word:** compute and verify the CRC-8 (`0x31`, init `0xFF`) for each 16-bit word, in both directions; reject the whole read on a CRC error rather than returning partial values.
- **Read only when data is ready:** `0x0300` before the next sample is ready returns stale data or an error; poll `0x0202` (or `RDY`) first.
- **Ambient pressure vs. altitude:** a non-zero pressure given with the start command overrides the altitude compensation until the next start command; the driver converts from pascals to mbar (hPa) because the chip's argument unit is mbar. It never exposes mbar.
- **ASC needs fresh air:** ASC assumes the sensor sees 400 ppm outdoor air at least once a week (about 7 days of operation and regular exposure); in sealed rooms leave it off and use FRC, or CO₂ readings drift low.
- **Self-heating:** the module heats itself; set the temperature offset (typically a few kelvin) after warm-up and after the final installation; the humidity value is compensated from it.
- **Power:** the SCD30 draws up to 75 mA peaks; supply it from a stiff 3.3–5 V source. The I²C pins are 3 V logic with internal pull-ups.
- **Settings persistence:** interval, ASC, FRC, temperature offset and altitude survive power cycles; the driver does not rewrite them on every `init`.
- **Address overlap:** `0x61` is unique among the repo's chips; probe with `active=True` only (a write is interpreted as a command).

## Sigrok Decoder

Decoder id `scd30`, input `['i2c']`. Matches I²C address `0x61`. Annotates each 16-bit command with its name, decodes arguments with CRC verification (interval, pressure, altitude, offset, FRC value, ASC flag), and decodes the 18-byte measurement into CO₂ (ppm), temperature (°C) and humidity (%RH) with a CRC check on every word; warns on CRC errors, on a measurement read without a preceding data-ready status of `1`, on a read issued earlier than 3 ms after the command and on interval values outside 2–1800 s. No conformance annotation pair is emitted (no checked timing constraint).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/gas/scd30.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/gas/scd30/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/gas/scd30/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/gas/scd30/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/gas/scd30_test.py` (MicroPython)
- [ ] Tests `python/tests/gas/scd30_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/gas/scd30_test_linux.py` (Linux)
- [ ] Unit test `python/tests/gas/scd30_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/gas/scd30/scd30.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/gas/scd30/scd30_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/gas/scd30/scd30.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/gas/scd30/Scd30.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/gas/scd30/Scd30.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/gas/Scd30.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/gas/Scd30.cpp`
- [ ] Examples `cpp/examples/arduino/gas/Scd30/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/gas/Scd30/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/gas/Scd30/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/gas/Scd30/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/gas/Scd30/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/gas/Scd30/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/gas/Scd30/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/gas/Scd30/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/gas/Scd30/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/gas/Scd30/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/gas/Scd30/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/gas/Scd30/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/gas/Scd30/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/gas/Scd30/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/gas/Scd30/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/gas/Scd30/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/gas/Scd30/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/gas/Scd30/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/gas/scd30_test/scd30_test.ino` (Arduino)
- [ ] Tests `cpp/tests/gas/scd30_test_linux/scd30_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/gas/scd30_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/gas/scd30_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/gas/scd30_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/gas/scd30_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/gas/scd30_test_unit/scd30_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/gas/scd30.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/gas/scd30/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/gas/scd30/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/gas/scd30/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/gas/scd30_test.js`
- [ ] Unit test `nodejs/tests/gas/scd30_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-gas/nodes/scd30/scd30.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-gas/nodes/scd30/scd30.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-gas/examples/scd30/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/gas/scd30.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/gas/scd30/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/gas/scd30/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/gas/scd30/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/gas/scd30_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/gas/scd30_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/gas/scd30.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/gas/scd30.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/gas/scd30/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/gas/scd30/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/gas/scd30/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/gas/scd30/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/scd30/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/scd30/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/gas/scd30_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/gas/scd30_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/gas/scd30_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Scd30Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Scd30Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Scd30Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Scd30Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Scd30Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Scd30Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/gas/scd30/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/gas/scd30/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/gas/scd30/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/gas/scd30/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/gas/scd30/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/gas/scd30/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/gas/scd30/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/gas/scd30/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/gas/scd30/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/gas/scd30/Scd30Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/gas/Scd30Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/gas/Scd30Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/gas/Scd30Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/scd30/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/scd30/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [ ] Checker `conformance/gas/scd30_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/gas/scd30_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/scd30/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

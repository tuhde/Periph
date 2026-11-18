# Chip Spec: SPS30

**Manufacturer:** Sensirion  
**Datasheet:** `datasheets/gas/sps30.pdf` (Sensirion SPS30 Particulate Matter Sensor for Air Quality Monitoring and Control, Preliminary Version 0.9 – D1, November 2018)  
**Category:** gas  
**Transports:** I²C (the module also has a UART/SHDLC interface, which this spec does not cover)

## Overview

The SPS30 is a laser-scattering particulate matter sensor module with a fan, measuring mass concentration of PM1.0, PM2.5, PM4 and PM10 (0–1000 µg/m³, accuracy ±10 µg/m³ below 100 µg/m³ and ±10 % above), number concentration of five size bins (PM0.5 … PM10, 0–3000 cm⁻³) and the typical particle size, once per second. A fan auto-cleaning procedure (fan at maximum speed for 10 s, default every 7 days) blows accumulated dust out. The module starts in Idle mode (< 8 mA); measurement mode draws 60 mA on average (80 mA for the first ~200 ms) and needs < 8 s to start up. Supply 4.5–5.5 V; the interface is I²C (pin `SEL` to GND) with 16-bit pointer addresses and CRC-8-protected 2-byte words, or UART. Values are IEEE-754 floats. Typical uses: air purifiers, HVAC, air-quality monitors.

## Transport Configuration

### I²C
- **Address:** `0x69` (7-bit, fixed)
- **Max clock:** 100 kHz; clock stretching is not used. The datasheet recommends the UART interface for cables longer than 10 cm
- **Interface selection:** the `SEL` pin must be connected to GND to select I²C (floating selects UART); set it before power-up
- **Command format:** a 16-bit pointer address (MSB first) written in one transaction ("Set Pointer"), optionally followed by data words, each 2 bytes + CRC-8; reads return 2-byte words each followed by a CRC-8 (polynomial `0x31`, init `0xFF`, no reflection, no final XOR; `CRC(0xBEEF) = 0x92`). The pointer only has to be set once for repeated reads of the same address
- **Register addressing:** none (command pointers); set pointer, then read
- **Identity register:** none; the article code (`0xD025`) and serial number (`0xD033`) are commands. Registered in `registry/chips.json` with `id_probe: null`
- **Probe safety:** `write_sensitive` — a written 16-bit sequence is a command (`0x0010` starts the fan, `0xD304` resets). A probe should only set the pointer `0xD025` and read the response

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| SEL | — | interface select: GND = I²C, floating = UART |
| SDA, SCL | — | I²C (3.3 V logic levels), external pull-ups |
| VDD, GND | — | 4.5–5.5 V |

## Command Set

| Pointer | Command | Type | Description |
|---------|---------|------|-------------|
| `0x0010` | Start Measurement | set pointer + write 3 bytes | Data: `0x03` (measurement mode), `0x00` dummy, CRC; leaves Idle mode, fan starts |
| `0x0104` | Stop Measurement | set pointer | Returns to Idle mode |
| `0x0202` | Read Data-Ready Flag | set pointer + read 3 bytes | Byte 1 = `0x01` new measurement ready, `0x00` not (byte 0 unused), CRC |
| `0x0300` | Read Measured Values | set pointer + read 60 bytes | 10 float32 values (each 2 words + 2 CRC): PM1.0, PM2.5, PM4.0, PM10 mass (µg/m³); PM0.5, PM1.0, PM2.5, PM4.0, PM10 number (#/cm³); typical particle size (µm); resets the data-ready flag |
| `0x8004` | Read/Write Auto Cleaning Interval | set pointer + read/write 6 bytes | Unsigned 32-bit interval in seconds (2 words + CRC each), 0 disables; default 604 800 s; stored permanently; a read returns the old value until the next start/reset |
| `0x5607` | Start Fan Cleaning | set pointer | Manual fan cleaning; **measurement mode only**; measurements are not updated for the 10 s of cleaning |
| `0xD025` | Read Article Code | set pointer + read ≤ 48 bytes | Up to 32 ASCII characters (zero terminated), 2 characters + CRC per word |
| `0xD033` | Read Serial Number | set pointer + read ≤ 48 bytes | Up to 32 ASCII characters |
| `0xD304` | Reset | set pointer | Software reset to the power-on state |

## Initialization Sequence

1. Power VDD (4.5–5.5 V) with `SEL` low; the module is in Idle mode.
2. Optionally read the article code (`0xD025`) as a presence check.
3. Optionally write the auto-cleaning interval (`0x8004`).
4. Start measurement (`0x0010` with the data `0x03 0x00` and its CRC, computed by the driver); the first valid values appear after the start-up time (< 8 s).
5. Poll the data-ready flag (`0x0202`) at ≥ 1 s intervals; when `0x01` set the pointer `0x0300`, read 60 bytes, verify the 20 CRCs and convert.

## Implementation Stages

### Minimal

Goal: read the particulate values with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Stores the plain `Connection`; reads the article code as a presence check and starts the measurement (`0x0010`) |
| `read` | — | `{pm1_0, pm2_5, pm4_0, pm10}` | Waits for the data-ready flag (polls `0x0202` every 100 ms for up to 3 s after the first 1 s), reads `0x0300` (60 bytes), verifies the CRCs and returns the four mass concentrations in µg/m³ |

**Sensible defaults:** measurement mode on, default auto-cleaning interval (7 days, not rewritten).

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_all` | — | dict | All ten values: `pm1_0`, `pm2_5`, `pm4_0`, `pm10` (µg/m³), `nc0_5`, `nc1_0`, `nc2_5`, `nc4_0`, `nc10` (cm⁻³), `typical_size_um` |
| `read_number_concentration` | — | dict | The five number-concentration bins |
| `data_ready` | — | bool | `0x0202` |
| `start` / `stop` | — | — | `0x0010` / `0x0104` |
| `set_cleaning_interval` / `get_cleaning_interval` | `seconds: int` / — | — / int | `0x8004` (stored permanently; call rarely) |
| `start_fan_cleaning` | — | — | `0x5607` (measurement mode only; waits 10 s) |
| `article_code` / `serial_number` | — | str | `0xD025` / `0xD033` |
| `reset` | — | — | `0xD304`, waits 100 ms, leaves the module in Idle mode |
| `read_word` / `write_command` | `pointer: int` / `pointer: int, data: bytes` | bytes / — | Raw pointer access with the CRC handled (diagnostics) |

**Additional configuration options:** auto-cleaning interval, manual cleaning, start/stop (idle mode saves power and fan wear).

## Data Conversion

```
# every 2-byte word followed by CRC-8 (poly 0x31, init 0xFF)
value_float = ieee754_float32_big_endian(word_hi << 16 | word_lo)   # 10 values in 0x0300 order

pm1_0, pm2_5, pm4_0, pm10   in ug/m^3     (mass concentration, 0 .. 1000)
nc0_5 .. nc10               in 1/cm^3     (number concentration, 0 .. 3000)
typical_size_um             in um
cleaning_interval_s         = (word0 << 16) | word1      # unsigned 32 bit, 0 = disabled
```

Number-concentration bins are cumulative (PM0.5 = 0.3–0.5 µm, PM1.0 = 0.3–1.0 µm, … PM10 = 0.3–10 µm). Accuracy is ±10 µg/m³ (0–100 µg/m³) and ±10 % (100–1000 µg/m³) relative to a TSI DustTrak reference at 25 °C; best performance at 10–40 °C and 20–80 %RH.

## Node-RED

Node name: `periph-sps30`  
Package: `node-red-contrib-periph-gas`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ pm1_0, pm2_5, pm4_0, pm10, nc0_5, nc1_0, nc2_5, nc4_0, nc10, typical_size_um }` | latest measurement (waits for the next sample if none is ready) |

Config panel fields: I²C bus number, auto-cleaning interval.

### Demo flow

Inject node (every 5 s) → `periph-sps30` → function node mapping PM2.5 to an air-quality band → gauge and debug nodes.

## Examples

### Demo

**Dust monitor.** With the Full driver print the article code and serial number and the cleaning interval, start the measurement and read all values every 2 s for 30 s with a PM2.5 band, show the number-concentration bins, start a manual fan cleaning (skipped output while cleaning), change and restore the auto-cleaning interval (documented as a permanent write), stop the measurement to show Idle mode and `reset`. Exercises `init`, `read`, `read_all`, `read_number_concentration`, `data_ready`, `start`, `stop`, `set_cleaning_interval`, `get_cleaning_interval`, `start_fan_cleaning`, `article_code`, `serial_number`, `reset`, `read_word`, `write_command`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Start-up time (first valid data) | < 8 s |
| Sampling interval | 1 s |
| Fan cleaning | 10 s (values not updated); default every 604 800 s |
| I²C clock | ≤ 100 kHz |

These are device-level times without a bus-decodable start/end annotation with a datasheet bound, so `sps30_timing.conf` defines no checks.

## Implementation Notes

- **Plain Connection, pointer protocol:** a "set pointer" transaction writes two bytes; data commands append words with CRC bytes; reads use `read`. Do not use a register-addressed connection.
- **CRC on every word:** compute and verify CRC-8 over each 2-byte word; reject the whole 60-byte read on a CRC error.
- **Interface select:** `SEL` must be tied to GND *before* power-up; a floating pin selects UART.
- **Fan and power:** measurement mode starts the fan (60 mA average); call `stop` when idle to extend the lifetime; supply is 5 V with 3.3 V logic levels.
- **Cleaning interval:** it is stored in non-volatile memory — the driver writes it only on request; the time counter resets when the module loses power, so call `start_fan_cleaning` at least weekly if the module is power-cycled daily.
- **Data-ready polling:** poll at 1 s or slower; `Read Measured Values` always returns the latest values and resets the flag.
- **Preliminary datasheet:** this spec follows a preliminary datasheet (Version 0.9); confirm the command set against the current Sensirion document before implementation.
- **Address:** the fixed `0x69` is shared with other chips (e.g. gyroscopes with the address pin high); use the article code to confirm presence.

## Sigrok Decoder

Decoder id `sps30`, input `['i2c']`. Matches I²C address `0x69`. Annotates each 16-bit pointer with its command name, decodes the start-measurement argument, data-ready flag, the 60-byte measurement into ten values with CRC checks, the cleaning interval, the article code/serial number strings and reset; warns on CRC errors, on data-ready polls faster than 1 Hz, on a fan-cleaning command while no measurement is running (not tracked across captures) and on reads longer than the documented size. No conformance annotation pair is emitted (no checked timing constraint).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/gas/sps30.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/gas/sps30/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/gas/sps30/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/gas/sps30/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/gas/sps30_test.py` (MicroPython)
- [ ] Tests `python/tests/gas/sps30_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/gas/sps30_test_linux.py` (Linux)
- [ ] Unit test `python/tests/gas/sps30_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/gas/sps30/sps30.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/gas/sps30/sps30_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/gas/sps30/sps30.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/gas/sps30/Sps30.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/gas/sps30/Sps30.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/gas/Sps30.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/gas/Sps30.cpp`
- [ ] Examples `cpp/examples/arduino/gas/Sps30/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/gas/Sps30/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/gas/Sps30/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/gas/Sps30/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/gas/Sps30/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/gas/Sps30/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/gas/Sps30/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/gas/Sps30/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/gas/Sps30/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/gas/Sps30/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/gas/Sps30/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/gas/Sps30/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/gas/Sps30/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/gas/Sps30/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/gas/Sps30/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/gas/Sps30/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/gas/Sps30/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/gas/Sps30/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/gas/sps30_test/sps30_test.ino` (Arduino)
- [ ] Tests `cpp/tests/gas/sps30_test_linux/sps30_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/gas/sps30_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/gas/sps30_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/gas/sps30_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/gas/sps30_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/gas/sps30_test_unit/sps30_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/gas/sps30.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/gas/sps30/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/gas/sps30/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/gas/sps30/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/gas/sps30_test.js`
- [ ] Unit test `nodejs/tests/gas/sps30_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-gas/nodes/sps30/sps30.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-gas/nodes/sps30/sps30.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-gas/examples/sps30/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/gas/sps30.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/gas/sps30/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/gas/sps30/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/gas/sps30/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/gas/sps30_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/gas/sps30_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/gas/sps30.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/gas/sps30.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/gas/sps30/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/gas/sps30/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/gas/sps30/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/gas/sps30/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/sps30/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/sps30/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/gas/sps30_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/gas/sps30_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/gas/sps30_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Sps30Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Sps30Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Sps30Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Sps30Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Sps30Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Sps30Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/gas/sps30/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/gas/sps30/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/gas/sps30/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/gas/sps30/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/gas/sps30/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/gas/sps30/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/gas/sps30/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/gas/sps30/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/gas/sps30/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/gas/sps30/Sps30Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/gas/Sps30Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/gas/Sps30Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/gas/Sps30Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/sps30/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/sps30/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [ ] Checker `conformance/gas/sps30_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/gas/sps30_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/sps30/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

# Chip Spec: HTU21D-F

**Manufacturer:** Measurement Specialties (TE Connectivity)  
**Datasheet:** `datasheets/humidity/htu21df.pdf` (HPC199_3, October 2013)  
**Category:** humidity  
**Transports:** I²C

> **Note:** The HTU21D-F measures relative humidity and temperature (no pressure); it sits in `humidity` next to the SHTC3 and HDC1080. The "-F" variant has a PTFE filter membrane; the electrical interface is identical to the HTU21D (and the Si7021's command set is largely compatible, but that chip needs its own spec).

## Overview

The HTU21D(F) is a digital relative-humidity and temperature sensor with 12-bit (default) RH and 14-bit temperature resolution (configurable 8/12, 10/13 or 11/11 bit), typical accuracy ±2 %RH (20–80 %RH, 25 °C) and ±0.3 °C, an on-chip heater for diagnostics, an end-of-battery flag and a CRC-8 on every result. Measurements are triggered by a command byte and read back as a 16-bit value plus checksum, in either "hold master" (clock stretching) or "no hold master" (polling) mode. Typical uses: HVAC, weather stations, humidity logging.

## Transport Configuration

### I²C
- **Address:** `0x40` (fixed; 7-bit `1000000`) — shared with HDC1008/HDC1080 and INA2xx at `0x40`
- **Max clock:** 400 kHz (fast mode)
- **Supply:** 1.5–3.6 V (3.0 V typ.); decouple with 100 nF
- **Command protocol:** no register pointer: each transfer starts with a command byte (below). Results are 3 bytes: MSB, LSB, CRC-8.
- **Identity register:** none — the user register read (`0xE7`) returns `0x02` at reset but not uniquely; `id_probe: null` in `registry/chips.json`.
- **Probe safety:** `register_pointer` — sending the single command byte `0xE7` (read user register) has no side effects.

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| SDA | — | I²C data, open drain, external pull-up |
| SCL | — | I²C clock (hold master mode may stretch it) |
| VDD, GND | — | 1.5–3.6 V |

No interrupt pins; the DFN package also has NC pads.

## Command Set

| Command | Code | Description |
|---------|------|-------------|
| Trigger temperature measurement (hold master) | `0xE3` | SCL held low until done |
| Trigger humidity measurement (hold master) | `0xE5` | SCL held low until done |
| Trigger temperature measurement (no hold) | `0xF3` | read is NACKed until done |
| Trigger humidity measurement (no hold) | `0xF5` | read is NACKed until done |
| Write user register | `0xE6` | then 1 data byte |
| Read user register | `0xE7` | then read 1 byte |
| Soft reset | `0xFE` | reboot, ≤ 15 ms |

Measurement sequence (no hold): S, `0x80` (W), `0xF3`/`0xF5`, (P or rS), then repeatedly S, `0x81` (R) until ACKed; read MSB, LSB, CRC; NACK the CRC byte to skip it. The two LSBs of the 16-bit result are status bits: bit 1 = `0` temperature, `1` humidity; bit 0 is reserved; they are cleared to 0 before conversion.

### User register (read `0xE7`, write `0xE6`)

| Bit | Name | Description |
|-----|------|-------------|
| 7, 0 | RES | Resolution (RH / T): `00` 12/14 bit (default), `01` 8/12, `10` 10/13, `11` 11/11 |
| 6 | VDD_LOW | End of battery: `1` when VDD < 2.25 V (±0.1 V); updated after each measurement; read-only |
| 5:3 | — | Reserved; read-modify-write, never change |
| 2 | HEATER | `1` = on-chip heater on (≈ 5.5 mW, +0.5–1.5 °C); not reset by soft reset |
| 1 | OTP_DIS | Disable OTP reload (default `1`; do not change) |

Default value `0x02`. Always read the register, change the wanted bits and write it back.

## Initialization Sequence

1. Power up (VDD 1.5–3.6 V); wait ≥ 15 ms for the sensor to reach idle (SCL high).
2. Send the soft reset command `0xFE`; wait 15 ms (resets the user register except the heater bit).
3. Optionally read the user register (default `0x02`) and write the resolution.
4. Trigger a measurement (`0xF3`/`0xF5`), wait for the conversion time (below) or poll with read headers until ACKed, read 3 bytes and verify the CRC.

## Interrupt

Level 0 — no interrupt output.

## Implementation Stages

### Minimal

Goal: read temperature and humidity with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Waits 15 ms, soft reset, waits 15 ms |
| `read` | — | `(temperature_c, humidity_pct)` | Triggers `0xF3` then `0xF5` (no hold master), polls the read header until ACKed (timeout 100 ms), verifies both CRCs (raises on mismatch after one retry) |

**Sensible defaults:** 12-bit RH / 14-bit temperature, no hold master, heater off, CRC checked.

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_temperature` | — | `float` | °C |
| `read_humidity` | — | `float` | %RH (not clamped; the ASIC range is −6…118 %RH) |
| `read_humidity_compensated` | — | `float` | %RH corrected for temperature using the datasheet coefficient |
| `read_raw` | — | `(int, int)` | Raw 16-bit (temperature, humidity) with status bits cleared |
| `set_resolution` | `res: int` (0–3) | — | `RES` bits of the user register (read-modify-write) |
| `get_resolution` | — | `int` | `RES` bits |
| `set_heater` | `on: bool` | — | `HEATER` bit |
| `heater` | — | `bool` | Heater state |
| `battery_low` | — | `bool` | `VDD_LOW` bit |
| `read_user_register` | — | `int` | `0xE7` |
| `soft_reset` | — | — | `0xFE`, 15 ms; heater bit survives |
| `set_hold_master` | `on: bool` | — | Use `0xE3`/`0xE5` (SCL stretching; needs controller support) instead of polling |
| `dew_point` | — | `float` | °C from one measurement (formula below) |

**Additional configuration options:** resolution (4 settings), heater, hold/no-hold master.

## Data Conversion

```
S = (MSB << 8 | LSB) & 0xFFFC                 # clear the two status bits
RH_percent = -6.0 + 125.0 * S / 65536.0
T_celsius  = -46.85 + 175.72 * S / 65536.0
RH_compensated = RH + (25.0 - T) * (-0.15)    # CoeffTemp = -0.15 %RH/°C (valid 0 °C ... 80 °C)
```


Raw 0x0000 → −6 %RH, 0xFFFC → ≈ 118.99 (clamped by the ASIC to 118 %RH).

CRC-8: polynomial x⁸ + x⁵ + x⁴ + 1 (`0x131`), initial value `0x00`, no final XOR, over the two data bytes. Example: `0x683A` → `0x7C`; `0x4E85` → `0x6B`; `0xDC` → `0x79`.

### Dew point

```
A = 8.1332; B = 1762.39; C = 235.66
PP = 10 ** (A - B / (T + C))                       # partial pressure in mmHg
Td = -B / (log10(RH * PP / 100.0) - A) - C         # °C
```

## Node-RED

Node name: `periph-htu21df`  
Package: `node-red-contrib-periph-humidity`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature: float, humidity: float }` | °C and %RH |

Config panel fields: I²C bus number (default `1`), resolution (default 12/14 bit), heater (off).

### Demo flow

Inject node (repeat every 2 s) → `periph-htu21df` → Function node (format `"%.2f °C, %.1f %%RH"`) → Debug node.

## Examples

### Demo

Humidity logger with heater self-check. Soft reset, print the user register and resolution, then take five readings of temperature, humidity and dew point at 12/14 bit; switch to 8/12 bit and show the shorter conversion time and coarser resolution; switch the heater on for 10 s, show the RH drop and temperature rise, switch it off and print `battery_low()`. Exercises `init`, `read`, `read_temperature`, `read_humidity`, `read_humidity_compensated`, `read_raw`, `set_resolution`, `get_resolution`, `set_heater`, `heater`, `battery_low`, `read_user_register`, `soft_reset`, `dew_point`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Power-up to idle | ≤ 15 ms |
| Soft reset | ≤ 15 ms |
| Temperature conversion (max) | 14 bit 50 ms, 13 bit 25 ms, 12 bit 13 ms, 11 bit 7 ms |
| Humidity conversion (max) | 12 bit 16 ms, 11 bit 8 ms, 10 bit 5 ms, 8 bit 3 ms |
| Max I²C clock | 400 kHz |

**Conformance-checked constraint:** the maximum conversion time (50 ms, the 14-bit temperature worst case) via the `conversion_start` / `conversion_done` annotation pair (command STOP → first ACKed read header); see `htu21df_timing.conf`.

## Implementation Notes

- **No hold master by default:** it works on every bus layer. In no-hold mode the read header is NACKed until the conversion is done; treat that NACK as "not ready", not as an error, and retry until the maximum conversion time has passed.
- **Hold master** relies on clock stretching, which not every I²C master (e.g. some Linux bit-banging or RP2040 setups) supports.
- **Read-modify-write the user register:** reserved bits may change between production lots; never write a fixed constant.
- **Heater survives soft reset;** all other bits are reset.
- **Status bits:** mask the two LSBs before conversion; use bit 1 to check that the received value is the expected quantity.
- **Address overlap:** `0x40` is also used by the HDC1008/HDC1080 (which have identity registers) and INA2xx parts; the HTU21D has no identity register.
- **RH range:** the sensor can report −6…118 %RH; the driver does not clamp (the raw ASIC clamp applies).

## Sigrok Decoder

Decoder id `htu21df`, input `['i2c']`. Matches I²C address `0x40`. Annotates the command byte by name (trigger T/RH hold/no-hold, user register read/write, soft reset), decodes the 3-byte result into °C / %RH with the CRC check result and the status bits, decodes the user register into RES/VDD_LOW/HEATER, and annotates polling (read header with a missing ACK). It emits the conformance pair `conversion_start` (STOP after a trigger command) and `conversion_done` (first ACKed read header).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/humidity/htu21df.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/humidity/htu21df/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/humidity/htu21df/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/humidity/htu21df/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/humidity/htu21df_test.py` (MicroPython)
- [ ] Tests `python/tests/humidity/htu21df_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/humidity/htu21df_test_linux.py` (Linux)
- [ ] Unit test `python/tests/humidity/htu21df_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/humidity/htu21df/htu21df.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/humidity/htu21df/htu21df_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/humidity/htu21df/htu21df.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/humidity/htu21df/HTU21D-F.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/humidity/htu21df/HTU21D-F.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/humidity/HTU21D-F.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/humidity/HTU21D-F.cpp`
- [ ] Examples `cpp/examples/arduino/humidity/HTU21D-F/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/humidity/HTU21D-F/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/humidity/HTU21D-F/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/humidity/HTU21D-F/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/humidity/HTU21D-F/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/humidity/HTU21D-F/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/humidity/HTU21D-F/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/humidity/HTU21D-F/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/humidity/HTU21D-F/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/humidity/HTU21D-F/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/humidity/HTU21D-F/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/humidity/HTU21D-F/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/humidity/HTU21D-F/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/humidity/HTU21D-F/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/humidity/HTU21D-F/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/humidity/HTU21D-F/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/humidity/HTU21D-F/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/humidity/HTU21D-F/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/humidity/htu21df_test/htu21df_test.ino` (Arduino)
- [ ] Tests `cpp/tests/humidity/htu21df_test_linux/htu21df_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/humidity/htu21df_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/humidity/htu21df_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/humidity/htu21df_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/humidity/htu21df_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/humidity/htu21df_test_unit/htu21df_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/humidity/htu21df.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/humidity/htu21df/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/humidity/htu21df/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/humidity/htu21df/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/humidity/htu21df_test.js`
- [ ] Unit test `nodejs/tests/humidity/htu21df_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-humidity/nodes/htu21df/htu21df.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-humidity/nodes/htu21df/htu21df.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-humidity/examples/htu21df/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/humidity/htu21df.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/humidity/htu21df/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/humidity/htu21df/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/humidity/htu21df/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/humidity/htu21df_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/humidity/htu21df_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/humidity/htu21df.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/humidity/htu21df.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/humidity/htu21df/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/humidity/htu21df/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/humidity/htu21df/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/humidity/htu21df/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/htu21df/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/htu21df/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/humidity/htu21df_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/humidity/htu21df_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/humidity/htu21df_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/HTU21D-FMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/HTU21D-FFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/HTU21D-FMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/HTU21D-FFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/HTU21D-FMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/HTU21D-FFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/humidity/htu21df/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/humidity/htu21df/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/humidity/htu21df/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/humidity/htu21df/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/humidity/htu21df/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/humidity/htu21df/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/humidity/htu21df/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/humidity/htu21df/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/humidity/htu21df/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/humidity/htu21df/HTU21D-FTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/humidity/HTU21D-FTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/humidity/HTU21D-FTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/humidity/HTU21D-FSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/htu21df/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/htu21df/pd.py` — annotates commands, results with CRC check and the user register per the Sigrok Decoder section — for the conformance-checked Timing Constraints above, emits the named `conversion_start` / `conversion_done` annotation pair

### Conformance
- [ ] Checker `conformance/humidity/htu21df_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/humidity/htu21df_timing.conf`
- [ ] Decoder `sigrok/htu21df/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

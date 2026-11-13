# Chip Spec: HS30xx

**Manufacturer:** Renesas (IDT)  
**Datasheet:** `datasheets/humidity/hs30xx.pdf` (HS3xxx datasheet R36DS0045EU0101 Rev. 1.01, Jun 17, 2024 — HS3001, HS3002, HS3003; the issue's "HS30xx")  
**Category:** humidity  
**Transports:** I²C

> **Note:** The HS30xx family measures relative humidity and temperature (no pressure); it is placed in `humidity` next to the DHT11, SHTC3 and HDC1080. The sibling family with CRC, periodic mode and alerts is the HS40xx (`specs/humidity/hs40xx.md`).

## Overview

The HS3xxx (HS3001 / HS3002 / HS3003; waterproof and non-waterproof variants, three accuracy classes) are fully calibrated relative humidity and temperature sensors with a MEMS capacitive element: humidity 0–100 %RH (typical ±1.5 %RH for the HS3x01), 14-bit resolution (0.01 %RH), independently programmable resolutions of 8, 10, 12 or 14 bits for humidity and temperature, supply 2.3–5.5 V and an I²C interface at a fixed address. The sensor sleeps until a **measurement request** (MR) wakes it; the result is then fetched with a **data fetch** (DF) read of up to four bytes whose two MSBs are a status field. Resolution settings and a 32-bit ID live in non-volatile memory (NVM) reachable only in a **programming mode** that must be entered within 10 ms after power-up.

## Transport Configuration

### I²C
- **Address:** `0x44` (7-bit, default; custom addresses on request; the sensor responds only to this address)
- **Max clock:** 400 kHz (tLOW ≥ 0.6 µs, tHIGH ≥ 0.6 µs; the combined widths must meet the SCL period)
- **Identity register:** none readable in normal mode (the ID is in NVM, programming mode only) — `registry/chips.json` entry has `"id_probe": null`
- **Probe safety:** `write_sensitive` — an address-only write is a measurement request, and bytes written within 10 ms of power-up are interpreted as NVM programming commands
- **Protocol:** no register pointer. **MR:** `[addr+W] P` (an **address-only write**, no data byte). **DF:** `[addr+R] [byte0] [byte1] [byte2] [byte3]` — the master ends the read with NACK + STOP after as many bytes as needed (2 for humidity only, 4 for humidity + temperature). The drivers use the plain `Connection` (`write(b"")` quick write and `read(n)`); see `specs/feature_i2c_discovery.md` §5 for the per-platform empty-write primitives

## Pin Configuration

| Pin | Notes |
|-----|-------|
| SDA, SCL | I²C; external pull-ups |
| VDD, GND | 2.3–5.5 V supply |

No interrupt, enable or address pins.

## Command Set

| Command | Sequence | Description |
|---------|----------|-------------|
| Measurement request (MR) | `[addr+W] P` | Wakes the sensor and starts a humidity + temperature conversion followed by the DSP correction |
| Data fetch (DF) | `[addr+R] b0 b1 [b2 b3]` | Reads the latest result |
| Enter programming mode | `[addr+W] 0xA0 0x00 0x00` | Only within 10 ms after power-up; takes 120 µs |
| Leave programming mode | `[addr+W] 0x80 0x00 0x00` | Return to normal measurement operation |
| NVM register read | `[addr+W] reg 0x00 0x00` then `[addr+R] status hi lo` | Programming mode only; `status` = `0x81` on success |
| NVM register write | `[addr+W] reg hi lo` | Programming mode only; 14 ms to commit |

### Data fetch frame

| Byte | Content |
|------|---------|
| 0 | bits 7:6 **status**, bits 5:0 humidity [13:8] |
| 1 | humidity [7:0] |
| 2 | temperature [13:6] |
| 3 | bits 7:2 temperature [5:0], bits 1:0 undetermined (mask off) |

Status: `00` = valid (data not yet fetched since the last measurement cycle); `01` = **stale** (already fetched since the last measurement; a fetch before/during the very first measurement after power-up also returns stale although the data are invalid). The output is always scaled to 14 bits regardless of the configured resolution.

### NVM registers (programming mode)

| Address | Description |
|---------|-------------|
| `0x06` | Humidity resolution — read register (bits 11:10) |
| `0x46` | Humidity resolution — write register (bits 11:10) |
| `0x11` | Temperature resolution — read register (bits 11:10) |
| `0x51` | Temperature resolution — write register (bits 11:10) |
| `0x1E` | Sensor ID, upper 16 bits (read) |
| `0x1F` | Sensor ID, lower 16 bits (read) |

Bits 11:10: `00` = 8 bit, `01` = 10 bit, `10` = 12 bit, `11` = 14 bit (default). All other bits must be left unchanged: read the register, change bits 11:10, write the whole register back to the write address.

## Initialization Sequence

1. Power-up; the sensor is in sleep mode. (For NVM access: send `0xA0 0x00 0x00` within 10 ms of applying power — see Implementation Notes.)
2. `init` stores the connection; it performs no bus access (any access within 10 ms of power-up could be taken as a programming command).
3. A reading: MR (`write(b"")`), wait the measurement time, DF (read 4 bytes), check the status bits, convert.

## Implementation Stages

### Minimal

Goal: read temperature and humidity with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Stores the connection; no bus access |
| `read` | — | `{temperature_c, humidity_pct}` | MR, waits 40 ms (the 14-bit RH+T time incl. wake-up is 35 ms, derived margin), DF of 4 bytes; if the status field says stale, waits 10 ms and retries once; raises on persistent stale data |

**Sensible defaults:** the factory resolution (14 bit for both channels), a fixed 40 ms wait; no NVM access.

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_temperature` | — | float | °C (4-byte DF, discards humidity) |
| `read_humidity` | — | float | %RH (2-byte DF) |
| `read_raw` | — | `(raw_humidity, raw_temperature, status)` | 14-bit raw values and the 2-bit status |
| `set_measurement_wait` | `ms: float` | — | Override the post-MR wait (e.g. for lower resolutions: `0.1 + RH_time/2 … + T_time/2`, see Timing) |
| `enter_programming_mode` | — | — | `0xA0 0x00 0x00` + 120 µs; only valid within 10 ms after power-up |
| `exit_programming_mode` | — | — | `0x80 0x00 0x00` |
| `read_nvm` | `reg: int` | int | NVM register read (programming mode); raises unless the status byte is `0x81` |
| `get_resolution` | `power_cycle=None` | `(humidity_bits, temperature_bits)` | Reads registers `0x06` / `0x11`; needs programming mode — `power_cycle` is a callable that cuts and restores the sensor supply; the driver then enters programming mode within 10 ms |
| `set_resolution` | `humidity_bits: int \| None`, `temperature_bits: int \| None`, `power_cycle=None` | — | Read-modify-write of `0x46` / `0x51` via `0x06` / `0x11` (bits 11:10 only), waits 14 ms after each write, leaves programming mode; raises for values other than 8/10/12/14 |
| `read_id` | `power_cycle=None` | int | 32-bit ID from registers `0x1E` / `0x1F` (programming mode) |
| `dew_point` | — | float | °C from one measurement (Magnus formula) |

**Additional configuration options:** NVM resolution settings and ID (programming-mode only), measurement wait.

## Data Conversion

```
humidity_raw    = ((b0 & 0x3F) << 8) | b1                         # 14 bit
temperature_raw = (b2 << 6) | (b3 >> 2)                           # 14 bit
status          = b0 >> 6                                          # 0 = valid, 1 = stale

humidity_pct  = humidity_raw    / (2^14 - 1) * 100
temperature_c = temperature_raw / (2^14 - 1) * 165 - 40
```

Example: humidity raw `0x2000` → 50.0 %RH (8192 / 16383); temperature raw `0x1000` → −40 + 41.25 = 1.25 °C.

## Node-RED

Node name: `periph-hs30xx`  
Package: `node-red-contrib-periph-humidity`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature_c, humidity_pct }` | One measurement per input message |

Config panel fields: I²C bus number, address (`0x44`).

### Demo flow

Inject node (every 5 s) → `periph-hs30xx` → function node formatting `"23.4 °C / 41.2 %RH"` → debug node and gauges.

## Examples

### Demo

**Hygrometer with dew point.** With the Full driver read temperature and humidity every 2 s for 30 s, print the raw values and status, compute the dew point, and — when run with a `--resolution` flag and a power-cycle helper — show `get_resolution()` / `set_resolution(12, 12)` and compare the readings and the wait time.

## Timing Constraints

- **Measurement time (typical, per channel):** humidity or temperature alone: 0.60 ms (8 bit), 1.40 ms (10 bit), 4.60 ms (12 bit), 17.00 ms (14 bit); wake-up 0.1 ms. RH + T at equal resolution including wake-up: 1.4 ms (8 bit), 2.9 ms (10 bit), 10 ms (12 bit), 35 ms (14 bit). For different resolutions the datasheet adds the 0.1 ms wake-up to the per-channel times of the equal-resolution table: e.g. an 8-bit RH and a 12-bit T measurement take `0.1 ms + 1.4 ms/2 + 10 ms/2 = 5.8 ms`.
- **Stale-data guard:** a fetch before the measurement completes returns the previous (stale, status `01`) data; the status bits are the completion indicator.
- **Programming mode:** the enter command must be sent within **10 ms** after applying power; entering takes 120 µs; loading an NVM register 120 µs; writing 14 ms; failing to wait may corrupt the NVM.
- **I²C:** ≤ 400 kHz — clock-rate property, not an event-to-event delay.
- **Conformance:** the times are typical values only, so `specs/humidity/hs30xx_timing.conf` defines no checks.

## Implementation Notes

- **MR is an empty write:** the measurement request has no data byte; the connection's zero-length write (or SMBus quick write) is required. A plain single-byte write could be misread as a command, especially right after power-up.
- **Status bits:** check `status == 0` for a valid reading; after power-on the first fetch can report stale for invalid data — always trigger an MR and wait before the first fetch.
- **NVM needs a power cycle window:** resolution and ID access require entering programming mode within 10 ms of power-up; a host that cannot cycle the sensor's supply cannot change the resolution. The Full driver therefore takes an optional `power_cycle` callable (e.g. toggling a MOSFET or a GPIO powering the sensor); without it the NVM methods raise.
- **Only the resolution bits 11:10** may be changed in the NVM registers; preserve the others.
- **Resolution is stored in NVM** (survives power cycles); changing it is a rare configuration step.
- **Conditioning:** after soldering or long storage outside 10–50 °C / 20–60 %RH the sensor must be rehydrated (75 %RH at 20–30 °C for ≥ 12 h, or 40–50 %RH for 3–5 days) to remove the RH offset.
- **Self-heating:** use one measurement per second or slower for the lowest heating.
- **Datasheet:** Renesas HS3xxx Rev. 1.01, downloaded from `https://www.renesas.com/en/document/dst/hs3xxx-datasheet`.

## Sigrok Decoder

Decoder id `hs30xx`, input `['i2c']`. Matches I²C address `0x44`. Decodes the measurement request (address-only write), data fetches (status bits, humidity %RH, temperature °C; 2- or 4-byte reads), the programming-mode commands (`0xA0`/`0x80` + two zero bytes), NVM register reads/writes (decoded resolution bits, ID words) and warns on stale-status fetches, on programming commands outside the first 10 ms of the capture (not detectable — no timing pair) and on NVM writes that change bits other than 11:10. No timing annotation pair is emitted.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/humidity/hs30xx.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/humidity/hs30xx/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/humidity/hs30xx/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/humidity/hs30xx/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/humidity/hs30xx_test.py` (MicroPython)
- [ ] Tests `python/tests/humidity/hs30xx_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/humidity/hs30xx_test_linux.py` (Linux)
- [ ] Unit test `python/tests/humidity/hs30xx_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/humidity/hs30xx/hs30xx.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/humidity/hs30xx/hs30xx_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/humidity/hs30xx/hs30xx.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/humidity/hs30xx/HS30xx.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/humidity/hs30xx/HS30xx.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/humidity/HS30xx.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/humidity/HS30xx.cpp`
- [ ] Examples `cpp/examples/arduino/humidity/HS30xx/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/humidity/HS30xx/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/humidity/HS30xx/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/humidity/HS30xx/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/humidity/HS30xx/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/humidity/HS30xx/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/humidity/HS30xx/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/humidity/HS30xx/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/humidity/HS30xx/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/humidity/HS30xx/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/humidity/HS30xx/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/humidity/HS30xx/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/humidity/HS30xx/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/humidity/HS30xx/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/humidity/HS30xx/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/humidity/HS30xx/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/humidity/HS30xx/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/humidity/HS30xx/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/humidity/hs30xx_test/hs30xx_test.ino` (Arduino)
- [ ] Tests `cpp/tests/humidity/hs30xx_test_linux/hs30xx_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/humidity/hs30xx_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/humidity/hs30xx_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/humidity/hs30xx_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/humidity/hs30xx_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/humidity/hs30xx_test_unit/hs30xx_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/humidity/hs30xx.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/humidity/hs30xx/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/humidity/hs30xx/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/humidity/hs30xx/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/humidity/hs30xx_test.js`
- [ ] Unit test `nodejs/tests/humidity/hs30xx_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-humidity/nodes/hs30xx/hs30xx.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-humidity/nodes/hs30xx/hs30xx.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-humidity/examples/hs30xx/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/humidity/hs30xx.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/humidity/hs30xx/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/humidity/hs30xx/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/humidity/hs30xx/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/humidity/hs30xx_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/humidity/hs30xx_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/humidity/hs30xx.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/humidity/hs30xx.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/humidity/hs30xx/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/humidity/hs30xx/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/humidity/hs30xx/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/humidity/hs30xx/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/hs30xx/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/hs30xx/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/humidity/hs30xx_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/humidity/hs30xx_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/humidity/hs30xx_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/HS30xxMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/HS30xxFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/HS30xxMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/HS30xxFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/HS30xxMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/HS30xxFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/humidity/hs30xx/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/humidity/hs30xx/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/humidity/hs30xx/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/humidity/hs30xx/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/humidity/hs30xx/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/humidity/hs30xx/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/humidity/hs30xx/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/humidity/hs30xx/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/humidity/hs30xx/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/humidity/hs30xx/HS30xxTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/humidity/HS30xxTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/humidity/HS30xxTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/humidity/HS30xxSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/hs30xx/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/hs30xx/pd.py` — annotates every measurement request, data fetch and NVM access per the Sigrok Decoder section (no timing annotation pair: no conformance-checked constraints)

### Conformance
- [ ] Checker `conformance/humidity/hs30xx_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/humidity/hs30xx_timing.conf` — already committed; defines no checks (see its header)
- [ ] Decoder `sigrok/hs30xx/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

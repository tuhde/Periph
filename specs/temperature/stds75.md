# Chip Spec: STDS75

**Manufacturer:** STMicroelectronics  
**Datasheet:** `datasheets/temperature/stds75.pdf` (STDS75, Doc ID 13297 Rev 9)  
**Category:** temperature  
**Transports:** I²C

## Overview

The STDS75 is an I²C/SMBus-compatible digital temperature sensor and thermal watchdog (LM75/DS75-compatible) with a user-selectable resolution of 9 to 12 bits (0.5 °C down to 0.0625 °C), a −55…+125 °C range (±2 °C max over −25…+100 °C, ±3 °C max over the full range), a 2.7–5.5 V supply and a thermostat output `OS/INT` driven by an over-temperature (TOS) and a hysteresis (THYS) register. It defaults to 9-bit resolution for compatibility with the STLM75. It converts continuously; the conversion time grows with the resolution (150 ms at 9 bit up to 1200 ms at 12 bit). Three address pins allow eight devices per bus. Typical uses: thermal management, board and enclosure monitoring, thermostat functions.

## Transport Configuration

### I²C
- **Address:** `0x48` + (A2 << 2 | A1 << 1 | A0): `0x48`–`0x4F` (7-bit address `1001 A2 A1 A0`)
- **Max clock:** 400 kHz
- **Register access:** 8-bit command/pointer register (bits 1:0 select the register, bits 7:2 must be 0); temperature and limit registers are 16 bit, MSB first; the configuration register is 8 bit; the pointer persists between operations
- **Identity register:** none (`id_probe: null` in `registry/chips.json`); the address range is shared with LM75B, TMP102, TMP117 and others
- **Probe safety:** `register_pointer` — a one-byte write only moves the pointer (writing a `1` into the upper pointer bits terminates the operation)

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| OS/INT | `POL` (low by default) | open-drain thermostat output; pull-up required |
| A2, A1, A0 | — | address select (GND or VDD) |
| SDA, SCL | — | I²C; open drain, pull-ups required |
| VDD, GND | — | 2.7–5.5 V |

## Register Map

| Pointer | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00` | TEMP | R | — | Temperature, 16-bit two's complement, left-justified (9–12 bit valid) |
| `0x01` | CONF | R/W | `0x00` | Configuration (8 bit) |
| `0x02` | THYS | R/W | `0x4B00` (75 °C) | Hysteresis temperature |
| `0x03` | TOS | R/W | `0x5000` (80 °C) | Over-temperature shutdown limit |

### Bit Fields

#### `CONF` (`0x01`, 8 bit)

| Bits | Name | R/W | Description |
|------|------|-----|-------------|
| 7 | — | R | Reserved, must be 0 (read only) |
| 6:5 | RC1:RC0 | R/W | Resolution: `00` 9-bit (0.5 °C, default), `01` 10-bit (0.25 °C), `10` 11-bit (0.125 °C), `11` 12-bit (0.0625 °C) |
| 4:3 | FT1:FT0 | R/W | Fault tolerance (consecutive faults): `00` 1 (default), `01` 2, `10` 4, `11` 6 |
| 2 | POL | R/W | OS/INT polarity: `0` active low (default), `1` active high |
| 1 | M | R/W | Thermostat mode: `0` comparator (default), `1` interrupt |
| 0 | SD | R/W | Shutdown: `1` = finish the current conversion, then standby |

#### `TEMP`, `TOS`, `THYS` (16 bit)

Bit 15 = sign, bits 14:4 = value (12-bit: bits 15:4; 11-bit: 15:5; 10-bit: 15:6; 9-bit: 15:7); unused low bits and bits 3:0 read 0. The 4 LSBs of TOS and THYS are hard-wired to 0 (writes ignored); the thermostat compares only as many bits as the selected resolution.

## Initialization Sequence

1. Power up (VDD 2.7–5.5 V). The device converts continuously at 9-bit resolution; the registers are in their default state (comparator mode, TOS 80 °C, THYS 75 °C).
2. Read `TEMP` (pointer `0x00`, 2 bytes) no earlier than one conversion time (150 ms at 9 bit) after power-up.
3. Optionally write `CONF` to select a higher resolution (the conversion time follows the setting).

## Interrupt

| Property | Value |
|----------|-------|
| INT pin | `OS/INT`, open-drain, active low by default (`POL`) — requires an external pull-up |
| Level | 1 (single fixed condition) |
| Condition | temperature > TOS for `FT` consecutive conversions (asserted one conversion time after the fault tolerance is met) |
| Clear mechanism | **Comparator mode (M = 0):** inactive again once the temperature falls below THYS for the fault-tolerance count (shutdown does not clear it). **Interrupt mode (M = 1):** stays active until any register is read or the device enters shutdown; re-arms when the temperature falls below THYS |

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback, int_pin=None)` | Subscribe on OS/INT (active edge per `POL`) via `connection.int_pin` (or `int_pin`), otherwise a polling task calling `poll_interrupt` every conversion time; `callback(temperature_c)` |
| `off_interrupt` | `off_interrupt()` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> bool` | Reads `TEMP` (clears an interrupt-mode alert); returns `True` when the temperature is above TOS (comparator state estimated in software, since the chip has no alert-status bit) |

## Implementation Stages

### Minimal

Goal: read the temperature with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Stores the connection (`RegisterConnection`, `reg_bytes` = 1, 16-bit big-endian values); no bus access |
| `read_temperature` | — | `float` | °C at the current resolution (9 bit = 0.5 °C by default) |

**Sensible defaults:** the power-on state (9-bit, continuous conversion, comparator mode, TOS 80 °C, THYS 75 °C).

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_raw` | — | `int` | Signed 16-bit register value (`raw / 256` = °C) |
| `read_config` / `write_config` | — / `value: int` | `int` / — | CONF byte (bit 7 forced 0) |
| `set_resolution` | `bits: int` (9–12) | — | `RC1:RC0` |
| `get_resolution` | — | `int` | bits |
| `conversion_time` | — | `float` | Seconds: 0.15 / 0.3 / 0.6 / 1.2 s for 9 / 10 / 11 / 12 bit (datasheet maximum) |
| `set_tos` / `get_tos` | `celsius: float` / — | — / `float` | Over-temperature limit, rounded to 0.0625 °C; range −55…+125 °C |
| `set_thys` / `get_thys` | `celsius: float` / — | — / `float` | Hysteresis limit, same coding |
| `set_fault_tolerance` | `count: int` (1, 2, 4, 6) | — | `FT1:FT0`; raises otherwise |
| `set_polarity` | `active_high: bool` | — | `POL` |
| `set_thermostat_mode` | `interrupt: bool` | — | `M` (`False` = comparator) |
| `shutdown` / `wake` | — | — | `SD` bit (shutdown also clears an interrupt-mode alert); after `wake` the first result is available after one conversion time |
| `is_shutdown` | — | `bool` | |
| `on_interrupt` / `off_interrupt` / `poll_interrupt` | see Interrupt | | |

All configuration writes are read-modify-write on `CONF`.

**Additional configuration options:** resolution, fault tolerance, polarity, thermostat mode, shutdown, TOS/THYS limits.

## Data Conversion

```
raw16 = (byte1 << 8) | byte2
temperature_c = signed16(raw16) / 256            # all resolutions; unused low bits are 0
# LSB size by resolution: 0.5 / 0.25 / 0.125 / 0.0625 °C for 9 / 10 / 11 / 12 bit

limit register: reg16 = (round(celsius * 16) << 4) & 0xFFF0     # 0.0625 °C steps; 80 °C -> 0x5000, 75 °C -> 0x4B00
```

Examples (datasheet format table): `0x0A20` = +10.125 °C, `0x0080` = +0.5 °C, `0xFF80` = −0.5 °C.

## Node-RED

Node name: `periph-stds75`  
Package: `node-red-contrib-periph-temperature`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature: float }` | °C |

Config panel fields: I²C bus number (default `1`), I²C address (`0x48`–`0x4F`, default `0x48`), resolution (default 9 bit).

### Demo flow

Inject node (repeat every 2 s) → `periph-stds75` → Function node (format `"%.3f °C"`) → Debug node.

## Examples

### Demo

Thermal watchdog with selectable resolution. Print the power-on configuration and limits, read the temperature once per conversion time at 9, 10, 11 and 12-bit resolution (printing each reading and the elapsed time), program TOS/THYS to current temperature + 1 °C / − 1 °C with fault tolerance 2 in interrupt mode, and report `poll_interrupt` alerts while warming the sensor with a finger, then shut down, wake and restore defaults. Exercises `init`, `read_temperature`, `read_raw`, `read_config`, `write_config`, `set_resolution`, `get_resolution`, `conversion_time`, `set_tos`, `get_tos`, `set_thys`, `get_thys`, `set_fault_tolerance`, `set_polarity`, `set_thermostat_mode`, `shutdown`, `wake`, `is_shutdown` and the interrupt API.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Conversion time (9 / 10 / 11 / 12 bit) | 150 / 300 / 600 / 1200 ms max |
| Minimum access interval | not less than tCONV (max) when polling continuously, otherwise the register may not update |
| OS/INT assertion | one tCONV after the fault tolerance is met |
| I²C clock | ≤ 400 kHz |

**Conformance:** the chip converts continuously with no start/done marker on the bus, so `specs/temperature/stds75_timing.conf` defines no checks.

## Implementation Notes

- **Do not poll faster than tCONV(max):** if a read is in progress when a conversion finishes the previous value is returned, and continuous accessing can starve the register update.
- **Resolution default is 9 bit** (STLM75 compatibility); raise it with `set_resolution` and allow the longer conversion time.
- **Pointer persists:** one pointer write serves consecutive reads; the upper six pointer bits must be 0.
- **Read-modify-write CONF:** bit 7 is read-only; keep it 0.
- **Interrupt mode cleanup:** any register read clears OS/INT, so `poll_interrupt` reads `TEMP`.
- **No identity register:** at `0x48`–`0x4F` the STDS75 is indistinguishable from LM75B/TMP102 by probing; registry entries list the candidates.
- **Hysteresis semantic:** in comparator mode OS/INT releases when the temperature falls below THYS (not TOS).

## Sigrok Decoder

Decoder id `stds75`, input `['i2c']`. Matches I²C addresses `0x48`–`0x4F`. Annotates the pointer byte as a register name, decodes TEMP reads as °C (using the configured resolution when the CONF write was seen), CONF into RC/FT/POL/M/SD, TOS/THYS values as °C, and warns on pointer writes with the upper bits set. No timing annotation pair is emitted.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/temperature/stds75.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/temperature/stds75/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/temperature/stds75/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/temperature/stds75/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/temperature/stds75_test.py` (MicroPython)
- [ ] Tests `python/tests/temperature/stds75_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/temperature/stds75_test_linux.py` (Linux)
- [ ] Unit test `python/tests/temperature/stds75_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/temperature/stds75/stds75.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/temperature/stds75/stds75_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/temperature/stds75/stds75.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/temperature/stds75/STDS75.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/temperature/stds75/STDS75.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/temperature/STDS75.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/temperature/STDS75.cpp`
- [ ] Examples `cpp/examples/arduino/temperature/STDS75/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/temperature/STDS75/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/temperature/STDS75/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/temperature/STDS75/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/temperature/STDS75/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/temperature/STDS75/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/temperature/STDS75/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/temperature/STDS75/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/temperature/STDS75/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/temperature/STDS75/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/temperature/STDS75/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/temperature/STDS75/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/temperature/STDS75/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/temperature/STDS75/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/temperature/STDS75/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/temperature/STDS75/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/temperature/STDS75/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/temperature/STDS75/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/temperature/stds75_test/stds75_test.ino` (Arduino)
- [ ] Tests `cpp/tests/temperature/stds75_test_linux/stds75_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/temperature/stds75_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/temperature/stds75_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/temperature/stds75_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/temperature/stds75_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/temperature/stds75_test_unit/stds75_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/temperature/stds75.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/temperature/stds75/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/temperature/stds75/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/temperature/stds75/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/temperature/stds75_test.js`
- [ ] Unit test `nodejs/tests/temperature/stds75_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-temperature/nodes/stds75/stds75.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-temperature/nodes/stds75/stds75.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-temperature/examples/stds75/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/temperature/stds75.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/temperature/stds75/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/temperature/stds75/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/temperature/stds75/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/temperature/stds75_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/temperature/stds75_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/temperature/stds75.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/temperature/stds75.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/temperature/stds75/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/temperature/stds75/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/temperature/stds75/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/temperature/stds75/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/temperature/stds75/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/temperature/stds75/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/temperature/stds75_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/temperature/stds75_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/temperature/stds75_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/temperature/STDS75Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/temperature/STDS75Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/temperature/STDS75Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/temperature/STDS75Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/temperature/STDS75Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/temperature/STDS75Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/temperature/stds75/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/temperature/stds75/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/temperature/stds75/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/temperature/stds75/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/temperature/stds75/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/temperature/stds75/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/temperature/stds75/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/temperature/stds75/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/temperature/stds75/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/temperature/stds75/STDS75Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/temperature/STDS75Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/temperature/STDS75Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/temperature/STDS75Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/stds75/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/stds75/pd.py` — annotates every register access per the Sigrok Decoder section (no timing annotation pair: no conformance-checked constraints)

### Conformance
- [ ] Checker `conformance/temperature/stds75_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/temperature/stds75_timing.conf` — already committed; defines no checks (see its header)
- [ ] Decoder `sigrok/stds75/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

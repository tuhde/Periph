# Chip Spec: LM75B

**Manufacturer:** NXP Semiconductors  
**Datasheet:** `datasheets/temperature/lm75b.pdf` (LM75B Rev. 6.1, 6 February 2015)  
**Category:** temperature  
**Transports:** I²C

## Overview

The LM75B is a digital temperature sensor and thermal watchdog: a band-gap sensor with a delta-sigma ADC gives an 11-bit two's-complement temperature with 0.125 °C resolution (accuracy ±2 °C from −25 to +100 °C, ±3 °C from −55 to +125 °C) over an I²C interface (up to 400 kHz). A conversion runs every 100 ms (10 ms active, the rest idle) in normal mode; shutdown mode drops the supply current to ≈ 0.2 µA. The result is compared with a programmable overtemperature shutdown threshold (`Tos`, default 80 °C) and hysteresis (`Thyst`, default 75 °C); an open-drain **OS** output (comparator or interrupt behavior, selectable polarity, fault queue of 1/2/4/6) signals an overtemperature condition. Three address pins give eight addresses. Supply 2.8–5.5 V. It is register-compatible with the industry-standard LM75 (9-bit use).

## Transport Configuration

### I²C
- **Address:** `0x48`–`0x4F` (address byte `1001 A2 A1 A0 R/W`; A2/A1/A0 strapped to GND or VCC)
- **Max clock:** 400 kHz (20 Hz–400 kHz); bus fault time-out: the chip releases SDA and returns to idle if SDA is held low longer than 75 ms (guaranteed 50 ms)
- **Identity register:** none; chip has no ID register (`registry/chips.json` entry has `"id_probe": null`; the addresses `0x48`–`0x4B` are shared with the TMP117 and the ADS1x1x/PCF8591 families)
- **Probe safety:** `register_pointer` — a 1-byte write only loads the 2-bit pointer register
- **Register addressing:** 1-byte pointer (`RegisterConnection`, `reg_bytes = 1`); 16-bit registers are transferred MSByte first; a register read after a pointer write uses a repeated START. The pointer persists between transactions, but writes always include it

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| OS | selectable (`OS_POL`), open-drain | Overtemperature shutdown output; requires an external pull-up |
| A0, A1, A2 | — | Address bits; tie to GND or VCC |
| SDA, SCL | — | I²C; external pull-ups (≈ 10 kΩ) |
| VCC, GND | — | 2.8–5.5 V supply |

## Register Map

| Pointer | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00` | Temp | R | n/a | Temperature, 16 bits, upper 11 bits used (D10…D0 in bits 15:5), 0.125 °C/LSB, two's complement |
| `0x01` | Conf | R/W | `0x00` | 8-bit configuration register |
| `0x02` | Thyst | R/W | `0x4B00` (75 °C) | Hysteresis threshold, upper 9 bits used, 0.5 °C/LSB, two's complement |
| `0x03` | Tos | R/W | `0x5000` (80 °C) | Overtemperature shutdown threshold, upper 9 bits used, 0.5 °C/LSB |

The pointer register holds only the two LSBs (`B1:B0`); the six MSBs are 0. At power-up the pointer is `0x00` (Temp). Only pointer values `0x00`–`0x03` exist.

### Bit Fields

#### `Conf` (`0x01`)

| Bits | Name | Description |
|------|------|-------------|
| 7:5 | — | Reserved; keep `000` |
| 4:3 | OS_F_QUE | OS fault queue: `00` = 1 (default), `01` = 2, `10` = 4, `11` = 6 consecutive faults |
| 2 | OS_POL | OS polarity: `0` = active low (default), `1` = active high |
| 1 | OS_COMP_INT | OS mode: `0` = comparator (default), `1` = interrupt |
| 0 | SHUTDOWN | `0` = normal (default), `1` = shutdown |

#### Temperature data (`Temp`, `Tos`, `Thyst`)

16-bit big-endian words; `Temp` carries D10…D0 in bits 15:5 (bits 4:0 read 0), `Tos`/`Thyst` carry D8…D0 in bits 15:7 (bits 6:0 read 0). Reading only the first byte gives 1 °C resolution.

## Initialization Sequence

1. The chip powers up in normal mode, OS comparator mode, OS active low, fault queue 1, `Tos` = 80 °C, `Thyst` = 75 °C; no software initialization is required.
2. The first conversion result is available after the first conversion (≤ 100 ms after power-up); reading `Temp` earlier returns the previous/zero value.
3. `init` stores the connection and (Minimal) performs no writes; the Full class additionally reads `Conf` to initialise its shadow.

## Interrupt

| Property | Value |
|----------|-------|
| INT pin | `OS`, open-drain, polarity per `OS_POL` (active low by default) — requires an external pull-up |
| Level | 1 (single fixed condition) |
| Condition | `Temp` exceeded `Tos` for `OS_F_QUE` consecutive conversions |
| Clear mechanism | **Comparator mode:** the output deactivates when `Temp` falls to or below `Thyst` (reading registers does not change it). **Interrupt mode:** the output stays active until any register is read (or the device is put in shutdown); it re-arms when `Temp` falls below `Thyst`, then activates again on the next `Tos` crossing |

There is no status register: the OS state is only visible on the pin.

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback, int_pin=None)` | Subscribe on the OS output (active edge per the configured polarity) via `connection.int_pin` (or `int_pin`), otherwise a 1 s polling task calling `poll_interrupt`; `callback(temperature_c)` receives the current temperature |
| `off_interrupt` | `off_interrupt()` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> bool` | Reads `Temp` (which also resets an interrupt-mode OS output) and returns `True` while the driver's model says OS is active: it becomes active when `Temp > Tos` and inactive again when `Temp ≤ Thyst` (the fault queue is not modelled) |

## Implementation Stages

### Minimal

Goal: read the temperature with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Stores the connection; no bus access |
| `read_temperature` | — | float | °C (11-bit, 0.125 °C resolution) |

**Sensible defaults:** the chip's power-on state (normal mode, comparator OS, `Tos` 80 °C, `Thyst` 75 °C).

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_raw` | — | int | Signed 11-bit `Temp` count (`raw × 0.125 °C`) |
| `read_config` | — | int | `Conf` byte |
| `write_config` | `value: int` | — | Write `Conf` (bits 7:5 forced to 0) |
| `set_tos` / `get_tos` | `celsius: float` / — | — / float | Overtemperature threshold, rounded to 0.5 °C; range −55…+125 °C |
| `set_thyst` / `get_thyst` | `celsius: float` / — | — / float | Hysteresis threshold, same coding |
| `set_fault_queue` | `count: int` (1, 2, 4, 6) | — | `OS_F_QUE`; raises on other values |
| `set_os_polarity` | `active_high: bool` | — | `OS_POL` |
| `set_os_mode` | `interrupt: bool` | — | `OS_COMP_INT` (`False` = comparator, `True` = interrupt) |
| `shutdown` | — | — | `SHUTDOWN` ← 1 (also resets an interrupt-mode OS output) |
| `wake` | — | — | `SHUTDOWN` ← 0; the next conversion result is ready after ≤ 100 ms |
| `is_shutdown` | — | bool | Read `Conf` bit 0 |
| `on_interrupt` / `off_interrupt` / `poll_interrupt` | see Interrupt | | |

All writes are read-modify-write on `Conf` using a shadow value so unrelated bits are preserved.

## Data Conversion

```
raw11   = ((msb << 8) | lsb) >> 5                  # Temp: 11 bits, sign-extend from bit 10
temp_c  = raw11 * 0.125                            # -55 .. +125 °C

raw9    = ((msb << 8) | lsb) >> 7                  # Tos / Thyst: 9 bits, sign-extend from bit 8
limit_c = raw9 * 0.5

write limit: word = (round(celsius / 0.5) & 0x1FF) << 7        # e.g. 80 °C -> 0x5000, 75 °C -> 0x4B00
```

Examples: `0x7D00` → +125.0 °C; `0x1900` → +25.0 °C; `0xE700` → −25.0 °C; `0xC900` → −55.000 °C (raw11 `0x648`) (datasheet Table 10: `0x648` = −55.000 °C, `0x649` = −54.875 °C).

## Node-RED

Node name: `periph-lm75b`  
Package: `node-red-contrib-periph-temperature`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature_c }` | One reading per input message |

Config panel fields: I²C bus number, address (`0x48`–`0x4F`).

### Demo flow

Inject node (every 1 s) → `periph-lm75b` → function node formatting `"23.625 °C"` → debug node and a chart node.

## Examples

### Demo

**Thermal watchdog.** With the Full driver, read the temperature once per second; set `Tos` to 30 °C and `Thyst` to 28 °C (`set_tos`, `set_thyst`), fault queue 2 and interrupt mode; print the temperature, the OS state from `poll_interrupt()` (or the OS callback), and warm the sensor with a finger to trigger it. Finally show `shutdown()` / `wake()` and restore the defaults (`Tos` 80 °C, `Thyst` 75 °C).

## Timing Constraints

- **Conversion period:** 100 ms in normal mode (`Tconv`), of which ≈ 10 ms is the active temperature conversion (`tconv(T)`); the `Temp` register is updated at the end of each conversion and an access never interrupts a conversion.
- **After wake from shutdown / power-up:** the first valid `Temp` after ≤ 100 ms.
- **Bus time-out:** SDA held low longer than 75 ms (50 ms guaranteed) resets the interface.
- **Power:** normal 100–200 µA (300 µA max with 400 kHz traffic), shutdown 0.2 µA typ. (1 µA max).
- **I²C:** 20 Hz – 400 kHz — clock-rate properties, not event-to-event delays.
- **Conformance:** none of these has a bus-decodable start/end annotation pair, so `specs/temperature/lm75b_timing.conf` defines no checks.

## Implementation Notes

- **RegisterConnection:** accept `RegisterConnection` (1-byte pointer) and call `connection.read(reg, 2)` / `connection.write(reg, data)`; no chip-local `_read_reg`/`_write_reg`, no `bus_type` branch (see `specs/feature_register_access_design.md`). `Temp`/`Tos`/`Thyst` are 2-byte registers, `Conf` is 1 byte.
- **Always read both bytes:** the chip clocks out both data bytes of `Temp`/`Tos`/`Thyst`; the lower bits are zero and must be ignored (11 bits for `Temp`, 9 for the limits).
- **Two's complement:** sign-extend after the shift (bit 10 for `Temp`, bit 8 for the limits); do not use the raw word as a signed 16-bit value divided by 256 without the shift.
- **Reading resets OS in interrupt mode:** any register read deactivates the OS output in interrupt mode, so `poll_interrupt` doubles as the acknowledge; in comparator mode reads have no effect.
- **Shutdown:** the I²C interface stays active in shutdown; `Temp` keeps its last value. Setting the shutdown bit also resets an interrupt-mode OS output.
- **Fault queue** counts consecutive conversions (100 ms each): a queue of 6 delays OS by ≈ 600 ms.
- **LM75 compatibility:** for 9-bit LM75 behavior use only the top 9 bits of `Temp`; the thresholds are defined exactly as in the LM75.
- **Address overlap:** `0x48`–`0x4F` is shared with the TMP117, ADS1x1x and PCF8591; a wrong-chip access returns meaningless data rather than an error.
- **Datasheet:** NXP LM75B Rev. 6.1, from `https://www.nxp.com/docs/en/data-sheet/LM75B.pdf`.

## Sigrok Decoder

Decoder id `lm75b`, input `['i2c']`. Matches I²C addresses `0x48`–`0x4F`. Annotates pointer writes (register name Temp/Conf/Thyst/Tos), `Conf` writes and reads decoded field by field (fault queue, polarity, mode, shutdown), and 2-byte reads/writes of `Temp` (°C, 0.125 °C steps) and `Tos`/`Thyst` (°C, 0.5 °C steps); flags a warning for pointer values above `0x03`, for writes to `Temp`, and for a read of fewer than 2 bytes (reduced resolution). No timing annotation pair is emitted.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/temperature/lm75b.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/temperature/lm75b/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/temperature/lm75b/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/temperature/lm75b/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/temperature/lm75b_test.py` (MicroPython)
- [ ] Tests `python/tests/temperature/lm75b_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/temperature/lm75b_test_linux.py` (Linux)
- [ ] Unit test `python/tests/temperature/lm75b_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/temperature/lm75b/lm75b.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/temperature/lm75b/lm75b_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/temperature/lm75b/lm75b.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/temperature/lm75b/LM75B.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/temperature/lm75b/LM75B.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/temperature/LM75B.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/temperature/LM75B.cpp`
- [ ] Examples `cpp/examples/arduino/temperature/LM75B/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/temperature/LM75B/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/temperature/LM75B/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/temperature/LM75B/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/temperature/LM75B/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/temperature/LM75B/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/temperature/LM75B/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/temperature/LM75B/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/temperature/LM75B/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/temperature/LM75B/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/temperature/LM75B/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/temperature/LM75B/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/temperature/LM75B/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/temperature/LM75B/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/temperature/LM75B/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/temperature/LM75B/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/temperature/LM75B/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/temperature/LM75B/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/temperature/lm75b_test/lm75b_test.ino` (Arduino)
- [ ] Tests `cpp/tests/temperature/lm75b_test_linux/lm75b_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/temperature/lm75b_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/temperature/lm75b_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/temperature/lm75b_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/temperature/lm75b_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/temperature/lm75b_test_unit/lm75b_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/temperature/lm75b.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/temperature/lm75b/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/temperature/lm75b/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/temperature/lm75b/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/temperature/lm75b_test.js`
- [ ] Unit test `nodejs/tests/temperature/lm75b_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-temperature/nodes/lm75b/lm75b.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-temperature/nodes/lm75b/lm75b.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-temperature/examples/lm75b/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/temperature/lm75b.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/temperature/lm75b/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/temperature/lm75b/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/temperature/lm75b/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/temperature/lm75b_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/temperature/lm75b_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/temperature/lm75b.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/temperature/lm75b.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/temperature/lm75b/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/temperature/lm75b/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/temperature/lm75b/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/temperature/lm75b/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/temperature/lm75b/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/temperature/lm75b/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/temperature/lm75b_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/temperature/lm75b_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/temperature/lm75b_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/temperature/LM75BMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/temperature/LM75BFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/temperature/LM75BMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/temperature/LM75BFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/temperature/LM75BMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/temperature/LM75BFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/temperature/lm75b/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/temperature/lm75b/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/temperature/lm75b/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/temperature/lm75b/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/temperature/lm75b/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/temperature/lm75b/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/temperature/lm75b/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/temperature/lm75b/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/temperature/lm75b/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/temperature/lm75b/LM75BTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/temperature/LM75BTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/temperature/LM75BTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/temperature/LM75BSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/lm75b/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/lm75b/pd.py` — annotates every register access per the Sigrok Decoder section (no timing annotation pair: no conformance-checked constraints)

### Conformance
- [ ] Checker `conformance/temperature/lm75b_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/temperature/lm75b_timing.conf` — already committed; defines no checks (see its header)
- [ ] Decoder `sigrok/lm75b/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

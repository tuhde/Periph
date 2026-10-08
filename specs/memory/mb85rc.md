# Chip Spec: MB85RC

**Manufacturer:** Fujitsu Semiconductor (now RAMXEED)  
**Datasheet:** `datasheets/memory/mb85rc.pdf`  
**Category:** memory  
**Transports:** I²C

## Overview

The MB85RC family are I²C ferroelectric RAM (FRAM) chips: non-volatile like EEPROM, but writes complete at bus speed with no write cycle, no page buffer and ≥ 10¹² write cycles per byte. This spec covers the **MB85RC256V** (256 Kbit = 32,768 × 8), the part documented by the committed datasheet (DS501-00017-3v0-E) and linked from the issue. Other family members (MB85RC04V, MB85RC16, MB85RC64TA, MB85RC512T, MB85RC1MT, …) differ in density and address width and are **not** covered; each would need its own spec. Typical uses: configuration and calibration storage, event/data logging, counters that change constantly, battery-free state retention.

## Transport Configuration

### I²C
- **Address:** `0x50` + (A2 A1 A0) — `0x50`…`0x57`, set by the A0–A2 pins (pulled down internally; open = 0); up to 8 devices per bus. Type code is `1010`.
- **Max clock:** 1 MHz (Fast-mode Plus); 400 kHz fast mode; 100 kHz standard mode
- **Identity register:** none reachable by the standard scan — the Device ID (see below) is read through the reserved slave address `0x7C`, outside the discovery scan range `0x08`–`0x77` (feeds `registry/chips.json` with `id_probe: null`)
- **Probe safety:** `register_pointer` — a 1-byte write only supplies the first half of a memory address; no data is stored until a second address byte and a data byte follow.

Memory addressing is **two bytes** (high, low; bit 7 of the high byte must be 0 — 15-bit address, `0x0000`–`0x7FFF`), so the chip is driven as a `RegisterConnection` with `reg_bytes` = 2 (see `specs/feature_register_access_design.md`).

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| A0, A1, A2 | — | device address; tie to VDD or VSS (internally pulled down) |
| WP | high | write protect: H = whole array write-protected, L (default, internally pulled down) = writable; reads are always allowed |
| SDA | — | open drain; external pull-up required |
| SCL | — | clock |
| VDD | — | 2.7–5.5 V |

No interrupt pins.

## Memory Map

| Address Range | Name | R/W | Description |
|---------------|------|-----|-------------|
| `0x0000`–`0x7FFF` | FRAM | R/W | 32,768 bytes; no page structure |

There are no control or status registers.

### Device ID

A read-only 3-byte ID is returned by the "Device ID" command (reserved slave ID `0xF8` write / `0xF9` read, i.e. 7-bit address `0x7C`):

1. START, `0xF8` (W), then the device address word (`1010 A2 A1 A0 x`, R/W bit is don't-care),
2. repeated START, `0xF9` (R), then read 3 bytes (NACK the last).

| Byte | Content | Value |
|------|---------|-------|
| 1 | Manufacturer ID bits 11:4 | `0x00` |
| 2 | Manufacturer ID bits 3:0 (`0xA`), density (`0x5` = 256 Kbit) | `0xA5` |
| 3 | Product ID proprietary bits | `0x10` |

Together: manufacturer `0x00A` (Fujitsu), product `0x510` → 24-bit value `0x00A510`. ACKing the 3rd byte restarts the ID from byte 1.

## Initialization Sequence

1. Power up (VDD 2.7–5.5 V). The datasheet gives no power-on delay; no register writes are needed.
2. Optionally verify presence with a random read of address `0x0000` (the chip ACKs its address) or `read_device_id`.
3. The current-address pointer is undefined after power-up — do not use "current address read" before the first access.

## Write Operations

- **Byte write:** S, `1010 A2A1A0 0`, address high, address low, data, P.
- **Page ("sequential") write:** the same, with more data bytes before P. There are no pages: the address auto-increments through the whole array and rolls over from `0x7FFF` to `0x0000`.
- **No write delay and no ACK polling:** data is committed right after each byte's ACK; the next command may follow the STOP immediately.
- **Write protect:** with WP high, writes are ignored (the device still ACKs; datasheet does not say whether it NACKs data bytes — see Implementation Notes).

## Read Operations

- **Random read:** S, `...0`, address high, address low, rS, `...1`, data, NACK, P (driver: `connection.read(address, 1)`).
- **Sequential read:** as random read but ACK each byte; the address rolls over from `0x7FFF` to `0x0000`.
- **Current address read:** S, `...1`, data (address n+1 after the previous access). Not exposed.

## Interrupt

Level 0 — the chip has no interrupt output.

## Implementation Stages

### Minimal

Goal: single-byte persistent storage with no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Stores the connection (a `RegisterConnection` with `reg_bytes` = 2 at `0x50`–`0x57`); no bus traffic |
| `read_byte` | `address: int` (0–32767) | `int` (0–255) | Random read; raises on an out-of-range address |
| `write_byte` | `address: int`, `value: int` | — | Byte write; returns when the transfer completes (no polling needed) |

**Sensible defaults:** none to set; the full 32 KiB is addressable.

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read` | `address: int`, `length: int` | `bytes` | Sequential read, split into chunks of `max_chunk` bytes; the range `address`…`address + length` must lie inside `0x0000`–`0x7FFF` (no silent wrap-around) |
| `write` | `address: int`, `data: bytes` | — | Sequential write, split into chunks of `max_chunk` bytes; same range rule |
| `fill` | `address: int`, `length: int`, `value: int=0` | — | Writes `value` over a range (chunked) |
| `size` | — | `int` | `32768` |
| `read_device_id` | `id_connection` | `(manufacturer: int, density: int, product: int)` | Device ID via the reserved slave address `0x7C`; `id_connection` is a `RegisterConnection` (`reg_bytes` = 1) bound to `0x7C`, and the "register" is the device address word `0xA0 + (A2A1A0 × 2)` — see Implementation Notes. Returns `(0x00A, 0x5, 0x10)` for the MB85RC256V |
| `max_chunk` | property, default 30 | `int` | Maximum data bytes per bus transaction (Arduino `Wire` limits transfers to 32 bytes including the 2 address bytes); settable |

**Additional configuration options:** `max_chunk` only. Write protect is a hardware pin.

## Data Conversion

None; data is raw bytes. Addresses are big-endian on the wire (high byte first).

## Node-RED

Node name: `periph-mb85rc`  
Package: `node-red-contrib-periph-memory`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| `{ op: "read", address: 0, length: 4 }` (default op) | `{ data: [0x12, 0x34, …] }` | Sequential read |
| `{ op: "write", address: 0, data: [0x01, 0x02] }` | `{ ok: true }` | Sequential write |
| `{ op: "fill", address: 0, length: 16, value: 0 }` | `{ ok: true }` | Fill a range |

Config panel fields: I²C bus number (default `1`), I²C address (`0x50`–`0x57`, default `0x50`).

### Demo flow

An Inject node every 5 seconds → a function node reads a boot counter at address `0x0000` via `periph-mb85rc`, increments it and writes it back → Debug node shows the counter, demonstrating non-volatile state that survives a Node-RED restart and needs no write delay.

## Examples

### Demo

Persistent boot counter and ring-buffer logger. On startup read a 4-byte big-endian boot counter at `0x0000`, increment it, write it back and print it. Then log 1,000 16-byte records (record index + a counter) into a ring buffer starting at `0x0100` using `write` back-to-back with **no delays**, time the whole run and print the achieved throughput (bytes per second), read the last 5 records back with `read` and verify them, and finally `fill` the log area with zero. Print `read_device_id()` when a `0x7C` connection is available (Linux hosts may not allow it). Exercises `init`, `read_byte`, `write_byte`, `read`, `write`, `fill`, `size`, `read_device_id`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Write cycle time | none — data is written at bus speed; no ACK polling, no delay after STOP |
| Max I²C clock (VDD 2.7–5.5 V) | 1 MHz |
| Bus free time between transmissions | ≥ 4.7 µs (100 kHz), 1.3 µs (400 kHz), 0.5 µs (1 MHz) |
| Endurance / retention | ≥ 10¹² read/write cycles per byte; 10 years at +85 °C |

No timing constraint gets a conformance check (the absence of a write delay is not observable as a bound), so there is no `mb85rc_timing.conf`.

## Implementation Notes

- **Register-addressed, 2-byte address:** accept `RegisterConnection` with `reg_bytes` = 2 and call `connection.read(address, length)` / `connection.write(address, data)` directly — no chip-local helpers (see `specs/feature_register_access_design.md`). The 16-bit address is sent high byte first; bit 15 must be 0, so the driver rejects addresses ≥ `0x8000`.
- **Wrap-around is silent in hardware:** sequential reads/writes beyond `0x7FFF` continue at `0x0000` and overwrite data. The driver refuses ranges that would wrap rather than letting that happen.
- **Chunking:** many bus layers limit one transfer (Arduino `Wire` 32 bytes). The driver splits at `max_chunk` (default 30) and advances the address itself; each chunk is a fresh transaction with its own address bytes.
- **Not an EEPROM:** unlike 24AA02UID there is no page size, no write cycle and no ACK polling; do not copy that driver's page-splitting or polling logic. A missing ACK always means a bus problem.
- **Write protect behaviour is not documented:** the datasheet says only that writes are disabled with WP high; whether data bytes are ACKed is not stated. The driver cannot detect WP; verify with a read-back if the pin is wired.
- **Device ID needs address `0x7C`:** that address is in the I²C reserved range; Linux `i2c-dev` (`I2C_SLAVE`) may refuse addresses `0x78`–`0x7F`, in which case `read_device_id` raises `unsupported`. Minimal never uses it. The write phase sends the device address word, then a repeated START and the read — this maps onto `write_read` / `connection.read(address_word, 3)`.
- **Address pins are real:** unlike the 24AA02UID, A0–A2 select the address (`0x50`–`0x57`). Up to eight chips can share a bus; a bus full of them looks identical to a 24AA025UID block in discovery (neither has an ID reachable by scan) — see `specs/feature_i2c_discovery.md` §7.5.
- **Bus recovery:** the datasheet describes a software reset sequence (9 × START + a single `1` bit just before a command) for a stuck slave. It needs bit-level SDA/SCL control and is **not implemented**; the supported recovery is to retry the failed command.
- **Family:** MB85RC64TA, MB85RC128A, MB85RC512T, MB85RC1MT, MB85RC04V/16 differ in address width (1, 2 or 3 bytes), density and Device ID; no compatibility is assumed.

## Sigrok Decoder

Decoder id `mb85rc`, input `['i2c']`. Matches I²C addresses `0x50`–`0x57` (A2:A0 shown in the annotation) and `0x7C`. Annotates byte write / sequential write (15-bit address, data bytes), random read / sequential read, current-address read, and the Device ID sequence (decoding the 3 bytes into manufacturer, density and product, checked against `0x00A510`). Address-wrap past `0x7FFF` and an address with bit 15 set are flagged as warnings.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/memory/mb85rc.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/memory/mb85rc/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/memory/mb85rc/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/memory/mb85rc/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/memory/mb85rc_test.py` (MicroPython)
- [ ] Tests `python/tests/memory/mb85rc_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/memory/mb85rc_test_linux.py` (Linux)
- [ ] Unit test `python/tests/memory/mb85rc_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/memory/mb85rc/mb85rc.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/memory/mb85rc/mb85rc_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/memory/mb85rc/mb85rc.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/memory/mb85rc/MB85RC.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/memory/mb85rc/MB85RC.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/memory/MB85RC.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/memory/MB85RC.cpp`
- [ ] Examples `cpp/examples/arduino/memory/MB85RC/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/memory/MB85RC/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/memory/MB85RC/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/memory/MB85RC/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/memory/MB85RC/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/memory/MB85RC/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/memory/MB85RC/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/memory/MB85RC/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/memory/MB85RC/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/memory/MB85RC/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/memory/MB85RC/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/memory/MB85RC/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/memory/MB85RC/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/memory/MB85RC/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/memory/MB85RC/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/memory/MB85RC/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/memory/MB85RC/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/memory/MB85RC/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/memory/mb85rc_test/mb85rc_test.ino` (Arduino)
- [ ] Tests `cpp/tests/memory/mb85rc_test_linux/mb85rc_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/memory/mb85rc_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/memory/mb85rc_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/memory/mb85rc_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/memory/mb85rc_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/memory/mb85rc_test_unit/mb85rc_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/memory/mb85rc.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/memory/mb85rc/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/memory/mb85rc/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/memory/mb85rc/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/memory/mb85rc_test.js`
- [ ] Unit test `nodejs/tests/memory/mb85rc_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-memory/nodes/mb85rc/mb85rc.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-memory/nodes/mb85rc/mb85rc.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-memory/examples/mb85rc/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/memory/mb85rc.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/memory/mb85rc/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/memory/mb85rc/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/memory/mb85rc/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/memory/mb85rc_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/memory/mb85rc_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/memory/mb85rc.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/memory/mb85rc.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/memory/mb85rc/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/memory/mb85rc/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/memory/mb85rc/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/memory/mb85rc/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/memory/mb85rc/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/memory/mb85rc/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/memory/mb85rc_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/memory/mb85rc_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/memory/mb85rc_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/memory/MB85RCMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/memory/MB85RCFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/memory/MB85RCMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/memory/MB85RCFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/memory/MB85RCMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/memory/MB85RCFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/memory/mb85rc/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/memory/mb85rc/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/memory/mb85rc/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/memory/mb85rc/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/memory/mb85rc/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/memory/mb85rc/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/memory/mb85rc/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/memory/mb85rc/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/memory/mb85rc/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/memory/mb85rc/MB85RCTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/memory/MB85RCTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/memory/MB85RCTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/memory/MB85RCSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/mb85rc/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/mb85rc/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

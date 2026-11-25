# Chip Spec: 47XXX

**Manufacturer:** Microchip Technology  
**Datasheet:** `datasheets/memory/47xxx.pdf` (DS20005371E, 47L04/47C04/47L16/47C16)  
**Category:** memory  
**Transports:** I²C

## Overview

The 47XXX family are I²C **EERAM** devices: a fast SRAM array with an EEPROM backup array. The host reads and writes the SRAM at bus speed with unlimited endurance and no write delay; the SRAM contents are copied to the EEPROM (**Store**) automatically on power loss (Auto-Store, needs an external VCAP capacitor), on a pulse on the HS pin (Hardware Store) or on a software command, and copied back (**Recall**) automatically at power-up or on a software command. This spec covers all four members, which differ only in density and supply range:

| Part | Density | Array size | VCC |
|------|---------|------------|-----|
| 47L04 | 4 Kbit | 512 × 8 (`0x000`–`0x1FF`) | 2.7–3.6 V |
| 47C04 | 4 Kbit | 512 × 8 | 4.5–5.5 V |
| 47L16 | 16 Kbit | 2,048 × 8 (`0x000`–`0x7FF`) | 2.7–3.6 V |
| 47C16 | 16 Kbit | 2,048 × 8 | 4.5–5.5 V |

The driver takes the array size as a constructor parameter (`size`, 512 or 2048). Typical uses: counters and state that change constantly but must survive a power cut, data loggers, configuration shadowing.

## Transport Configuration

### I²C
- **Address:** the chip answers on **two** 7-bit addresses (the 8-bit control byte is `op-code, A2, A1, 0, R/W`):
  - **SRAM array:** `0x50` + (A2 A1) × 2 — `0x50`, `0x52`, `0x54`, `0x56` (op code `1010`)
  - **Control registers:** `0x18` + (A2 A1) × 2 — `0x18`, `0x1A`, `0x1C`, `0x1E` (op code `0011`)
  
  A1/A2 are pins (internally weakly held; tie to VCC or VSS); up to four devices per bus. The two units are separate I²C targets, so the Full driver needs two connections.
- **Max clock:** 1 MHz (all parts, VCC ranges above)
- **Identity register:** none — the SRAM address range is shared with 24xx EEPROMs and FRAM (feeds `registry/chips.json` with `id_probe: null`). Only the SRAM addresses are registered.
- **Probe safety:** `register_pointer` — a 1-byte write only supplies half of the 2-byte array address; no data is stored until an address LSB and a data byte follow.

The SRAM is addressed with **two address bytes** (MSB, LSB), the control registers with **one** address byte: the SRAM is driven as a `RegisterConnection` with `reg_bytes` = 2, the control registers as a `RegisterConnection` with `reg_bytes` = 1 (see `specs/feature_register_access_design.md`).

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| A1, A2 | — | device address bits; must match the control byte |
| HS | high | Hardware Store: ≥ 150 ns high pulse starts a Store (ignored while VCAP < VTRIP and during Store/Recall); sets the EVENT bit; tie to VSS if unused |
| VCAP | — | Auto-Store capacitor: required to VSS when ASE = 1: 47C04 ≥ 3.5 µF (4.7 µF typ.), 47C16 and 47L04 ≥ 5 µF (6.8 µF typ.), 47L16 ≥ 8 µF (10 µF typ.); if no capacitor is fitted, tie VCAP to VCC and set ASE = 0 |
| SDA | — | open drain; external pull-up required |
| SCL | — | clock |
| VCC | — | 2.7–3.6 V (47L) or 4.5–5.5 V (47C) |

No interrupt pins (HS is an input).

## Memory Map

### SRAM array (op code `1010`)

| Address Range | Name | R/W | Description |
|---------------|------|-----|-------------|
| `0x000`–`0x1FF` (47X04) / `0x000`–`0x7FF` (47X16) | SRAM | R/W | Directly accessed memory; recalled from EEPROM at power-up |

Write: S, `1010 A2 A1 0 0`, address MSB, address LSB, data…, P. Byte writes and sequential writes (address auto-increments, wraps from the last address to `0x000`) take effect at bus speed — no write cycle, no ACK polling. A write to a **protected** address (see BP bits) is NACKed on the data byte and not stored. Reads: random read (address write then repeated START + read), sequential read, current-address read.

### Control registers (op code `0011`)

| Address | Name | R/W | Description |
|---------|------|-----|-------------|
| `0x00` | STATUS | R/W | Array-modified flag, block protect, Auto-Store enable, event flag |
| `0x55` | COMMAND | W | Software Store / Software Recall |

Register address byte is one byte; any other address is NACKed.

#### STATUS (`0x00`)

| Bit | Name | R/W | Description |
|-----|------|-----|-------------|
| 7 | AM | R (volatile) | Array Modified: `1` = SRAM written since the last Store/Recall. Must be `1` for Auto-Store and Hardware Store to execute (Software Store always executes). Cleared when a Store or Recall completes |
| 6:5 | — | R | Read as 0 |
| 4:2 | BP2:BP0 | R/W (non-volatile) | Block protect — upper fraction of the array write-protected: `000` none, `001` upper 1/64, `010` 1/32, `011` 1/16, `100` 1/8, `101` 1/4, `110` 1/2, `111` all |
| 1 | ASE | R/W (non-volatile) | Auto-Store Enable: `1` = Auto-Store on power-down (needs VCAP capacitor), `0` = disabled |
| 0 | EVENT | R/W (non-volatile) | Set when a Hardware Store pulse was detected on HS; can be cleared by writing 0 |

A STATUS write stores the non-volatile bits to EEPROM: the chip does not ACK for **TWC ≤ 1 ms** after the STOP. Multiple data bytes may be sent; the last one wins.

Protected ranges: 47X04: upper 1/64 = `0x1F8`–`0x1FF`, 1/32 = `0x1F0`–, 1/16 = `0x1E0`–, 1/8 = `0x1C0`–, 1/4 = `0x180`–, 1/2 = `0x100`–`0x1FF`, all = `0x000`–`0x1FF`. 47X16: `0x7E0`, `0x7C0`, `0x780`, `0x700`, `0x600`, `0x400`, `0x000` through `0x7FF`.

#### COMMAND (`0x55`, write-only)

| Value | Command | Effect |
|-------|---------|--------|
| `0x33` | Software Store | Copies SRAM → EEPROM; chip silent for TSTORE (≤ 8 ms 47X04, ≤ 25 ms 47X16) |
| `0xDD` | Software Recall | Copies EEPROM → SRAM; chip silent for TRECALL (≤ 2 ms 47X04, ≤ 5 ms 47X16) |

Exactly one data byte must follow the address; any other value or additional bytes are NACKed and the operation aborted. AM is cleared at the end of either operation.

## Initialization Sequence

1. Power up VCC (and VCAP). When VCAP first rises above VTRIP (2.4–2.6 V for 47L, 4.0–4.4 V for 47C) the chip performs an **Auto-Recall** (EEPROM → SRAM) and does not respond for TRECALL (≤ 2 ms / 5 ms). No ACK is given while VCAP < VTRIP.
2. Wait ≥ 5 ms (TRECALL, worst case) or poll for an ACK on the SRAM address.
3. Optionally read STATUS to check ASE and BP.
4. Without a VCAP capacitor: write STATUS with ASE = 0 once (non-volatile) and tie VCAP to VCC; call `store()` explicitly when data must persist.

## Interrupt

Level 0 — no interrupt output. The EVENT status bit is a non-volatile flag for the HS pin, polled via STATUS.

## Implementation Stages

### Minimal

Goal: use the chip as fast, power-fail-safe RAM with no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection`, `size=512` | — | `connection`: `RegisterConnection` (`reg_bytes` = 2) at the SRAM address; `size` = 512 (47X04) or 2048 (47X16); no bus traffic |
| `read_byte` | `address: int` | `int` (0–255) | Random read; raises on an out-of-range address |
| `write_byte` | `address: int`, `value: int` | — | Byte write; returns when the transfer completes |

**Sensible defaults:** none are written. With a VCAP capacitor fitted (factory/previous ASE = 1) data survives power loss automatically; the Minimal class relies on that and never touches the control registers.

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `init` | `connection`, `size=512`, `control_connection` | — | `control_connection`: `RegisterConnection` (`reg_bytes` = 1) at the control address (`0x18`…`0x1E`) |
| `read` | `address: int`, `length: int` | `bytes` | Sequential read, chunked by `max_chunk`; the range must lie inside the array (no silent wrap) |
| `write` | `address: int`, `data: bytes` | — | Sequential write, chunked; same range rule |
| `fill` | `address: int`, `length: int`, `value: int=0` | — | Writes `value` over a range |
| `size` | — | `int` | Array size in bytes |
| `read_status` | — | `int` | STATUS register |
| `array_modified` | — | `bool` | AM bit |
| `auto_store_enabled` | — | `bool` | ASE bit |
| `set_auto_store` | `enable: bool` | — | Read-modify-write STATUS ASE; waits TWC (1 ms) after the write |
| `event` | — | `bool` | EVENT bit |
| `clear_event` | — | — | Writes EVENT = 0 (read-modify-write, waits TWC) |
| `block_protect` | — | `int` (0–7) | BP2:BP0 |
| `set_block_protect` | `bp: int` | — | Writes BP2:BP0 (0–7), waits TWC |
| `store` | — | — | Writes COMMAND `0x33`, then waits TSTORE (8 ms for 512 B / 25 ms for 2048 B) or polls ACK |
| `recall` | — | — | Writes COMMAND `0xDD`, then waits TRECALL (2 ms / 5 ms) or polls ACK |
| `max_chunk` | property, default 30 | `int` | Maximum data bytes per transaction (Arduino `Wire` 32-byte limit including 2 address bytes) |

**Additional configuration options:** `max_chunk`. Auto-Store, block protect and the event flag are STATUS functions above; the HS pin is hardware.

## Data Conversion

None; data is raw bytes. SRAM addresses are big-endian on the wire (MSB first).

## Node-RED

Node name: `periph-47xxx`  
Package: `node-red-contrib-periph-memory`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| `{ op: "read", address: 0, length: 4 }` (default op) | `{ data: [0x12, 0x34, …] }` | Sequential read |
| `{ op: "write", address: 0, data: [1, 2] }` | `{ ok: true }` | Sequential write |
| `{ op: "status" }` | `{ am, ase, bp, event }` | STATUS decoded |
| `{ op: "store" }` / `{ op: "recall" }` | `{ ok: true }` | Software Store / Recall |

Config panel fields: I²C bus number (default `1`), SRAM I²C address (`0x50`, `0x52`, `0x54`, `0x56`, default `0x50`; control address derived), size (512 or 2048, default 512).

### Demo flow

An Inject node every 5 seconds → a function node reads a 4-byte counter at address 0, increments and writes it back → a second Inject node sends `{ op: "store" }`; Debug nodes show the counter surviving a power cycle with a VCAP capacitor fitted.

## Examples

### Demo

Power-fail-safe counter and logger. Read the 4-byte big-endian boot counter at address `0x000`, increment, write it back and print it; print STATUS (AM, ASE, BP, EVENT). Make sure Auto-Store is enabled (`set_auto_store(True)`), then append 100 16-byte records at `0x010` with `write` back-to-back with no delays, time the run and print bytes per second. Call `store()`, overwrite the counter with `0xFFFFFFFF`, `recall()` and verify the old counter returns; set `set_block_protect(1)` (upper 1/64), check that a write there is NACKed (and the data unchanged), and restore `set_block_protect(0)`. Exercises `init`, `read_byte`, `write_byte`, `read`, `write`, `fill`, `size`, `read_status`, `array_modified`, `auto_store_enabled`, `set_auto_store`, `event`, `clear_event`, `block_protect`, `set_block_protect`, `store`, `recall`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| SRAM write cycle | none — no ACK polling after STOP |
| STATUS write cycle TWC | ≤ 1 ms after STOP; chip does not ACK |
| Software Store TSTORE | ≤ 8 ms (47X04), ≤ 25 ms (47X16) after STOP |
| Software Recall TRECALL | ≤ 2 ms (47X04), ≤ 5 ms (47X16) after STOP; also after Auto-Recall at power-up |
| Hardware Store pulse THSPW | ≥ 150 ns high on HS |
| VCC rise / fall rate | ≥ 70 µs/V |
| Max I²C clock | 1 MHz; THIGH/TLOW ≥ 500 ns, TBUF ≥ 500 ns |
| Endurance | unlimited SRAM; ≥ 1,000,000 Store cycles |

No constraint gets a conformance check (the bounds are measured from a command's STOP to the next ACK, which has no annotation pair), so `47xxx_timing.conf` defines no checks.

## Implementation Notes

- **Two I²C addresses:** SRAM at `0x50`+, control registers at `0x18`+. The Minimal class uses only the SRAM connection; the Full class takes a second connection for the control registers. On Linux both are opened on the same `/dev/i2c-N`.
- **Wrap-around is silent in hardware** (last address → `0x000`); the driver refuses ranges that would wrap.
- **Store/Recall blocking:** the chip does not ACK its addresses during TWC, TSTORE and TRECALL; the driver either sleeps the datasheet maximum for the configured `size` or polls the SRAM address until it ACKs (preferred where the bus layer supports an empty write; otherwise sleep).
- **Protected writes:** a write to a protected block gets a NACK on the data byte (Table 2-1); the driver must translate that into a distinct error rather than a generic bus error where the bus layer can distinguish it.
- **AM bit gating:** Auto-Store and Hardware Store only run when AM = 1; Software Store always runs.
- **VCAP:** if no capacitor is fitted, VCAP must be tied to VCC and ASE must be 0, otherwise a power cut during Auto-Store can corrupt data; the chip does not ACK anything while VCAP < VTRIP.
- **Address overlap:** SRAM addresses `0x50`–`0x56` (even) overlap 24xx EEPROMs and MB85RC FRAM; the control addresses `0x18`–`0x1E` are not registered (they collide with accelerometers etc. and are only meaningful together with the SRAM address).
- **Family:** 47L256/47C256 and other EERAM densities have a different address width and are not covered.

## Sigrok Decoder

Decoder id `47xxx`, input `['i2c']`. Matches I²C addresses `0x50`/`0x52`/`0x54`/`0x56` (SRAM) and `0x18`/`0x1A`/`0x1C`/`0x1E` (control registers). Annotates SRAM byte/sequential writes and reads (2-byte address, data), STATUS reads/writes decoded into AM/BP/ASE/EVENT, COMMAND writes as Software Store (`0x33`) / Software Recall (`0xDD`), and flags invalid COMMAND values and invalid control register addresses.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/memory/47xxx.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/memory/47xxx/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/memory/47xxx/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/memory/47xxx/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/memory/47xxx_test.py` (MicroPython)
- [ ] Tests `python/tests/memory/47xxx_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/memory/47xxx_test_linux.py` (Linux)
- [ ] Unit test `python/tests/memory/47xxx_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/memory/47xxx/47xxx.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/memory/47xxx/47xxx_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/memory/47xxx/47xxx.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/memory/47xxx/47XXX.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/memory/47xxx/47XXX.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/memory/47XXX.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/memory/47XXX.cpp`
- [ ] Examples `cpp/examples/arduino/memory/47XXX/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/memory/47XXX/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/memory/47XXX/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/memory/47XXX/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/memory/47XXX/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/memory/47XXX/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/memory/47XXX/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/memory/47XXX/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/memory/47XXX/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/memory/47XXX/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/memory/47XXX/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/memory/47XXX/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/memory/47XXX/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/memory/47XXX/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/memory/47XXX/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/memory/47XXX/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/memory/47XXX/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/memory/47XXX/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/memory/47xxx_test/47xxx_test.ino` (Arduino)
- [ ] Tests `cpp/tests/memory/47xxx_test_linux/47xxx_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/memory/47xxx_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/memory/47xxx_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/memory/47xxx_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/memory/47xxx_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/memory/47xxx_test_unit/47xxx_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/memory/47xxx.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/memory/47xxx/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/memory/47xxx/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/memory/47xxx/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/memory/47xxx_test.js`
- [ ] Unit test `nodejs/tests/memory/47xxx_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-memory/nodes/47xxx/47xxx.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-memory/nodes/47xxx/47xxx.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-memory/examples/47xxx/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/memory/47xxx.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/memory/47xxx/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/memory/47xxx/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/memory/47xxx/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/memory/47xxx_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/memory/47xxx_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/memory/47xxx.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/memory/47xxx.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/memory/47xxx/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/memory/47xxx/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/memory/47xxx/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/memory/47xxx/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/memory/47xxx/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/memory/47xxx/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/memory/47xxx_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/memory/47xxx_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/memory/47xxx_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/memory/47XXXMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/memory/47XXXFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/memory/47XXXMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/memory/47XXXFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/memory/47XXXMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/memory/47XXXFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/memory/47xxx/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/memory/47xxx/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/memory/47xxx/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/memory/47xxx/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/memory/47xxx/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/memory/47xxx/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/memory/47xxx/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/memory/47xxx/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/memory/47xxx/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/memory/47xxx/47XXXTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/memory/47XXXTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/memory/47XXXTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/memory/47XXXSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/47xxx/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/47xxx/pd.py` — annotates SRAM accesses, STATUS and COMMAND per the Sigrok Decoder section (no timing annotation pair: no conformance-checked constraints)

### Conformance
- [ ] Checker `conformance/memory/47xxx_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/memory/47xxx_timing.conf` — already committed; defines no checks (see its header)
- [ ] Decoder `sigrok/47xxx/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

# Chip Spec: 24AA02UID

**Manufacturer:** Microchip Technology  
**Datasheet:** `datasheets/memory/24aa02uid.pdf`  
**Category:** memory  
**Transports:** I²C

## Overview

The 24AA02UID is a 2 Kbit (256-byte) I²C EEPROM with a factory-programmed, globally unique 32-bit serial number. The memory is organized as two 128-byte blocks: the lower half (0x00–0x7F) is general-purpose read/write EEPROM, and the upper half (0x80–0xFF) is permanently write-protected and contains the serial number at 0xFC–0xFF plus Microchip manufacturer (0xFA) and device (0xFB) codes. The chip operates from 1.7 V to 5.5 V with up to 400 kHz I²C clock, and supports byte and 8-byte page writes with a 5 ms maximum internal write cycle. With more than 1 million erase/write cycles and greater than 200 years data retention, it is well suited as a non-volatile storage element for small configuration data combined with a guaranteed unique device identifier.

## Transport Configuration

### I²C

- **Address:** `0x50` (fixed — A0, A1, A2 pins are not connected on the 24AA02UID)
- **Max clock:** 400 kHz (Vcc ≥ 2.5 V); 100 kHz (Vcc < 2.5 V)

> **Note:** The 24AA025UID variant (16-byte page, cascadable) uses address pins A0–A2 and supports up to 8 devices on one bus (`0x50`–`0x57`). This spec covers only the 24AA02UID.

## Memory Map

| Address Range | Name | R/W | Description |
|---------------|------|-----|-------------|
| `0x00`–`0x7F` | USER_EEPROM | R/W | 128 bytes general-purpose EEPROM |
| `0x80`–`0xF9` | RESERVED_WP | R | Write-protected, reserved |
| `0xFA` | MFR_CODE | R | Manufacturer code — always `0x29` (Microchip) |
| `0xFB` | DEV_CODE | R | Device code — always `0x41` (I²C, 2 Kbit) |
| `0xFC`–`0xFF` | SERIAL_NUMBER | R | 32-bit unique serial number, MSB first |

### Page Layout (User EEPROM)

The 128-byte user EEPROM is divided into 16 pages of 8 bytes each. Page boundaries fall at multiples of 8 (0x00, 0x08, 0x10, …, 0x78). A page write must not cross a page boundary — bytes that would overflow wrap around to the start of the same page (FIFO overwrite).

| Page | Address Range |
|------|---------------|
| 0 | `0x00`–`0x07` |
| 1 | `0x08`–`0x0F` |
| … | … |
| 15 | `0x78`–`0x7F` |

## Initialization Sequence

No initialization registers. The chip is ready immediately after power-on:

1. Establish I²C bus at the target clock frequency (≤ 400 kHz for Vcc ≥ 2.5 V).
2. Verify the device is present by issuing a write-address command and checking for ACK.

No startup delay is required beyond standard I²C bus stabilization.

## Write Operations

### Byte Write

```
START | 0xA0 (W) | ACK | addr | ACK | data | ACK | STOP
```

After STOP, the internal write cycle begins (up to 5 ms). The device does not ACK during this period.

### Page Write

```
START | 0xA0 (W) | ACK | addr | ACK | d0 | ACK | d1 | ACK | … | d7 | ACK | STOP
```

Up to 8 bytes in one transaction. All bytes must lie within the same 8-byte physical page.

### ACK Polling (Write Completion Detection)

After issuing STOP, repeatedly send:

```
START | 0xA0 (W) | [ACK?]
```

If the device NACK's, the write cycle is still in progress. When ACK is returned, the cycle is complete and the next operation may begin. Maximum write cycle time is 5 ms.

## Read Operations

### Random Read

```
START | 0xA0 (W) | ACK | addr | ACK | START | 0xA1 (R) | ACK | data | NACK | STOP
```

### Sequential Read

```
START | 0xA1 (R) | ACK | d0 | ACK | d1 | ACK | … | dN | NACK | STOP
```

Each ACK from the master causes the internal address pointer to auto-increment. A NACK followed by STOP terminates the sequence. Sequential reads wrap at the end of the 256-byte address space.

### Current Address Read

Issue a read without first setting an address; the internal pointer advances from wherever it was left.

## Write Protection

Addresses 0x80–0xFF are permanently write-protected. Write operations to this range are silently ignored; read operations are unaffected.

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits everything and adds the rest.

### Minimal

Goal: read the chip's primary differentiating feature (the unique serial number) and provide simple single-byte user EEPROM access with no configuration required beyond the transport.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `transport` | — | Stores transport reference; no register writes needed |
| `read_uid` | — | `bytes` (4) | Reads 4-byte serial number from `0xFC`–`0xFF` |
| `read_byte` | `address: int` | `int` (0–255) | Random read from user EEPROM (`0x00`–`0x7F`) |
| `write_byte` | `address: int`, `value: int` | — | Byte write + ACK-poll until complete (max 5 ms) |

**Sensible defaults:** No configuration registers. `write_byte` always waits for write completion via ACK polling before returning.

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read` | `address: int`, `length: int` | `bytes` | Sequential read of `length` bytes from any address (0x00–0xFF) |
| `write_page` | `address: int`, `data: bytes` | — | Page write; caller must ensure all bytes lie within one 8-byte page |
| `write` | `address: int`, `data: bytes` | — | Write arbitrary-length data, automatically split across 8-byte page boundaries; waits for each page write cycle |
| `read_manufacturer_code` | — | `int` | Reads `0xFA`; always `0x29` |
| `read_device_code` | — | `int` | Reads `0xFB`; always `0x41` |

**Additional configuration options:**
- `write` handles crossing page boundaries by splitting the write into page-aligned chunks and ACK-polling after each chunk.
- `write_page` is the low-level primitive; it does not validate page alignment — the caller is responsible.

## Data Conversion

EEPROM stores and returns raw bytes; no unit conversion is required.

The 32-bit serial number is read as 4 consecutive bytes at 0xFC–0xFF, MSB first:

```
uid_int = (data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3]
```

## Node-RED

Node name: `periph-24aa02uid`  
Package: `node-red-contrib-periph-memory`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message (default: read UID) | `{ uid: "AABBCCDD", uid_int: 2864434397 }` | UID as hex string and integer |
| `{ op: "read", address: 0, length: 4 }` | `{ data: [0x12, 0x34, …] }` | Sequential read from user EEPROM |
| `{ op: "write", address: 0, data: [0x01, 0x02] }` | `{ ok: true }` | Byte/page write |

Config panel fields: I²C bus number (default `1`), I²C address (fixed `0x50`, shown read-only), operation (Read UID / Read Memory / Write Memory).

### Demo flow

A flow with an Inject node triggering every 5 seconds → `periph-24aa02uid` node configured for "Read UID" → Debug node printing `msg.payload.uid`. A second branch writes a 2-byte counter to address `0x00` on each tick and reads it back, demonstrating round-trip EEPROM writes with visible increment.

## Examples

### Demo

A device-tracking demonstration: on startup, the program reads the 32-bit serial number from the chip and prints it as an 8-character hex string. It then writes a 4-byte boot counter to user EEPROM address `0x00` (read back the existing value, increment it, write it back), prints the updated count, and loops reading only the UID every 2 seconds — showing that the UID never changes while the counter does. This highlights the chip's two distinct areas: immutable identification and rewritable application storage.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Write cycle time (byte or page) | 5 ms max (3 ms typical) |
| ACK polling interval | ≥ 0 ms (poll immediately after STOP) |
| Max I²C clock (Vcc ≥ 2.5 V) | 400 kHz |
| Max I²C clock (Vcc < 2.5 V) | 100 kHz |
| Bus free time between transmissions (400 kHz mode) | ≥ 1.3 µs |

Drivers must not issue the next command until ACK polling confirms the write cycle is complete.

## Implementation Notes

- **Page boundary wrap:** A page write that overflows an 8-byte page boundary wraps back to the start of that page (not the next page). The `write` method in Full must split data at page boundaries to avoid this.
- **Write-protected range is silent:** Writing to 0x80–0xFF produces an ACK (the device accepts the transaction) but does not alter the data. Drivers should document this and optionally raise an error if a write address is ≥ 0x80.
- **Address pins not connected:** On the 24AA02UID, A0/A1/A2 are internally disconnected. The chip responds to any address in the 0x50–0x57 range. Use `0x50` as the canonical address.
- **ACK polling is the recommended completion check:** The 5 ms maximum write time is a worst-case; ACK polling avoids unnecessary delays in typical conditions.
- **No ACK during write cycle:** The device does not generate any ACK while the internal write cycle is running. The host must not mistake a missing ACK for a missing device.

## Sigrok Decoder

The `24aa02uid` sigrok decoder operates on an I²C capture. It matches the fixed device address `0x50` (or `0x50`–`0x57` for decoder flexibility) and annotates: write operations (address byte, data bytes), read operations (distinguishing random read from sequential read), the ACK-polling sequence (start + control byte with R/W=0 and missing ACK), and the write-protected region access at 0x80–0xFF. For reads that cover 0xFA–0xFF, the decoder annotates the manufacturer code, device code, and the 32-bit UID value as a formatted hex string. Input is the I²C decoded protocol (`i2c` decoder stack). Decoder ID: `24aa02uid`.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/memory/24aa02uid.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/memory/24aa02uid/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/memory/24aa02uid/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/memory/24aa02uid/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/memory/24aa02uid_test.py` (MicroPython)
- [ ] Tests `python/tests/memory/24aa02uid_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/memory/24aa02uid_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/memory/24aa02uid.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/memory/24aa02uid.cpp`
- [ ] Examples `cpp/examples/24AA02UID_Minimal/24AA02UID_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/24AA02UID_Complete/24AA02UID_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/24AA02UID_Demo/24AA02UID_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/24AA02UID_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/24AA02UID_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/24AA02UID_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/memory/24aa02uid_test/24aa02uid_test.ino` (Arduino)
- [ ] Tests `cpp/tests/memory/24aa02uid_test_linux/24aa02uid_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/memory/24aa02uid_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/memory/24aa02uid.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/memory/24aa02uid/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/memory/24aa02uid/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/memory/24aa02uid/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/memory/24aa02uid_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-memory/nodes/24aa02uid/24aa02uid.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-memory/nodes/24aa02uid/24aa02uid.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-memory/examples/24aa02uid/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/memory/24aa02uid.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/24aa02uid_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/24aa02uid_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/24aa02uid_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/memory/24aa02uid_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/memory/24aa02uid_test_esp32s3/src/main.rs` (ESP32-S3)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/memory/24AA02UIDMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/memory/24AA02UIDFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/memory/24AA02UIDMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/memory/24AA02UIDFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/memory/24AA02UIDMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/memory/24AA02UIDFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/memory/24aa02uid/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/memory/24aa02uid/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/memory/24aa02uid/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/memory/24aa02uid/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/memory/24aa02uid/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/memory/24aa02uid/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/memory/24aa02uid/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/memory/24aa02uid/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/memory/24aa02uid/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/memory/24aa02uid/24AA02UIDTest.java` (Pi hardware, JBang)

### Sigrok
- [ ] Decoder `sigrok/24aa02uid/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/24aa02uid/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` only

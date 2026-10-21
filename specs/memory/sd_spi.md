# Chip Spec: SD card (SPI mode)

**Manufacturer:** SD Association (generic; any SD / SDHC / SDXC card or microSD in a socket / breakout)  
**Datasheet:** `datasheets/memory/sd_spi.pdf` (SD Physical Layer Specification; SPI mode is §7)  
**Category:** memory  
**Transports:** SPI

## Overview

Every SD card can be run on a plain SPI bus ("SPI mode"), selected by holding CS low while sending CMD0 after power-up. Only a subset of the native SD command set exists in SPI mode, commands are 6-byte frames with a CRC7, responses are R1/R2/R3/R7, and data moves in 512-byte blocks framed by start/stop tokens and an (optional) CRC16. This spec covers SD v1 (SDSC), SD v2 (SDSC/SDHC/SDXC) and microSD; it does **not** cover MMC or eMMC, the native 4-bit SD bus, or UHS modes. The same protocol core is reused by the SD NAND spec (`specs/memory/sd_nand.md`).

The driver implements `BlockDevice` (`specs/feature_block_device.md`): 512-byte blocks, no erase needed. It also uses the SPI optional extensions `idle_clocks` and `set_speed` (`specs/transport_spi.md`).

## Transport Configuration

### SPI
- **Mode:** CPOL=0 CPHA=0 (Mode 0); data latched on the rising SCK edge
- **Clock:** ≤ 400 kHz (the spec requires 100–400 kHz) until init completes, then up to 25 MHz (card dependent; default 12 MHz)
- **Bit order:** MSB first
- **CS active:** low
- **MOSI must be held high (`0xFF`) whenever the host is only reading.** A card interprets `0x00`-led traffic as a command start; connections must clock `0xFF` for the dummy bytes of every `read`/`write_read` read phase (not leave the buffer or bus at an arbitrary value).
- **Register addressing:** none. The driver takes the bare `Connection`.
- **3.3 V only.** Cards are not 5 V tolerant; a bare breakout without level shifting must be used on 3.3 V MCUs only. DAT1/DAT2 (unused in SPI mode) are left unconnected; CD (card detect) is an optional `InputPin`.

### Command frame

`0x40 | cmd`, 4 argument bytes (big-endian), `(CRC7 << 1) | 1`. CRC is checked only for CMD0 and CMD8 until `CMD59` enables it; the driver always sends correct CRC7, so enabling is cheap.

| Command | Code | Argument | Response | Use |
|---------|------|----------|----------|-----|
| `CMD0` GO_IDLE_STATE | 0 | 0 | R1 | software reset into SPI mode (CRC `0x95`) |
| `CMD8` SEND_IF_COND | 8 | `0x000001AA` | R7 | v2 check: voltage 2.7–3.6 V, echo pattern `0xAA` (CRC `0x87`); illegal command (R1 bit 2) ⇒ v1 card |
| `CMD9` SEND_CSD | 9 | 0 | R1 + 16-byte data block | capacity |
| `CMD10` SEND_CID | 10 | 0 | R1 + 16-byte data block | identification |
| `CMD12` STOP_TRANSMISSION | 12 | 0 | R1b | ends multi-block read |
| `CMD16` SET_BLOCKLEN | 16 | 512 | R1 | SDSC only |
| `CMD17` READ_SINGLE_BLOCK | 17 | address | R1 + data | |
| `CMD18` READ_MULTIPLE_BLOCK | 18 | address | R1 + data… | |
| `CMD24` WRITE_BLOCK | 24 | address | R1 | |
| `CMD25` WRITE_MULTIPLE_BLOCK | 25 | address | R1 | |
| `CMD32` ERASE_WR_BLK_START | 32 | address | R1 | optional Full erase |
| `CMD33` ERASE_WR_BLK_END | 33 | address | R1 | |
| `CMD38` ERASE | 38 | 0 | R1b | |
| `CMD55` APP_CMD | 55 | 0 | R1 | prefix for ACMD |
| `CMD58` READ_OCR | 58 | 0 | R3 | CCS bit 30 ⇒ block addressing |
| `CMD59` CRC_ON_OFF | 59 | 1/0 | R1 | |
| `ACMD23` SET_WR_BLK_ERASE_COUNT | 23 | count | R1 | pre-erase before multi-block write |
| `ACMD41` SD_SEND_OP_COND | 41 | `0x40000000` (HCS) for v2, `0` for v1 | R1 | repeat until R1 = `0x00` |

Addressing: SDHC/SDXC (CCS = 1) use the **block number**; SDSC uses the **byte address** (`block × 512`).

### Responses and tokens

- **R1:** bit 7 = 0; bit 0 idle, 1 erase reset, 2 illegal command, 3 command CRC error, 4 erase sequence error, 5 address error, 6 parameter error. R1b is R1 followed by busy (card holds MISO low until ready).
- **R7/R3:** R1 followed by 4 bytes.
- **Read data token:** `0xFE` (single and each block of a multi-read), then 512 data bytes + CRC16. Error token `0b000xxxxx` (bit 0 error, 1 CC error, 2 card ECC failed, 3 out of range).
- **Write data tokens:** `0xFE` (CMD24 and CMD25 block start), `0xFC` (CMD25 block start), `0xFD` (CMD25 stop). Data response byte after each block: `xxx0sss1` — `010` accepted, `101` CRC error, `110` write error. Then busy (MISO low) until programming completes.

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| CS (DAT3) | low | chip select; must be low during CMD0 to enter SPI mode |
| SCK (CLK) | — | clock |
| MOSI (CMD) | — | host → card; held high when idle |
| MISO (DAT0) | — | card → host; weak pull-up recommended (≈ 50 kΩ) |
| CD | low (switch to GND, board-dependent) | optional `InputPin`; level read via `is_high()` |
| WP | high | optional; driver exposes `info().read_only` only if wired |

No interrupt pins.

## Memory Map

Not register-mapped. Capacity from the CSD register (16 bytes, read with CMD9), identification from the CID register.

### CSD / CID fields used

| Field | Bits | Notes |
|-------|------|-------|
| `CSD_STRUCTURE` | CSD[127:126] | 0 = v1 (SDSC), 1 = v2 (SDHC/SDXC) |
| CSD v2 `C_SIZE` | CSD[69:48] | capacity = `(C_SIZE + 1) × 512 KiB`, i.e. `block_count = (C_SIZE + 1) × 1024` |
| CSD v1 `C_SIZE`, `C_SIZE_MULT`, `READ_BL_LEN` | CSD[73:62], [49:47], [83:80] | `capacity = (C_SIZE + 1) << (C_SIZE_MULT + 2) << READ_BL_LEN` |
| CID `MID`, `OID`, `PNM`, `PRV`, `PSN`, `MDT` | CID[127:0] | manufacturer, name, revision, serial, date |
| OCR `CCS` | OCR[30] | 1 = block addressing |
| OCR voltage window | OCR[23:15] | must include 3.3 V |

## Initialization Sequence

1. Set SCK to ≤ 400 kHz (`set_speed(400000)`). CS high.
2. `idle_clocks(80)` — at least 74 clocks with CS high.
3. CS low, `CMD0` (expect R1 = `0x01`). Retry up to 10 times; failure ⇒ `NotReady` (no card).
4. `CMD8 0x1AA`: R7 echo `0xAA` and voltage bits ⇒ v2 card (`hcs = 1`); illegal-command ⇒ v1 card (`hcs = 0`); any other mismatch ⇒ `NotReady`.
5. Loop `ACMD41` (`CMD55` + `CMD41` with `hcs << 30`) until R1 = `0x00`, timeout 1 s ⇒ `Timeout`.
6. v2 only: `CMD58`; OCR bit 30 ⇒ block addressing, else byte addressing and `CMD16 512`.
7. `CMD59 1` if CRC is enabled (default on for Full, off for Minimal).
8. `set_speed(25000000)` (or the configured `speed`), clamped to the platform's achievable maximum.
9. Read CSD (`CMD9`) to compute `block_count`.

## Interrupt

None (card-detect is a plain level signal, not an interrupt).

## Implementation Stages

### Minimal

Class `SDSPIMinimal` — works out of the box: init above (CRC off), then 512-byte blocks.

| Method | Description |
|--------|-------------|
| `SDSPIMinimal(connection, cd_pin=None)` | Runs the init sequence; raises `NotReady` / `Timeout` |
| `read_block(block, buf)` | `CMD17` + token + 512 B + CRC16 read-and-discard |
| `write_block(block, data)` | `CMD24` + `0xFE` + 512 B + dummy CRC + data-response check + busy wait |
| `block_count()` | From the CSD |
| `sector_count()` | Alias of `block_count()` (FatFs terminology) |

### Full

Class `SDSPIFull(SDSPIMinimal)` — adds and **implements `BlockDevice`**.

| Method | Description |
|--------|-------------|
| `read(block, count)` | `CMD18`…`CMD12` multi-block read; `count == 1` falls back to `CMD17` |
| `write(block, data)` | `CMD25` with `ACMD23` pre-erase hint for ≥ 2 blocks; `0xFD` stop token; `CMD13`-less busy wait |
| `erase(block, count)` | `CMD32/33/38`; best-effort, optional (some cards ignore) |
| `sync()` | wait until MISO is high (`0xFF`) |
| `block_size()` | 512 |
| `erase_size()` | 512 (`needs_erase()` false) |
| `info()` | `block_size`, `block_count`, `erase_size`, `needs_erase = false`, `read_only` (WP pin), `bad_blocks = false`, `name` from CID PNM |
| `cid()`, `csd()` | 16-byte registers and a decoded struct |
| `ocr()`, `card_type()` | OCR word; `SD_V1`, `SD_V2`, `SDHC` |
| `set_crc(enabled)` | `CMD59` and verify CRC16 on every read; `Crc` error on mismatch |
| `set_speed(hz)` | forwards to the SPI connection |
| `is_present()` | card-detect level, or a `CMD0`-less probe (`CMD13`) |

## Data Conversion

| Quantity | Formula |
|----------|---------|
| Capacity (bytes, v2) | `(C_SIZE + 1) × 524288` |
| `block_count` (v2) | `(C_SIZE + 1) × 1024` |
| CRC7 | polynomial `x⁷ + x³ + 1`, over the first 5 command bytes, result `<< 1 | 1` |
| CRC16 | CCITT, polynomial `0x1021`, initial value `0`, over the 512 data bytes |

## Node-RED

One node `sd-spi`: config = SPI bus/device; on input message `{op: "read"|"write"|"info", block, payload}`; output `msg.payload` as a `Buffer`, or the `info()` object. Block reads/writes are rate-limited by the caller. Demo flow: read block 0 on a button inject and display the first 16 bytes.

### Demo flow

`examples/sd_spi/demo.json`: inject → read block 0 → debug.

## Examples

### Demo

**Scenario:** log sensor samples to a raw block range on an SD card, then read them back — the card as a minimal data logger, filesystem-free.

1. Construct `SDSPIFull` on the SPI connection, print `info()` (name, capacity).
2. Write a 512-byte block pattern at block 2048 (above the usual first partition table region), `sync()`.
3. Read it back and compare; print the verified byte count and time per block.
4. Optional (MicroPython only): mount `VfsFat` through the `BlockDeviceVFS` shim and write a text file.

## Timing Constraints

| Item | Value |
|------|-------|
| Power-up | ≥ 1 ms after VDD stable, then ≥ 74 clocks with CS high |
| `ACMD41` init loop | up to 1 s |
| Read data token wait | up to 100 ms (SDSC) / 100 ms (SDHC) |
| Write busy | up to 250 ms single block (SDHC), 500 ms (SDSC) |
| Erase busy (`CMD38`) | up to several seconds |
| Between commands | 1 byte (8 clocks) of `0xFF` with CS high after each response to release MISO |

## Implementation Notes

- Read the R1 response by clocking `0xFF` up to 8 times (Ncr ≤ 8 bytes) until bit 7 clears.
- After every transaction, send one extra `0xFF` byte with CS **high** so the card releases MISO (some cards drive until the next clock). The driver must not assume the bus-level `Connection` does this.
- The `Connection` read path must send `0xFF` (not the buffer content, not `0x00`) on MOSI. `SPIConnectionSTM32Cube::_read` currently calls `HAL_SPI_Receive`, which on STM32 in 2-line master mode clocks out the receive buffer — the connection fix lands with the SPI extensions (`read_into`).
- Large transfers use `read_into` to avoid allocation; the C++ driver never mallocs.
- A CRC16 mismatch on read must be reported as `Crc`, not retried silently; a retry policy is the caller's decision (the Full class exposes `set_retries(n)`).
- Block-addressed cards ignore `CMD16`; SDSC cards must receive it once.
- SDSC multi-block writes and `CMD23` (pre-set block count, not used here) differ across cards; `CMD25` + stop token is the portable path.
- Wear levelling and bad blocks are internal to the card; `bad_blocks` is always false.

## Sigrok Decoder

Stack on the built-in `spi` decoder: annotate commands (`CMDn`, argument), R1/R7 responses, data tokens, data blocks (length only) and CRC fields. Annotation conventions per `specs/sigrok_annotations.md`.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/memory/sd_spi.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/memory/sd_spi/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/memory/sd_spi/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/memory/sd_spi/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/memory/sd_spi_test.py` (MicroPython)
- [ ] Tests `python/tests/memory/sd_spi_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/memory/sd_spi_test_linux.py` (Linux)
- [ ] Unit test `python/tests/memory/sd_spi_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Block device (storage chips only)
- [ ] Full class implements `BlockDevice` in every language; passes the shared contract suite

### UIFlow 1
- [ ] Manifest `python/uiflow1/memory/sd_spi/sd_spi.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/memory/sd_spi/sd_spi_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/memory/sd_spi/sd_spi.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/memory/sd_spi/SDSPI.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/memory/sd_spi/SDSPI.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/memory/SD_SPI.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/memory/SD_SPI.cpp`
- [ ] Examples `cpp/examples/arduino/memory/SD_SPI/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/memory/SD_SPI/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/memory/SD_SPI/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/memory/SD_SPI/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/memory/SD_SPI/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/memory/SD_SPI/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/memory/SD_SPI/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/memory/SD_SPI/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/memory/SD_SPI/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/memory/SD_SPI/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/memory/SD_SPI/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/memory/SD_SPI/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/memory/SD_SPI/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/memory/SD_SPI/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/memory/SD_SPI/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/memory/SD_SPI/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/memory/SD_SPI/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/memory/SD_SPI/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/memory/sd_spi_test/sd_spi_test.ino` (Arduino)
- [ ] Tests `cpp/tests/memory/sd_spi_test_linux/sd_spi_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/memory/sd_spi_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/memory/sd_spi_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/memory/sd_spi_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/memory/sd_spi_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/memory/sd_spi_test_unit/sd_spi_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/memory/sd_spi.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/memory/sd_spi/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/memory/sd_spi/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/memory/sd_spi/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/memory/sd_spi_test.js`
- [ ] Unit test `nodejs/tests/memory/sd_spi_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-memory/nodes/sd_spi/sd_spi.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-memory/nodes/sd_spi/sd_spi.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-memory/examples/sd_spi/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/memory/sd_spi.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/memory/sd_spi/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/memory/sd_spi/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/memory/sd_spi/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/memory/sd_spi_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/memory/sd_spi_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/memory/sd_spi.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/memory/sd_spi.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/memory/sd_spi/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/memory/sd_spi/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/memory/sd_spi/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/memory/sd_spi/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/memory/sd_spi/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/memory/sd_spi/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/memory/sd_spi_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/memory/sd_spi_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/memory/sd_spi_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/memory/SD_SPIMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/memory/SD_SPIFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/memory/SD_SPIMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/memory/SD_SPIFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/memory/SD_SPIMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/memory/SD_SPIFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/memory/sd_spi/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/memory/sd_spi/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/memory/sd_spi/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/memory/sd_spi/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/memory/sd_spi/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/memory/sd_spi/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/memory/sd_spi/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/memory/sd_spi/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/memory/sd_spi/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/memory/sd_spi/SDSPITest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/memory/SD_SPITest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/memory/SD_SPITest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/memory/SD_SPISpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/sd_spi/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/sd_spi/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [ ] Checker `conformance/memory/sd_spi_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/memory/sd_spi_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/sd_spi/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

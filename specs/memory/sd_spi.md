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

No timing constraint gets a conformance check: the waits (data token, write busy, erase busy) are card-negotiated and bounded by timeouts, not fixed times, so there is no `sd_spi_timing.conf`.

## Implementation Notes

- Read the R1 response by clocking `0xFF` up to 8 times (Ncr ≤ 8 bytes) until bit 7 clears.
- After every transaction, send one extra `0xFF` byte with CS **high** so the card releases MISO (some cards drive until the next clock). The driver must not assume the bus-level `Connection` does this.
- The `Connection` read path must send `0xFF` (not the buffer content, not `0x00`) on MOSI. `SPIConnectionSTM32Cube::_read` used to call `HAL_SPI_Receive`, which on STM32 in 2-line master mode clocks out the receive buffer; it now fills the buffer with `0xFF` and uses `HAL_SPI_TransmitReceive`. Every SPI connection that an SD driver runs on must do the equivalent.
- Large transfers use `read_into` to avoid allocation; the C++ driver never mallocs.
- A CRC16 mismatch on read must be reported as `Crc`, not retried silently; a retry policy is the caller's decision.
- Linux hosts (spidev) cannot hold chip select across `ioctl` calls, so every Linux connection (Python, C++, Go, Node.js, Java) takes a GPIO chip select, disables the kernel's CS with `SPI_NO_CS` and drives the pin itself. Without a CS pin `idle_clocks` / `select` / `deselect` fail. On a Linux host with a kernel `mmc_spi` driver, use that driver instead of this one.
- Rust: the SD drivers are generic over `SpiExtensions` (not `SpiDevice`); `SpiManualCs` builds one from an `embedded-hal` `SpiBus` and a GPIO chip select.
- Block-addressed (SDHC/SDXC) cards were exercised only against the simulated card in the unit tests; the hardware bench used a byte-addressed 2 GB SDSC card (v2).
- Block-addressed cards ignore `CMD16`; SDSC cards must receive it once.
- SDSC multi-block writes and `CMD23` (pre-set block count, not used here) differ across cards; `CMD25` + stop token is the portable path.
- Wear levelling and bad blocks are internal to the card; `bad_blocks` is always false.

## Sigrok Decoder

Stack on the built-in `spi` decoder (CS, MOSI and MISO required): annotate commands (`CMDn` / `ACMDn`, argument, checked CRC7), R1/R2/R3/R7 responses (OCR decoded), start/stop/error tokens, the write data response, data blocks with a checked CRC16 and one-line CSD / CID / boot-signature decodings. State is kept across CS assertions. Clocks sent with CS high (the 74+ init clocks) are invisible to the `spi` decoder. Annotation conventions per `specs/sigrok_annotations.md`.

## Implementation Checklist

Tick each box as the item is committed.

### Python
- [x] Driver `python/periph/chips/memory/sd_spi.py` — Google-style docstring on every class and public method
- [x] Storage `python/periph/storage/block_device.py`, `ram_block_device.py` — `BlockDevice`, errors, `RamBlockDevice`, `BlockDeviceVFS`
- [x] SPI extensions in `spi_micropython.py`, `spi_circuitpython.py`, `spi_linux.py` (GPIO CS), `spi_mock.py`
- [x] Examples `python/examples/memory/sd_spi/minimal.py` — Tier-1 signature comment on every call
- [x] Examples `python/examples/memory/sd_spi/complete.py` — Tier-1 + Tier-2
- [x] Examples `python/examples/memory/sd_spi/demo.py` — Tier-1 + Tier-3
- [x] Tests `python/tests/memory/sd_spi_test.py` (MicroPython)
- [x] Tests `python/tests/memory/sd_spi_test_cp.py` (CircuitPython)
- [x] Tests `python/tests/memory/sd_spi_test_linux.py` (Linux)
- [x] Unit test `python/tests/memory/sd_spi_test_unit.py` — simulated SD card behind the SPI connection interface

### UIFlow 1
- [x] Manifest `python/uiflow1/memory/sd_spi/sd_spi.json` — `Periph` category, `#C084FC` color
- [x] Blocks `python/uiflow1/memory/sd_spi/sd_spi_*.py`
- [x] Generated `python/uiflow1/memory/sd_spi/sd_spi.m5b` — `python/uiflow1/generate.sh`

### UIFlow 2
- [x] Wrapper class `python/uiflow2/memory/sd_spi/SDSPI.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color
- [x] `python/uiflow2/memory/sd_spi/SDSPI.m5b2` — rebuilt from the wrapper with the same rules as the hand-built siblings (byte-identical to `SRAM23X1024.m5b2` when applied to its wrapper); **not yet round-tripped through the real UiFlow 2 Block Designer**

### C++
- [x] Driver `cpp/src/chips/memory/SD_SPI.h` / `SD_SPI.cpp` — Doxygen on every class and public method
- [x] `cpp/src/storage/BlockDevice.h`, `RamBlockDevice.h`; `cpp/src/connection/SPIExtensions.h`
- [x] SPI extensions in `SPIConnection` (Arduino), `SPIConnectionLinux` (GPIO CS), `SPIConnectionZephyr`, `SPIConnectionESPIDF` (`setManualCS`, `enableSpeedChange`), `SPIConnectionPicoSDK`, `SPIConnectionSTM32Cube`
- [x] Examples `cpp/examples/arduino/memory/SD_SPI/{minimal,complete,demo}/*.ino`
- [x] `cpp/src/Periph.h` regenerated; `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega`
- [x] Examples `cpp/examples/linux/memory/SD_SPI/{minimal,complete,demo}/main.cpp`
- [x] Examples `cpp/examples/zephyr/memory/SD_SPI/{minimal,complete,demo}/main.cpp`
- [x] Examples `cpp/examples/espidf/memory/SD_SPI/{minimal,complete,demo}/main/main.cpp`
- [x] Examples `cpp/examples/picosdk/memory/SD_SPI/{minimal,complete,demo}/src/main.cpp`
- [x] Examples `cpp/examples/stm32cube/memory/SD_SPI/{minimal,complete,demo}/Core/Src/main.cpp`
- [x] Tests `sd_spi_test/sd_spi_test.ino` (Arduino), `sd_spi_test_linux/sd_spi_test_linux.cpp`, `sd_spi_test_zephyr`, `sd_spi_test_espidf`, `sd_spi_test_picosdk`, `sd_spi_test_stm32cube` under `cpp/tests/memory/`
- [x] Unit test `cpp/tests/memory/sd_spi_test_unit/sd_spi_test_unit.cpp` — simulated SD card, run via `test_linux.sh --level unit`

### Node.js
- [x] Driver `nodejs/packages/periph/src/chips/memory/sd_spi.js` — JSDoc on every class and exported method
- [x] `nodejs/packages/periph/src/storage/block_device.js`, `ram_block_device.js`; SPI extensions in `connection/spi.js` (GPIO CS) and `spi_mock.js`
- [x] Examples `nodejs/packages/periph/examples/memory/sd_spi/{minimal,complete,demo}.js`
- [x] Tests `nodejs/tests/memory/sd_spi_test.js`
- [x] Unit test `nodejs/tests/memory/sd_spi_test_unit.js`

### Node-RED
- [x] Node runtime `nodejs/packages/node-red-contrib-periph-memory/nodes/sd_spi/sd_spi.js`
- [x] Node editor `nodejs/packages/node-red-contrib-periph-memory/nodes/sd_spi/sd_spi.html`
- [x] Demo flow `nodejs/packages/node-red-contrib-periph-memory/examples/sd_spi/demo.json`

### Rust
- [x] Driver `rust/periph/src/chips/memory/sd_spi.rs` — `//!` module doc + `///` on every `pub` item
- [x] `rust/periph/src/storage/` (`BlockDevice`, `RamBlockDevice`); `SpiExtensions` + `SpiManualCs` in `rust/periph/src/connection/spi.rs`
- [x] Examples `rust/examples/linux/memory/sd_spi/{minimal,complete,demo}/src/main.rs`
- [x] Tests `rust/tests/memory/sd_spi_test/src/main.rs` (Linux), `rust/tests/memory/sd_spi_test_esp32s3/src/main.rs` (ESP32-S3)
- [x] Unit tests `#[cfg(test)] mod tests` colocated in `sd_spi.rs`, `storage/ram.rs` and `connection/spi.rs`

### Go
- [x] Driver `go/periph/chips/memory/sdspi.go` — Go doc comment on every exported type and method
- [x] `go/periph/storage/` (`BlockDevice`, `RamBlockDevice`); `go/periph/connection/spi_ext.go`, `spi_linux.go` (GPIO CS), `spi_tinygo.go`
- [x] Examples `go/examples/linux/memory/sd_spi/{minimal,complete,demo}`, `go/examples/tinygo/memory/sd_spi/{minimal,complete,demo}`
- [x] Tests `go/tests/memory/sd_spi_test/main.go` (host), `go/tests/memory/sd_spi_test_tinygo/main.go` (TinyGo)
- [x] Unit test `go/periph/chips/memory/sdspi_test.go`, `go/periph/storage/ram_test.go`

### JVM
- [x] Drivers `SdSpiMinimal` / `SdSpiFull` in `jvm/periph-java`, `jvm/periph-kotlin`, `jvm/periph-groovy` (`it.uhde.periph.chips.memory`)
- [x] `it.uhde.periph.storage` (`BlockDevice`, `BlockInfo`, `BlockDeviceException`, `RamBlockDevice`) in `periph-java` and `periph-groovy`; `SPIExtensions` in `periph-connection` (`SPIConnection` with GPIO CS, `MockConnection` records calls)
- [x] Examples `jvm/examples/{java,kotlin,groovy}/memory/sd_spi/{Minimal,Complete,Demo}`
- [x] Tests `jvm/tests/memory/sd_spi/SdSpiTest.java` (Pi hardware, JBang)
- [x] Unit tests `SdSpiTest.java` (JUnit), `SdSpiTest.kt` (JUnit5), `SdSpiSpec.groovy` (Spock) — simulated SD card

### Sigrok
- [x] Decoder `sigrok/sd_spi/__init__.py` — module docstring describing transport input and what is annotated
- [x] Decoder `sigrok/sd_spi/pd.py` — annotates commands, responses, tokens, data blocks and CRCs; produces `OUTPUT_ANN` and `OUTPUT_PYTHON`
- [x] `sigrok/tests/sd_spi/SDSPI-synthetic.sr` — synthetic capture (driver run against the simulated card); a bench capture is still to be added

### Block device
- [x] The Full class implements `BlockDevice` in every language and shares the contract suite with the RAM mock (`specs/feature_block_device.md`)

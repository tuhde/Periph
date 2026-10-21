# Feature: BlockDevice

## Problem

The `memory` category only holds byte-addressable chips (23X1024 SRAM, 24AA02UID EEPROM,
MB85RC FRAM), each with its own ad-hoc `read` / `write` / `fill` / `size` API. The next
chips — SD card (SPI mode), SD NAND, SPI NAND and SPI NOR — are **block** devices whose
purpose is to carry a filesystem (FAT, LittleFS, …). Each has a different native unit
(SD: 512 B sectors with transparent erase; NOR: 256 B pages / 4 KiB erase sectors; NAND:
2 KiB pages / 128 KiB erase blocks with bad blocks and ECC), so every filesystem would need
chip-specific glue.

Periph does **not** implement a filesystem. It defines one **block-device contract** that
the four chip families implement, so existing filesystem stacks (MicroPython `vfs`, FatFs,
LittleFS, Zephyr `disk_access`, `embedded-sdmmc`, `embedded-storage`) bind through a thin,
reusable adapter instead of per-chip code.

## Design goals

1. One contract for all four chips; a RAM-backed mock implements it for tests.
2. Honest geometry: the interface exposes the erase unit instead of hiding it, so
   LittleFS (erase-aware) and FAT (erase-unaware) can both be served.
3. NAND bad blocks and ECC failures surface as typed errors, never silent corruption.
4. Follows the Minimal / Full rule: the **Minimal** class stays the raw chip API; only the
   **Full** class implements `BlockDevice`.
5. No dependence on a concrete bus: implementations sit on a bare `Connection`
   (`RegisterConnection` does not fit opcode + address framing, see
   `specs/feature_register_access_design.md` §11).

## Contract

Units are **bytes** and **block indices**. A *block* is the read/program unit exposed to the
filesystem (the *logical block*, 512 B on SD; one page, or a fixed multiple, on NOR/NAND).

| Member | Description |
|--------|-------------|
| `block_size()` | Logical block size in bytes (read/program unit) |
| `block_count()` | Number of addressable logical blocks (good blocks only on NAND, after BBT scan) |
| `erase_size()` | Erase unit in bytes; a multiple of `block_size()`. SD / SD NAND: equals `block_size()` and `needs_erase()` is false |
| `needs_erase()` | `true` if a block must be erased before it is programmed (NOR, NAND) |
| `read(block, count) → bytes` | Read `count` consecutive blocks |
| `write(block, data)` | Program `len(data)/block_size()` consecutive blocks. Caller guarantees the range was erased when `needs_erase()`; see `auto_erase` below |
| `erase(block, count)` | Erase the erase units covering `[block, block+count)`; range must be erase-unit aligned. No-op on SD |
| `sync()` | Block until all pending program/erase work has finished (WIP / OIP / busy token) |
| `info()` | Struct/dict: `block_size`, `block_count`, `erase_size`, `needs_erase`, `read_only`, `bad_blocks` (bool: device may contain bad blocks), `name` |
| `is_bad(erase_unit)` | Optional (`bad_blocks == true`): factory/runtime bad-block query; always `false` on others |
| `mark_bad(erase_unit)` | Optional: persist a bad-block mark |

**`auto_erase`** — a constructor/property flag on NOR/NAND Full classes (default `false`).
When `true`, `write()` performs erase-before-program (read-modify-write of the containing
erase unit through a one-unit cache) so erase-unaware filesystems such as FAT work
unchanged. When `false`, `write()` on an un-erased block raises `NotErased`. LittleFS
adapters set `false` and call `erase()` itself.

### Errors

A typed error per language (exception subclass / enum / `error` value), same names everywhere:

| Error | Meaning |
|-------|---------|
| `NotReady` | Device absent or init failed (no card, bad JEDEC ID) |
| `OutOfRange` | Block index / count beyond `block_count()`, or unaligned `erase()` |
| `NotErased` | Program to un-erased block while `auto_erase == false` |
| `WriteProtected` | Card WP / status-register block protection set |
| `BadBlock` | Target erase unit is marked bad (NAND) |
| `Ecc` | Uncorrectable ECC error on read (NAND); correctable flips are reported via `info`-level counters, not as errors |
| `Crc` | SD command/data CRC mismatch |
| `Timeout` | Busy never cleared |
| `Io` | Transport failure (wrapped) |

C++ builds without exceptions: methods return a `BlockError` status enum (`Ok = 0`) and fill
output through pointers, consistent with existing C++ drivers.

## Per-language binding

| Language | Form | Location |
|----------|------|----------|
| Python | Duck-typed protocol class `BlockDevice` with the members above; MicroPython/CircuitPython shim `BlockDeviceVFS(dev)` adds `readblocks(n, buf, off=0)`, `writeblocks(n, buf, off=0)`, `ioctl(op, arg)` (ops 1 INIT, 2 DEINIT, 3 SYNC, 4 COUNT, 5 SECTOR_SIZE, 6 ERASE_BLOCK) so `vfs.mount(vfs.VfsFat(dev), "/sd")` / `VfsLfs2` work | `python/periph/storage/block_device.py`, `python/periph/storage/ram_block_device.py` (mock) |
| C++ | `class BlockDevice` with pure virtual methods; `BlockError` enum; `RamBlockDevice` mock | `cpp/src/storage/BlockDevice.h`, `RamBlockDevice.h` |
| Node.js | Base class `BlockDevice` (methods throw `NotImplemented`), `BlockError` subclasses; async (Promise) like existing SPI I/O; `RamBlockDevice` | `nodejs/packages/periph/src/storage/` |
| Rust | `trait BlockDevice { type Error; … }`; `RamBlockDevice`. NOR also implements `embedded_storage::nor_flash::{ReadNorFlash, NorFlash}`, SD also implements `embedded_sdmmc::BlockDevice` | `rust/periph/src/storage/` |
| Java | `interface BlockDevice`, `BlockDeviceException` with `Kind` enum; Kotlin and Groovy implement the Java interface directly (cross-language base pattern as for the NeoPixel LED family) | `jvm/periph-java/…/it/uhde/periph/storage/` (see decision below) |
| Go | `type BlockDevice interface`, sentinel errors (`ErrNotReady`, …) with `errors.Is`; `RamBlockDevice` | `go/periph/storage/` |

Java location decision: the interface lives in **periph-java** (`it.uhde.periph.storage`),
not `periph-connection`, because it is a chip-layer concept; Kotlin depends on periph-java
already, Groovy keeps its own copy (never add periph-java as a Maven dependency of
periph-groovy, see known-bug note).

## Adapters (follow-up work, not part of the chip PRs)

| Adapter | Platforms | Maps onto |
|---------|-----------|-----------|
| MicroPython VFS | MicroPython, CircuitPython | `BlockDeviceVFS` shim above |
| FatFs `diskio` | ESP-IDF, Pico SDK, STM32Cube, Arduino | `disk_read/write/ioctl` ↔ `read/write/sync/info` |
| LittleFS `lfs_config` | all C++ platforms | `read/prog/erase/sync` ↔ `read/write/erase/sync`; `block_size = erase_size()` |
| Zephyr | Zephyr | `disk_access` (SD) / `flash` API (NOR/NAND) |
| Linux | Linux | none (kernel already has MTD / mmc) |

## Chip mapping

| Chip | `block_size` | `erase_size` | `needs_erase` | `bad_blocks` |
|------|-------------|--------------|---------------|--------------|
| SD card (SPI) / SD NAND | 512 | 512 | false | false |
| SPI NOR | 512 (4 KiB sector = 8 blocks) | 4096 | true | false |
| SPI NAND | 2048 (page) | 128 KiB (64 pages) | true | true |

## Required SPI transport additions (decide in Phase 1)

The SD card SPI-mode init requires ≥74 clocks with CS **deasserted**, and the clock must
rise from ≤400 kHz to ≥ 20 MHz after init. The current `Connection` API
(`write` / `read` / `write_read`, CS hidden) can do neither. Proposed minimal extension to
`specs/transport_spi.md` (SPI connections only; I²C unaffected):

| Addition | Semantics |
|----------|-----------|
| `idle_clocks(n)` | Clock `n` bits of `0xFF` with CS high |
| `set_speed(hz)` | Change SCK frequency at run time |
| `read_into(buf)` (non-Python) | Fill a caller buffer without allocating, for 512 B+ transfers |

These are a prerequisite PR; no chip PR may work around them with chip-private bit-bang.

## Spec and AGENTS.md updates

- `specs/_template_chip.md`: add an optional "Block device" section (geometry table above)
  and an Implementation Checklist row "BlockDevice (Full class)".
- `AGENTS.md`: a "Block devices" section — only Full classes implement it; Minimal remains raw.
- `CLAUDE.md` repository layout: add `storage/` next to `connection/` in the Python, C++,
  Node.js, Rust, Go (and Java) trees.
- Wiki: a "Storage / BlockDevice" page; per-chip pages link to it.

## Tests

- Unit: RAM mock passes a shared contract suite (read-after-write, range errors, erase
  alignment, `auto_erase` on/off) in each language; each chip Full class re-runs the same
  suite against an SPI fake (simulated NOR / NAND / SD state machine).
- Hardware-in-loop: write a block, `sync()`, read back, then mount FAT (SD) or LittleFS
  (NOR/NAND) via the MicroPython shim as end-to-end proof.

## Order of work

1. This feature spec + SPI transport extension (prep PR, then implementation PR with
   `BlockDevice`, mocks and contract suite in all languages).
2. `spi_nor` → `spi_nand` → `sd_spi` → `sd_nand` (each against the merged contract).
3. Adapters as separate issues.

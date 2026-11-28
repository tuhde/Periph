# Chip Spec: ST25DV (ST25DV04K / ST25DV16K / ST25DV64K)

**Manufacturer:** STMicroelectronics  
**Datasheet:** `datasheets/rfid/st25dv.pdf` (ST25DV04K, ST25DV16K, ST25DV64K Dynamic NFC/RFID tag IC, DS10925 Rev 12; one datasheet for the three densities)  
**Category:** rfid  
**Transports:** I²C

## Overview

The ST25DV family are dynamic NFC/RFID tag ICs: an ISO/IEC 15693 / NFC Forum Type 5 tag with a 4-, 16- or 64-Kbit EEPROM (512 B, 2 KiB, 8 KiB) that is accessible both from a **contactless RF reader** (13.56 MHz) and, in parallel, from a host microcontroller over a **two-wire I²C** interface (up to 1 MHz). The user memory is split into up to four areas with independent RF and I²C access protection by 64-bit passwords. Further features: a fast-transfer-mode (FTM) **mailbox** — a 256-byte half-duplex buffer to pass messages between the RF reader and the host — an open-drain/CMOS **GPO** pin that signals RF events (field change, RF write, mailbox put/get, activity) to the host, an energy-harvesting power output (`V_EH`) and a low-power-down input (`LPD`). Memory sizes: ST25DV04K 512 B (device `IC_REF = 0x24`), ST25DV16K 2 KiB and ST25DV64K 8 KiB (`IC_REF = 0x26`; the size is read from `MEM_SIZE`). Supply 1.8–5.5 V (VCC), write time about 5 ms per I²C write (up to 256 bytes per page). Typical uses: NFC-readable device settings and logs, tap-to-configure and tap-to-pair, parameter and firmware hand-over between smartphone and MCU, asset/ID tags with a host.

## Transport Configuration

### I²C
- **Device select code:** `1010 E2 1 1 RW` — the 4-bit device type `1010`, a chip-enable bit `E2` (not a pin; it selects the memory area) and `11`:
  - `E2 = 0`: **user memory, dynamic registers and the mailbox** → 7-bit address **`0x53`** (8-bit `0xA6` write / `0xA7` read)
  - `E2 = 1`: **system configuration area** (static registers, passwords, UID, `MEM_SIZE`, …) → 7-bit address **`0x57`** (8-bit `0xAE` / `0xAF`)

  So the chip occupies two I²C addresses, and the driver needs a connection for each (the user/dynamic area at `0x53` and the system area at `0x57`)
- **Max clock:** 1 MHz (Fast-mode Plus)
- **Register addressing:** **16-bit** address, MSB first (`reg_bytes = 2`); multi-byte reads and writes auto-increment; a write may cover up to 256 bytes within one page; the internal write cycle takes about 5 ms (the chip does not ACK during it: poll with ACK polling or wait 5 ms)
- **Identity register:** `IC_REF` (system area `0x0017`) = `0x24` (ST25DV04K) or `0x26` (ST25DV16K/64K), read at address `0x57`; registered in `registry/chips.json` as `id_probe` register `0x0017`, `reg_bytes` 2, `length` 1, mask `0xFF`, expected `[0x24, 0x26]` for the **system address `0x57`** (the `0x53` user-area address has no ID register)
- **Probe safety:** `register_pointer` — a 2-byte write only sets the address pointer; a third byte would be data written to EEPROM, so probe with a 2-byte write + 1-byte read only

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| GPO | low (-IE) / high (-JF) | interrupt/event output, configured by `GPO`/`GPO_CTRL_Dyn`: the **-IE** variants have an open-drain output that pulls low (external pull-up > 4.7 kΩ required), the **-JF** variants a CMOS output that goes high (needs `VDCG` supplied) |
| LPD | high | low-power-down input (10-ball and 12-pin packages only, internal pull-down): high forces low-power-down; tie to GND for normal operation |
| VDCG | — | supply of the GPO driver (10-ball/12-pin packages; required for the CMOS -JF output) |
| V_EH | — | energy-harvesting power output (available when an RF field is present, controlled by `EH_*`) |
| AC0, AC1 | — | RF antenna pins |
| SDA, SCL | — | I²C; pull-ups required |
| VCC, VSS | — | 1.8–5.5 V |

## Memory and Register Map

All addresses are 16-bit; "E2 = 0" is the device select at `0x53`, "E2 = 1" at `0x57`.

### User memory (E2 = 0)

`0x0000`–`0x01FF` (04K), `0x0000`–`0x07FF` (16K), `0x0000`–`0x1FFF` (64K); the last byte address is `MEM_SIZE × BLK_SIZE − 1`. Divided into up to four areas by `ENDA1`–`ENDA3` (area 1 starts at 0).

### Dynamic registers (E2 = 0)

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x2000` | GPO_CTRL_Dyn | R/W | copy of `GPO` | runtime copy of the GPO register; only bit 7 `GPO_EN` writable without an open security session |
| `0x2002` | EH_CTRL_Dyn | R/W | `0x00` (+ power-source bits) | energy-harvesting control and power status |
| `0x2003` | RF_MNGT_Dyn | R/W | `0x00` | RF interface state (runtime) |
| `0x2004` | I2C_SSO_Dyn | R | `0x00` | bit 0: I²C security session open |
| `0x2005` | IT_STS_Dyn | R | `0x00` | interrupt status, **cleared on read** |
| `0x2006` | MB_CTRL_Dyn | R/W | `0x00` | fast-transfer-mode control and status |
| `0x2007` | MB_LEN_Dyn | R | — | length of the mailbox message minus 1 |
| `0x2008`–`0x2107` | MB_Dyn | R/W | — | fast transfer mode buffer, 256 bytes (a write must start at `0x2008`) |

### System configuration (E2 = 1; writable only with the I²C security session open and the register not locked)

| Address | Name | Description |
|---------|------|-------------|
| `0x0000` | GPO | enables the GPO interrupt sources (`GPO_EN` bit 7 …) |
| `0x0001` | IT_TIME | GPO pulse duration: `301 µs − IT_TIME × 37.65 µs` (bits 2:0, reset `011`) |
| `0x0002` | EH_MODE | bit 0: 0 = energy harvesting forced after boot, 1 = on demand only (reset 1) |
| `0x0003` | RF_MNGT | RF state after power-on (bit 0 `RF_DISABLE`, bit 1 `RF_SLEEP`) |
| `0x0004`/`0x0006`/`0x0008`/`0x000A` | RFA1SS … RFA4SS | RF access protection of area 1–4 |
| `0x0005`/`0x0007`/`0x0009` | ENDA1 … ENDA3 | end of area 1–3 with a granularity of 8 blocks (32 bytes): area *n* ends at byte `ENDAn × 32 + 31` |
| `0x000B` | I2CSS | I²C access protection of areas 1–4 (2 bits per area) |
| `0x000C` | LOCK_CCFILE | write protection of blocks 0 and 1 against RF writes |
| `0x000D` | MB_MODE | bit 0: fast transfer mode allowed |
| `0x000E` | MB_WDG | mailbox watchdog: `2^(MB_WDG − 1) × 30 ms ± 6 %` |
| `0x000F` | LOCK_CFG | protect system configuration against RF writes |
| `0x0010`/`0x0011` | LOCK_DSFID/LOCK_AFI | lock status (RO from I²C) |
| `0x0012`/`0x0013` | DSFID / AFI | ISO 15693 DSFID and AFI values (RO from I²C) |
| `0x0014`–`0x0015` | MEM_SIZE | number of RF blocks minus 1 (2 bytes, LSB first: 04K `0x7F 0x00`, 16K `0xFF 0x01`, 64K `0xFF 0x07`) |
| `0x0016` | BLK_SIZE | block size in bytes minus 1 (`0x03` = 4 bytes) |
| `0x0017` | IC_REF | `0x24` (ST25DV04K), `0x26` (ST25DV16K/64K) |
| `0x0018`–`0x001F` | UID | 8-byte unique ID (read-only); first byte `0xE0`, second byte `0x02` (ST) |
| `0x0020` | IC_REV | IC revision |
| `0x0900`–`0x0907` | I2C_PWD | I²C password (8 bytes, factory `00…00`); read/write only through the password commands below |

#### `GPO` / `GPO_CTRL_Dyn` bits

| Bit | Name | Description |
|-----|------|-------------|
| 7 | GPO_EN | GPO output enabled (reset 1); 0 = output off (interrupts are still reported in `IT_STS_Dyn`) |
| 6 | RF_WRITE_EN | pulse when the RF reader finished a valid write to EEPROM |
| 5 | RF_GET_MSG_EN | pulse when the RF reader read the end of a mailbox message |
| 4 | RF_PUT_MSG_EN | pulse when the RF reader wrote a mailbox message |
| 3 | FIELD_CHANGE_EN | pulse when the RF field appears or disappears (reset 1) |
| 2 | RF_INTERRUPT_EN | GPO pulse controlled by the RF "Manage GPO" command |
| 1 | RF_ACTIVITY_EN | GPO level follows RF command activity |
| 0 | RF_USER_EN | GPO level controlled by the RF "Manage GPO" set/reset |

#### `IT_STS_Dyn` bits (cleared on read)

`b7` RF_WRITE, `b6` RF_GET_MSG, `b5` RF_PUT_MSG, `b4` FIELD_RISING, `b3` FIELD_FALLING, `b2` RF_INTERRUPT, `b1` RF_ACTIVITY, `b0` RF_USER.

#### `EH_CTRL_Dyn` bits

`b3` VCC_ON (VCC present, LPD low), `b2` FIELD_ON (RF field present), `b1` EH_ON (energy harvesting active), `b0` EH_EN (enable energy harvesting).

#### `MB_CTRL_Dyn` bits

`b7` RF_CURRENT_MSG, `b6` HOST_CURRENT_MSG, `b5` RF_MISS_MSG, `b4` HOST_MISS_MSG, `b3` reserved, `b2` RF_PUT_MSG (the reader wrote a message), `b1` HOST_PUT_MSG (the host wrote a message), `b0` MB_EN (enable fast transfer mode; reset 0; cleared automatically if `MB_MODE` is 0).

### Password commands (I²C security session)

- **Present password** (opens the session; a wrong password closes it): write to device select `0x57` with address bytes `0x09, 0x00`, then **8 password bytes (MSB first), the validation code `0x09`, and the 8 password bytes again** (17 data bytes); the chip compares after the STOP. Success sets `I2C_SSO_Dyn = 0x01`
- **Write password** (change `I2C_PWD`, only with an open session): the same sequence with the validation code `0x07` and the new password twice (the datasheet's I2C write-password command)
- A password present attempt with a wrong value closes the session; power removal also closes it

## Initialization Sequence

1. Power up VCC (1.8–5.5 V) with `LPD` low; wait for the chip (≥ 1 ms) — the I²C interface is available when `EH_CTRL_Dyn.VCC_ON = 1`.
2. Read the identification: system area `IC_REF` (`0x0017`), `MEM_SIZE` (`0x0014`–`0x0015`), `BLK_SIZE` (`0x0016`) at device `0x57`.
3. For user memory access use device `0x53`: write a 2-byte address, then data (≤ 256 bytes per page, within the page boundary: a write wraps at the 256-byte page end), wait 5 ms or ACK-poll; read with a repeated start.
4. To change the system configuration: present the I²C password (default `00 00 00 00 00 00 00 00`), write the registers at `0x57`, then re-read to verify.

## Interrupt

| Property | Value |
|----------|-------|
| INT pin | `GPO` — -IE: open-drain, active-low (external pull-up > 4.7 kΩ); -JF: CMOS, active-high; pulse duration set by `IT_TIME` |
| Level | 2 (selectable sources) |
| Condition(s) | RF field change, RF write, mailbox put/get by the RF reader, RF activity and RF-"Manage GPO" events |
| Clear mechanism | read `IT_STS_Dyn` (`0x2005`); pulse events end by themselves |

### Interrupt sources

| Constant | Value | Condition |
|----------|-------|-----------|
| `SOURCE_RF_USER` | `0x01` | `IT_STS_Dyn.RF_USER` (Manage GPO set/reset) |
| `SOURCE_RF_ACTIVITY` | `0x02` | RF access |
| `SOURCE_RF_INTERRUPT` | `0x04` | Manage GPO interrupt request |
| `SOURCE_FIELD_FALLING` | `0x08` | RF field disappeared |
| `SOURCE_FIELD_RISING` | `0x10` | RF field appeared |
| `SOURCE_RF_PUT_MSG` | `0x20` | RF reader wrote a mailbox message |
| `SOURCE_RF_GET_MSG` | `0x40` | RF reader read the mailbox message |
| `SOURCE_RF_WRITE` | `0x80` | RF reader wrote to EEPROM |

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback, int_pin=None)` | Subscribe on GPO (falling edge for -IE, rising edge for -JF) via the user connection's `int_pin` (or `int_pin`), otherwise a polling task calling `poll_interrupt` (10 ms; Go: the 5 ms `PollingInputPin`); `callback(status)` receives the non-zero `IT_STS_Dyn` value. C++ subscribes only when a pin is present; Rust has `poll_interrupt` only |
| `off_interrupt` | `off_interrupt()` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> int` | Reads `IT_STS_Dyn` (clears it), returns its `SOURCE_*` bits (0 = none) |
| `enable_interrupt` | `enable_interrupt(source)` | Sets the matching enable bits in the static `GPO` register (session required; bits 0–6 of `GPO_CTRL_Dyn` are read-only over I²C, and the chip copies `GPO` into `GPO_CTRL_Dyn`) plus `GPO_EN`, then sets `GPO_CTRL_Dyn.GPO_EN`. Source → GPO bit mapping: bits 0–2 unchanged, `FIELD_RISING`/`FIELD_FALLING` → `FIELD_CHANGE_EN` (bit 3, shared), `RF_PUT_MSG`/`RF_GET_MSG`/`RF_WRITE` (bits 5–7) → GPO bits 4–6 |
| `disable_interrupt` | `disable_interrupt(source)` | Clears the mapped bits in the static `GPO` register (session required); disabling either field source clears `FIELD_CHANGE_EN` for both |

## Implementation Stages

### Minimal

Goal: use the tag as a persistent byte store that is also readable by an NFC phone: read and write user memory, with no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `user: Connection` (address `0x53`), `system: Connection` (address `0x57`) | — | Stores the register-addressed connections (16-bit register addresses); reads `IC_REF`, `MEM_SIZE`, `BLK_SIZE` from the system area and derives the memory size in bytes (`(MEM_SIZE + 1) × (BLK_SIZE + 1)`) |
| `read` | `address: int`, `length: int` | `bytes` | Reads `length` bytes of user memory starting at `address` (raises when outside the memory) |
| `write` | `address: int`, `data: bytes` | — | Writes `data` to user memory, split into writes that do not cross 256-byte pages, waiting 5 ms (or ACK polling) after each page |

**Sensible defaults:** memory area boundaries, RF/I²C protection and GPO as delivered (area 1 = the whole memory, no protection, `GPO_EN = 1`, field change interrupt only).

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `memory_size` | — | int | Size of the user memory in bytes |
| `ic_reference` | — | int | `IC_REF` (`0x24`/`0x26`) |
| `uid` | — | `bytes` (8) | `UID` from the system area, returned most significant byte first (`E0 02 …`; stored LSB first at `0x0018`) |
| `ic_revision` | — | int | `IC_REV` |
| `read_system` / `write_system` | `address: int, length: int` / `address: int, data: bytes` | bytes / — | Raw system-area access (needs an open session for writes) |
| `present_password` | `password: bytes (8)` | bool | Runs the I²C present-password sequence; returns whether the session opened (`I2C_SSO_Dyn`) |
| `write_password` | `password: bytes (8)` | — | Changes the I²C password (session must be open) |
| `session_open` | — | bool | `I2C_SSO_Dyn.I2C_SSO` |
| `set_area_end` | `area: int` (1–3), `end_block: int` | — | `ENDA1..3` |
| `set_area_protection` | `area: int` (1–4), `rf: int`, `i2c: int` | — | `rf` = the `RFAnSS` value 0–15 (bits 3:2 RW protection, bits 1:0 the RF password that opens the area), `i2c` = the area's 2-bit `I2CSS` level 0–3 (read-modify-write of `I2CSS`) |
| `get_area_protection` | `area: int` | `(rf, i2c)` | `RFAnSS & 0x0F` and the area's `I2CSS` level |
| `set_gpo` / `get_gpo` | `sources: int, enable: bool = True` / — | — / int | Static `GPO` register (system area, session required): `sources` are the `GPO_*` enable bits 0–6, `enable` sets `GPO_EN` |
| `set_gpo_runtime` | `enable: bool` | — | `GPO_CTRL_Dyn.GPO_EN` only (no session); bits 0–6 of `GPO_CTRL_Dyn` are read-only over I²C |
| `set_interrupt_pulse` | `value: int` (0–7) | — | `IT_TIME` |
| `field_present` | — | bool | `EH_CTRL_Dyn.FIELD_ON` |
| `set_energy_harvesting` | `enabled: bool, forced: bool = False` | — | `EH_CTRL_Dyn.EH_EN`; `EH_MODE` (0 = forced, 1 = on demand) is written only when it differs, so the call needs a session only when `forced` changes |
| `set_rf_state` | `disable: bool = False, sleep: bool = False` | — | `RF_MNGT_Dyn` (runtime only); persist via `write_system(0x0003, …)` |
| `enable_mailbox` / `disable_mailbox` | — | — | enable: writes `MB_MODE` (static, session required) only if it is 0, then sets `MB_CTRL_Dyn.MB_EN`; disable: clears `MB_EN` only (`MB_MODE` stays) |
| `mailbox_status` | — | dict | `MB_CTRL_Dyn` decoded (`enabled`, `host_put`, `rf_put`, `host_miss`, `rf_miss`, `host_current`, `rf_current`) |
| `mailbox_write` | `data: bytes` (1–256) | — | Writes the message to `0x2008…` in one transaction; raises when `MB_EN` is 0 or a message is pending (`HOST_PUT_MSG`/`RF_PUT_MSG`) |
| `mailbox_read` | — | `bytes` | Reads `MB_LEN_Dyn + 1` bytes from `0x2008…` (16-byte reads); empty when no message is pending |
| `set_mailbox_watchdog` | `value: int` | — | `MB_WDG` |
| `lock_ccfile` | `lock: bool = True` | — | `LOCK_CCFILE` (blocks 0/1 RF write protection); irreversible without an RF configuration session |
| `write_ndef` | `message: bytes` | — | Writes an NFC Forum Type 5 capability container followed by a TLV `03 <len> <message> FE` (`03 FF <hi> <lo> …` for 255 bytes and more) to user memory (a minimal NDEF writer). CC: 4-byte `E1 40 <MLEN> 01` up to 2 KiB with `MLEN = min(size / 8, 0xFF)` (04K: `0x40`, 16K: `0xFF`), 8-byte `E2 40 00 01 00 00 <MLEN hi> <MLEN lo>` above with `MLEN = size / 8 − 1` (64K: `0x03FF`). Raises when the image does not fit |
| `read_ndef` | — | `bytes` | Parses the CC (`E1` → 4 bytes, `E2` → 8 bytes), skips NULL and other TLVs, returns the first NDEF TLV (`0x03`) message or empty |
| `wait_write_complete` | — | — | ACK polling on `0x53` |
| interrupt API | | | see Interrupt section |

**Additional configuration options:** memory areas and protection, passwords, GPO sources and pulse, energy harvesting, RF state, mailbox/FTM mode and watchdog.

## Data Conversion

```
memory_bytes = (MEM_SIZE + 1) * (BLK_SIZE + 1)      # e.g. ST25DV16K: 512 blocks x 4 bytes = 2048
mb_message_bytes = MB_LEN_Dyn + 1                   # 1..256

gpo_pulse_us = 301 - IT_TIME * 37.65                # IT_TIME 0..7, +/- 2 us

mb_watchdog_s = 2 ** (MB_WDG - 1) * 0.030           # +/- 6 %, MB_WDG = 0 disables the watchdog (datasheet)
```

`MEM_SIZE` is stored as two bytes (little-endian) holding the number of blocks minus one; `BLK_SIZE` holds the block size in bytes minus one (4-byte blocks: `0x03`). The NDEF/CC layout follows the NFC Forum Type 5 Tag specification (the first 4 or 8 bytes of user memory are the capability container).

## Node-RED

Node name: `st25dv` (config node `st25dv-device`)  
Package: `node-red-contrib-periph-rfid`

| Input `msg.payload` | Output `msg.payload` fields | Notes |
|---------------------|-----------------------------|-------|
| `{ address, length }` | `{ address, data }` | reads user memory (`length` default 16, `data` byte array) |
| `{ address, data }` | `{ address, written }` | writes user memory (`data` array or hex string) |
| `{ ndef }` | `{ ndef }` | writes an NDEF message (`data` array or hex string) and returns the stored message as a byte array; `{ ndef: true }` only reads it |

Config panel fields: I²C bus number, memory device address (`0x53`), system device address (`0x57`).

### Demo flow

Inject node (hex string "Hello NFC") → `periph-st25dv` (write) → debug node; second inject (read) → `periph-st25dv` → debug node showing the bytes.

## Examples

### Demo

**NFC tag with host mailbox.** With the Full driver print `IC_REF`, UID and memory size, write a text record with `write_ndef` (a phone reads it) and read it back, read/write raw bytes across a page boundary, define two memory areas with different I²C/RF protection (with the password session), change and restore the I²C password, enable GPO field-change and RF-write interrupts and print them with `on_interrupt` while a phone approaches, then enable the fast transfer mode and exchange messages with `mailbox_write` / `mailbox_read` / `mailbox_status`, show `field_present` and energy-harvesting control, and finish by restoring the defaults. Exercises `init`, `read`, `write`, `memory_size`, `ic_reference`, `uid`, `ic_revision`, `read_system`, `write_system`, `present_password`, `write_password`, `session_open`, `set_area_end`, `set_area_protection`, `get_area_protection`, `set_gpo`, `get_gpo`, `set_interrupt_pulse`, `field_present`, `set_energy_harvesting`, `set_rf_state`, `enable_mailbox`, `disable_mailbox`, `mailbox_status`, `mailbox_write`, `mailbox_read`, `set_mailbox_watchdog`, `lock_ccfile`, `write_ndef`, `read_ndef`, `wait_write_complete`, `on_interrupt`, `off_interrupt`, `poll_interrupt`, `enable_interrupt`, `disable_interrupt`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| EEPROM write time (I²C, one page) | ≈ 5 ms typ. (no ACK during it) |
| Page size for an I²C write | 256 bytes (do not cross the boundary) |
| GPO pulse duration | `301 µs − IT_TIME × 37.65 µs` (± 2 µs) |
| Mailbox watchdog | `2^(MB_WDG − 1) × 30 ms` (± 6 %) |
| I²C clock | ≤ 1 MHz |

The write time can be observed on the bus as a NACK period, but the datasheet gives it as a typical value only, so `st25dv_timing.conf` defines no checks.

## Implementation Notes

- **Two I²C addresses:** the driver takes two connections (`0x53` user/dynamic, `0x57` system) built on the same bus; both use 16-bit register addresses (`reg_bytes = 2`). Document this clearly in the examples (the second connection is easy to forget).
- **Register-addressed connection:** accept `RegisterConnection` with `reg_bytes = 2` and use `read(reg, n)` / `write(reg, data)` directly; no chip-local helpers (see `specs/feature_register_access_design.md`).
- **Page writes:** an I²C write of up to 256 bytes within one 256-byte page is allowed; a write across the page boundary wraps around inside the page. Split writes in the driver and wait for the write cycle (ACK polling or 5 ms) after each page.
- **Concurrent RF access:** if an RF reader is writing the EEPROM, the I²C access is held off (and vice versa); the chip returns NACK/busy during RF writes. The driver retries before raising.
- **Security session:** system configuration is writable only after presenting the I²C password and only for registers not locked by `LOCK_CFG`; user-memory areas can be write/read protected per area for I²C (`I2CSS`) independent of RF. A wrong password closes the session; a lost password cannot be recovered (the chip still needs the password to reset it).
- **Dynamic registers:** `GPO_CTRL_Dyn`, `RF_MNGT_Dyn`, `EH_CTRL_Dyn` are volatile and are reloaded from the static registers at power-up (and whenever the static register is updated); changing the static register persists the setting, the dynamic one only lasts until power-down.
- **Mailbox rules:** enable `MB_MODE` (static) first, then `MB_CTRL_Dyn.MB_EN`; a write to the mailbox must start at `0x2008`; read starts at any address from `0x2008`; the message is released when the RF reader has read it to the end or when the watchdog expires (`HOST_MISS_MSG`/`RF_MISS_MSG`).
- **NDEF:** the Full-driver NDEF helpers only write a single NDEF TLV with the Type 5 capability container; encoding records (URI, text) is left to the caller.
- **Address overlap:** `0x53` also appears for 24xx-family EEPROMs (`0x50`–`0x57`) and other parts; `0x57` is shared with EEPROMs and the DS3231 module's AT24C32. The `IC_REF` register at `0x57` identifies the ST25DV; probe it only with the 2-byte-write/1-byte-read ID sequence.
- **Variants:** the ST25DV04K-IE/JF, 16K and 64K differ only in memory size, `IC_REF` and the `ENDAn` granularity; the **-IE** (open-drain, active-low GPO) and **-JF** (CMOS, active-high GPO) variants differ in the GPO output polarity, so the driver takes a `gpo_active_high` option (default low); the driver reads sizes from the chip.

### Implementation details

- **Transfers:** user-memory reads and writes are split into transfers of at most 16 bytes aligned to 16-byte boundaries. They never cross a 256-byte page or a (32-byte granular) area border, and they fit the 32-byte Arduino Wire buffer and the 32-byte data limit of the C++/Rust register helpers. The EEPROM programming time is the same as for page-sized writes, since it depends on the number of 4-byte internal pages.
- **Write completion:** every EEPROM write (user memory, system registers, `write_password`) is ACK-polled with an address-only write (1 ms interval, 50 ms timeout, `wait_write_complete` uses the same poll on `0x53`). `present_password` ACK-polls `0x57` without raising on a timeout and then reads `I2C_SSO_Dyn`; dynamic-register and mailbox writes are not polled (programmed at the ACK).
- **Concurrent RF access:** every register read is retried up to 3 times, 5 ms apart, before the bus error is raised.
- **Init:** Python, Go and the JVM read `MEM_SIZE`/`BLK_SIZE`/`IC_REF` (one 4-byte read at `0x0014`) in the constructor; C++ and Rust have `init()` (no bus traffic in the constructor, Arduino-safe), Node.js has `async init()`. The driver does not reject unknown `IC_REF` values.
- **Password:** `write_password` checks `I2C_SSO_Dyn` first and raises when the session is closed.
- **Mailbox on C++:** `mailbox_write` sends the whole message in one transaction (up to 258 bytes on the bus); on AVR Arduino boards the 32-byte Wire buffer limits a message to 30 bytes. C++ `mailbox_read`/`read_ndef` copy into a caller buffer and return the length (0 when none or when it does not fit).
- **Rust:** `St25dvMinimal<I2C, D>` / `St25dvFull<I2C, D>` own the bus and a `DelayNs` provider (ACK polling, read retries) and take both addresses: `new(i2c, delay, 0x53, 0x57)`; no `gpo_active_high` argument, since there is no edge subscription.
- **Errors:** Python raises `ValueError` (range/arguments) and `OSError` (bus, timeout, closed session, mailbox state); Node.js `RangeError` / `Error`; JVM `IllegalArgumentException` / `IOException`; C++ returns `false` / 0; Rust `Error::{Bus, OutOfRange, InvalidArgument, Timeout, SessionClosed, MailboxDisabled, MailboxBusy, BufferTooSmall}`; Go `ErrST25DVRange`, `ErrST25DVArgument`, `ErrST25DVTimeout`, `ErrST25DVSessionClosed`, `ErrST25DVMailboxDisabled`, `ErrST25DVMailboxBusy`.
- **UIFlow:** the UIFlow 1 and UIFlow 2 blocks wrap `ST25DVFull` with fixed addresses `0x53`/`0x57`; byte data is passed as hex strings, `mailbox_status`/`get_area_protection` are split into single-value blocks.

## Sigrok Decoder

Decoder id `st25dv`, input `['i2c']`. Matches I²C addresses `0x53` (user memory, dynamic registers, mailbox) and `0x57` (system area). Annotates each access with the 16-bit address and its name (user memory offset, dynamic register, mailbox byte, system register), decodes `GPO`/`GPO_CTRL_Dyn`, `IT_STS_Dyn`, `EH_CTRL_Dyn`, `MB_CTRL_Dyn`, `I2C_SSO_Dyn`, `MEM_SIZE`/`BLK_SIZE`/`IC_REF`/`UID`, and the password-present sequence (`0x0900` with the `0x09` validation code and the repeated password; the password bytes are marked as sensitive); warns on writes crossing a 256-byte page, on a write inside the 5 ms write cycle (NACKed address) and on system-area writes without a preceding password presentation. No conformance annotation pair is emitted (no checked timing constraint).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] Driver `python/periph/chips/rfid/st25dv.py` — Google-style docstring on every class and public method
- [x] Examples `python/examples/rfid/st25dv/minimal.py` — Tier-1 signature comment on every call
- [x] Examples `python/examples/rfid/st25dv/complete.py` — Tier-1 + Tier-2
- [x] Examples `python/examples/rfid/st25dv/demo.py` — Tier-1 + Tier-3
- [x] Tests `python/tests/rfid/st25dv_test.py` (MicroPython)
- [x] Tests `python/tests/rfid/st25dv_test_cp.py` (CircuitPython)
- [x] Tests `python/tests/rfid/st25dv_test_linux.py` (Linux)
- [x] Unit test `python/tests/rfid/st25dv_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [x] Manifest `python/uiflow1/rfid/st25dv/st25dv.json` — `Periph` category, `#C084FC` color
- [x] Blocks `python/uiflow1/rfid/st25dv/st25dv_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [x] Generated `python/uiflow1/rfid/st25dv/st25dv.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [x] Wrapper class `python/uiflow2/rfid/st25dv/St25dv.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [x] Exported `python/uiflow2/rfid/st25dv/St25dv.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [x] Driver `cpp/src/chips/rfid/St25dv.h` — Doxygen `/** @brief */` on every class and public method
- [x] Driver `cpp/src/chips/rfid/St25dv.cpp`
- [x] Examples `cpp/examples/arduino/rfid/St25dv/minimal/minimal.ino` — Tier-1
- [x] Examples `cpp/examples/arduino/rfid/St25dv/complete/complete.ino` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/arduino/rfid/St25dv/demo/demo.ino` — Tier-1 + Tier-3
- [x] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [x] Examples `cpp/examples/linux/rfid/St25dv/minimal/main.cpp` — Tier-1
- [x] Examples `cpp/examples/linux/rfid/St25dv/complete/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/linux/rfid/St25dv/demo/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/zephyr/rfid/St25dv/minimal/main.cpp` — Tier-1
- [x] Examples `cpp/examples/zephyr/rfid/St25dv/complete/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/zephyr/rfid/St25dv/demo/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/espidf/rfid/St25dv/minimal/main/main.cpp` — Tier-1
- [x] Examples `cpp/examples/espidf/rfid/St25dv/complete/main/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/espidf/rfid/St25dv/demo/main/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/picosdk/rfid/St25dv/minimal/src/main.cpp` — Tier-1
- [x] Examples `cpp/examples/picosdk/rfid/St25dv/complete/src/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/picosdk/rfid/St25dv/demo/src/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/stm32cube/rfid/St25dv/minimal/Core/Src/main.cpp` — Tier-1
- [x] Examples `cpp/examples/stm32cube/rfid/St25dv/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/stm32cube/rfid/St25dv/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [x] Tests `cpp/tests/rfid/st25dv_test/st25dv_test.ino` (Arduino)
- [x] Tests `cpp/tests/rfid/st25dv_test_linux/st25dv_test_linux.cpp` (Linux GCC)
- [x] Tests `cpp/tests/rfid/st25dv_test_zephyr/src/main.cpp` (Zephyr)
- [x] Tests `cpp/tests/rfid/st25dv_test_espidf/main/main.cpp` (ESP-IDF)
- [x] Tests `cpp/tests/rfid/st25dv_test_picosdk/src/main.cpp` (Pico SDK)
- [x] Tests `cpp/tests/rfid/st25dv_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [x] Unit test `cpp/tests/rfid/st25dv_test_unit/st25dv_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [x] Driver `nodejs/packages/periph/src/chips/rfid/st25dv.js` — JSDoc on every class and exported method
- [x] Examples `nodejs/packages/periph/examples/rfid/st25dv/minimal.js` — Tier-1
- [x] Examples `nodejs/packages/periph/examples/rfid/st25dv/complete.js` — Tier-1 + Tier-2
- [x] Examples `nodejs/packages/periph/examples/rfid/st25dv/demo.js` — Tier-1 + Tier-3
- [x] Tests `nodejs/tests/rfid/st25dv_test.js`
- [x] Unit test `nodejs/tests/rfid/st25dv_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [x] Node runtime `nodejs/packages/node-red-contrib-periph-rfid/nodes/st25dv/st25dv.js`
- [x] Node editor `nodejs/packages/node-red-contrib-periph-rfid/nodes/st25dv/st25dv.html` — `data-help-name` section with inputs, outputs, and config description
- [x] Demo flow `nodejs/packages/node-red-contrib-periph-rfid/examples/st25dv/demo.json` — tab `info` field describes the scenario

### Rust
- [x] Driver `rust/periph/src/chips/rfid/st25dv.rs` — `//!` module doc + `///` on every `pub` item
- [x] Examples `rust/examples/linux/rfid/st25dv/minimal/src/main.rs` — Tier-1
- [x] Examples `rust/examples/linux/rfid/st25dv/complete/src/main.rs` — Tier-1 + Tier-2
- [x] Examples `rust/examples/linux/rfid/st25dv/demo/src/main.rs` — Tier-1 + Tier-3
- [x] Tests `rust/tests/rfid/st25dv_test/src/main.rs` (Linux)
- [x] Tests `rust/tests/rfid/st25dv_test_esp32s3/src/main.rs` (ESP32-S3)
- [x] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/rfid/st25dv.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [x] Driver `go/periph/chips/rfid/st25dv.go` — Go doc comment on every exported type and method
- [x] Examples `go/examples/linux/rfid/st25dv/minimal/minimal.go` — Tier-1 signature comment on every call
- [x] Examples `go/examples/linux/rfid/st25dv/complete/complete.go` — Tier-1 + Tier-2
- [x] Examples `go/examples/linux/rfid/st25dv/demo/demo.go` — Tier-1 + Tier-3
- [x] Examples `go/examples/tinygo/rfid/st25dv/minimal/minimal.go` — Tier-1 (TinyGo)
- [x] Examples `go/examples/tinygo/rfid/st25dv/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [x] Examples `go/examples/tinygo/rfid/st25dv/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [x] Tests `go/tests/rfid/st25dv_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [x] Tests `go/tests/rfid/st25dv_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [x] Unit test `go/periph/chips/rfid/st25dv_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [x] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/rfid/St25dvMinimal.java` — Javadoc on every class and public method
- [x] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/rfid/St25dvFull.java` — Javadoc on every class and public method
- [x] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/rfid/St25dvMinimal.kt` — KDoc on every class and public method
- [x] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/rfid/St25dvFull.kt` — KDoc on every class and public method
- [x] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/rfid/St25dvMinimal.groovy` — Groovydoc on every class and public method
- [x] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/rfid/St25dvFull.groovy` — Groovydoc on every class and public method
- [x] Examples `jvm/examples/java/rfid/st25dv/Minimal.java` — Tier-1
- [x] Examples `jvm/examples/java/rfid/st25dv/Complete.java` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/java/rfid/st25dv/Demo.java` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/kotlin/rfid/st25dv/Minimal.kt` — Tier-1
- [x] Examples `jvm/examples/kotlin/rfid/st25dv/Complete.kt` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/kotlin/rfid/st25dv/Demo.kt` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/groovy/rfid/st25dv/Minimal.groovy` — Tier-1
- [x] Examples `jvm/examples/groovy/rfid/st25dv/Complete.groovy` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/groovy/rfid/st25dv/Demo.groovy` — Tier-1 + Tier-3
- [x] Tests `jvm/tests/rfid/st25dv/St25dvTest.java` (Pi hardware, JBang)
- [x] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/rfid/St25dvTest.java` (JUnit)
- [x] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/rfid/St25dvTest.kt` (Kotest/JUnit5)
- [x] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/rfid/St25dvSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [x] Decoder `sigrok/st25dv/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [x] Decoder `sigrok/st25dv/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [x] Checker `conformance/rfid/st25dv_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [x] Timing config `specs/rfid/st25dv_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [x] Decoder `sigrok/st25dv/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

# Chip Spec: MCP4XXX (MCP453X / 454X / 455X / 456X / 463X / 464X / 465X / 466X)

**Manufacturer:** Microchip Technology  
**Datasheet:** `datasheets/adc_dac/mcp4xxx.pdf` (volatile parts: MCP453X/455X/463X/465X, DS22096B, 2013, supplied by the URL of issue #258) and `datasheets/adc_dac/mcp4xxx_nv.pdf` (non-volatile parts: MCP454X/456X/464X/466X, DS22107B, 2013, supplied as an attachment to issue #258)  
**Category:** `adc_dac`  
**Transports:** I²C

## Variants

The two datasheets cover **sixteen** parts that share one command format, one I²C protocol, one TCON register and one volatile-wiper behaviour. They differ in resolution, number of resistor networks, pot-vs-rheostat pinout, I²C address scheme and — between the two datasheets — in memory: the volatile parts (453X / 455X / 463X / 465X) power up at mid-scale; the non-volatile parts (454X / 456X / 464X / 466X) add an EEPROM wiper (power-up value), ten general-purpose EEPROM registers, a STATUS register, **WiperLock** and **EEPROM write protect**. One driver family covers all of them; the part is selected by a `part` constructor argument that picks a row of this table (see Implementation Notes — "Family abstraction").

| Part | Networks | Configuration | Resolution | Max code | Memory | Mid-scale / factory wiper | I²C 7-bit address range | Address pins | WP pin |
|------|----------|---------------|------------|----------|--------|---------------------------|--------------------------|--------------|--------|
| MCP4531 | 1 | Potentiometer (A, W, B pins) | 7-bit | 128 (`0x80`) | RAM | `0x40` (POR) | `0x2E`–`0x2F` | A0 | — |
| MCP4532 | 1 | Rheostat (W, B pins; A internal) | 7-bit | 128 | RAM | `0x40` | `0x58`–`0x5B` | A1, A0 | — |
| MCP4551 | 1 | Potentiometer | 8-bit | 256 (`0x100`) | RAM | `0x80` | `0x2E`–`0x2F` | A0 | — |
| MCP4552 | 1 | Rheostat | 8-bit | 256 | RAM | `0x80` | `0x58`–`0x5B` | A1, A0 | — |
| MCP4631 | 2 | Dual potentiometer | 7-bit | 128 | RAM | `0x40` | `0x28`–`0x2F` | A2, A1, A0 | — |
| MCP4632 | 2 | Dual rheostat | 7-bit | 128 | RAM | `0x40` | `0x58`–`0x5B` | A1, A0 | — |
| MCP4651 | 2 | Dual potentiometer | 8-bit | 256 | RAM | `0x80` | `0x28`–`0x2F` | A2, A1, A0 | — |
| MCP4652 | 2 | Dual rheostat | 8-bit | 256 | RAM | `0x80` | `0x58`–`0x5B` | A1, A0 | — |
| MCP4541 | 1 | Potentiometer | 7-bit | 128 | EEPROM | `0x40` (EEPROM) | `0x2E`–`0x2F` | A0 | — |
| MCP4542 | 1 | Rheostat | 7-bit | 128 | EEPROM | `0x40` | `0x58`–`0x5B` | A1, A0 | — |
| MCP4561 | 1 | Potentiometer | 8-bit | 256 | EEPROM | `0x80` | `0x2E`–`0x2F` | A0 | — |
| MCP4562 | 1 | Rheostat | 8-bit | 256 | EEPROM | `0x80` | `0x58`–`0x5B` | A1, A0 | — |
| MCP4641 | 2 | Dual potentiometer | 7-bit | 128 | EEPROM | `0x40` | `0x28`–`0x2F` | A2, A1, A0 | **yes** |
| MCP4642 | 2 | Dual rheostat | 7-bit | 128 | EEPROM | `0x40` | `0x58`–`0x5B` | A1, A0 | — |
| MCP4661 | 2 | Dual potentiometer | 8-bit | 256 | EEPROM | `0x80` | `0x28`–`0x2F` | A2, A1, A0 | **yes** |
| MCP4662 | 2 | Dual rheostat | 8-bit | 256 | EEPROM | `0x80` | `0x58`–`0x5B` | A1, A0 | — |

Steps = max code + 1 (129 or 257). All parts are ordered with RAB = 5 kΩ (-502), 10 kΩ (-103), 50 kΩ (-503) or 100 kΩ (-104); the wiper resistance is about 75 Ω typical. VDD: volatile parts 1.8–5.5 V (specified performance from 2.7 V), non-volatile parts 2.7–5.5 V (operation from 1.8 V). Volatile parts power up at mid-scale; non-volatile parts power up with the wiper value stored in the EEPROM wiper (factory value: mid-scale; the custom POR options the datasheets mention are out of scope). The "single / dual", "7 / 8-bit" and "pot / rheostat" columns drive the same code in both families; the `Memory` column gates the non-volatile feature set.

## Overview

The MCP4XXX parts are digital potentiometers (X1) and rheostats (X2) with an I²C interface (100 kHz, 400 kHz and 3.4 MHz HS mode). The resistor network is 128 (7-bit) or 256 (8-bit) equal resistors between terminal B and terminal A with the wiper `PxW` selecting any of 129 / 257 taps (code 0 = wiper at `PxB`, full scale = wiper at `PxA`). A potentiometer (X1) brings terminals A, W and B out; a rheostat (X2) leaves terminal A floating internally. Dual parts (46XX) have two independent networks (wiper 0 and wiper 1). The serial interface offers wiper read / write, **increment / decrement commands**, a Terminal Control (TCON) register for disconnecting terminals (shutdown), and an optional I²C **General Call** for synchronised updates of several devices. The digital inputs tolerate up to 12.5 V.

The **non-volatile** parts keep the power-up wiper value in EEPROM (automatic recall at POR/BOR, 1 000 000 write cycles), provide ten 9-bit general-purpose EEPROM registers, a read-only STATUS register (EEPROM-write-active, WiperLock and write-protect flags), **WiperLock** (secures a network's wiper and TCON bits against any change) and an EEPROM **write protect** (a nonvolatile bit and, on the MCP46X1 only, a hardware `WP` pin). WiperLock and the write-protect bit can only be changed with **high-voltage commands**, i.e. with the `HVC/A0` pin driven to 8.5–12.5 V, which needs external circuitry. On the volatile parts wiper values are lost at power-down (POR sets mid-scale).

## Transport Configuration

### I²C
- **Address:** by family member (Variants table): `0101 11 A0` (X1 single), `0101 1 A1 A0` (X2 single and dual), `0101 A2 A1 A0` (46X1 dual pot). The address pin **A0 is multiplexed with the high-voltage-command pin HVC/A0**; its level is latched at POR/BOR. Address pins that a package does not have read as 1.

| Part group | Address bits | 7-bit addresses |
|------------|--------------|-----------------|
| 45X1 (MCP4531, 4541, 4551, 4561) | `0101 11 A0` | `0x2E`, `0x2F` |
| 45X2 / 46X2 (MCP4532, 4542, 4552, 4562, 4632, 4642, 4652, 4662) | `0101 1 A1 A0` | `0x58`–`0x5B` |
| 46X1 (MCP4631, 4641, 4651, 4661) | `0101 A2 A1 A0` | `0x28`–`0x2F` |

- **Max clock:** 400 kHz (the library drives ≤ 400 kHz; standard mode 100 kHz and HS mode 3.4 MHz are also supported by the chip — HS mode needs a master code that `Connection` does not provide)
- **Identity register:** none; chips have no ID register (feeds `registry/chips.json` with `"id_probe": null` and `"driver": null` until implemented, see `specs/feature_i2c_discovery.md`); the sixteen parts cannot be told apart by software
- **Probe safety:** `write_sensitive` — the first byte after the address is a **command byte**, and a command byte with the increment / decrement operation is a complete one-byte command that moves the wiper (`0x04` = increment wiper 0, `0x08` = decrement, `0x14` / `0x18` for wiper 1 on dual parts), so a stray single-byte write can change the resistance; on a non-volatile part with `HVC/A0` high a stray byte could even change WiperLock or write protect (only with the pin at the high-voltage level)
- **Register addressing:** command byte `AD3 AD2 AD1 AD0 C1 C0 D9 D8` (`RegisterConnection`, `reg_bytes = 1`, the command byte plays the role of the register address): `AD` = memory address (`0` wiper 0, `1` wiper 1 on dual parts, `2` / `3` non-volatile wiper 0 / 1, `4` TCON, `5` STATUS, `6`–`0xF` general-purpose EEPROM), `C1:C0` = operation (`00` write, `01` increment, `10` decrement, `11` read), `D9:D8` = the two most significant data bits (D9 unused, D8 used by 8-bit wiper values and by 9-bit register values). Write = `[addr+W] [command byte] [data byte]`; read = `[addr+W] [command byte with C=11] [Sr] [addr+R] [data high] [data low]` — a read returns **two bytes** (`0000 000D8`, `D7…D0`). The chip retains the last memory address received (POR: `0x00`)

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| SDA, SCL | — | I²C (open-drain SDA); high-voltage tolerant up to 12.5 V; the chip does not stretch SCL |
| HVC/A0 | — | Address bit 0 (latched at POR/BOR) **and** high-voltage-command input: at VIHH (8.5–12.5 V) the following commands are high-voltage commands (WiperLock / write-protect changes on the non-volatile parts); tie to GND or VDD for normal use (internal weak "smart" pull-up). A high-voltage command needs an external circuit that drives the pin to VIHH; the library models it as an optional output pin (see Implementation Notes). On the volatile parts it is just address bit 0 |
| A1 | — | Address bit 1 (X2 and 46XX parts); tie to GND or VDD |
| A2 | — | Address bit 2 (46X1 parts only); tie to GND or VDD |
| WP | high | Hardware EEPROM write protect, **MCP46X1 (MCP4641 / MCP4661) only**; blocks all EEPROM writes while high (OR-ed with the nonvolatile WP bit); internal weak "smart" pull-up |
| P0A, P0W, P0B | — | Network 0 terminals (P0A only on the X1 potentiometers) |
| P1A, P1W, P1B | — | Network 1 terminals (dual parts; P1A only on 46X1) |
| VDD, VSS | — | Supply 1.8–5.5 V (2.7 V for specified performance; non-volatile parts specified 2.7–5.5 V) |

There is no reset, interrupt or enable pin. All resistor terminals must stay between VSS and VDD; their polarity relative to each other is not restricted; terminal currents ≤ 2.5 mA.

## Register Map

| Address | Name | R/W | Reset | Parts | Description |
|---------|------|-----|-------|-------|-------------|
| `0x00` | WIPER0 | R/W | mid-scale (volatile parts) / NV wiper 0 (NV parts) | all | Volatile wiper 0 (9-bit field; 7-bit parts use 0–128, 8-bit parts 0–256) |
| `0x01` | WIPER1 | R/W | as above | dual | Volatile wiper 1 |
| `0x02` | NVWIPER0 | R/W | mid-scale | NV | EEPROM wiper 0: loaded into WIPER0 at POR/BOR |
| `0x03` | NVWIPER1 | R/W | mid-scale | NV dual | EEPROM wiper 1 |
| `0x04` | TCON | R/W | `0x1FF` | all | Terminal control register (9 bits, volatile) |
| `0x05` | STATUS | R | `0x1F0 \| flags` | NV | Status: EEPROM write active, WiperLock, write protect (read only) |
| `0x06`–`0x0F` | DATA0–DATA9 | R/W | `0xFF` (factory-programmed) | NV | Ten general-purpose 9-bit EEPROM registers (address `0x0F` is also the write-protect address for high-voltage commands) |

On the volatile parts `0x02`, `0x03`, `0x05` (reads `0x1F7`) and `0x06`–`0x0F` are reserved; `0x01` and `0x03` are invalid on the single-network parts. An invalid address / command combination is **not acknowledged** and every following command is ignored until the master issues a new START. The driver never uses addresses the selected part does not have.

### Bit Fields

#### Command byte

| Bits | Name | Description |
|------|------|-------------|
| 7:4 | AD[3:0] | Memory address (see Register Map) |
| 3:2 | C[1:0] | `00` write data, `01` increment (volatile wipers; high-voltage: WiperLock disable / write-protect disable), `10` decrement (volatile wipers; high-voltage: WiperLock enable / write-protect enable), `11` read data |
| 1 | D9 | Unused (0) |
| 0 | D8 | Bit 8 of the data (8-bit wiper value `0x100`, 9-bit register values) |

#### `WIPER0` / `WIPER1` / `NVWIPER0` / `NVWIPER1` (`0x00`–`0x03`)

| Bits | Name | Description |
|------|------|-------------|
| 8:0 | W | Wiper position. 7-bit parts: `0x000` zero scale (W = B), `0x040` mid-scale, `0x080` full scale (W = A); 8-bit parts: `0x000`, `0x080`, `0x100`. Larger values (up to `0x3FF`) are reserved and also act as full scale; increment commands are ignored at full scale and above, decrement is ignored at `0x000` (volatile wipers only; increment / decrement on a non-volatile address is an error, unless it is a high-voltage WiperLock command) |

#### `TCON` (`0x04`) — reset `0x1FF`, nine bits (D8 from the command byte)

| Bits | Name | Description |
|------|------|-------------|
| 8 | GCEN | 1 = the device accepts General Call commands (address `0x00`) |
| 7 | R1HW | Resistor 1 hardware configuration: 0 = forced into the shutdown configuration (A disconnected, W connected to B), 1 = normal — **dual parts only** (single parts: write 1) |
| 6 | R1A | 1 = terminal P1A connected to network 1 (dual parts) |
| 5 | R1W | 1 = wiper terminal P1W connected (dual parts) |
| 4 | R1B | 1 = terminal P1B connected (dual parts) |
| 3 | R0HW | Resistor 0 hardware configuration: 0 = forced into shutdown, 1 = normal |
| 2 | R0A | 1 = terminal P0A connected to network 0 (internal on rheostats) |
| 1 | R0W | 1 = wiper terminal P0W connected |
| 0 | R0B | 1 = terminal P0B connected |

`RxHW` = 0 overrides `RxA`/`RxW`/`RxB` without changing them or the wiper register; returning `RxHW` to 1 restores the previous state. The serial interface keeps working in shutdown (EEPROM write cycles complete). On the non-volatile parts, when WiperLock is enabled for a network, writes to that network's TCON bits (`RxHW`, `RxA`, `RxW`, `RxB`) are inhibited. At POR/BOR TCON is loaded with `0x1FF` (all terminals connected, General Call enabled) — also on the non-volatile parts (TCON is not stored in EEPROM, so the host must restore a stored shutdown state after every power-up).

#### `STATUS` (`0x05`, non-volatile parts, read only)

| Bits | Name | Description |
|------|------|-------------|
| 8:4 | — | Reserved, forced to 1 |
| 3 | EEWA | 1 = an EEPROM write cycle is in progress (only volatile addresses `0x00`, `0x01`, `0x04`, `0x05` accept commands) |
| 2 | WL1 | 1 = WiperLock active for network 1: volatile and non-volatile wiper 1 and TCON bits R1HW/R1A/R1W/R1B are write-protected |
| 1 | WL0 | 1 = WiperLock active for network 0 (wiper 0, NV wiper 0, TCON bits R0HW/R0A/R0W/R0B) |
| 0 | WP | 1 = EEPROM write-protected (OR of the `WP` pin and the nonvolatile WP bit): all non-volatile writes (NV wipers and general-purpose EEPROM) are blocked, volatile wipers and TCON are not |

`WL0` / `WL1` / `WP` reflect the non-volatile bits (loaded at POR/BOR); they can only be changed with high-voltage commands (see Data Conversion). WiperLock state survives POR/BOR.

## Initialization Sequence

1. Power VDD (1.8–5.5 V; non-volatile parts 2.7–5.5 V). At POR the volatile wiper register(s) are loaded with mid-scale (volatile parts) or with the EEPROM wiper value (non-volatile parts), TCON with `0x1FF` and the address pointer with `0x00`; the device is ready after TBORD (10–20 µs). A0 is latched at POR.
2. `init` performs no writes: the wipers stay at the value the chip holds. It reads `WIPER0` (and `WIPER1` on dual parts) once as a bus check and to cache the wiper codes, and reads `TCON` to cache the terminal state; on non-volatile parts it also reads `STATUS` (caching WiperLock / write-protect state and checking that no EEPROM write cycle is still running).
3. Set the wipers with write commands. On volatile parts keep VDD applied to keep the settings (brown-out below the RAM retention voltage corrupts the registers; below VBOR the interface is disabled). On non-volatile parts `store_wiper` writes the current setting to the EEPROM wiper so it survives power cycles.

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits everything and adds the rest. One Minimal / Full pair serves **all sixteen parts**; the `part` argument selects resolution, channel count, pot / rheostat behaviour, memory type and address default.

### Minimal

Goal: set and read back the wiper position or the W–B resistance of either network (volatile wiper; Minimal never touches the EEPROM).

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection, `part: str = "MCP4532"`, `r_ab: float = 10000.0`, `r_wiper: float = 75.0` | — | `part` ∈ {`"MCP4531"`, `"4532"`, `"4541"`, `"4542"`, `"4551"`, `"4552"`, `"4561"`, `"4562"`, `"4631"`, `"4632"`, `"4641"`, `"4642"`, `"4651"`, `"4652"`, `"4661"`, `"4662"` with the `MCP` prefix} (case-insensitive, an unknown part raises); stores the connection, the part parameters (Variants table), the end-to-end resistance RAB (Ω; 5 / 10 / 50 / 100 kΩ for -502 / -103 / -503 / -104) and the wiper resistance RW (Ω); reads the wiper register(s) and `TCON` (and `STATUS` on non-volatile parts) as a bus check |
| `set_wiper` | `code: int`, `channel: int = 0` | — | Writes command byte `(channel << 4) \| (D8)` and the data byte to the **volatile** wiper; clamps `code` to [0, max code] (128 / 256); `channel` 1 on a single part raises; a locked network (WiperLock) ignores the write |
| `get_wiper` | `channel: int = 0` | `int` | Read command `(channel << 4) \| 0x0C` (two bytes); returns the 9-bit value clamped to the max code |
| `set_resistance` | `ohms: float`, `channel: int = 0` | — | Sets the W–B resistance to the nearest code (inverse of the formula below), clamped to the reachable range |
| `get_resistance` | `channel: int = 0` | `float` Ω | W–B resistance for the current code |

**Sensible defaults:** `part` = `"MCP4532"`, RAB = 10 kΩ, RW = 75 Ω (typical, 5.5 V); wipers at their power-up value (mid-scale, or the stored EEPROM value on non-volatile parts) until written; all terminals connected (TCON `0x1FF`, no shutdown); General Call left at the chip's power-up setting (`GCEN` = 1, enabled — see Implementation Notes); no EEPROM access and no high-voltage command pin.

### Full

Goal: expose complete chip functionality. Extends Minimal. Operations marked **NV** exist only on the non-volatile parts (they raise `UnsupportedOperation` / the platform equivalent on the volatile parts).

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection, `part="MCP4532"`, `r_ab=10000.0`, `r_wiper=75.0`, `hvc=None`, `hvc_settle_ms=1` | — | As Minimal; `hvc` is an optional `OutputPin` that switches an external circuit driving `HVC/A0` to VIHH (needed only for `set_wiper_lock` / `set_write_protect`; ignored on volatile parts); `hvc_settle_ms` is the delay between asserting `hvc` and the first SCL edge (the datasheets require a delay but give no value; driver default 1 ms) |
| *(inherits Minimal)* | | | |
| `channels` | — | `int` | 1 or 2 (property of the part) |
| `steps` | — | `int` | 129 or 257 (property of the part) |
| `is_potentiometer` | — | `bool` | `True` for the X1 parts (terminal A on a pin) |
| `is_nonvolatile` | — | `bool` | `True` for the 454X / 456X / 464X / 466X parts |
| `set_ratio` | `ratio: float` (0.0–1.0), `channel: int = 0` | — | Wiper at `round(ratio × max_code)` — fraction of RAB between W and B (the unloaded divider ratio `V(W−B)/V(A−B)` on the potentiometer parts) |
| `get_ratio` | `channel: int = 0` | `float` | `code / max_code` |
| `get_resistance_wa` | `channel: int = 0` | `float` Ω | Potentiometer parts only: W–A resistance `(max_code − N) / max_code × RAB + RW`; rheostat parts raise (terminal A is not available) |
| `set_resistance_wa` | `ohms: float`, `channel: int = 0` | — | Potentiometer parts only: nearest code for the W–A resistance |
| `get_resistance_step` | — | `float` Ω | One step: `RAB / max_code` |
| `set_both` | `code0: int`, `code1: int` | — | Dual parts: writes wiper 0 and wiper 1 (two transactions, or one continuous write: command byte for wiper 0, data, command byte for wiper 1, data); single parts raise |
| `increment` | `steps: int = 1`, `channel: int = 0` | `int` | Sends `steps` increment commands in one continuous transaction (no data bytes; each command byte increments by 1, saturating at the max code) and returns the new code (read back) |
| `decrement` | `steps: int = 1`, `channel: int = 0` | `int` | Same with decrement commands, saturating at 0 |
| `shutdown` | `channel: int = 0` | — | `TCON.RxHW` = 0: terminal A disconnected, W connected to B (≈ RW); the wiper register is kept |
| `resume` | `channel: int = 0` | — | `TCON.RxHW` = 1 |
| `is_shutdown` | `channel: int = 0` | `bool` | Read from `TCON` (RxHW = 0) |
| `set_terminals` | `a: bool = True`, `w: bool = True`, `b: bool = True`, `channel: int = 0` | — | `TCON.RxA` / `RxW` / `RxB` (disconnect terminals to minimise current); other TCON bits preserved |
| `get_terminals` | `channel: int = 0` | `(a, w, b)` | From `TCON` |
| `get_tcon` | — | `int` | Raw 9-bit `TCON` value |
| `set_general_call` | `enabled: bool` | — | `TCON.GCEN`; when enabled the chip answers the General Call address (`0x00`) commands "write wiper 0 / 1", "write TCON", "increment / decrement wiper 0 / 1" |
| `reset` | — | — | Emulates POR: writes every volatile wiper with its power-up value (mid-scale on volatile parts, the EEPROM wiper value on non-volatile parts) and `TCON` `0x1FF`; networks locked by WiperLock are skipped (the chips have no reset pin or command) |
| `read_register` | `address: int` | `int` | Raw 9-bit read of an address the part has |
| `write_register` | `address: int`, `value: int` | — | Raw write of a writable address the part has (volatile wipers 0–0x3FF, TCON 0–0x1FF; on NV parts also NV wipers and DATA, see below); `STATUS` raises |
| `set_rab` | `r_ab: float`, `r_wiper: float \| None = None` | — | Changes the resistance parameters used by the conversions (no I²C traffic) |
| `read_status` | — | `dict` | **NV.** Reads `STATUS`: `{eeprom_busy, wiper_lock: [wl0(, wl1)], write_protect}` |
| `wait_ready` | `timeout_ms: float = 20` | — | **NV.** Polls `STATUS.EEWA` (a read of address `0x05`, which is allowed during an EEPROM write) until the EEPROM write cycle finished; raises `TimeoutError` after `timeout_ms` (tWC is 5 ms typical, 10 ms maximum) |
| `get_nv_wiper` | `channel: int = 0` | `int` | **NV.** Reads the EEPROM wiper (`0x02` / `0x03`), clamped to the max code |
| `set_nv_wiper` | `code: int`, `channel: int = 0` | — | **NV.** Writes the EEPROM wiper (single write, STOP starts the EEPROM cycle), then `wait_ready()`; the volatile wiper is **not** changed; blocked by WiperLock / write protect (the write is ignored — check `read_status`) |
| `store_wiper` | `channel: int = 0` | `int` | **NV.** Reads the volatile wiper and writes it to the EEPROM wiper (the datasheet's read → write procedure), waits for completion, returns the stored code; the setting then survives power cycles |
| `read_data` | `index: int` (0–9) | `int` | **NV.** Reads general-purpose EEPROM register `0x06 + index` (9-bit value) |
| `write_data` | `index: int`, `value: int` (0–0x1FF) | — | **NV.** Writes a general-purpose EEPROM register (single write, then `wait_ready()`); blocked by write protect |
| `is_wiper_locked` | `channel: int = 0` | `bool` | **NV.** `STATUS.WLx` |
| `is_write_protected` | — | `bool` | **NV.** `STATUS.WP` (pin OR nonvolatile bit) |
| `set_wiper_lock` | `locked: bool`, `channel: int = 0` | — | **NV, needs `hvc`.** High-voltage command at address `0x02` / `0x03`: decrement = enable WiperLock, increment = disable; the driver asserts `hvc`, waits `hvc_settle_ms`, sends the single command byte, releases `hvc` after the STOP, then `wait_ready()` (the EEPROM write cycle stores the bit). Raises if no `hvc` pin was given |
| `set_write_protect` | `enabled: bool` | — | **NV, needs `hvc`.** High-voltage command at address `0x0F`: decrement = enable the nonvolatile WP bit, increment = disable; same handling as `set_wiper_lock`. The hardware `WP` pin (MCP4641 / MCP4661) can still block writes while the bit is clear |

**Additional configuration options:** increment / decrement stepping, per-network terminal disconnect and shutdown, General Call enable, resistance model parameters (RAB, RW), ratio helpers, dual-network helpers; non-volatile parts: EEPROM wiper, ten general-purpose EEPROM registers, WiperLock, write protect, status.

## Data Conversion

```
max_code = 128 (7-bit parts)  or  256 (8-bit parts)       # steps = max_code + 1

R_WB(N) = N / max_code * RAB + RW            # wiper to terminal B, N = 0..max_code (RW wiper resistance)
R_WA(N) = (max_code - N) / max_code * RAB + RW      # potentiometer parts only (terminal A on a pin)
R_S     = RAB / max_code                     # step resistance
N       = round((R - RW) / RAB * max_code)   # inverse for R_WB, clamp 0..max_code
ratio   = N / max_code                       # unloaded divider ratio V(W-B) / V(A-B) on X1 parts

read   : value = ((high & 0x01) << 8) | low  # D8 from the first data byte, D7..D0 from the second
write  : command byte = (address << 4) | (0b00 << 2) | ((value >> 8) & 0x01) ; data byte = value & 0xFF
inc/dec: command byte = (address << 4) | (0b01 << 2)  = 0x04 / 0x14    (increment, wiper 0 / 1)
                        (address << 4) | (0b10 << 2)  = 0x08 / 0x18    (decrement)    — no data byte
read   : command byte = (address << 4) | (0b11 << 2)   # 0x0C wiper 0, 0x1C wiper 1, 0x2C NV wiper 0, 0x3C NV wiper 1,
                                                       # 0x4C TCON, 0x5C STATUS, 0x6C..0xFC data EEPROM

NV parts only:
STATUS : eewa = (v >> 3) & 1 ; wl1 = (v >> 2) & 1 ; wl0 = (v >> 1) & 1 ; wp = v & 1       # bits 8:4 read 1
HV commands (HVC/A0 at 8.5-12.5 V; each is ONE command byte, then STOP, then an EEPROM write cycle):
    enable  WiperLock 0 : command byte 0x28   (address 0x02, decrement)      disable : 0x24   (address 0x02, increment)
    enable  WiperLock 1 : command byte 0x38   (address 0x03, decrement)      disable : 0x34   (address 0x03, increment)
    enable  write protect: command byte 0xF8  (address 0x0F, decrement)      disable : 0xF4   (address 0x0F, increment)
GP data: address = 0x06 + index (index 0..9, i.e. 0x06..0x0F)
```

Example (RAB = 10 kΩ, RW = 75 Ω, 7-bit): N = 0 → 75 Ω, 1 → 153 Ω, 64 → 5075 Ω, 128 → 10 075 Ω (8-bit parts: step RAB/256 = 39 Ω). Zero scale is **not** 0 Ω; keep terminal current ≤ 2.5 mA. Nominal RAB tolerance is ±20 %, so absolute resistance is approximate; steps and ratios are accurate (R-INL ±0.5 LSb typ. at 5.5 V; potentiometer INL ±0.25 LSb (7-bit) / ±0.5 LSb (8-bit) typ.). RW is 75 Ω typ. (160 Ω max at 5.5 V, 300 Ω max at 2.7 V) and varies with code, VDD, terminal voltages and temperature; RAB tempco 50 ppm/°C (0–70 °C) to 150 ppm/°C; ratiometric tempco 15 ppm/°C. The two networks of a dual part match within 0.2 % (pot) / 0.25 % (rheostat) typically.

## Node-RED

Node name: `periph-mcp4xxx`  
Package: `node-red-contrib-periph-adc-dac`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| `msg.payload = { channel?, code }` | `{ channel, code, resistance }` | Sets the (volatile) wiper (0–128 / 0–256) of the network and returns the W–B resistance (Ω) |
| `msg.payload = { channel?, ohms }` | `{ channel, code, resistance }` | Sets the nearest resistance |
| `msg.payload = { channel?, ratio }` | `{ channel, code, resistance }` | Sets the divider ratio (0–1) |
| `msg.payload = { channel?, step: ±n }` | `{ channel, code, resistance }` | Increments / decrements by n steps |
| `msg.payload = { channel?, shutdown: true\|false }` | `{ channel, code, resistance, shutdown }` | Shutdown / resume |
| `msg.payload = { channel?, store: true }` | `{ channel, code, resistance, stored }` | Non-volatile parts: store the current wiper in EEPROM |
| `msg.payload = { data: { index, value? } }` | `{ index, value }` | Non-volatile parts: read / write a general-purpose EEPROM register |
| any other message | `{ wiper: [c0(, c1)], resistance: [r0(, r1)], status? }` | Reads back all networks (and the STATUS flags on non-volatile parts) |

Config panel fields: part (MCP4531 … MCP4662, 16 choices), I²C bus number, address (limited to the part's range), RAB (5 / 10 / 50 / 100 kΩ), wiper resistance, optional GPIO number of the high-voltage control pin (non-volatile parts; enables WiperLock / write-protect commands).

### Demo flow

Inject node (numeric slider via a function node scaled to the part's range) → `periph-mcp4xxx` (channel 0) → debug node showing code and resistance; a second inject sweeps the wiper in 16 steps every 500 ms with `{ step: n }` to show the staircase (for a dual part a third inject mirrors the code to channel 1; for a non-volatile part a button inject sends `{ store: true }`).

## Examples

### Demo

**Family sweep, shutdown and EEPROM.** Using the Full driver with `part` as a command-line / constant argument (default `"MCP4532"`) and a 10 kΩ part: print the part's properties (`channels()`, `steps()`, `is_potentiometer()`, `is_nonvolatile()`), the power-up wiper and its resistance; set 2.2 kΩ with `set_resistance`, read back the code and print the quantisation error; step the wiper up with `increment(8)` several times then down with `decrement(4)`, printing code and resistance (and show saturation with `increment(10)` near the top); set ratios 0.0, 0.25, 0.5, 0.75 and 1.0 with `set_ratio`; on a potentiometer part also print `get_resistance_wa`; on a dual part drive both networks (`set_both`, and `increment` / `decrement` on channel 1); demonstrate `shutdown` / `resume` (W connected to B, then restored); disconnect the wiper with `set_terminals(w=False)` and reconnect it. On a non-volatile part additionally: print `read_status()`, `store_wiper()` the chosen code, `read_data` / `write_data` index 0 (e.g. a calibration marker, warning that each write uses one of 1 000 000 cycles), read back `get_nv_wiper()`, and — only when a high-voltage control pin is configured — `set_wiper_lock(True)`, show that `set_wiper` is then ignored, and `set_wiper_lock(False)`. Finally `reset()` to the power-up state.

## Timing Constraints

- **Power-on:** the volatile registers are loaded with their POR values (mid-scale, or the EEPROM wiper on non-volatile parts) when VDD crosses VPOR/VBOR (< 1.65 V); the device is operational TBORD = 10–20 µs after leaving reset. VDD must go below VBOR and the RAM-retention voltage for a power-reset; between VBOR and 2.7 V the serial interface works but analog specifications are not guaranteed. Below VBOR the interface and EEPROM writes are disabled.
- **EEPROM write cycle (NV parts):** tWC = 5 ms typical, 10 ms maximum, started by the STOP condition of a write to a non-volatile address or of a high-voltage WiperLock / write-protect command. During tWC only volatile addresses (`0x00`, `0x01`, `0x04`, `0x05`) accept commands; commands to non-volatile addresses are ignored (a read control byte addressing the device while its pointer is a non-volatile address is NACKed). Poll `STATUS.EEWA` instead of sleeping. A brown-out during a write can corrupt the memory location. Endurance 1 000 000 write cycles.
- **Wiper update:** a write or increment / decrement command updates the volatile wiper on the acknowledge of the command (write: after the data byte's ACK; increment / decrement: after the command byte's ACK); the analog settling follows the network bandwidth (2 MHz for 5 kΩ down to 100 kHz for 100 kΩ).
- **I²C:** standard mode ≤ 100 kHz, fast mode ≤ 400 kHz, HS mode ≤ 3.4 MHz (master code `0000 1XXX` after START, not acknowledged, ends at the next STOP); the device never stretches SCL; spike suppression 50 ns (FS) / 10 ns (HS). Setup / hold values: datasheet "I²C Serial Interface Timing Specifications".
- **High-voltage commands (NV parts):** `HVC/A0` must be at VIHH (≥ 8.5 V, ≤ 12.5 V) when the command is sent; "there is a required delay after the HVC pin is driven to VIHH to the first edge of SCL" — no value is given (the driver waits `hvc_settle_ms`, default 1 ms); the pin must be back at VIH / VIL (the latched A0 level) afterwards.
- **Error recovery:** after an invalid command the chip releases SDA and ignores everything until a new START; an I²C state-machine reset is the "software reset sequence" of AN1028 (START, nine `1` bits, START, STOP) — it needs bit-level bus control and is not provided by the library.
- **Conformance:** none of these has a bus-decodable start / end annotation pair that the host must honour (the wiper update is simultaneous with the acknowledge; the EEPROM write cycle is handled by polling STATUS or by the chip ignoring commands; the POR delay is a power event), so `specs/adc_dac/mcp4xxx_timing.conf` defines no checks.

## Implementation Notes

- **Family abstraction (one code path for all parts):** the whole family is one driver per language built from a small **part descriptor** — `{name, channels (1|2), bits (7|8) → max_code (128|256), potentiometer (bool), nonvolatile (bool), wp_pin (bool, MCP46X1 only), address range / default, address pins}` — taken from the Variants table; everything else (command bytes, TCON, increment / decrement, shutdown, conversions) is identical code parameterised by `max_code` and `channels`. The non-volatile operations (`read_status`, `wait_ready`, `get_nv_wiper`, `set_nv_wiper`, `store_wiper`, `read_data`, `write_data`, `is_wiper_locked`, `is_write_protected`, `set_wiper_lock`, `set_write_protect`) are methods of the same `Mcp4xxxFull` class gated on the descriptor's `nonvolatile` flag (they raise on volatile parts). Implement the descriptor as a lookup table keyed by the upper-case part name; the constructor rejects unknown parts. Methods take a `channel` argument (0 or 1); a channel ≥ `channels` raises. Potentiometer-only operations (`get_resistance_wa`, `set_resistance_wa`) raise on rheostat parts. Classes: `Mcp4xxxMinimal` / `Mcp4xxxFull` (e.g. `MCP4XXXMinimal.java`).
- **Register-addressed wrapper:** accept `RegisterConnection` (1-byte "register" = the command byte, no `bus_type` branch, no chip-local `_read_reg`/`_write_reg`); see `specs/feature_register_access_design.md`. Writes are `connection.write(command_byte, bytes([data]))`; reads are `connection.read(command_byte, 2)`; increment / decrement and the high-voltage WiperLock / write-protect commands are command-byte-only transactions — `connection.write(inc_byte, bytes([inc_byte] * (steps - 1)))` sends `steps` increment commands in one continuous sequence (the datasheet's "continuous" mode). If a platform's `RegisterConnection.write` cannot send an empty data field, `increment` / `decrement` fall back to `set_wiper(clamped current ± steps)` and `set_wiper_lock` / `set_write_protect` raise.
- **Default address:** the driver's default `addr` follows the part: `0x2E` for 45X1 (A0 = 0), `0x58` for 45X2 / 46X2 (A1 = A0 = 0), `0x28` for 46X1 (A2 = A1 = A0 = 0); the constructor verifies the address lies in the part's range and raises otherwise.
- **Read returns two bytes:** the high byte only carries `D8` (bit 0, the rest 0); `get_wiper` masks `& 0x1FF` and clamps to the max code (values above full scale act as full scale).
- **Invalid commands lock the interface until START:** the chip NACKs an invalid address / operation combination and ignores everything else until a START; the driver therefore uses exactly the documented commands and, after any NACK error, restarts the transaction (a new START). Wiper 1 commands on a single-network part, EEPROM addresses on a volatile part, increment / decrement on a non-volatile address without the high-voltage pin, and continuous writes to non-volatile memory are such invalid combinations, so the driver validates `channel` / `address` / the part's memory type before any I²C traffic.
- **EEPROM writes are single transactions:** a write to a non-volatile address starts its EEPROM cycle at the STOP; a "continuous" write (several data bytes without STOP) to non-volatile memory is an error; no other command may follow before the STOP. Every `set_nv_wiper`, `write_data`, `set_wiper_lock` and `set_write_protect` is therefore one self-contained transaction followed by `wait_ready()`. During tWC the pointer may sit on a non-volatile address, so reads of volatile addresses after a write use an explicit command byte (the library's `RegisterConnection.read` always sends one).
- **8-bit parts need D8:** wiper code 256 (`0x100`) is written with `D8` = 1 in the command byte and data byte `0x00`; the 7-bit parts never set D8 (max code 128 < 256). The general-purpose EEPROM registers and the NV wipers are 9-bit on every part (values to `0x1FF`); only the wiper range is limited by the part's resolution.
- **WiperLock and write protect:** WiperLock (`WL0` / `WL1`) blocks serial writes to the network's volatile wiper, NV wiper **and** TCON `RxHW`/`RxA`/`RxW`/`RxB` bits (whether the chip NACKs or silently ignores the write is not stated — the driver does not rely on a NACK and does not verify writes; applications check `is_wiper_locked` — a locked network keeps its old value). Write protect blocks all EEPROM writes (NV wipers, general-purpose data) but not the volatile wiper / TCON. WL and WP are nonvolatile, survive POR/BOR and are changed only by high-voltage commands; the hardware `WP` pin (MCP4641 / MCP4661 only) is OR-ed into `STATUS.WP`.
- **High-voltage commands need hardware:** `HVC/A0` must be driven to 8.5–12.5 V (external boost / switch). The library only models the control line: `hvc` is an `OutputPin` that the board connects to that circuit; the driver asserts it, waits `hvc_settle_ms`, sends the one-byte command and releases it. The HVC pin shares address bit A0, which is latched at POR: the external circuit must restore the A0 level afterwards, and never change the pin during POR. Never assert `hvc` during normal commands: with `HVC/A0` at VIHH normal writes become high-voltage commands. Writing a high-voltage command to the wrong address (e.g. a high-voltage decrement to `0x0F`) enables write protect; the descriptions above give the exact byte values.
- **General Call is enabled at power-up (`GCEN` = 1):** the General Call address `0x00` is acknowledged and a host can change every MCP45XX/46XX on the bus at once (`1000 00d` write wiper 0, `1001 00d` write wiper 1 (dual), `1100 00d` write TCON, `1000 01x` / `1001 01x` increment, `1000 10x` / `1001 10x` decrement — these act on the volatile wipers / TCON only); the chip does not acknowledge the I²C reserved General Call commands (reset, address programming). Call `set_general_call(False)` if the bus carries devices for which a General Call write of `0x80` / `0xC0` would be harmful; the library has no General Call transmit API (`RegisterConnection` cannot address `0x00`).
- **POR on non-volatile parts:** TCON returns to `0x1FF` (the terminal state is not stored) and the volatile wiper is reloaded from the NV wiper; restore stored shutdown / terminal settings after every power-up. `reset()` emulates that.
- **Wiper above full scale:** writes above the max code select full scale (W = A); the driver clamps to the max code and never writes more.
- **Read-modify-write of TCON:** only the documented bits are changed; bits belonging to the absent second network of a single part are kept at 1 (their POR value); GCEN is preserved.
- **Volatile parts:** no EEPROM; the wipers are mid-scale after every power cycle; keep VDD applied to retain the setting.
- **Terminal limits:** terminals must stay within VSS … VDD; wiper current ≤ 2.5 mA.
- **Addressing and registry:** every part has its own registry entry (identical protocol, different address range, all `write_sensitive`, no identity register); the volatile parts point at `mcp4xxx.pdf`, the non-volatile parts at `mcp4xxx_nv.pdf`. The ranges `0x28`–`0x2F` overlap the AD5243 / AD5248 (`0x2C`–`0x2F`) and DS3502 (`0x28`–`0x2B`); the `0x58`–`0x5B` range and `0x2E`/`0x2F` collide with no existing registry chip but with other I²C devices. Discovery can only report candidates, never confirm.
- **Datasheets:** DS22096B (volatile) and DS22107B (non-volatile), 2013. The two share all register / command descriptions of the volatile subset; the non-volatile datasheet adds the EEPROM, STATUS, WiperLock and write-protect sections used here.

## Sigrok Decoder

Decoder id `mcp4xxx`, input `['i2c']`. Matches I²C addresses `0x28`–`0x2F` and `0x58`–`0x5B` and the General Call address `0x00` (annotated as General Call). Because the part cannot be identified from the bus, the decoder annotates generically (options select the resolution, default 7-bit, and whether the part is non-volatile): it decodes the command byte (address field, operation: write / increment / decrement / read, D9:D8) and the data bytes: writes to `0x00` / `0x01` as the wiper code (9-bit value from D8 + data byte, with `code/max_code` as a ratio and the nominal W–B resistance fraction), to `0x02` / `0x03` as the EEPROM wiper (annotated "EEPROM write follows STOP"), writes to `0x04` as TCON with the decoded GCEN, R1HW…R1B, R0HW (shutdown), R0A, R0W, R0B, to `0x06`–`0x0F` as general-purpose EEPROM data; increment / decrement commands (including several in one transaction, each annotated "+1" / "−1" and the wiper number; an increment / decrement to `0x02`, `0x03` or `0x0F` is annotated as the high-voltage WiperLock / write-protect command "WL0 enable" / "WL1 disable" / "WP enable" …); reads, using the pointer set by the preceding command (random read) or the last one (stand-alone read), as the returned 9-bit value, with the `STATUS` register (`0x05`) decoded into EEWA / WL1 / WL0 / WP. Warnings: an address / operation combination no family member accepts (reserved addresses, increment on TCON), a wiper value above the part option's full scale ("full scale"), a data byte after an increment / decrement command, wiper 1 accessed when the part option is a single-network part, EEPROM addresses accessed on a volatile part option, a continuous (multi-byte) write to non-volatile memory, a command to a non-volatile address issued while an EEPROM write cycle is expected to run (tWC after the previous EEPROM write STOP), and a NACK on a command byte followed by further traffic without a START. No timing annotation pair is emitted, since no constraint is conformance-checked.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] Driver `python/periph/chips/adc_dac/mcp4xxx.py` — Google-style docstring on every class and public method
- [x] Examples `python/examples/adc_dac/mcp4xxx/minimal.py` — Tier-1 signature comment on every call
- [x] Examples `python/examples/adc_dac/mcp4xxx/complete.py` — Tier-1 + Tier-2
- [x] Examples `python/examples/adc_dac/mcp4xxx/demo.py` — Tier-1 + Tier-3
- [x] Tests `python/tests/adc_dac/mcp4xxx_test.py` (MicroPython)
- [x] Tests `python/tests/adc_dac/mcp4xxx_test_cp.py` (CircuitPython)
- [x] Tests `python/tests/adc_dac/mcp4xxx_test_linux.py` (Linux)
- [x] Unit test `python/tests/adc_dac/mcp4xxx_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [x] Manifest `python/uiflow1/adc_dac/mcp4xxx/mcp4xxx.json` — `Periph` category, `#C084FC` color
- [x] Blocks `python/uiflow1/adc_dac/mcp4xxx/mcp4xxx_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [x] Generated `python/uiflow1/adc_dac/mcp4xxx/mcp4xxx.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [x] Wrapper class `python/uiflow2/adc_dac/mcp4xxx/MCP4XXX.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [x] Exported `python/uiflow2/adc_dac/mcp4xxx/MCP4XXX.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [x] Driver `cpp/src/chips/adc_dac/MCP4XXX.h` — Doxygen `/** @brief */` on every class and public method
- [x] Driver `cpp/src/chips/adc_dac/MCP4XXX.cpp`
- [x] Examples `cpp/examples/arduino/adc_dac/MCP4XXX/minimal/minimal.ino` — Tier-1
- [x] Examples `cpp/examples/arduino/adc_dac/MCP4XXX/complete/complete.ino` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/arduino/adc_dac/MCP4XXX/demo/demo.ino` — Tier-1 + Tier-3
- [x] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [x] Examples `cpp/examples/linux/adc_dac/MCP4XXX/minimal/main.cpp` — Tier-1
- [x] Examples `cpp/examples/linux/adc_dac/MCP4XXX/complete/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/linux/adc_dac/MCP4XXX/demo/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/zephyr/adc_dac/MCP4XXX/minimal/main.cpp` — Tier-1
- [x] Examples `cpp/examples/zephyr/adc_dac/MCP4XXX/complete/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/zephyr/adc_dac/MCP4XXX/demo/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/espidf/adc_dac/MCP4XXX/minimal/main/main.cpp` — Tier-1
- [x] Examples `cpp/examples/espidf/adc_dac/MCP4XXX/complete/main/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/espidf/adc_dac/MCP4XXX/demo/main/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/picosdk/adc_dac/MCP4XXX/minimal/src/main.cpp` — Tier-1
- [x] Examples `cpp/examples/picosdk/adc_dac/MCP4XXX/complete/src/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/picosdk/adc_dac/MCP4XXX/demo/src/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/stm32cube/adc_dac/MCP4XXX/minimal/Core/Src/main.cpp` — Tier-1
- [x] Examples `cpp/examples/stm32cube/adc_dac/MCP4XXX/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/stm32cube/adc_dac/MCP4XXX/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [x] Tests `cpp/tests/adc_dac/mcp4xxx_test/mcp4xxx_test.ino` (Arduino)
- [x] Tests `cpp/tests/adc_dac/mcp4xxx_test_linux/mcp4xxx_test_linux.cpp` (Linux GCC)
- [x] Tests `cpp/tests/adc_dac/mcp4xxx_test_zephyr/src/main.cpp` (Zephyr)
- [x] Tests `cpp/tests/adc_dac/mcp4xxx_test_espidf/main/main.cpp` (ESP-IDF)
- [x] Tests `cpp/tests/adc_dac/mcp4xxx_test_picosdk/src/main.cpp` (Pico SDK)
- [x] Tests `cpp/tests/adc_dac/mcp4xxx_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [x] Unit test `cpp/tests/adc_dac/mcp4xxx_test_unit/mcp4xxx_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [x] Driver `nodejs/packages/periph/src/chips/adc_dac/mcp4xxx.js` — JSDoc on every class and exported method
- [x] Examples `nodejs/packages/periph/examples/adc_dac/mcp4xxx/minimal.js` — Tier-1
- [x] Examples `nodejs/packages/periph/examples/adc_dac/mcp4xxx/complete.js` — Tier-1 + Tier-2
- [x] Examples `nodejs/packages/periph/examples/adc_dac/mcp4xxx/demo.js` — Tier-1 + Tier-3
- [x] Tests `nodejs/tests/adc_dac/mcp4xxx_test.js`
- [x] Unit test `nodejs/tests/adc_dac/mcp4xxx_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [x] Node runtime `nodejs/packages/node-red-contrib-periph-adc-dac/nodes/mcp4xxx/mcp4xxx.js`
- [x] Node editor `nodejs/packages/node-red-contrib-periph-adc-dac/nodes/mcp4xxx/mcp4xxx.html` — `data-help-name` section with inputs, outputs, and config description
- [x] Demo flow `nodejs/packages/node-red-contrib-periph-adc-dac/examples/mcp4xxx/demo.json` — tab `info` field describes the scenario

### Rust
- [x] Driver `rust/periph/src/chips/adc_dac/mcp4xxx.rs` — `//!` module doc + `///` on every `pub` item
- [x] Examples `rust/examples/linux/adc_dac/mcp4xxx/minimal/src/main.rs` — Tier-1
- [x] Examples `rust/examples/linux/adc_dac/mcp4xxx/complete/src/main.rs` — Tier-1 + Tier-2
- [x] Examples `rust/examples/linux/adc_dac/mcp4xxx/demo/src/main.rs` — Tier-1 + Tier-3
- [x] Tests `rust/tests/adc_dac/mcp4xxx_test/src/main.rs` (Linux)
- [x] Tests `rust/tests/adc_dac/mcp4xxx_test_esp32s3/src/main.rs` (ESP32-S3)
- [x] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/adc_dac/mcp4xxx.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [x] Driver `go/periph/chips/adc_dac/mcp4xxx.go` — Go doc comment on every exported type and method
- [x] Examples `go/examples/linux/adc_dac/mcp4xxx/minimal/minimal.go` — Tier-1 signature comment on every call
- [x] Examples `go/examples/linux/adc_dac/mcp4xxx/complete/complete.go` — Tier-1 + Tier-2
- [x] Examples `go/examples/linux/adc_dac/mcp4xxx/demo/demo.go` — Tier-1 + Tier-3
- [x] Examples `go/examples/tinygo/adc_dac/mcp4xxx/minimal/minimal.go` — Tier-1 (TinyGo)
- [x] Examples `go/examples/tinygo/adc_dac/mcp4xxx/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [x] Examples `go/examples/tinygo/adc_dac/mcp4xxx/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [x] Tests `go/tests/adc_dac/mcp4xxx_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [x] Tests `go/tests/adc_dac/mcp4xxx_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [x] Unit test `go/periph/chips/adc_dac/mcp4xxx_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [x] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/adc_dac/MCP4XXXMinimal.java` — Javadoc on every class and public method
- [x] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/adc_dac/MCP4XXXFull.java` — Javadoc on every class and public method
- [x] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/adc_dac/MCP4XXXMinimal.kt` — KDoc on every class and public method
- [x] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/adc_dac/MCP4XXXFull.kt` — KDoc on every class and public method
- [x] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/adc_dac/MCP4XXXMinimal.groovy` — Groovydoc on every class and public method
- [x] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/adc_dac/MCP4XXXFull.groovy` — Groovydoc on every class and public method
- [x] Examples `jvm/examples/java/adc_dac/mcp4xxx/Minimal.java` — Tier-1
- [x] Examples `jvm/examples/java/adc_dac/mcp4xxx/Complete.java` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/java/adc_dac/mcp4xxx/Demo.java` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/kotlin/adc_dac/mcp4xxx/Minimal.kt` — Tier-1
- [x] Examples `jvm/examples/kotlin/adc_dac/mcp4xxx/Complete.kt` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/kotlin/adc_dac/mcp4xxx/Demo.kt` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/groovy/adc_dac/mcp4xxx/Minimal.groovy` — Tier-1
- [x] Examples `jvm/examples/groovy/adc_dac/mcp4xxx/Complete.groovy` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/groovy/adc_dac/mcp4xxx/Demo.groovy` — Tier-1 + Tier-3
- [x] Tests `jvm/tests/adc_dac/mcp4xxx/MCP4XXXTest.java` (Pi hardware, JBang)
- [x] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/adc_dac/MCP4XXXTest.java` (JUnit)
- [x] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/adc_dac/MCP4XXXTest.kt` (Kotest/JUnit5)
- [x] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/adc_dac/MCP4XXXSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [x] Decoder `sigrok/mcp4xxx/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [x] Decoder `sigrok/mcp4xxx/pd.py` — annotates the register accesses as described in the Sigrok Decoder section (no timing annotation pair: no conformance-checked constraints)

### Conformance
- [x] Checker `conformance/adc_dac/mcp4xxx_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [x] Timing config `specs/adc_dac/mcp4xxx_timing.conf` — already committed; defines no checks (see its header)
- [x] Decoder `sigrok/mcp4xxx/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

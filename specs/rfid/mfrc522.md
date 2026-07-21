# Chip Spec: MFRC522

**Manufacturer:** NXP Semiconductors
**Datasheet:** `datasheets/rfid/mfrc522.pdf` (MFRC522 product data sheet, Rev. 3.9 — 27 April 2016, doc 112139); ISO/IEC 14443-3 Type A anticollision/select commands are a well-known open standard and are not detailed in the NXP datasheet — this spec documents them from common knowledge alongside the MFRC522-specific register/command set
**Category:** `rfid`
**Transports:** SPI, I²C, UART

## Overview

The MFRC522 is a highly integrated 13.56 MHz contactless reader/writer IC ("MIFARE and NTAG frontend") supporting ISO/IEC 14443 A. It drives a reader antenna directly (TX1/TX2 output stage), demodulates and decodes ISO/IEC 14443 A card responses, and manages framing, parity, and CRC in hardware via its internal "contactless UART". A 64-byte FIFO buffers commands and data between the host and the RF state machine. The MFRC522 supports MIFARE Classic (Mini/1K/4K, including the Crypto1 authentication cipher), MIFARE Ultralight, and NTAG chips, at transfer speeds up to 848 kBd. It is the most widely used chip for building RFID/NFC card readers (access control, ticketing, asset tracking).

The host talks to the MFRC522 through one of three interfaces — SPI, I²C, or UART — all of which expose the *same* internal register bank; the framing of the register address/data differs per interface (see Register Access below). The driver then uses the MFRC522's own command set (Idle, Transceive, MFAuthent, CalcCRC, …) to run the ISO/IEC 14443 A protocol and MIFARE memory operations.

## Transport Configuration

### I²C
- **Address:** `0x28`–`0x2F` (pin `EA` = LOW; upper 4 address bits fixed to `0101b`, lower 3 bits set by pins `ADR_0`–`ADR_2`; `0x28` is the common default with all address pins tied LOW) — fully custom `0x00`–`0x3F` via pins `ADR_0`–`ADR_5` when `EA` = HIGH (`ADR_6` fixed to 0)
- **Max clock:** 400 kHz (Fast mode); up to 3.4 MHz (High-speed mode) also supported

### SPI
- **Mode:** CPOL=0 CPHA=0 (Mode 0)
- **Max clock:** 10 Mbit/s
- **Bit order:** MSB first
- **CS active:** low (pin `NSS`)

### UART
- **Baud rate:** 9.6 kBd (default after reset); configurable to 7.2, 14.4, 19.2, 38.4, 57.6, 115.2, 128, 230.4, 460.8, 921.6, or 1228.8 kBd via `SerialSpeedReg`
- **Frame:** 8N1, LSB first, no parity bit
- **Levels:** RS232-compatible, voltage levels follow the pin supply

Only one host interface is active at a time. The MFRC522 auto-detects which interface is wired by sensing pin levels immediately after power-on/reset (see Table 5 of the datasheet); the driver does not select the interface in software — it is determined entirely by hardware wiring (pins `SDA/NSS/RX`, `I2C`, `EA`, `D1`–`D7`).

## Register Access

All three interfaces address the same 64-register (6-bit address, `0x00`–`0x3F`) bank, but frame the address byte differently. The driver must pick the correct framing based on which concrete transport was passed to the constructor (e.g. via a per-platform type check on the transport object — the same "bus type" pattern used for chips with both I²C and SPI, see `specs/pressure/bmp280.md` Implementation Notes).

| Transport | Read | Write |
|-----------|------|-------|
| I²C | `write_read([reg], n)` — write the register pointer, repeated START, read n bytes | `write([reg] + data)` — register pointer followed by n data bytes in one frame |
| SPI | address byte = `(reg << 1) & 0x7E`, bit 7 = 1 for read; `write_read([addr\|0x80]*n + [0x00], n+1)`, discard first byte, keep the remaining n | address byte = `(reg << 1) & 0x7E`, bit 7 = 0; `write([addr] + data)` — one address byte followed by all n data bytes |
| UART | address byte = `reg & 0x3F`, bit 7 = 1 for read, bit 6 reserved = 0; `write_read([addr\|0x80], 1)` per byte (no documented burst framing — repeat the single-byte read per byte, matching the SPI burst pattern; see Implementation Notes) | address byte = `reg & 0x3F`, bit 7 = 0; `write([addr, data])` per byte |

For SPI, reading `n` bytes from the same register requires sending the read-address byte `n` times followed by one dummy `0x00` byte, and discarding the first received byte (NXP Table 6/7 — `MOSI: addr, addr, …, addr, 00` / `MISO: X, data0, …, data(n-1), data(n)`). Writing `n` bytes needs only a single address byte followed directly by the data bytes (no repetition, no dummy byte).

## Register Map

Register addresses are 6 bits (`0x00`–`0x3F`), organized into 4 pages. Only registers the driver touches are given full bit-field tables below; the full address list is included for completeness.

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00` | — | — | `0x00` | Reserved |
| `0x01` | `CommandReg` | R/W | `0x20` | Starts and stops command execution |
| `0x02` | `ComIEnReg` | R/W | `0x80` | Interrupt enable (IRQ pin) |
| `0x03` | `DivIEnReg` | R/W | `0x00` | Interrupt enable (IRQ pin, misc.) |
| `0x04` | `ComIrqReg` | mixed | `0x14` | Interrupt request bits |
| `0x05` | `DivIrqReg` | mixed | `0x0x`(*) | Interrupt request bits |
| `0x06` | `ErrorReg` | R | `0x00` | Error status of last command |
| `0x07` | `Status1Reg` | R | `0x21` | CRC/IRQ/FIFO status |
| `0x08` | `Status2Reg` | mixed | `0x00` | Receiver/transmitter/Crypto1 status |
| `0x09` | `FIFODataReg` | D | `0xxx`(*) | FIFO input/output |
| `0x0A` | `FIFOLevelReg` | mixed | `0x00` | Bytes stored in FIFO |
| `0x0B` | `WaterLevelReg` | R/W | `0x08` | FIFO almost empty/full threshold |
| `0x0C` | `ControlReg` | mixed | `0x10` | Miscellaneous control bits |
| `0x0D` | `BitFramingReg` | mixed | `0x00` | Bit-oriented frame adjustments |
| `0x0E` | `CollReg` | mixed | `0xxx`(*) | Bit position of first RF collision |
| `0x0F` | — | — | `0x00` | Reserved |
| `0x10` | — | — | `0x00` | Reserved |
| `0x11` | `ModeReg` | R/W | `0x3F` | General TX/RX mode |
| `0x12` | `TxModeReg` | mixed | `0x00` | TX data rate/framing |
| `0x13` | `RxModeReg` | mixed | `0x00` | RX data rate/framing |
| `0x14` | `TxControlReg` | R/W | `0x80` | Antenna driver control (TX1/TX2) |
| `0x15` | `TxASKReg` | R/W | `0x00` | TX modulation setting |
| `0x16` | `TxSelReg` | R/W | `0x10` | Analog module source select |
| `0x17` | `RxSelReg` | R/W | `0x84` | Receiver setting select |
| `0x18` | `RxThresholdReg` | R/W | `0x84` | Bit decoder thresholds |
| `0x19` | `DemodReg` | R/W | `0x4D` | Demodulator settings |
| `0x1A` | — | — | `0x00` | Reserved |
| `0x1B` | — | — | `0x00` | Reserved |
| `0x1C` | `MfTxReg` | R/W | `0x62` | MIFARE TX parameters |
| `0x1D` | `MfRxReg` | R/W | `0x00` | MIFARE RX parameters |
| `0x1E` | — | — | `0x00` | Reserved |
| `0x1F` | `SerialSpeedReg` | R/W | `0xEB` | UART speed |
| `0x20` | — | — | `0x00` | Reserved |
| `0x21` | `CRCResultReg` (MSB) | R | `0xFF` | CRC coprocessor result, high byte |
| `0x22` | `CRCResultReg` (LSB) | R | `0xFF` | CRC coprocessor result, low byte |
| `0x23` | — | — | `0x88` | Reserved |
| `0x24` | `ModWidthReg` | R/W | `0x26` | Modulation width |
| `0x25` | — | — | `0x87` | Reserved |
| `0x26` | `RFCfgReg` | R/W | `0x48` | Receiver gain |
| `0x27` | `GsNReg` | R/W | `0x88` | N-driver conductance |
| `0x28` | `CWGsPReg` | R/W | `0x20` | P-driver conductance, no modulation |
| `0x29` | `ModGsPReg` | R/W | `0x20` | P-driver conductance, modulation |
| `0x2A` | `TModeReg` | R/W | `0x00` | Timer settings (upper) |
| `0x2B` | `TPrescalerReg` | R/W | `0x00` | Timer settings (lower) |
| `0x2C` | `TReloadReg` (MSB) | R/W | `0x00` | Timer reload value, high byte |
| `0x2D` | `TReloadReg` (LSB) | R/W | `0x00` | Timer reload value, low byte |
| `0x2E` | `TCounterValReg` (MSB) | R | `0xxx`(*) | Timer value, high byte |
| `0x2F` | `TCounterValReg` (LSB) | R | `0xxx`(*) | Timer value, low byte |
| `0x30` | — | — | `0x00` | Reserved |
| `0x31` | `TestSel1Reg` | R/W | `0x00` | Test signal configuration |
| `0x32` | `TestSel2Reg` | R/W | `0x00` | Test signal configuration, PRBS |
| `0x33` | `TestPinEnReg` | R/W | `0x80` | Test bus pin driver enable |
| `0x34` | `TestPinValueReg` | R/W | `0x00` | Test bus pin values |
| `0x35` | `TestBusReg` | R | `0xxx`(*) | Internal test bus status |
| `0x36` | `AutoTestReg` | R/W | `0x40` | Digital self test control |
| `0x37` | `VersionReg` | R | `0xxx`(*) | Chip/version identification |
| `0x38` | `AnalogTestReg` | R/W | `0x00` | AUX1/AUX2 test signal select |
| `0x39` | `TestDAC1Reg` | R/W | `0xxx`(*) | TestDAC1 value |
| `0x3A` | `TestDAC2Reg` | R/W | `0xxx`(*) | TestDAC2 value |
| `0x3B` | `TestADCReg` | R | `0xxx`(*) | ADC I/Q channel value |
| `0x3C`–`0x3F` | — | — | mixed | Reserved for production test |

(*) `xx` = undefined / not meaningfully defined at reset.

### Bit Fields

#### `CommandReg` (`0x01`, reset `0x20`)

| Bits | Name | Description |
|------|------|-------------|
| 5 | `RcvOff` | 1 = analog part of receiver switched off |
| 4 | `PowerDown` | 1 = enter soft power-down; 0 = wake up (read as 1 until wake-up completes) |
| 3:0 | `Command[3:0]` | Command code, see MFRC522 Command Set below |

#### `ComIrqReg` (`0x04`, reset `0x14`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `Set1` | Write 1 = set the marked bits below; write 0 = clear them |
| 6 | `TxIRq` | Transmission finished |
| 5 | `RxIRq` | Reception finished |
| 4 | `IdleIRq` | Command finished |
| 3 | `HiAlertIRq` | FIFO almost full |
| 2 | `LoAlertIRq` | FIFO almost empty |
| 1 | `ErrIRq` | Error bit set in `ErrorReg` |
| 0 | `TimerIRq` | Timer decremented to 0 |

#### `ErrorReg` (`0x06`, reset `0x00`, all bits R)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `WrErr` | Data written to FIFO at an invalid time |
| 6 | `TempErr` | Overheating; antenna drivers auto-switched off |
| 4 | `BufferOvfl` | FIFO overflow |
| 3 | `CollErr` | Bit collision detected (bitwise anticollision, 106 kBd only) |
| 2 | `CRCErr` | CRC check failed on reception |
| 1 | `ParityErr` | Parity check failed (106 kBd only) |
| 0 | `ProtocolErr` | SOF incorrect, or wrong byte count during `MFAuthent` |

#### `Status1Reg` (`0x07`, reset `0x21`, all bits R)

| Bits | Name | Description |
|------|------|-------------|
| 6 | `CRCOk` | CRC result is zero (valid) |
| 5 | `CRCReady` | CRC calculation finished |
| 4 | `IRq` | Any enabled interrupt is pending |
| 3 | `TRunning` | Timer is running |
| 1 | `HiAlert` | FIFO almost full |
| 0 | `LoAlert` | FIFO almost empty |

#### `Status2Reg` (`0x08`, reset `0x00`)

| Bits | Name | Description |
|------|------|-------------|
| 3 | `MFCrypto1On` | Crypto1 unit active (set automatically by a successful `MFAuthent`; host may write 0 to clear it after use) |
| 2:0 | `ModemState[2:0]` | Transmitter/receiver state machine state (`000`=idle, `011`=transmitting, `110`=receiving, …) |

#### `FIFODataReg` (`0x09`)

| Bits | Name | Description |
|------|------|-------------|
| 7:0 | `FIFOData[7:0]` | Write: push one byte into the 64-byte FIFO. Read: pop one byte |

#### `FIFOLevelReg` (`0x0A`, reset `0x00`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `FlushBuffer` | Write 1: clear FIFO read/write pointers and `BufferOvfl` |
| 6:0 | `FIFOLevel[6:0]` | Number of bytes currently stored in the FIFO |

#### `ControlReg` (`0x0C`, reset `0x10`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `TStopNow` | Write 1: stop timer immediately |
| 6 | `TStartNow` | Write 1: start timer immediately |
| 2:0 | `RxLastBits[2:0]` | Valid bits in the last received byte; `000` = whole byte valid |

#### `BitFramingReg` (`0x0D`, reset `0x00`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `StartSend` | Write 1: start transmission (only valid with the `Transceive` command) |
| 6:4 | `RxAlign[2:0]` | Bit position in the first FIFO byte where the first received bit is stored (bitwise anticollision at 106 kBd only) |
| 2:0 | `TxLastBits[2:0]` | Bits of the last TX byte actually sent; `000` = whole byte, `111` = 7 bits (used for the 7-bit REQA/WUPA short frames) |

#### `CollReg` (`0x0E`, reset undefined)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `ValuesAfterColl` | 0 = all received bits cleared after a collision |
| 5 | `CollPosNotValid` | 1 = no collision detected, or position out of range |
| 4:0 | `CollPos[4:0]` | Bit position of the first detected collision (`0` = 32nd bit) |

#### `ModeReg` (`0x11`, reset `0x3F`)

| Bits | Name | Description |
|------|------|-------------|
| 5 | `TxWaitRF` | 1 = transmitter only starts once an RF field is generated |
| 3 | `PolMFin` | Polarity of pin MFIN; 1 = active HIGH |
| 1:0 | `CRCPreset[1:0]` | CRC coprocessor preset for `CalcCRC`/TX/RX: `00`=`0x0000`, `01`=`0x6363` (ISO/IEC 14443-3 CRC_A preset — use this for all card communication), `10`=`0xA671`, `11`=`0xFFFF` |

#### `TxModeReg` (`0x12`, reset `0x00`) / `RxModeReg` (`0x13`, reset `0x00`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `TxCRCEn` / `RxCRCEn` | 1 = append/check CRC_A on TX/RX (must be 0 at 106 kBd, where CRC is handled inline by the framing; 1 at 212/424/848 kBd) |
| 6:4 | `TxSpeed[2:0]` / `RxSpeed[2:0]` | `000`=106 kBd, `001`=212 kBd, `010`=424 kBd, `011`=848 kBd |
| 3 | `RxNoErr` (RxModeReg only) | 1 = ignore invalid streams shorter than 4 bits, keep receiver active |
| 2 | `RxMultiple` (RxModeReg only) | 1 = keep receiving after a frame (used for polling); receiver/`Transceive` no longer auto-terminates |

#### `TxControlReg` (`0x14`, reset `0x80`)

| Bits | Name | Description |
|------|------|-------------|
| 1 | `Tx2RFEn` | 1 = pin TX2 delivers the modulated 13.56 MHz carrier |
| 0 | `Tx1RFEn` | 1 = pin TX1 delivers the modulated 13.56 MHz carrier |

Antenna on = both bits set (`TxControlReg |= 0x03`); antenna off = both cleared. Both are 0 at reset — the antenna is off until the driver explicitly enables it.

#### `TxASKReg` (`0x15`, reset `0x00`)

| Bits | Name | Description |
|------|------|-------------|
| 6 | `Force100ASK` | 1 = force 100% ASK modulation regardless of `ModGsPReg`; recommended set for reliable operation with most antenna designs |

#### `RFCfgReg` (`0x26`, reset `0x48`)

| Bits | Name | Description |
|------|------|-------------|
| 6:4 | `RxGain[2:0]` | Receiver gain: `000`/`010`=18 dB, `001`/`011`=23 dB, `100`=33 dB, `101`=38 dB, `110`=43 dB, `111`=48 dB (reset default `100` = 33 dB) |

#### `TModeReg` (`0x2A`, reset `0x00`) / `TPrescalerReg` (`0x2B`, reset `0x00`)

| Bits | Name | Description |
|------|------|-------------|
| `TModeReg` 7 | `TAuto` | 1 = timer starts automatically at the end of transmission |
| `TModeReg` 6:5 | `TGated[1:0]` | `00` = non-gated |
| `TModeReg` 4 | `TAutoRestart` | 1 = timer reloads and restarts at 0 instead of stopping |
| `TModeReg` 3:0 | `TPrescaler_Hi[3:0]` | Upper 4 bits of the 12-bit `TPrescaler` |
| `TPrescalerReg` 7:0 | `TPrescaler_Lo[7:0]` | Lower 8 bits of the 12-bit `TPrescaler` |

Timer frequency: `f_timer = 13.56 MHz / (2 × TPrescaler + 1)`. Total delay: `t = (TPrescaler × 2 + 1) × (TReloadVal + 1) / 13.56 MHz`.

#### `TReloadReg` (`0x2C`–`0x2D`, reset `0x0000`)

16-bit timer reload value, MSB at `0x2C`, LSB at `0x2D`.

#### `CRCResultReg` (`0x21`–`0x22`, reset `0xFFFF`)

16-bit result of the CRC coprocessor, MSB at `0x21`, LSB at `0x22`. Valid only when `Status1Reg.CRCReady` = 1.

#### `VersionReg` (`0x37`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4 | Chip type | `9h` = MFRC522 |
| 3:0 | Version | `1h` = v1.0 (software version `0x91`), `2h` = v2.0 (software version `0x92`) |

#### `AutoTestReg` (`0x36`, reset `0x40`)

| Bits | Name | Description |
|------|------|-------------|
| 3:0 | `SelfTest[3:0]` | `1001b` enables the digital self test (started via `CalcCRC`); `0000b` for normal operation |

## Initialization Sequence

1. Perform a `SoftReset` command (`CommandReg` = `0x0F`).
2. Wait until `CommandReg.PowerDown` reads 0 (oscillator started and chip ready); poll or wait ≥ 50 ms as a safe upper bound.
3. Set `TModeReg` = `0x80` (`TAuto` = 1), `TPrescalerReg` = `0xA9`, `TReloadReg` = `0x03E8` (1000) — gives a ~25 ms auto-timeout on receive (`TPrescaler` = 169 combined with `TModeReg.TPrescaler_Hi` = 0, per the datasheet's own 25 µs-per-tick example; 1000 ticks × 25 µs ≈ 25 ms).
4. Set `TxASKReg` = `0x40` (`Force100ASK` = 1).
5. Set `ModeReg` = `0x3D` (`TxWaitRF` = 1, `PolMFin` = 1, `CRCPreset` = `01` → `0x6363`, the ISO/IEC 14443-3 CRC_A preset).
6. Turn the antenna on: `TxControlReg |= 0x03`.

No hardware reset pin (`NRSTPD`) handling is required from software — it is a board-level power/reset wiring concern (typically tied permanently HIGH), not a GPIO the driver toggles per operation. If a hard reset is ever needed, driving `NRSTPD` low for ≥ 100 ns then high requires the same wait as step 2 before re-running steps 3–6.

## MFRC522 Command Set

Commands are started by writing a 4-bit code to `CommandReg.Command[3:0]`; arguments/data flow through the FIFO.

| Command | Code | Action |
|---------|------|--------|
| `Idle` | `0000` | No action; cancels the current command |
| `Mem` | `0001` | Transfers 25 bytes between the FIFO and an internal buffer |
| `Generate RandomID` | `0010` | Generates a 10-byte random ID into the internal buffer |
| `CalcCRC` | `0011` | Runs the CRC coprocessor over the FIFO content; result in `CRCResultReg`; terminate by issuing `Idle` |
| `Transmit` | `0100` | Transmits the FIFO content over RF |
| `NoCmdChange` | `0111` | Modifies `CommandReg` bits (e.g. `PowerDown`, `RcvOff`) without affecting a running command |
| `Receive` | `1000` | Activates the receiver, waits for a data stream |
| `Transceive` | `1100` | Transmits FIFO content, then automatically switches to receive; transmission itself must be kicked off separately by setting `BitFramingReg.StartSend` = 1 |
| `MFAuthent` | `1110` | Performs MIFARE Classic (Crypto1) authentication as a reader |
| `SoftReset` | `1111` | Resets the device to power-on defaults (except the `Mem` internal buffer and `SerialSpeedReg`, which resets to `0xEB`) |

`CalcCRC` doubles as the self-test trigger: if `AutoTestReg.SelfTest[3:0]` = `1001b` when `CalcCRC` starts, the MFRC522 runs its digital self test instead and writes 64 fixed reference bytes to the FIFO (see Full API `selfTest()`).

## ISO/IEC 14443-3 Type A Protocol (PICC commands)

These byte-level commands are sent to the *card* via `Transceive`/`Receive` and are standardized by ISO/IEC 14443-3 (not MFRC522-specific, hence not detailed in the NXP datasheet). All frames except REQA/WUPA/anticollision use CRC_A (`TxCRCEn`/`RxCRCEn` = 1, `ModeReg.CRCPreset` = `0x6363`).

| Command | Bytes | Frame | Notes |
|---------|-------|-------|-------|
| REQA | `0x26` | 7-bit short frame, no CRC | `BitFramingReg.TxLastBits` = `0b111`; only wakes IDLE cards; response = 2-byte ATQA |
| WUPA | `0x52` | 7-bit short frame, no CRC | Same as REQA but also wakes HALTed cards |
| Anticollision CL1 | `0x93 0x20` | Standard frame, no CRC | Card responds with 4 bytes UID (or `0x88` cascade tag + 3 UID bytes if the UID is longer than 4 bytes) + 1 BCC byte (XOR of the 4 preceding bytes) |
| Select CL1 | `0x93 0x70 <5 bytes: UID+BCC>` + CRC_A | Standard frame, CRC | Response: 1-byte SAK + CRC_A. If SAK bit 2 (`0x04`, "UID not complete") is set, continue to CL2 |
| Anticollision CL2 | `0x95 0x20` | Standard frame, no CRC | Same pattern as CL1, using command byte `0x95` |
| Select CL2 | `0x95 0x70 <5 bytes>` + CRC_A | Standard frame, CRC | Same as CL1; if SAK bit 2 still set, continue to CL3 |
| Anticollision CL3 | `0x97 0x20` | Standard frame, no CRC | Same pattern, command byte `0x97` |
| Select CL3 | `0x97 0x70 <5 bytes>` + CRC_A | Standard frame, CRC | Completes the UID for the (rare) 10-byte case |
| HLTA | `0x50 0x00` + CRC_A | Standard frame, CRC | Card does not acknowledge; puts the currently selected card into HALT state |

UID length is determined by how many cascade levels are needed: 4 bytes (single size, e.g. MIFARE Classic) needs only CL1; 7 bytes (double size, e.g. most Ultralight/NTAG) needs CL1 (yielding `0x88` + 3 bytes) + CL2; 10 bytes (triple size) needs all three levels. The reassembled UID drops the `0x88` cascade tag bytes.

## MIFARE Classic Memory Commands

Require `MFAuthent` first for the sector containing the target block. Sent via `Transceive`, CRC_A appended.

| Command | Bytes sent to FIFO before `MFAuthent` | Notes |
|---------|-----------------------------------------|-------|
| `MFAuthent` | `0x60` (key A) or `0x61` (key B), block address, 6 key bytes, 4 UID bytes | 12 bytes total; sets `Status2Reg.MFCrypto1On` = 1 on success |

| Command | Bytes | Response | Notes |
|---------|-------|----------|-------|
| Read | `0x30`, block address + CRC_A | 16 data bytes + CRC_A | |
| Write | `0xA0`, block address + CRC_A | 4-bit ACK (`0xA`) | Then send 16 data bytes + CRC_A; card responds with another 4-bit ACK |
| Increment | `0xC1`, block address + CRC_A | 4-bit ACK | Then send 4-byte signed delta + CRC_A; card responds with no further ACK; result stays in the card's internal data register until `Transfer` |
| Decrement | `0xC0`, block address + CRC_A | 4-bit ACK | Same flow as Increment |
| Restore | `0xC2`, block address + CRC_A | 4-bit ACK | Then send 4 dummy bytes + CRC_A; loads the block's current value into the internal data register unchanged |
| Transfer | `0xB0`, block address + CRC_A | 4-bit ACK | Commits the internal data register to the named block (may differ from the block used in Increment/Decrement/Restore) |

A MIFARE Classic **value block** is a 16-byte block holding a 4-byte little-endian signed value (stored 3 times: normal, inverted, normal) plus a 1-byte address (stored 4 times). The driver is only responsible for the arithmetic command sequencing above — the value-block byte layout itself is a MIFARE Classic memory convention, not something the driver needs to construct manually beyond what `Increment`/`Decrement`/`Restore`/`Transfer` already handle on-card.

## MIFARE Ultralight / NTAG Memory Commands

No authentication required (Ultralight/NTAG use 4-byte pages, no Crypto1 by default).

| Command | Bytes | Response | Notes |
|---------|-------|----------|-------|
| Read | `0x30`, page address + CRC_A | 16 data bytes + CRC_A | Returns 4 consecutive pages (the requested page + next 3) |
| Write | `0xA2`, page address, 4 data bytes + CRC_A | 4-bit ACK | Writes exactly one 4-byte page |

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits everything and adds the rest.

### Minimal

Goal: detect a card in the field and read its UID, with no configuration beyond the transport and no MIFARE-specific knowledge required.

**Sensible defaults:** the register defaults baked in by the Initialization Sequence above (25 ms receive timeout, `Force100ASK` = 1, CRC_A preset, antenna on, reset-default 33 dB RX gain, 106 kBd — the universal baseline speed all ISO/IEC 14443 A cards support).

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | transport | — | Runs the Initialization Sequence |
| `isCardPresent()` | — | bool | Sends REQA; `true` if a card answered with a valid ATQA |
| `readUid()` | — | bytes \| None | Runs REQA → anticollision/select (all cascade levels as needed) → HLTA; returns the reassembled UID (4, 7, or 10 bytes) or `None`/`null`/empty if no card answered. Self-contained: a card read this way is immediately halted, so the next call re-detects it from scratch |

### Full

Goal: expose complete chip functionality — antenna/gain control, self test, the lower-level anticollision primitives, MIFARE Classic authenticated read/write/value operations, and MIFARE Ultralight/NTAG page read/write.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `reset()` | — | — | Re-runs `SoftReset` + the full Initialization Sequence |
| `antennaOn()` / `antennaOff()` | — | — | `TxControlReg` bits 0/1 |
| `setAntennaGain(dB)` | `dB: int` (one of 18, 23, 33, 38, 43, 48) | — | `RFCfgReg.RxGain` |
| `antennaGain()` | — | int (dB) | Reads back `RFCfgReg.RxGain` |
| `version()` | — | struct/tuple `{chipType, version}` | Raw `VersionReg` decode |
| `selfTest()` | — | bool | Runs the datasheet-defined digital self test; compares the 64 FIFO output bytes against the reference array matching the detected `version()` |
| `wakeupCard()` | — | bool | WUPA instead of REQA — also wakes HALTed cards |
| `selectCard()` | — | bytes \| None | Anticollision/select only (no automatic REQA and no automatic HLTA); leaves the card active/selected for subsequent authenticated operations. Returns the UID |
| `haltCard()` | — | — | Sends HLTA |
| `authenticate(blockAddress, keyType, key, uid)` | `blockAddress: int, keyType: A\|B, key: 6 bytes, uid: 4 bytes` | bool | Runs `MFAuthent`; `uid` is the card's plain 4-byte UID (for 7/10-byte UID cards, MIFARE Classic authentication is not applicable — those are Ultralight/NTAG which use the page commands instead) |
| `stopCrypto()` | — | — | Clears `Status2Reg.MFCrypto1On` directly; call after finishing authenticated access to a card before selecting/authenticating a different one |
| `readBlock(blockAddress)` | `blockAddress: int` | 16 bytes | Requires prior `authenticate()` for the containing sector |
| `writeBlock(blockAddress, data)` | `blockAddress: int, data: 16 bytes` | — | Requires prior `authenticate()` |
| `incrementValue(blockAddress, delta)` | `blockAddress: int, delta: uint32` | — | `Increment` + `Transfer` to the same block |
| `decrementValue(blockAddress, delta)` | `blockAddress: int, delta: uint32` | — | `Decrement` + `Transfer` to the same block |
| `restoreValue(blockAddress)` | `blockAddress: int` | — | `Restore` + `Transfer` to the same block (re-commits the unchanged value; used to reset the internal data register) |
| `transferValue(destinationBlock)` | `destinationBlock: int` | — | Low-level primitive: commits whatever is in the internal data register (left over from a prior Increment/Decrement/Restore) to `destinationBlock`, which may differ from the source |
| `readUltralightPage(pageAddress)` | `pageAddress: int` | 16 bytes (4 pages) | No authentication |
| `writeUltralightPage(pageAddress, data)` | `pageAddress: int, data: 4 bytes` | — | No authentication |
| `generateRandomId()` | — | 10 bytes | `Generate RandomID` command |

**Additional configuration options:** antenna gain (18/23/33/38/43/48 dB), key type (A/B) for authentication, arbitrary block/page addressing, value-block arithmetic.

## Data Conversion

No analog-to-digital conversion is involved — the MFRC522 is a digital protocol frontend. The only "conversion" the driver performs is CRC_A validation (compare `Status1Reg.CRCOk` or the frame's trailing 2 CRC bytes) and reassembling multi-cascade UIDs by dropping `0x88` cascade-tag bytes:

```
uid = cl1_bytes[1:4] + cl2_bytes            // if CL1 response starts with 0x88 (double-size UID)
uid = cl1_bytes[0:4]                        // if CL1 response has no cascade tag (single-size UID)
```

## Node-RED

Node name: `periph-mfrc522`
Package: `node-red-contrib-periph-rfid`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ present: bool, uid: string \| null }` | `uid` is the hex-encoded UID (e.g. `"04A1B2C3"`), `null` when no card is present |

Config panel fields: transport selection (SPI bus/CS pin, I²C bus/address, or UART port/baud), poll interval (ms).

### Demo flow

An inject node (repeat every 500 ms) feeds an `mfrc522` node in Minimal mode. A switch node routes on `msg.payload.present`: when true and `uid` differs from the previous message, a change node formats a "Card detected: `<uid>`" string into a debug node; a second debug node shows the raw payload on every poll for troubleshooting.

## Examples

### Demo

**Prepaid-card credit counter.** Simulates a simple transit-gate / vending-machine credit system using a MIFARE Classic card's value-block feature:

1. Poll for a card (Minimal `isCardPresent()` / Full `selectCard()`).
2. On detection, authenticate sector 1's block 4 with the well-known MIFARE factory default key A (`FF FF FF FF FF FF`) — the demo explains this key must be replaced with a per-deployment secret key in any real access-control system.
3. Read the value block; if uninitialized (all-zero UID/BCC pattern), write an initial credit balance of 10 via `writeBlock` in the value-block layout, then `restoreValue` to normalize it.
4. `decrementValue(blockAddress, 1)` to "spend" one credit; `readBlock` to print the remaining balance.
5. If the balance reaches 0, print "Access denied — no credits remaining" instead of decrementing.
6. `stopCrypto()`, `haltCard()`.

This exercises authentication, block read/write, and the increment/decrement/restore/transfer value-block cycle — the parts of the API a simple UID-only demo would never touch.

## Timing Constraints

- Oscillator/reset start-up: allow up to ~50 ms after `SoftReset` (or hard reset) before the chip is guaranteed ready; poll `CommandReg.PowerDown` == 0 rather than a fixed sleep where possible.
- Soft power-down exit: after clearing `CommandReg.PowerDown`, 1024 clock cycles (~37.74 µs at 27.12 MHz) before the chip responds again.
- The driver's own 25 ms receive timeout (`TReloadReg` = 1000 @ `TPrescaler` = 169) bounds how long `Transceive`/`Receive`/`MFAuthent` wait for a card response before the timer's `TimerIRq` fires; poll `ComIrqReg` (or `Status1Reg.TRunning`) rather than blocking indefinitely.
- MIFARE Classic `Write`/`Increment`/`Decrement`/`Restore`/`Transfer` each involve two RF round-trips (command+address, then data) — expect on the order of a few ms per operation at 106 kBd including card-side EEPROM write time.

## Implementation Notes

- **Antenna is off at reset.** `TxControlReg` resets to `0x80` (`Tx1RFEn`/`Tx2RFEn` = 0) — nothing is transmitted until the driver explicitly sets both bits.
- **`CommandReg` resets to `0x20`** (`RcvOff` = 1), not `0x00` — this is a datasheet-documented reset value, not a bug; it is not something the driver needs to correct, since any command write overwrites `Command[3:0]` and the receiver is (re-)enabled implicitly by `Receive`/`Transceive`.
- **CRC must be explicitly enabled per transfer speed.** `TxModeReg`/`RxModeReg` reset to `0x00` (`TxCRCEn`/`RxCRCEn` = 0). REQA/WUPA/anticollision frames never use CRC (and must not enable it); all other card communication (Select, HLTA, MFAuthent, Read/Write, value ops, Ultralight Read/Write) requires CRC_A, so the driver must set these bits before those transfers and may leave them set thereafter as long as it always uses standard (non-short, non-anticollision) frames.
- **Short frames use `BitFramingReg.TxLastBits`, not the FIFO length**, to signal 7-bit REQA/WUPA. Bitwise anticollision (`0x93 0x20` / `0x95 0x20` / `0x97 0x20`) does not use CRC and always runs at 106 kBd regardless of the configured `TxSpeed`/`RxSpeed` — the bitwise collision-detection scheme these commands rely on is only meaningful at that data rate.
- **UART burst register access is inferred, not directly documented.** The NXP datasheet's UART section (§8.1.3) only shows the single-byte read/write byte order (Tables 12/13); it does not spell out how multi-byte FIFO reads/writes should be framed the way the SPI section does (Tables 6/7). Since all three interfaces feed the same internal register bank (Fig. 1), this spec assumes the same "repeat the address byte per output byte" pattern documented for SPI applies to UART reads as well; treat this as the one part of the register-access framing to verify against real hardware if UART support is implemented.
- **`Status2Reg.MFCrypto1On` is a `D` (dynamic) bit** — the datasheet's access-type table defines `D` bits as writable by the host in addition to being set automatically by internal state machines, so `stopCrypto()` may clear it with a direct register write.
- **Never change the CRC preset away from `0x6363` (CRCPreset=`01`)** for card communication — `0x6363` is the ISO/IEC 14443-3 CRC_A initial value; any other preset produces a CRC the card will reject.
- **SAK bit 2 (`0x04`) signals cascade continuation**, not card type. Do not confuse it with SAK's other bits, which the ISO/IEC 14443-3 / MIFARE application family identifies via a separate lookup (out of scope for this driver — the driver only needs SAK to decide whether another cascade level is required).
- **The well-known MIFARE Classic factory-default key is `FF FF FF FF FF FF`** (key A, sector trailer default). Never hardcode this as a "real" access-control key in anything beyond the demo — it is publicly known and provides no security.

## Sigrok Decoder

Stacks on top of the `spi` protocol decoder (the most common wiring for this chip). Annotates the register address and R/W direction of every SPI transaction (address byte bit 7 = read/write, bits 6:1 = register, decoded via the register name table above), the register's symbolic name, and the byte(s) transferred. FIFO writes/reads (register `0x09`) are grouped and additionally decoded as recognized higher-level frames where the leading command byte matches a known ISO/IEC 14443-3 or MIFARE command (`REQA`, `WUPA`, anticollision/select per cascade level, `HLTA`, `MFAuthent`, MIFARE Read/Write/Increment/Decrement/Restore/Transfer, Ultralight Read/Write), annotated with the command name and its arguments (block/page address, key type). `CommandReg` writes (register `0x01`) are separately annotated with the MFRC522 command name (`Idle`, `Transceive`, `MFAuthent`, …). Decoder id: `mfrc522`.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/rfid/mfrc522.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/rfid/mfrc522/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/rfid/mfrc522/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/rfid/mfrc522/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/rfid/mfrc522_test.py` (MicroPython)
- [ ] Tests `python/tests/rfid/mfrc522_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/rfid/mfrc522_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/rfid/MFRC522.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/rfid/MFRC522.cpp`
- [ ] Examples `cpp/examples/MFRC522_Minimal/MFRC522_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/MFRC522_Complete/MFRC522_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/MFRC522_Demo/MFRC522_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/MFRC522_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/MFRC522_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/MFRC522_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/rfid/mfrc522_test/mfrc522_test.ino` (Arduino)
- [ ] Tests `cpp/tests/rfid/mfrc522_test_linux/mfrc522_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/rfid/mfrc522_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/rfid/mfrc522.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/rfid/mfrc522/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/rfid/mfrc522/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/rfid/mfrc522/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/rfid/mfrc522_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-rfid/nodes/mfrc522/mfrc522.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-rfid/nodes/mfrc522/mfrc522.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-rfid/examples/mfrc522/demo.json` — tab `info` field describes the scenario
- [ ] Package `nodejs/packages/node-red-contrib-periph-rfid/package.json` (new category — first RFID chip)

### Rust
- [ ] Driver `rust/periph/src/chips/rfid/mfrc522.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/mfrc522_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/mfrc522_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/mfrc522_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/rfid/mfrc522_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/rfid/mfrc522_test_esp32s3/src/main.rs` (ESP32-S3)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/rfid/MFRC522Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/rfid/MFRC522Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/rfid/MFRC522Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/rfid/MFRC522Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/rfid/MFRC522Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/rfid/MFRC522Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/rfid/mfrc522/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/rfid/mfrc522/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/rfid/mfrc522/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/rfid/mfrc522/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/rfid/mfrc522/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/rfid/mfrc522/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/rfid/mfrc522/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/rfid/mfrc522/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/rfid/mfrc522/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/rfid/mfrc522/MFRC522Test.java` (Pi hardware, JBang)

### Sigrok
- [ ] Decoder `sigrok/mfrc522/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/mfrc522/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` only

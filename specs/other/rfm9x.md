# Chip Spec: RFM9x (RFM95/96/97/98W)

**Manufacturer:** HOPERF  
**Datasheet:** `datasheets/other/RFM9x.pdf`  
**Category:** other  
**Transports:** SPI

## Variants

All four modules share identical pins, register maps, SPI protocol, and LoRa modem logic. They differ only in supported frequency bands and, for RFM97W, the maximum spreading factor.

| Part | Frequency Band | Band | Valid Freq Range | Max SF | Sensitivity |
|------|----------------|------|------------------|--------|-------------|
| RFM95W | 868 / 915 MHz | HF | 862–1020 MHz | 12 | −148 dBm |
| RFM96W | 433 / 470 MHz | LF | 410–525 MHz | 12 | −148 dBm |
| RFM97W | 868 / 915 MHz | HF | 862–1020 MHz | 9 | −139 dBm |
| RFM98W | 433 / 470 MHz | LF | 410–525 MHz | 12 | −148 dBm |

HF = high-frequency band (`LowFrequencyModeOn` = 0 in `RegOpMode`).  
LF = low-frequency band (`LowFrequencyModeOn` = 1).  
By default after POR, `LowFrequencyModeOn` = 1; driver must clear this for HF variants.

## Overview

The RFM95/96/97/98W are low-power LoRa transceiver modules built around Semtech's SX1276 RF IC. They implement LoRa spread-spectrum modulation for ultra-long-range, low-power wireless links (up to +20 dBm output, −148 dBm sensitivity, 168 dB link budget). Three independently configurable parameters — spreading factor (SF), signal bandwidth (BW), and coding rate (CR) — let the user trade off data rate, sensitivity, and airtime. The modules also support legacy FSK/OOK modulation; this spec covers LoRa mode only.

## Transport Configuration

### SPI

- **Mode:** CPOL=0, CPHA=0 (Mode 0)
- **Max clock:** 10 MHz
- **Bit order:** MSB first
- **CS (NSS) active:** low

### SPI Address Byte

Every SPI frame begins with one address byte:

| Bit 7 | Bits 6:0 |
|-------|----------|
| `WNR` | `ADDR[6:0]` |

`WNR` = 1 for write, 0 for read. The following byte(s) are data. In burst mode NSS stays low and the address auto-increments for non-FIFO registers; FIFO accesses (`0x00`) do not auto-increment.

### GPIO Pins

Beyond SPI, two GPIO pins are used by the driver:

| Pin | Direction | Purpose |
|-----|-----------|---------|
| NRESET (pin 6) | Output (open-drain / push-pull) | Hardware reset — active low |
| DIO0 (pin 14) | Input | Interrupt: RxDone (mapping 00) / TxDone (mapping 01) |

Both are optional at Minimal stage (polling used). DIO0 interrupt support is added in Full.

## Register Map (LoRa Mode)

Register mapping changes when LoRa mode is selected. The table below covers the registers used by this driver. FSK/OOK registers at the same addresses are not listed.

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00` | `RegFifo` | RW | `0x00` | FIFO data input/output |
| `0x01` | `RegOpMode` | RW | `0x09` | Operating mode + LoRa/FSK select |
| `0x06` | `RegFrfMsb` | RW | `0x6C` | RF carrier frequency bits 23:16 |
| `0x07` | `RegFrfMid` | RW | `0x80` | RF carrier frequency bits 15:8 |
| `0x08` | `RegFrfLsb` | RW | `0x00` | RF carrier frequency bits 7:0 |
| `0x09` | `RegPaConfig` | RW | `0x4F` | PA selection and output power |
| `0x0B` | `RegOcp` | RW | `0x2B` | Over-current protection |
| `0x0C` | `RegLna` | RW | `0x20` | LNA gain and boost |
| `0x0D` | `RegFifoAddrPtr` | RW | `0x00` | FIFO SPI address pointer |
| `0x0E` | `RegFifoTxBaseAddr` | RW | `0x80` | TX buffer base address |
| `0x0F` | `RegFifoRxBaseAddr` | RW | `0x00` | RX buffer base address |
| `0x10` | `RegFifoRxCurrentAddr` | R | n/a | Start of last received packet in FIFO |
| `0x11` | `RegIrqFlagsMask` | RW | `0x00` | Mask bits for `RegIrqFlags` (1 = masked) |
| `0x12` | `RegIrqFlags` | RW | `0x00` | IRQ status; write bit to clear |
| `0x13` | `RegRxNbBytes` | R | n/a | Bytes in last received payload |
| `0x18` | `RegModemStat` | R | n/a | Live modem status |
| `0x19` | `RegPktSnrValue` | R | n/a | SNR of last packet (signed, ×4) |
| `0x1A` | `RegPktRssiValue` | R | n/a | RSSI of last packet |
| `0x1B` | `RegRssiValue` | R | n/a | Current RSSI |
| `0x1C` | `RegHopChannel` | R | n/a | PLL timeout, CRC flag, FHSS channel |
| `0x1D` | `RegModemConfig1` | RW | `0x72` | BW, coding rate, header mode |
| `0x1E` | `RegModemConfig2` | RW | `0x70` | SF, CRC, symbol timeout MSB |
| `0x1F` | `RegSymbTimeoutLsb` | RW | `0x64` | Symbol timeout LSB |
| `0x20` | `RegPreambleMsb` | RW | `0x00` | Preamble length MSB |
| `0x21` | `RegPreambleLsb` | RW | `0x08` | Preamble length LSB (default = 8) |
| `0x22` | `RegPayloadLength` | RW | `0x01` | Payload length (implicit header mode) |
| `0x23` | `RegMaxPayloadLength` | RW | `0xFF` | Max payload; excess triggers header CRC error |
| `0x26` | `RegModemConfig3` | RW | `0x00` | AGC auto, mobile-node |
| `0x42` | `RegVersion` | R | `0x12` | Silicon revision (0x12 = SX1276) |
| `0x4D` | `RegPaDac` | RW | `0x84` | +20 dBm high-power PA mode |

### Bit Fields

#### `RegOpMode` (`0x01`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `LongRangeMode` | 0 = FSK/OOK, 1 = LoRa — **writable only in SLEEP mode** |
| 6 | `AccessSharedReg` | 1 = access FSK registers 0x0D–0x3F while in LoRa mode |
| 3 | `LowFrequencyModeOn` | 0 = HF band (>525 MHz), 1 = LF band (<525 MHz); default 1 |
| 2:0 | `Mode` | See mode table below |

| `Mode` | Value | Description |
|--------|-------|-------------|
| SLEEP | `000` | Low power; FIFO inaccessible; only mode where LongRangeMode can change |
| STDBY | `001` | Crystal and baseband on; RF/PLL off; FIFO accessible |
| FSTX | `010` | Frequency synthesis TX — PLL locked at TX frequency |
| TX | `011` | Transmit; returns to STDBY on TxDone |
| FSRX | `100` | Frequency synthesis RX |
| RXCONTINUOUS | `101` | Continuous receive |
| RXSINGLE | `110` | Single receive; returns to STDBY on RxDone or RxTimeout |
| CAD | `111` | Channel activity detection |

#### `RegPaConfig` (`0x09`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `PaSelect` | 0 = RFO pin (max +14 dBm), 1 = PA_BOOST pin (max +17 dBm / +20 dBm) |
| 6:4 | `MaxPower` | Pmax = 10.8 + 0.6 × MaxPower dBm (RFO only) |
| 3:0 | `OutputPower` | See power formulas in Data Conversion |

#### `RegLna` (`0x0C`)

| Bits | Name | Description |
|------|------|-------------|
| 7:5 | `LnaGain` | 001 = max, 010–101 = G2–G5, 110 = min |
| 1:0 | `LnaBoostHf` | `11` = 150% LNA current (HF band only, recommended) |

#### `RegIrqFlags` (`0x12`) — write 1 to clear

| Bit | Name | Description |
|-----|------|-------------|
| 7 | `RxTimeout` | Receive timed out (RXSINGLE mode) |
| 6 | `RxDone` | Packet reception complete |
| 5 | `PayloadCrcError` | CRC error in received payload |
| 4 | `ValidHeader` | Valid header received in explicit mode |
| 3 | `TxDone` | Packet transmission complete |
| 2 | `CadDone` | CAD complete |
| 1 | `FhssChangeChannel` | FHSS hop required |
| 0 | `CadDetected` | LoRa preamble detected during CAD |

#### `RegModemConfig1` (`0x1D`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4 | `Bw` | Signal bandwidth — see BW table below |
| 3:1 | `CodingRate` | 001=4/5, 010=4/6, 011=4/7, 100=4/8 |
| 0 | `ImplicitHeaderModeOn` | 0 = explicit header, 1 = implicit header |

| `Bw` | Bandwidth | Note |
|------|-----------|------|
| `0000` | 7.8 kHz | |
| `0001` | 10.4 kHz | |
| `0010` | 15.6 kHz | |
| `0011` | 20.8 kHz | |
| `0100` | 31.25 kHz | |
| `0101` | 41.7 kHz | |
| `0110` | 62.5 kHz | |
| `0111` | 125 kHz | Default |
| `1000` | 250 kHz | Not supported on LF band (169 MHz) |
| `1001` | 500 kHz | Not supported on LF band (169 MHz) |

#### `RegModemConfig2` (`0x1E`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4 | `SpreadingFactor` | 6–12 (RFM97W max 9); default 7 |
| 3 | `TxContinuousMode` | 1 = continuous TX (spectral analysis only) |
| 2 | `RxPayloadCrcOn` | 1 = append / verify CRC |
| 1:0 | `SymbTimeout[9:8]` | RX symbol timeout MSB |

#### `RegModemConfig3` (`0x26`)

| Bits | Name | Description |
|------|------|-------------|
| 3 | `MobileNode` | 0 = static, 1 = mobile (enables additional frequency tracking) |
| 2 | `AgcAutoOn` | 1 = AGC loop controls LNA gain automatically (recommended) |

#### `RegHopChannel` (`0x1C`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `PllTimeout` | 1 = PLL failed to lock during TX/RX/CAD |
| 6 | `RxPayloadCrcOn` | CRC presence as signalled in received header |
| 5:0 | `FhssPresentChannel` | Current FHSS channel |

#### DIO Mapping (LoRa mode, `RegDioMapping1` `0x40`)

| Mapping | DIO0 | DIO1 | DIO3 |
|---------|------|------|------|
| `00` | RxDone | RxTimeout | CadDone |
| `01` | TxDone | FhssChangeChannel | ValidHeader |
| `10` | CadDone | CadDetected | PayloadCrcError |

Set via `RegDioMapping1[7:6]` for DIO0, `[5:4]` for DIO1, `[3:2]` for DIO2, `[1:0]` for DIO3.  
For TX: set DIO0 mapping to `01` before entering TX mode; restore to `00` for RX.

## Initialization Sequence

1. **Hardware reset** (if NRESET pin available): assert low > 100 µs, release, wait 5 ms.  
   Without reset pin: wait 10 ms after power-on (POR completes).
2. **Enter SLEEP mode:** write `RegOpMode` = `0x00` (LongRangeMode=0, Mode=SLEEP).
3. **Select LoRa mode:** write `RegOpMode` = `0x80` (LongRangeMode=1, Mode=SLEEP).  
   LongRangeMode can only be changed in SLEEP mode.
4. **Set band:** for HF variants (RFM95W, RFM97W) clear `LowFrequencyModeOn` (bit 3) in `RegOpMode` → write `0x88`.  
   For LF variants (RFM96W, RFM98W) leave `LowFrequencyModeOn` = 1 → write `0x80`.
5. **Configure FIFO base addresses:** `RegFifoTxBaseAddr` = `0x80`, `RegFifoRxBaseAddr` = `0x00` (default split) — or both to `0x00` for maximum single-mode FIFO.
6. **LNA boost** (HF only): `RegLna` = `0x23` (LnaGain=max, LnaBoostHf=11).
7. **Enable AGC:** `RegModemConfig3` |= `0x04`.
8. **Set carrier frequency:** write 24-bit `Frf` value to `RegFrfMsb/Mid/Lsb`.
9. **Set modem parameters:** write `RegModemConfig1` (BW, CR, header mode), `RegModemConfig2` (SF, CRC).
10. **Enter STANDBY mode:** `RegOpMode` = `0x81` (or `0x89` for LF).

## Implementation Stages

The `_RFM9xBase` class holds all register-level logic and takes `frequency_hz` as a constructor parameter. Four thin variant subclasses supply their frequency limits, maximum SF, and band flag. Minimal and Full follow the two-stage structure layered on top.

### Class Hierarchy

```
_RFM9xBase                  ← all register logic, accepts frequency_hz
    RFM95Minimal(RFM9xBase) ← HF, 862–1020 MHz, max SF=12
    RFM96Minimal(RFM9xBase) ← LF, 410–525 MHz, max SF=12
    RFM97Minimal(RFM9xBase) ← HF, 862–1020 MHz, max SF=9
    RFM98Minimal(RFM9xBase) ← LF, 410–525 MHz, max SF=12

    RFM95Full(RFM95Minimal)
    RFM96Full(RFM96Minimal)
    RFM97Full(RFM97Minimal)
    RFM98Full(RFM98Minimal)
```

Each variant subclass defines three class-level constants: `FREQ_MIN_HZ`, `FREQ_MAX_HZ`, `MAX_SF`, and `_LF_BAND` (bool). `_RFM9xBase.__init__` validates `frequency_hz` against these and raises `ValueError` if out of range.

### Minimal

Goal: send and receive LoRa packets with sensible defaults; no configuration required beyond the transport and frequency.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | transport, `frequency_hz: int` | — | Full init sequence; polling only (no GPIO) |
| `send` | `data: bytes` | — | STDBY → fill FIFO → TX → poll TxDone → STDBY; max 255 bytes |
| `receive` | `timeout_ms: int = 2000` | `bytes \| None` | RXSINGLE → poll RxDone/RxTimeout → read FIFO; None on timeout |

**Sensible defaults:**
- SF = 7, BW = 125 kHz, CR = 4/5
- Explicit header mode
- CRC enabled
- Preamble length = 8
- TX power: PA_BOOST, OutputPower = 15 → +17 dBm
- AGC enabled, LNA boost (HF) enabled
- FIFO split: TX base = 0x80, RX base = 0x00

### Full

Goal: expose complete LoRa functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `init` | transport, `frequency_hz: int`, `reset_pin=None`, `dio0_pin=None` | — | Adds optional GPIO for reset and DIO0 interrupt |
| `reset` | — | — | Hardware reset via NRESET pin; raises if not configured |
| `configure` | `sf: int`, `bandwidth_khz: float`, `coding_rate: int` (5–8), `crc: bool` | — | Validates SF against variant max; validates BW for LF band |
| `set_frequency` | `frequency_hz: int` | — | Change carrier frequency; validates range; requires STDBY or SLEEP |
| `set_tx_power` | `power_dbm: int`, `use_pa_boost: bool = True` | — | −1 to +14 dBm (RFO) or +2 to +20 dBm (PA_BOOST); configures `RegPaDac` for +20 dBm |
| `receive` | `timeout_ms: int = 2000`, `use_interrupt: bool = False` | `bytes \| None` | Interrupt mode requires `dio0_pin` |
| `receive_continuous` | — | — | Enters RXCONTINUOUS; subsequent `read_packet()` calls return buffered packets |
| `read_packet` | — | `bytes \| None` | Read one packet from FIFO in continuous mode; None if nothing ready |
| `stop_receive` | — | — | Return to STANDBY from RXCONTINUOUS |
| `rssi` | — | `float` | Current channel RSSI in dBm (readable in RXCONTINUOUS) |
| `last_packet_rssi` | — | `float` | RSSI of last received packet in dBm |
| `last_packet_snr` | — | `float` | SNR of last received packet in dB |
| `sleep` | — | — | Enter SLEEP mode |
| `standby` | — | — | Enter STANDBY mode |
| `version` | — | `int` | Read `RegVersion`; expect `0x12` |

**Additional configuration options:**
- SF 6–12 (variant-capped), all 10 BW settings, CR 4/5–4/8
- PA_BOOST / RFO path selection with full power range
- +20 dBm high-power mode (PA_BOOST + `RegPaDac` = `0x87`)
- DIO0 interrupt-driven receive (reduces CPU polling)
- Continuous receive mode for always-on receiver applications
- Hardware reset

## Data Conversion

### Carrier Frequency

```
Frf = round(frequency_hz * 2**19 / FXOSC)   # FXOSC = 32_000_000 Hz

RegFrfMsb = (Frf >> 16) & 0xFF
RegFrfMid = (Frf >>  8) & 0xFF
RegFrfLsb =  Frf        & 0xFF
```

Common values: 868 MHz → `0xD9_00_00`, 915 MHz → `0xE4_C0_00`, 433 MHz → `0x6C_80_00`.

### TX Power

**PA_BOOST pin** (`PaSelect` = 1):
```
OutputPower = power_dbm - 2           # Pout = 2 + OutputPower, range 2–17 dBm
```
For +20 dBm: set `OutputPower` = 15 AND write `RegPaDac` = `0x87`, set OCP trim to 240 mA.  
Reset `RegPaDac` to `0x84` for all other power levels.

**RFO pin** (`PaSelect` = 0):
```
Pmax_dbm = 10.8 + 0.6 * MaxPower      # MaxPower 0–7
OutputPower = power_dbm - Pmax_dbm + 15
```
Maximum ~+14 dBm.

### RSSI

```
packet_rssi_dbm = -137 + RegPktRssiValue    # last packet (0x1A)
current_rssi_dbm = -137 + RegRssiValue      # live channel (0x1B)
```

### SNR

```
snr_db = int8(RegPktSnrValue) / 4.0    # raw value is two's complement, unit = 0.25 dB
```

If SNR < 0 the signal is below the noise floor; if SNR ≥ 0 the signal is above.

### Symbol Rate and Time on Air

```
Rs = BW_hz / 2**SF                             # symbol rate (symbols/s)
Ts = 1 / Rs                                    # symbol period (s)

T_preamble = (preamble_length + 4.25) * Ts

# Explicit header (n = payload bytes):
n_payload = 8 + max(ceil((8*n - 4*SF + 44) / (4*SF)) * (CR+4), 0)
T_payload  = n_payload * Ts
T_packet   = T_preamble + T_payload
```

## Node-RED

Node name: `periph-rfm9x`  
Package: `node-red-contrib-periph-other`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| `msg.payload` (`Buffer` / `string`) | — | Transmit; string is UTF-8 encoded |
| any message to `receive` input | `{ data: Buffer, rssi: number, snr: number }` | Receive; fires on packet arrival |

Config panel fields:
- **SPI bus** — bus number (e.g., `0`)
- **SPI CS** — chip-select pin number
- **Variant** — `RFM95W` / `RFM96W` / `RFM97W` / `RFM98W`
- **Frequency (Hz)** — carrier frequency, validated against variant range
- **Spreading Factor** — 7–12 (or 6–9 for RFM97W)
- **Bandwidth** — dropdown of 10 values
- **Coding Rate** — 4/5 / 4/6 / 4/7 / 4/8
- **TX Power (dBm)** — numeric
- **CRC** — checkbox (default on)

### Demo flow

A LoRa ping-pong flow: one Inject node fires every 5 seconds and sends a UTF-8 string through `periph-rfm9x` (TX). A second `periph-rfm9x` node is wired as a receiver; its output feeds a Debug node that displays the payload, RSSI, and SNR. The flow demonstrates back-to-back send and receive on the same device via a loop-back (second module required for full demonstration, but the flow shows both nodes configured identically at 868 MHz, SF7, 125 kHz, 4/5).

## Examples

### Demo

Two-node link test on a desk: configure the driver at 868 MHz (or 433 MHz for RFM96/98), SF=7, BW=125 kHz, CR=4/5. In a loop, transmit an incrementing counter as a 4-byte big-endian integer, then immediately call `receive(timeout_ms=1000)` waiting for an echo (requires a second module running the same code in receive-echo mode). Print the round-trip time, the received RSSI, and the SNR on each successful exchange. After 10 iterations print the packet loss count. This shows the full TX→RX path and gives visible per-packet link quality numbers.

## Timing Constraints

- **POR ready:** 10 ms after VDD stable before any SPI access.
- **Manual reset ready:** 5 ms after NRESET released.
- **SLEEP → STDBY transition:** oscillator startup ≈ 250 µs (crystal dependent).
- **STDBY → TX:** PLL lock ≈ 100 µs; PA ramp = 40 µs (default `PaRamp` = `0x09`).
- **Frequency registers** (`RegFrfMsb/Mid/Lsb`): must only be written in SLEEP or STANDBY mode.
- **LoRa/FSK mode bit** (`LongRangeMode`): must only be written in SLEEP mode; writes in other modes are silently ignored.
- **FIFO access:** not available in SLEEP mode; write FIFO only in STANDBY mode.

## Implementation Notes

- **Mode-change-to-LoRa sequence is critical:** write `RegOpMode` = `0x00` first (FSK SLEEP), then `0x80` (LoRa SLEEP). Writing `0x81` (LoRa STDBY) without going through SLEEP first will not switch to LoRa mode.
- **Band flag:** After entering LoRa mode, `LowFrequencyModeOn` (bit 3 of `RegOpMode`) must be set correctly for the variant. HF variants need it cleared (write `0x88` for LoRa SLEEP HF). The HF LNA boost (`RegLna` = `0x23`) is also HF-only; do not set it for LF variants.
- **SF=6 special case:** SF=6 forces implicit header mode. In addition write `0x05` to bits 2:0 of register `0x31`, and `0x0C` to register `0x37`. This is handled automatically by `configure(sf=6, ...)`.
- **TxDone IRQ must be cleared** by writing `0x08` to `RegIrqFlags` after each transmission, otherwise the next TX check will see a stale flag.
- **RxDone FIFO extraction:** set `RegFifoAddrPtr` = `RegFifoRxCurrentAddr` before reading `RegFifo`; do not use `RegFifoRxBaseAddr` directly, as continuous mode may have wrapped the buffer.
- **+20 dBm mode** requires `RegPaDac` = `0x87` AND OCP trim to 240 mA (`RegOcp` = `0x3B`). Reset both to defaults (`0x84`, `0x2B`) for all lower power levels to avoid damaging the PA.
- **RegVersion** should read `0x12`. Reading a different value (e.g., `0x00` or `0xFF`) indicates a wiring or SPI configuration error; the driver should raise an error during `init`.
- **Bandwidth and LF band:** 250 kHz and 500 kHz are not supported on the 169 MHz sub-band. The driver validates BW against the variant's frequency range and raises `ValueError` if incompatible.
- **RSSI on LF band:** The −137 offset shown in the HopeRF datasheet matches the underlying SX1276 HF formula. Some SX1276 documentation uses −164 for LF band. Calibrate against a known signal level if absolute RSSI accuracy matters for LF variants.
- **SPI max rate:** 10 MHz. The SPI clock must be stable before NSS goes low and must stop before NSS goes high again on each frame.

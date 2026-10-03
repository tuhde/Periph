# Chip Spec: LIS3DH

**Manufacturer:** STMicroelectronics  
**Datasheet:** `datasheets/accelerometer/lis3dh.pdf`  
**Category:** accelerometer  
**Transports:** I²C (the chip also supports 3- and 4-wire SPI; out of scope for this issue — see Implementation Notes)

## Overview

The LIS3DH is an ultra-low-power, three-axis "nano" MEMS accelerometer with 16-bit left-justified output and four selectable full-scale ranges (±2, ±4, ±8, ±16 *g*). Three operating modes trade resolution for current — low-power (8-bit, 2 µA at 1 Hz), normal (10-bit) and high-resolution (12-bit) — across output data rates from 1 Hz to 5.376 kHz. It adds a 32-level FIFO (bypass / FIFO / stream / stream-to-FIFO), a selectable high-pass filter, two independent programmable inertial interrupt generators (wake-up, free-fall, 6D/4D orientation), single/double-click detection, a sleep-to-wake / return-to-sleep activity function, a self-test actuator, and an auxiliary three-channel 10-bit ADC whose third channel can be switched to an on-chip temperature sensor. Typical uses: orientation and tilt, free-fall and shock detection, tap gestures, motion wake-up for battery devices, vibration logging.

## Transport Configuration

### I²C
- **Address:** `0x18` (SA0 = GND) — `0x19` (SA0 = VDD_IO)
- **Max clock:** 400 kHz (fast mode)
- **Identity register:** `0x0F` = `0x33` (`WHO_AM_I`) — read with mask `0xFF`, expected `0x33`
- **Probe safety:** `register_pointer` — the identity read has no side effects

I²C framing: after the slave address the master sends an 8-bit sub-address `SUB`: the 7 LSbs are the register address and the **MSb enables address auto-increment**. A single-register access uses `SUB = reg`; a multi-byte read or write uses `SUB = reg | 0x80` (e.g. `0xA8` to burst-read the six output registers). A read is `SUB` write, repeated START, slave address with R/W = 1. CS must be tied to VDD_IO to select I²C (CS = 0 selects SPI); SA0 selects the address and must not float. The device is fast-mode compliant only; no high-speed mode.

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| INT1 | high (default), push-pull | inertial interrupt 1 / data-ready / FIFO events; polarity set by `INT_POLARITY` (`CTRL_REG6` bit 1) — drives low when idle |
| INT2 | high (default), push-pull | inertial interrupt 2 / click / activity / boot; same polarity setting |
| CS | — | tie to VDD_IO for I²C |
| SDO/SA0 | — | GND → `0x18`, VDD_IO → `0x19` |
| ADC1, ADC2, ADC3 | — | auxiliary ADC inputs (800–1600 mV); leave floating or tie to VDD/GND when unused |
| RES | — | connect to GND |

Both INT pins are push-pull outputs forced to GND until configured (datasheet Table 13) — no external pull-up is required, and wired-OR sharing of the lines is not possible.

## Register Map

Registers `0x00–0x06`, `0x0E`, `0x10–0x1D` are reserved and must not be written (writing them may damage the part). Registers loaded at boot (the factory trim) must not be changed. After power-up the boot procedure completes in ≈ 5 ms.

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x07` | STATUS_REG_AUX | R | — | Aux ADC overrun (`321OR`, `3OR`, `2OR`, `1OR`) and data-available (`321DA`, `3DA`, `2DA`, `1DA`) flags |
| `0x08` | OUT_ADC1_L | R | — | Aux ADC channel 1, low byte |
| `0x09` | OUT_ADC1_H | R | — | Aux ADC channel 1, high byte |
| `0x0A` | OUT_ADC2_L | R | — | Aux ADC channel 2, low byte |
| `0x0B` | OUT_ADC2_H | R | — | Aux ADC channel 2, high byte |
| `0x0C` | OUT_ADC3_L | R | — | Aux ADC channel 3 / temperature, low byte |
| `0x0D` | OUT_ADC3_H | R | — | Aux ADC channel 3 / temperature, high byte |
| `0x0F` | WHO_AM_I | R | `0x33` | Device identification |
| `0x1E` | CTRL_REG0 | R/W | `0x10` | `SDO_PU_DISC` bit 7; **bit 4 must stay 1, bits 6:5 and 3:0 must stay 0** |
| `0x1F` | TEMP_CFG_REG | R/W | `0x00` | `ADC_EN` bit 7, `TEMP_EN` bit 6 |
| `0x20` | CTRL_REG1 | R/W | `0x07` | `ODR<3:0>` 7:4, `LPen` 3, `Zen`/`Yen`/`Xen` 2:0 |
| `0x21` | CTRL_REG2 | R/W | `0x00` | High-pass filter mode, cutoff, data select, per-function enables |
| `0x22` | CTRL_REG3 | R/W | `0x00` | Interrupt routing to INT1 |
| `0x23` | CTRL_REG4 | R/W | `0x00` | `BDU` 7, `BLE` 6, `FS<1:0>` 5:4, `HR` 3, `ST<1:0>` 2:1, `SIM` 0 |
| `0x24` | CTRL_REG5 | R/W | `0x00` | `BOOT` 7, `FIFO_EN` 6, latch / 4D enables |
| `0x25` | CTRL_REG6 | R/W | `0x00` | Interrupt routing to INT2, `INT_POLARITY` |
| `0x26` | REFERENCE | R/W | `0x00` | High-pass reference value for interrupt generation (reading it resets the filter in HPM `00`) |
| `0x27` | STATUS_REG | R | — | `ZYXOR`, `ZOR`, `YOR`, `XOR`, `ZYXDA`, `ZDA`, `YDA`, `XDA` |
| `0x28` | OUT_X_L | R | — | X acceleration, low byte |
| `0x29` | OUT_X_H | R | — | X acceleration, high byte |
| `0x2A` | OUT_Y_L | R | — | Y acceleration, low byte |
| `0x2B` | OUT_Y_H | R | — | Y acceleration, high byte |
| `0x2C` | OUT_Z_L | R | — | Z acceleration, low byte |
| `0x2D` | OUT_Z_H | R | — | Z acceleration, high byte |
| `0x2E` | FIFO_CTRL_REG | R/W | `0x00` | `FM<1:0>` 7:6, `TR` 5, `FTH<4:0>` 4:0 |
| `0x2F` | FIFO_SRC_REG | R | — | `WTM`, `OVRN_FIFO`, `EMPTY`, `FSS<4:0>` |
| `0x30` | INT1_CFG | R/W | `0x00` | `AOI`, `6D`, per-axis high/low event enables |
| `0x31` | INT1_SRC | R | — | `IA` and per-axis event flags; reading clears |
| `0x32` | INT1_THS | R/W | `0x00` | 7-bit threshold |
| `0x33` | INT1_DURATION | R/W | `0x00` | 7-bit minimum duration (1 LSb = 1/ODR) |
| `0x34` | INT2_CFG | R/W | `0x00` | as `INT1_CFG` |
| `0x35` | INT2_SRC | R | — | as `INT1_SRC` |
| `0x36` | INT2_THS | R/W | `0x00` | as `INT1_THS` |
| `0x37` | INT2_DURATION | R/W | `0x00` | as `INT1_DURATION` |
| `0x38` | CLICK_CFG | R/W | `0x00` | Per-axis single / double click enables |
| `0x39` | CLICK_SRC | R | — | Click flags, sign, axis; reading clears |
| `0x3A` | CLICK_THS | R/W | `0x00` | `LIR_Click` bit 7, 7-bit click threshold |
| `0x3B` | TIME_LIMIT | R/W | `0x00` | Click time limit, 7 bits |
| `0x3C` | TIME_LATENCY | R/W | `0x00` | Click latency, 8 bits |
| `0x3D` | TIME_WINDOW | R/W | `0x00` | Click window, 8 bits |
| `0x3E` | ACT_THS | R/W | `0x00` | Sleep-to-wake activation threshold, 7 bits |
| `0x3F` | ACT_DUR | R/W | `0x00` | Sleep-to-wake duration, 8 bits |

### Bit Fields

#### `CTRL_REG1` (`0x20`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4 | ODR<3:0> | Output data rate / power-down — see table below |
| 3 | LPen | 1 = low-power mode (8-bit); with `HR` selects the operating mode |
| 2 | Zen | Z axis enable |
| 1 | Yen | Y axis enable |
| 0 | Xen | X axis enable |

| `ODR<3:0>` | Rate | Notes |
|------------|------|-------|
| `0000` | — | Power-down |
| `0001` | 1 Hz | all modes |
| `0010` | 10 Hz | all modes |
| `0011` | 25 Hz | all modes |
| `0100` | 50 Hz | all modes |
| `0101` | 100 Hz | all modes |
| `0110` | 200 Hz | all modes |
| `0111` | 400 Hz | all modes |
| `1000` | 1.620 kHz | low-power mode only |
| `1001` | 1.344 kHz / 5.376 kHz | 1.344 kHz in normal / high-resolution mode, 5.376 kHz in low-power mode |

| `LPen` | `HR` | Mode | Output | Bandwidth | Turn-on time |
|--------|------|------|--------|-----------|--------------|
| 1 | 0 | Low-power | 8-bit | ODR/2 | 1 ms |
| 0 | 0 | Normal | 10-bit | ODR/2 | 1.6 ms |
| 0 | 1 | High-resolution | 12-bit | ODR/9 | 7/ODR |
| 1 | 1 | not allowed | — | — | — |

#### `CTRL_REG2` (`0x21`)

| Bits | Name | Description |
|------|------|-------------|
| 7:6 | HPM<1:0> | High-pass mode: `00` normal (reset by reading `REFERENCE`), `01` reference signal for filtering, `10` normal, `11` auto-reset on interrupt event |
| 5:4 | HPCF<2:1> | High-pass cutoff selection (cutoff frequency in Hz depends on ODR; not tabulated in this datasheet revision) |
| 3 | FDS | 1 = filtered data goes to the output registers and FIFO; 0 = filter bypassed |
| 2 | HPCLICK | High-pass filter enabled for the click function |
| 1 | HP_IA2 | High-pass filter enabled for interrupt generator 2 |
| 0 | HP_IA1 | High-pass filter enabled for interrupt generator 1 |

#### `CTRL_REG3` (`0x22`) — routing to INT1

| Bits | Name | Description |
|------|------|-------------|
| 7 | I1_CLICK | Click interrupt on INT1 |
| 6 | I1_IA1 | Generator 1 interrupt on INT1 |
| 5 | I1_IA2 | Generator 2 interrupt on INT1 |
| 4 | I1_ZYXDA | New XYZ data available on INT1 |
| 3 | I1_321DA | New aux ADC data available on INT1 |
| 2 | I1_WTM | FIFO watermark on INT1 |
| 1 | I1_OVERRUN | FIFO overrun on INT1 |
| 0 | — | Reserved |

#### `CTRL_REG4` (`0x23`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | BDU | 1 = output registers not updated until both MSB and LSB are read (block data update) |
| 6 | BLE | 0 = LSB at lower address (driver keeps 0); 1 = MSB at lower address |
| 5:4 | FS<1:0> | Full scale: `00` ±2 *g*, `01` ±4 *g*, `10` ±8 *g*, `11` ±16 *g* |
| 3 | HR | High-resolution mode enable (with `LPen` = 0) |
| 2:1 | ST<1:0> | Self-test: `00` off, `01` self-test 0, `10` self-test 1, `11` not allowed |
| 0 | SIM | SPI mode: 0 = 4-wire, 1 = 3-wire (ignored on I²C) |

#### `CTRL_REG5` (`0x24`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | BOOT | 1 = reboot memory content (reloads factory trim) |
| 6 | FIFO_EN | 1 = FIFO enabled |
| 5:4 | — | Reserved |
| 3 | LIR_INT1 | Latch generator-1 interrupt until `INT1_SRC` is read |
| 2 | D4D_INT1 | 4D detection on generator 1 (effective when `6D` is set in `INT1_CFG`) |
| 1 | LIR_INT2 | Latch generator-2 interrupt until `INT2_SRC` is read |
| 0 | D4D_INT2 | 4D detection on generator 2 |

#### `CTRL_REG6` (`0x25`) — routing to INT2

| Bits | Name | Description |
|------|------|-------------|
| 7 | I2_CLICK | Click interrupt on INT2 |
| 6 | I2_IA1 | Generator 1 interrupt on INT2 |
| 5 | I2_IA2 | Generator 2 interrupt on INT2 |
| 4 | I2_BOOT | Boot-running indication on INT2 |
| 3 | I2_ACT | Activity (sleep-to-wake) interrupt on INT2 |
| 2 | — | Reserved |
| 1 | INT_POLARITY | 0 = INT1 and INT2 active-high, 1 = active-low |
| 0 | — | Reserved |

#### `STATUS_REG` (`0x27`) and `STATUS_REG_AUX` (`0x07`)

| Reg | Bit 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
|-----|-------|---|---|---|---|---|---|---|
| `0x27` | ZYXOR | ZOR | YOR | XOR | ZYXDA | ZDA | YDA | XDA |
| `0x07` | 321OR | 3OR | 2OR | 1OR | 321DA | 3DA | 2DA | 1DA |

`*OR` = a new sample overwrote an unread one; `*DA` = new data available. Both reset by reading the corresponding output registers.

#### `FIFO_CTRL_REG` (`0x2E`) and `FIFO_SRC_REG` (`0x2F`)

| Bits | Name | Description |
|------|------|-------------|
| `0x2E` 7:6 | FM<1:0> | `00` bypass, `01` FIFO, `10` stream, `11` stream-to-FIFO |
| `0x2E` 5 | TR | Trigger source for stream-to-FIFO: 0 = INT1, 1 = INT2 |
| `0x2E` 4:0 | FTH<4:0> | Watermark level |
| `0x2F` 7 | WTM | FIFO content exceeds the watermark |
| `0x2F` 6 | OVRN_FIFO | FIFO is full (32 unread samples); the next sample overwrites the oldest |
| `0x2F` 5 | EMPTY | All samples read; FIFO empty |
| `0x2F` 4:0 | FSS<4:0> | Number of unread samples |

#### `INTx_CFG` (`0x30`, `0x34`), `INTx_SRC` (`0x31`, `0x35`)

| Reg | Bit 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
|-----|-------|---|---|---|---|---|---|---|
| `INTx_CFG` | AOI | 6D | ZHIE | ZLIE | YHIE | YLIE | XHIE | XLIE |
| `INTx_SRC` | 0 | IA | ZH | ZL | YH | YL | XH | XL |

| `AOI` | `6D` | Interrupt mode |
|-------|------|----------------|
| 0 | 0 | OR combination of the enabled events |
| 0 | 1 | 6-direction movement recognition (fires when the orientation moves from an unknown to a known zone; lasts one ODR period) |
| 1 | 0 | AND combination of the enabled events |
| 1 | 1 | 6-direction position recognition (stays active while the orientation is inside a known zone) |

`xHIE` / `xLIE` enable an event when the (optionally high-pass filtered) acceleration on that axis is above / below the threshold `INTx_THS` (the same bits select the direction zones in 6D modes). Writes to `INT1_CFG` / `INT2_CFG` are possible only after boot completes. Reading `INTx_SRC` clears `IA` and the INT pin and, if latched, allows the register to refresh.

#### `CLICK_CFG` (`0x38`), `CLICK_SRC` (`0x39`), `CLICK_THS` (`0x3A`)

| Reg | Bit 7 | 6 | 5 | 4 | 3 | 2 | 1 | 0 |
|-----|-------|---|---|---|---|---|---|---|
| `CLICK_CFG` | — | — | ZD | ZS | YD | YS | XD | XS |
| `CLICK_SRC` | — | IA | DCLICK | SCLICK | Sign | Z | Y | X |
| `CLICK_THS` | LIR_Click | Ths6 | Ths5 | Ths4 | Ths3 | Ths2 | Ths1 | Ths0 |

`xS` / `xD` enable single / double click on an axis. `CLICK_SRC.Sign` 0 = positive, 1 = negative. `LIR_Click` = 1 keeps the interrupt high until `CLICK_SRC` is read; 0 keeps it high for the latency window.

#### `TEMP_CFG_REG` (`0x1F`) and `CTRL_REG0` (`0x1E`)

| Reg | Bit | Name | Description |
|-----|-----|------|-------------|
| `0x1F` | 7 | ADC_EN | 1 = auxiliary ADC enabled (requires `BDU` = 1 in `CTRL_REG4`) |
| `0x1F` | 6 | TEMP_EN | 1 = ADC channel 3 is connected to the temperature sensor |
| `0x1E` | 7 | SDO_PU_DISC | 1 = disconnect the pull-up on SDO/SA0 |

## Initialization Sequence

1. Power up (VDD and VDD_IO present together); wait ≥ 5 ms for the boot procedure to complete.
2. Read `WHO_AM_I` (`0x0F`); verify it equals `0x33`.
3. Put the control registers in a known state — the chip has no soft reset: write `CTRL_REG2`, `CTRL_REG3`, `CTRL_REG5`, `CTRL_REG6` and `FIFO_CTRL_REG` ← `0x00`, `TEMP_CFG_REG` ← `0x00`. (`CTRL_REG0` and the interrupt-generator registers are left untouched.)
4. Write `CTRL_REG4` ← `0x88` (`BDU` = 1, `FS` = `00` ±2 *g*, `HR` = 1 high-resolution).
5. Write `CTRL_REG1` ← `0x57` (`ODR` = `0101` 100 Hz, `LPen` = 0, X/Y/Z enabled) — this leaves power-down and starts conversions.
6. Wait ≥ 7/ODR (70 ms at 100 Hz) for the high-resolution turn-on; then the first sample is valid.

## Interrupt

| Property | Value |
|----------|-------|
| INT pins | `INT1` and `INT2`, both push-pull, active-high by default (`INT_POLARITY` = 1 → active-low) — no external pull-up needed |
| Level | 3 (multiple INT lines) |
| Condition(s) | new data (XYZ or aux ADC); FIFO watermark / overrun; inertial generator 1 / 2 (threshold, free-fall, 6D/4D orientation); single / double click; activity (sleep-to-wake); boot |
| Clear mechanism | generators: read `INTx_SRC`; click: read `CLICK_SRC`; data-ready: read the output registers; FIFO flags: read the FIFO until below the watermark / not full |

### Interrupt sources

| Constant | Value | Condition | Pin(s) |
|----------|-------|-----------|--------|
| `SOURCE_DATA_READY` | `0x0001` | New XYZ sample set (`ZYXDA`) | INT1 |
| `SOURCE_AUX_DATA_READY` | `0x0002` | New aux ADC sample (`321DA`) | INT1 |
| `SOURCE_WATERMARK` | `0x0004` | FIFO content exceeds watermark (`WTM`) | INT1 |
| `SOURCE_OVERRUN` | `0x0008` | FIFO overrun (`OVRN_FIFO`) | INT1 |
| `SOURCE_IA1` | `0x0010` | Inertial generator 1 event (`INT1_SRC.IA`) | INT1 or INT2 |
| `SOURCE_IA2` | `0x0020` | Inertial generator 2 event (`INT2_SRC.IA`) | INT1 or INT2 |
| `SOURCE_CLICK` | `0x0040` | Single / double click (`CLICK_SRC.IA`) | INT1 or INT2 |
| `SOURCE_ACTIVITY` | `0x0080` | Sleep-to-wake / return-to-sleep activity | INT2 |
| `SOURCE_BOOT` | `0x0100` | Boot procedure running | INT2 |

`SOURCE_ACTIVITY` and `SOURCE_BOOT` are pin-only: the chip has no register flag for them, so `poll_interrupt()` never reports them.

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback, pin=1)` | Subscribe on INT`pin` (1 or 2); callback receives the status int (see below) |
| `off_interrupt` | `off_interrupt(pin=1)` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> int` | Read the source registers of every currently enabled source and return the combined status int; **reading `INT1_SRC` / `INT2_SRC` / `CLICK_SRC` clears their latched flags** |
| `enable_interrupt` | `enable_interrupt(source, pin=1)` | Route one source to INT`pin`; `pin` is ignored for INT1-only / INT2-only sources |
| `disable_interrupt` | `disable_interrupt(source, pin=1)` | Remove one source from INT`pin` |

`enable_interrupt` only routes the source; the generator or function behind it is configured with the `set_*` methods in the Full class.

### Status register bit layout (`poll_interrupt()` return value)

| Bit | Constant | Meaning |
|-----|----------|---------|
| 0 | `SOURCE_DATA_READY` | `STATUS_REG.ZYXDA` set |
| 1 | `SOURCE_AUX_DATA_READY` | `STATUS_REG_AUX.321DA` set |
| 2 | `SOURCE_WATERMARK` | `FIFO_SRC_REG.WTM` set |
| 3 | `SOURCE_OVERRUN` | `FIFO_SRC_REG.OVRN_FIFO` set |
| 4 | `SOURCE_IA1` | `INT1_SRC.IA` set |
| 5 | `SOURCE_IA2` | `INT2_SRC.IA` set |
| 6 | `SOURCE_CLICK` | `CLICK_SRC.IA` set |

## Implementation Stages

### Minimal

Goal: read X, Y, Z acceleration in *g* with sensible defaults; no configuration required beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Runs the Initialization Sequence (±2 *g*, 100 Hz, high-resolution 12-bit mode, BDU on) |
| `read` | — | `(x, y, z)` floats | Acceleration in *g*; burst-reads `0x28–0x2D` with auto-increment |

**Sensible defaults:** ±2 *g* (1 mg/LSB), 100 Hz ODR, high-resolution (12-bit) mode, all three axes enabled, `BDU` = 1, high-pass filter bypassed, FIFO bypassed, all interrupt routing cleared, aux ADC and temperature sensor off, `CTRL_REG0` untouched.

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `set_range` | `range_g: int` (2, 4, 8, 16) | — | RMW `CTRL_REG4` `FS`; updates the cached sensitivity |
| `set_data_rate` | `rate_hz: int` (0 = power-down, 1, 10, 25, 50, 100, 200, 400, 1344, 1620, 5376) | — | RMW `CTRL_REG1` `ODR`; raises if the rate is not allowed in the current mode (1620 and 5376 low-power only, 1344 normal / high-resolution only) |
| `set_mode` | `mode: int` (0 low-power 8-bit, 1 normal 10-bit, 2 high-resolution 12-bit) | — | RMW `LPen` and `HR`; updates the cached resolution; allow the Table 11 turn-on time before trusting data |
| `set_axes` | `x: bool=True, y: bool=True, z: bool=True` | — | `Xen` / `Yen` / `Zen` |
| `power_down` | — | — | `ODR` = `0000`; register contents are retained |
| `read_raw` | — | `(x, y, z)` ints | Signed counts at the current resolution (right-shifted, see Data Conversion) |
| `new_data_available` | — | bool | `STATUS_REG.ZYXDA` |
| `read_status` | — | int | `STATUS_REG` byte (overrun and data-available flags) |
| `read_temperature_delta` | — | int | Relative temperature in °C (1 digit/°C); enables `ADC_EN` + `TEMP_EN` on first use, see Implementation Notes |
| `read_adc` | `channel: int` (1, 2, 3) | float | Aux ADC voltage in V; channel 3 requires `TEMP_EN` = 0 (raises otherwise) |
| `enable_adc` | `enabled: bool` | — | `ADC_EN` in `TEMP_CFG_REG` |
| `set_high_pass` | `mode: int=0, cutoff: int=0, filter_output: bool=False, on_ia1: bool=False, on_ia2: bool=False, on_click: bool=False` | — | Writes `CTRL_REG2` (`mode` = `HPM`, `cutoff` = `HPCF<2:1>` 0–3) |
| `reset_high_pass` | — | int | Reads `REFERENCE` (resets the filter in `HPM` = `00`) and returns the value |
| `set_reference` | `value: int` (0–255) | — | `REFERENCE` for `HPM` = `01` |
| `set_fifo` | `mode: int` (0 bypass, 1 FIFO, 2 stream, 3 stream-to-FIFO), `watermark: int=0, trigger_int2: bool=False` | — | Writes `FIFO_CTRL_REG`; sets `FIFO_EN` in `CTRL_REG5` for modes 1–3; returning to bypass clears the buffer |
| `fifo_level` | — | int | `FSS<4:0>` — unread sample sets (0–32) |
| `read_fifo` | `count: int=None` | list of `(x, y, z)` floats | Reads up to `count` sample sets (default: all `FSS`), one 6-byte burst at a time, in *g* |
| `read_fifo_status` | — | `(watermark, overrun, empty, level)` | Decoded `FIFO_SRC_REG` |
| `set_motion_interrupt` | `generator: int` (1, 2), `events: int, threshold_g: float, duration_ms: float=0, and_mode: bool=False` | — | Writes `INTx_CFG` (`events` is the 6-bit `ZHIE…XLIE` mask, `AOI` = `and_mode`), `INTx_THS`, `INTx_DURATION` |
| `set_free_fall` | `generator: int=1, threshold_g: float=0.35, duration_ms: float=30` | — | Convenience: all three axes low, AND mode (`INTx_CFG` = `0x95`) |
| `set_orientation` | `generator: int=1, position: bool=False, four_d: bool=False` | — | 6D movement (`position` = False) or position recognition; 4D via `D4D_INTx` |
| `set_latch` | `generator: int, enabled: bool` | — | `LIR_INT1` / `LIR_INT2` |
| `read_interrupt_source` | `generator: int` | int | `INTx_SRC` byte — **clears** the interrupt |
| `set_click` | `single: int=0, double: int=0, threshold_g: float=0.5, time_limit_ms: float=10, latency_ms: float=20, window_ms: float=50, latch: bool=False` | — | `single` / `double` are axis masks (X=1, Y=2, Z=4); writes `CLICK_CFG`, `CLICK_THS`, `TIME_LIMIT`, `TIME_LATENCY`, `TIME_WINDOW` |
| `read_click_source` | — | int | `CLICK_SRC` byte — **clears** the interrupt |
| `set_activity` | `threshold_g: float, duration_ms: float` | — | `ACT_THS`, `ACT_DUR`: drop to low-power 10 Hz below threshold, restore on activity; pair with `SOURCE_ACTIVITY` |
| `set_interrupt_polarity` | `active_low: bool` | — | `INT_POLARITY` |
| `enable_interrupt` / `disable_interrupt` | `source: int, pin: int=1` | — | See Interrupt |
| `on_interrupt` / `off_interrupt` / `poll_interrupt` | see Interrupt | | |
| `reboot` | — | — | `BOOT` = 1, waits 5 ms; reloads the factory trim; the datasheet does not say whether control registers survive, so re-run `init` afterwards |
| `set_sdo_pullup` | `connected: bool` | — | RMW `CTRL_REG0` bit 7 only; bit 4 stays 1 |
| `self_test` | `mode: int=1` | bool | Runs the datasheet self-test procedure (see Implementation Notes); true if every axis shows a 17–360 LSb change; restores the previous configuration |

**Additional configuration options:**
- 4 full-scale ranges (±2 to ±16 *g*); 10 data rates (1 Hz – 5.376 kHz); 3 power / resolution modes (8, 10, 12-bit)
- Per-axis enable; selectable high-pass filter (4 modes, 4 cutoffs) on output, interrupts and click
- 32-level FIFO in bypass / FIFO / stream / stream-to-FIFO mode with watermark and overrun flags
- Two independent inertial interrupt generators with OR / AND combination, thresholds, durations, free-fall, 6D / 4D orientation, latching
- Single and double click detection with per-axis enables, threshold and time windows
- Sleep-to-wake / return-to-sleep activity function
- Auxiliary 3-channel 10-bit ADC and relative temperature sensor
- INT polarity, SDO pull-up control, memory reboot, electrostatic self-test

## Data Conversion

Acceleration, per axis (16-bit two's complement, left-justified; LSB register first because `BLE` = 0):

```
raw16 = int16(OUT_x_L | OUT_x_H << 8)        # sign-extend the 16-bit pair
counts = raw16 >> shift                      # arithmetic shift: 4 (12-bit HR), 6 (10-bit normal), 8 (8-bit low-power)
acceleration_g = counts * sensitivity_mg / 1000
```

| Mode (resolution) | ±2 *g* | ±4 *g* | ±8 *g* | ±16 *g* |
|-------------------|--------|--------|--------|---------|
| High-resolution (12-bit), mg/LSB | 1 | 2 | 4 | 12 |
| Normal (10-bit), mg/LSB | 4 | 8 | 16 | 48 |
| Low-power (8-bit), mg/LSB | 16 | 32 | 64 | 192 |

Sensitivities are the typical values of datasheet Table 4 (at VDD = 2.5 V, 25 °C). Note the ±16 *g* high-resolution value is 12 mg/LSB, **not** 8 mg/LSB — it does not follow the doubling of the lower ranges. Acceleration in m/s² is not provided; values are in *g*.

Temperature (ADC channel 3 with `TEMP_EN` = 1, `ADC_EN` = 1, `BDU` = 1; 8-bit two's complement in `OUT_ADC3_H`, 1 digit/°C):

```
temperature_delta_C = int8(OUT_ADC3_H)       # relative to an unspecified reference
```

Aux ADC channels 1–3 (10-bit in normal / high-resolution mode, 8-bit in low-power mode; two's complement, left-justified in `OUT_ADCx_H:L`; input range 1200 mV ± 400 mV):

```
raw16 = int16(OUT_ADCx_L | OUT_ADCx_H << 8)
counts = raw16 >> 6                          # 10-bit; use >> 8 in low-power mode
voltage_V = 1.200 + counts * (0.400 / 512)   # derived from the stated input range
```

Thresholds and times (`range_g` = current full-scale, `ODR` = current output data rate in Hz):

```
INTx_THS code   = round(threshold_g * 1000 / lsb_mg)   # lsb_mg = 16 (±2 g), 32 (±4 g), 62 (±8 g), 186 (±16 g); 7-bit, clamp 0..127
INTx_DURATION   = round(duration_ms * ODR / 1000)      # 7-bit, 1 LSb = 1/ODR
ACT_THS code    = round(threshold_g * 1000 / lsb_mg)   # same scale as INTx_THS
ACT_DUR         = max(0, round((duration_ms * ODR / 1000 - 1) / 8))   # 8-bit, 1 LSb = (8·ACT_DUR + 1)/ODR
CLICK_THS code  = round(threshold_g * 1000 / lsb_mg)   # 7-bit, same scale as INTx_THS (units not restated in this datasheet revision)
TIME_LIMIT / TIME_LATENCY / TIME_WINDOW = round(time_ms * ODR / 1000)   # 1 LSb = 1/ODR (not restated in this datasheet revision)
```

## Node-RED

Node name: `periph-lis3dh`  
Package: `node-red-contrib-periph-accelerometer`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ x: float, y: float, z: float, temperature_delta: int }` | Acceleration in *g* per axis; relative temperature in °C |

Config panel fields:
- **Bus** — I²C bus number
- **I²C address** — `0x18` (SA0 = GND) or `0x19` (SA0 = VDD_IO)
- **Range** — ±2 / ±4 / ±8 / ±16 *g*
- **Data rate** — 1 Hz … 5.376 kHz
- **Mode** — low-power / normal / high-resolution

### Demo flow

An inject node triggers every 100 ms into `periph-lis3dh`; a function node computes √(x²+y²+z²) and pitch/roll; a debug node shows the values plus the relative temperature. Demonstrates a 12-bit (1 mg/LSB) tilt sensor.

## Examples

### Demo

Tilt, double-tap and free-fall monitor. Configure ±2 *g*, 50 Hz data rate, high-resolution mode. Run `self_test()` once and print the result. Arm `set_click(double=0x04, threshold_g=0.8, time_limit_ms=20, latency_ms=40, window_ms=200, latch=True)` for a double tap on Z and `set_free_fall(generator=1, threshold_g=0.35, duration_ms=60)` with `set_latch(1, True)`, routing `SOURCE_CLICK` and `SOURCE_IA1` to INT1 with `enable_interrupt`. Put the FIFO in stream mode with a 16-sample watermark. Every 100 ms drain the FIFO with `read_fifo()`, average the samples, and print pitch and roll in degrees (`atan2`), the vector magnitude and the relative temperature. When `poll_interrupt()` reports `SOURCE_CLICK` print "DOUBLE TAP"; for `SOURCE_IA1` print "FREE FALL". Run 60 s and exit. Exercises `init`, `self_test`, `set_data_rate`, `set_click`, `set_free_fall`, `set_latch`, `enable_interrupt`, `set_fifo`, `fifo_level`, `read_fifo`, `read_temperature_delta`, `poll_interrupt`.

## Timing Constraints

- **Boot:** the boot procedure completes ≈ 5 ms after power-up; no register access before that. Also applies after a `BOOT` reboot.
- **Turn-on from power-down / mode change:** low-power 1 ms, normal 1.6 ms, high-resolution 7/ODR (datasheet Table 10); changing mode costs 1/ODR (12 → 10-bit, 12 → 8-bit, 10 → 8-bit, 8 → 10-bit) or 7/ODR (10 → 12-bit, 8 → 12-bit) (Table 11). The driver waits for the longer of the turn-on time and one ODR period before returning from `init` / `set_mode` / `set_data_rate`.
- **First data after leaving power-down:** the first sample set is ready after the turn-on time (7/ODR = 70 ms at 100 Hz in the default high-resolution mode). **Conformance-checked**: `first_data_ready` — sigrok annotation pair `odr_start` (write to `CTRL_REG1` `0x20` with `ODR` ≠ `0000`) → `odr_ready` (first read of `STATUS_REG` `0x27` returning `ZYXDA` = 1). See `specs/accelerometer/lis3dh_timing.conf`.
- **Self-test settle:** after enabling or disabling self-test, valid data appears after 2 samples (low-power / normal) or 8 samples (high-resolution).
- **High-pass filter / interrupts:** after changing `FS`, `ODR`, mode or high-pass settings, discard the next few samples (≥ 1/ODR) and re-read `REFERENCE` in `HPM` = `00` before trusting threshold events.
- **Update rate:** conversions occur at the programmed ODR; at 1.344 kHz and above, I²C read-out of the 6-byte burst takes roughly 0.2–0.25 ms at 400 kHz, a significant share of the 0.74 ms sample period — use the FIFO or poll `ZYXDA` rather than the INT pin latency.
- **FIFO burst:** reading `OUT_X_L`…`OUT_Z_H` pops one sample set per 6-byte read; a full drain is 32 reads (the address auto-increment wraps from `0x2D` back to `0x28`).

## Implementation Notes

- **Auto-increment:** every multi-byte I²C transfer sets bit 7 of the sub-address (`reg | 0x80`); single-register accesses do not. The driver does this itself — `RegisterConnection` has no I²C multi-byte parameter (see `specs/feature_register_access_design.md` §4), same pattern as `L3GD20H`.
- **BDU:** `init` sets `BDU` = 1 so the six output bytes of a burst always belong to one sample; the aux ADC and temperature sensor require `BDU` = 1 anyway. Always read LSB before MSB (a 6-byte burst does).
- **No soft reset:** the chip has no reset command. `init` writes the control registers back to their defaults (Initialization Sequence step 3); the only "reset" is `BOOT` (`CTRL_REG5` bit 7), which reloads the factory trim — it is not required in normal use.
- **`CTRL_REG0` is not a normal register:** bit 4 must remain `1` and the other bits except `SDO_PU_DISC` must remain `0`. Only `set_sdo_pullup` touches it, by read-modify-write. Reserved registers (`0x00–0x06`, `0x0E`, `0x10–0x1D`) are never written; writing them may damage the part.
- **Mode select:** `LPen` = 1 with `HR` = 1 is not allowed; the driver rejects it. ODR codes `1000` (1.62 kHz) and the 5.376 kHz meaning of `1001` exist in low-power mode only; 1.344 kHz in `1001` is for normal / high-resolution only. The driver validates (`set_data_rate`, `set_mode`) and raises on an illegal combination.
- **Left-justified data:** the output is the 16-bit register pair shifted left by 4, 6 or 8 bits depending on the mode. Sign-extend the 16-bit value first, then arithmetic-shift right — do not mask-and-shift a 12-bit field. In the JVM drivers combine the bytes with both masked by `0xFF`, narrow with `.toShort()` / `(short)` to sign-extend, and cast to `double` before multiplying by the sensitivity.
- **Temperature is relative:** the datasheet defines only 1 digit/°C (8-bit) and gives no zero-degree reference or absolute calibration; expose a relative delta only (as for `L3GD20H`), never absolute °C. The temperature sensor is specified only for VDD 2–3.6 V. The driver sets both `ADC_EN` and `TEMP_EN` when enabling temperature: the datasheet text for the temperature path names only `TEMP_EN`, but ADC channel 3 conversions run through the same ADC, and setting both is harmless. `read_adc(3)` is therefore unavailable while `TEMP_EN` = 1.
- **Aux ADC conversion is derived:** the datasheet gives only the input range (1200 mV ± 400 mV) and "two's complement, left-aligned", not an explicit voltage formula; the Data Conversion mapping assumes 0 ↔ 1.200 V and ±512 counts ↔ ±400 mV. Verify against hardware before relying on absolute accuracy.
- **Units not restated for click timing:** this datasheet revision describes `CLICK_THS` and `TIME_LIMIT` / `TIME_LATENCY` / `TIME_WINDOW` only as "threshold" / "time" without units. The spec uses the interrupt-threshold scale and 1/ODR respectively, which is how the later ST application note describes them; verify on hardware. `INTx_THS` LSb values (16 / 32 / 62 / 186 mg) are taken as printed (nominally full-scale / 128).
- **`ACT_DUR` scaling:** 1 LSb = (8·`ACT_DUR` + 1)/ODR, not a plain multiple of 1/ODR; the conversion formula above inverts this and rounds.
- **High-pass cutoff:** `HPCF<2:1>` selects the filter cutoff, but the datasheet tabulates no Hz values (they scale with ODR). The Full class exposes the raw 0–3 code only.
- **Interrupt generator writes:** `INT1_CFG` / `INT2_CFG` may be written only after the boot procedure has completed; the driver never touches them before the init wait.
- **6D / 4D:** `AOI`/`6D` = `01` is *movement* recognition (a short pulse, one ODR period), `11` is *position* recognition (level, stays active while inside a known zone). 4D disables the Z-axis zone and is enabled with `D4D_INTx` plus `6D` in `INTx_CFG`.
- **FIFO:** only bypass mode empties the buffer; to restart FIFO mode after a full drain go through bypass (`set_fifo(0)` then `set_fifo(1)`). In stream mode the oldest samples are overwritten once the buffer is full; the watermark interrupt fires at level `FTH`, and up to `FTH + 1` samples can be read at the rise of the interrupt. Stream-to-FIFO switches on the selected INT pin (INT1 or INT2 per `TR`).
- **Sleep-to-wake:** while the activity function has dropped the chip into its 10 Hz low-power state, the `ODR`, `LPen` and `HR` settings are ignored; the chip restores them on activity. `set_activity` requires a non-zero `ACT_THS`.
- **Self-test:** `self_test(mode)` — configure ±2 *g*, normal mode (10-bit), 50 Hz, `BDU`; average a few samples with `ST` = `00`, set `ST` = `01` (self-test 0, or `10` self-test 1), wait 2 samples (normal mode), average again, and require `17 ≤ |Δ| ≤ 360` LSb on each axis (Table 4; 1 LSb = 4 mg at 10-bit / ±2 *g*). The sign of Δ follows the `ST` bits. Restore the previous `CTRL_REG1` / `CTRL_REG4` afterwards and put `ST` back to `00`.
- **SPI mode (not implemented):** the chip also supports 4-wire SPI and, via `SIM` in `CTRL_REG4`, 3-wire. SPI would use CPOL = 1, CPHA = 1 (mode 3; SPC idles high, data driven on the falling and captured on the rising edge), up to 10 MHz, CS active low, first byte = `[RW][MS][AD5:AD0]` — read bit `0x80`, multi-byte (auto-increment) bit `0x40`, i.e. the ADXL345-style `SPIConnection(read_bit=0x80, multi_byte_bit=0x40)` convention. This issue covers I²C only.
- **Sibling:** register-compatible ST parts exist (e.g. the LIS2DH12 family shares the `0x33` `WHO_AM_I` and most of this map); no compatibility is assumed or claimed here — each needs its own spec if added.
- **Datasheet revision:** the committed datasheet is DocID17530 Rev 2 (54 pages). Later revisions add detail (cutoff tables, click units, temperature offset) — see the notes above for each place this matters.

If this chip is register-addressed (I²C, SMBus, or SPI), accept `RegisterConnection` (not
the bare `Connection`) and call `connection.read(reg, length)` / `connection.write(reg,
data)` directly — no chip-local `_read_reg`/`_write_reg` and no `bus_type` branch. See
`specs/feature_register_access_design.md`.

## Sigrok Decoder

Decoder id `lis3dh`, input `['i2c']`. Matches I²C addresses `0x18` and `0x19`. Annotates every register in the Register Map by name (the auto-increment bit in the sub-address is stripped and shown separately); decodes `CTRL_REG0–6`, `TEMP_CFG_REG`, `STATUS_REG`, `STATUS_REG_AUX`, `FIFO_CTRL_REG`, `FIFO_SRC_REG`, `INTx_CFG`, `INTx_SRC`, `CLICK_CFG`, `CLICK_SRC` and `CLICK_THS` into named flags; decodes `ODR`, `FS`, `LPen` / `HR` (as the operating mode), `HPM`, `FM` and the self-test selection into human-readable values; assembles the burst read of `0x28–0x2D` into 12/10/8-bit signed values converted to *g* at the last-seen range and mode, `OUT_ADC1–3` into raw counts / V and the temperature delta into °C. `WHO_AM_I` reads are checked against `0x33`. For the First-data-ready Timing Constraint the decoder emits the `timing` annotation row markers `odr_start` (write to `CTRL_REG1` `0x20` with `ODR` ≠ `0000`) and `odr_ready` (first read of `STATUS_REG` `0x27` returning `ZYXDA` = 1), shaped per `specs/sigrok_annotations.md`'s Variable-Length Annotation Convention.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/accelerometer/lis3dh.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/accelerometer/lis3dh/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/accelerometer/lis3dh/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/accelerometer/lis3dh/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/accelerometer/lis3dh_test.py` (MicroPython)
- [ ] Tests `python/tests/accelerometer/lis3dh_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/accelerometer/lis3dh_test_linux.py` (Linux)
- [ ] Unit test `python/tests/accelerometer/lis3dh_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/accelerometer/lis3dh/lis3dh.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/accelerometer/lis3dh/lis3dh_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/accelerometer/lis3dh/lis3dh.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/accelerometer/lis3dh/LIS3DH.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/accelerometer/lis3dh/LIS3DH.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/accelerometer/LIS3DH.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/accelerometer/LIS3DH.cpp`
- [ ] Examples `cpp/examples/arduino/accelerometer/LIS3DH/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/accelerometer/LIS3DH/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/accelerometer/LIS3DH/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/accelerometer/LIS3DH/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/accelerometer/LIS3DH/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/accelerometer/LIS3DH/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/accelerometer/LIS3DH/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/accelerometer/LIS3DH/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/accelerometer/LIS3DH/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/accelerometer/LIS3DH/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/accelerometer/LIS3DH/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/accelerometer/LIS3DH/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/accelerometer/LIS3DH/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/accelerometer/LIS3DH/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/accelerometer/LIS3DH/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/accelerometer/lis3dh_test/lis3dh_test.ino` (Arduino)
- [ ] Tests `cpp/tests/accelerometer/lis3dh_test_linux/lis3dh_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/accelerometer/lis3dh_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/accelerometer/lis3dh_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/accelerometer/lis3dh_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Unit test `cpp/tests/accelerometer/lis3dh_test_unit/lis3dh_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/accelerometer/lis3dh.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/accelerometer/lis3dh/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/accelerometer/lis3dh/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/accelerometer/lis3dh/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/accelerometer/lis3dh_test.js`
- [ ] Unit test `nodejs/tests/accelerometer/lis3dh_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-accelerometer/nodes/lis3dh/lis3dh.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-accelerometer/nodes/lis3dh/lis3dh.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-accelerometer/examples/lis3dh/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/accelerometer/lis3dh.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/accelerometer/lis3dh/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/accelerometer/lis3dh/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/accelerometer/lis3dh/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/accelerometer/lis3dh_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/accelerometer/lis3dh_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/accelerometer/lis3dh.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/accelerometer/lis3dh.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/accelerometer/lis3dh/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/accelerometer/lis3dh/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/accelerometer/lis3dh/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/accelerometer/lis3dh/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/accelerometer/lis3dh/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/accelerometer/lis3dh/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/accelerometer/lis3dh_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/accelerometer/lis3dh_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/accelerometer/lis3dh_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/accelerometer/Lis3dhMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/accelerometer/Lis3dhFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/accelerometer/Lis3dhMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/accelerometer/Lis3dhFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/accelerometer/Lis3dhMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/accelerometer/Lis3dhFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/accelerometer/lis3dh/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/accelerometer/lis3dh/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/accelerometer/lis3dh/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/accelerometer/lis3dh/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/accelerometer/lis3dh/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/accelerometer/lis3dh/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/accelerometer/lis3dh/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/accelerometer/lis3dh/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/accelerometer/lis3dh/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/accelerometer/lis3dh/Lis3dhTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/accelerometer/Lis3dhTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/accelerometer/Lis3dhTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/accelerometer/Lis3dhSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/lis3dh/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/lis3dh/pd.py` — emits the `odr_start` / `odr_ready` annotation pair named in the Sigrok Decoder section

### Conformance
- [ ] Checker `conformance/accelerometer/lis3dh_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/accelerometer/lis3dh_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/lis3dh/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

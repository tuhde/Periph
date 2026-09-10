# Chip Spec: LPS33HW

**Manufacturer:** STMicroelectronics  
**Datasheet:** `datasheets/pressure/lps33hw.pdf`  
**Category:** `pressure`  
**Transports:** I²C, SPI

## Overview

The LPS33HW is a water-resistant, piezoresistive MEMS absolute pressure sensor with an integrated temperature sensor. It delivers 24-bit pressure output (260–1260 hPa) and 16-bit temperature output, factory-calibrated with no user compensation step required. The distinguishing feature is the hermetically sealed CCLGA-10L package (3.3 × 3.3 × 2.9 mm) which provides IPx8 water resistance, making it suitable for drones, wearables, and outdoor equipment exposed to moisture. ODR options from 1 to 75 Hz with an additional one-shot mode; a 32-slot FIFO buffers pressure and temperature pairs. Current draw is 15 µA at 1 Hz in normal mode or 3 µA in low-current mode; power-down idle is 1 µA. A configurable INT_DRDY pin signals data-ready, FIFO events, or differential pressure threshold crossings. Interface: I²C (up to 400 kHz) or SPI (up to 10 MHz, 4-wire or 3-wire).

## Transport Configuration

### I²C

- **Address:** `0x5C` (SA0=0) / `0x5D` (SA0=1)
- **Max clock:** 400 kHz

Auto-increment is enabled by default (IF_ADD_INC=1 in CTRL_REG2). For multi-byte burst reads, the sub-address byte's MSB (bit 7) must be set to 1 (e.g., read 5 bytes starting at 0x28 by sending sub-address 0xA8). Single-byte reads do not require bit 7 set.

### SPI

- **Mode:** CPOL=0 CPHA=0 (Mode 0)
- **Max clock:** 10 MHz
- **Bit order:** MSB first
- **CS active:** low

CS high → I²C mode selected; CS low → SPI mode selected. The first byte of an SPI frame is: bit 7 = R/W (1=read, 0=write), bits [6:1] = register address, bit 0 = auto-increment. Set bit 7 and bit 0 high for multi-byte reads. Use the SIM bit in CTRL_REG1 to enable 3-wire SPI.

## Register Map

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x0B` | `INTERRUPT_CFG` | R/W | `0x00` | Interrupt and autozero/autorifp control |
| `0x0C` | `THS_P_L` | R/W | `0x00` | Pressure threshold low byte |
| `0x0D` | `THS_P_H` | R/W | `0x00` | Pressure threshold high byte |
| `0x0F` | `WHO_AM_I` | R | `0xB1` | Fixed chip ID |
| `0x10` | `CTRL_REG1` | R/W | `0x00` | ODR, LPF, BDU, SPI mode |
| `0x11` | `CTRL_REG2` | R/W | `0x10` | Boot, FIFO, auto-increment, reset, one-shot |
| `0x12` | `CTRL_REG3` | R/W | `0x00` | INT_DRDY pin routing and polarity |
| `0x14` | `FIFO_CTRL` | R/W | `0x00` | FIFO mode and watermark |
| `0x15` | `REF_P_XL` | R/W | `0x00` | Reference pressure LSB |
| `0x16` | `REF_P_L` | R/W | `0x00` | Reference pressure middle byte |
| `0x17` | `REF_P_H` | R/W | `0x00` | Reference pressure MSB |
| `0x18` | `RPDS_L` | R/W | `0x00` | Pressure offset LSB |
| `0x19` | `RPDS_H` | R/W | `0x00` | Pressure offset MSB |
| `0x1A` | `RES_CONF` | R/W | `0x00` | Low-current mode enable |
| `0x25` | `INT_SOURCE` | R | — | Interrupt source flags |
| `0x26` | `FIFO_STATUS` | R | — | FIFO fill level and overrun flags |
| `0x27` | `STATUS` | R | — | Data-available and overrun flags |
| `0x28` | `PRESS_OUT_XL` | R | — | Pressure output LSB |
| `0x29` | `PRESS_OUT_L` | R | — | Pressure output middle byte |
| `0x2A` | `PRESS_OUT_H` | R | — | Pressure output MSB (reading this releases BDU latch) |
| `0x2B` | `TEMP_OUT_L` | R | — | Temperature output LSB |
| `0x2C` | `TEMP_OUT_H` | R | — | Temperature output MSB |
| `0x33` | `LPFP_RES` | R | — | Low-pass filter reset (read to flush transitory state) |

### Bit Fields

#### `INTERRUPT_CFG` (`0x0B`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `AUTORIFP` | Write 1: store next measured pressure in RPDS; self-clears after one conversion. Output becomes measured − RPDS×(1/16 hPa per LSB) |
| 6 | `RESET_ARP` | Write 1 to clear AUTORIFP mode and reset RPDS to 0 |
| 5 | `AUTOZERO` | Write 1: store current pressure in REF_P; self-clears. Output becomes measured − REF_P |
| 4 | `RESET_AZ` | Write 1 to clear AUTOZERO mode and reset REF_P to 0 |
| 3 | `DIFF_EN` | Enable differential pressure interrupt generation |
| 2 | `LIR` | Latch interrupt request (cleared by reading INT_SOURCE) |
| 1 | `PLE` | Enable interrupt on pressure below threshold |
| 0 | `PHE` | Enable interrupt on pressure above threshold |

#### `CTRL_REG1` (`0x10`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | — | Must be 0 |
| 6:4 | `ODR[2:0]` | Output data rate — see ODR table |
| 3 | `EN_LPFP` | Enable additional low-pass filter on pressure (0=disabled, 1=enabled) |
| 2 | `LPFP_CFG` | LPF bandwidth: 0=ODR/9, 1=ODR/20 (only when EN_LPFP=1) |
| 1 | `BDU` | Block data update: 0=continuous, 1=hold until PRESS_OUT_H read |
| 0 | `SIM` | SPI mode: 0=4-wire (default), 1=3-wire |

#### `CTRL_REG2` (`0x11`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `BOOT` | Reboot factory trimming from Flash; self-clears when complete |
| 6 | `FIFO_EN` | Enable FIFO (0=disabled, 1=enabled) |
| 5 | `STOP_ON_FTH` | Stop FIFO fill at watermark level |
| 4 | `IF_ADD_INC` | Auto-increment register address (default 1) |
| 3 | `I2C_DIS` | Disable I²C interface (SPI-only mode) |
| 2 | `SWRESET` | Software reset; self-clears when reset is complete |
| 1 | — | Must be 0 |
| 0 | `ONE_SHOT` | Trigger one measurement (ODR must be 000); self-clears when done |

#### `CTRL_REG3` (`0x12`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `INT_H_L` | INT_DRDY polarity: 0=active high, 1=active low |
| 6 | `PP_OD` | INT_DRDY drive: 0=push-pull, 1=open-drain |
| 5 | `F_FSS5` | Route FIFO full (32 samples) flag to INT_DRDY |
| 4 | `F_FTH` | Route FIFO threshold (watermark) flag to INT_DRDY |
| 3 | `F_OVR` | Route FIFO overrun flag to INT_DRDY |
| 2 | `DRDY` | Route data-ready signal to INT_DRDY |
| 1:0 | `INT_S[1:0]` | Signal on INT_DRDY: 00=data signals (DRDY/F_FTH/F_OVR/F_FSS5), 01=pressure high, 10=pressure low, 11=pressure low or high |

#### `FIFO_CTRL` (`0x14`)

| Bits | Name | Description |
|------|------|-------------|
| 7:5 | `F_MODE[2:0]` | FIFO mode — see FIFO mode table |
| 4:0 | `WTM[4:0]` | FIFO watermark level (0–31) |

#### `RES_CONF` (`0x1A`)

| Bits | Name | Description |
|------|------|-------------|
| 7:2 | — | Must be 0 |
| 1 | — | Reserved — do not modify |
| 0 | `LC_EN` | Low-current mode: 0=normal (low-noise), 1=low-current. Change only in power-down |

#### `INT_SOURCE` (`0x25`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `BOOT_STATUS` | 1 while boot/reboot is running |
| 2 | `IA` | Interrupt active (one or more events occurred) |
| 1 | `PL` | Differential pressure low event |
| 0 | `PH` | Differential pressure high event |

#### `FIFO_STATUS` (`0x26`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | `FTH_FIFO` | FIFO fill ≥ watermark level |
| 6 | `OVR` | FIFO full and at least one sample overwritten |
| 5:0 | `FSS[5:0]` | FIFO stored data count (0=empty, 32=full) |

#### `STATUS` (`0x27`)

| Bits | Name | Description |
|------|------|-------------|
| 5 | `T_OR` | Temperature data overrun |
| 4 | `P_OR` | Pressure data overrun |
| 1 | `T_DA` | Temperature data available |
| 0 | `P_DA` | Pressure data available |

## ODR Table

| ODR[2:0] | Rate | Supply current (normal) | Supply current (LC_EN=1) |
|----------|------|------------------------|--------------------------|
| `000` | Power-down / one-shot | 1 µA | — |
| `001` | 1 Hz | 15 µA | 3 µA |
| `010` | 10 Hz | 150 µA | 30 µA |
| `011` | 25 Hz | 375 µA | 75 µA |
| `100` | 50 Hz | 750 µA | 150 µA |
| `101` | 75 Hz | 1.12 mA | 225 µA |

## FIFO Mode Table

| F_MODE[2:0] | Mode | Description |
|-------------|------|-------------|
| `000` | Bypass | FIFO disabled; each new sample overwrites slot 0 |
| `001` | FIFO | Fill to 32 slots then stop; FSS shows fill level |
| `010` | Stream | Circular; oldest overwritten when full |
| `011` | Stream-to-FIFO | Stream until trigger (INT1), then fill and stop |
| `100` | Bypass-to-Stream | Bypass until trigger, then stream continuously |
| `101` | Reserved | — |
| `110` | Dynamic-Stream | Stream with OVR flag; STOP_ON_FTH halts at watermark |
| `111` | Bypass-to-FIFO | Bypass until trigger, then fill and stop |

## Initialization Sequence

### Continuous mode (Minimal)

1. Read `WHO_AM_I` (`0x0F`); verify value is `0xB1`.
2. Write `CTRL_REG2` = `0x04` (`SWRESET=1`) to software-reset. Poll until `SWRESET` self-clears (typically <1 ms).
3. Write `CTRL_REG2` = `0x10` to restore default `IF_ADD_INC=1` after reset.
4. Write `CTRL_REG1` = `0x12` (`ODR=001` 1 Hz, `BDU=1`).

### One-shot mode

1. Verify `WHO_AM_I`.
2. Software reset (step 2–3 above).
3. Keep `CTRL_REG1` = `0x02` (ODR=000, BDU=1). Device is in power-down.
4. To acquire: write `CTRL_REG2` bit `ONE_SHOT=1`. Wait until `STATUS` bits `P_DA` and `T_DA` are both 1 (typically <1 ms). Read output registers. `ONE_SHOT` self-clears; device returns to power-down.

## Implementation Stages

### Minimal

Goal: read pressure and temperature continuously at 1 Hz with sensible defaults. No configuration beyond the transport.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | transport | — | WHO_AM_I check, software reset, ODR=1 Hz, BDU=1 |
| `pressure` | — | float Pa | Waits for P_DA; returns pressure in pascals |
| `temperature` | — | float °C | Waits for T_DA; returns temperature in °C |

**Sensible defaults baked into Minimal:**
- ODR = 001 (1 Hz)
- BDU = 1 (block data update — prevents reading pressure bytes from different samples)
- EN_LPFP = 0 (LPF disabled, BW = ODR/2)
- IF_ADD_INC = 1 (auto-increment, kept from reset default)

### Full

Goal: expose all chip features. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `configure` | `odr` (0–5), `bdu` (bool), `en_lpfp` (bool), `lpfp_cfg` (bool), `lc_en` (bool), `sim` (bool) | — | Sets ODR, BDU, LPF; `lc_en` only writable in power-down (ODR=0) |
| `one_shot` | — | tuple[float, float] | (pressure Pa, temperature °C); requires ODR=0; polls P_DA+T_DA |
| `status` | — | dict | `{p_da, t_da, p_or, t_or}` from STATUS register |
| `reset` | — | — | Software reset via SWRESET; waits for self-clear |
| `reboot` | — | — | Reload factory trimming via BOOT; waits for self-clear |
| `set_pressure_offset` | `offset_hPa` float | — | Write RPDS[15:0] = round(offset_hPa × 16) |
| `set_autozero` | — | — | Set AUTOZERO=1; waits one ODR cycle; current pressure stored in REF_P |
| `clear_autozero` | — | — | Set RESET_AZ=1 |
| `set_autorifp` | — | — | Set AUTORIFP=1; next measurement stored in RPDS |
| `clear_autorifp` | — | — | Set RESET_ARP=1 |
| `configure_interrupt` | `drdy` (bool), `f_fth` (bool), `f_ovr` (bool), `f_fss5` (bool), `int_s` (0–3), `active_low` (bool), `open_drain` (bool) | — | Route events to INT_DRDY pin |
| `configure_pressure_interrupt` | `high_en` (bool), `low_en` (bool), `threshold_hPa` float, `latch` (bool) | — | Differential pressure threshold interrupt |
| `interrupt_status` | — | dict | `{ia, p_high, p_low, boot_status}` from INT_SOURCE |
| `enable_fifo` | `mode` (0–7, not 5), `watermark` (0–31) | — | Set FIFO_CTRL and FIFO_EN=1 |
| `disable_fifo` | — | — | Set FIFO_EN=0, F_MODE=Bypass |
| `fifo_status` | — | dict | `{fth, ovr, count}` from FIFO_STATUS |
| `read_fifo` | — | list[tuple[float, float]] | Reads all available FIFO slots; each is (pressure Pa, temperature °C) |
| `reset_lpf` | — | — | Read LPFP_RES (0x33) to flush LPF transitory state |

## Data Conversion

### Pressure

Raw output is a 24-bit two's complement signed integer assembled from PRESS_OUT_XL (LSB), PRESS_OUT_L, PRESS_OUT_H (MSB).

```
pressure_hPa = raw_pressure / 4096
pressure_Pa  = raw_pressure × 100 / 4096
```

Assembly (Python, big-endian sign extension):
```python
raw = (msb << 16) | (mid << 8) | xl
if raw >= 0x800000:
    raw -= 0x1000000
pressure_Pa = raw * 100 / 4096
```

### Temperature

Raw output is a 16-bit two's complement signed integer (TEMP_OUT_L = LSB, TEMP_OUT_H = MSB).

```
temperature_C = raw_temperature / 100
```

### Pressure threshold (THS_P)

THS_P is unsigned 16-bit stored in THS_P_H:THS_P_L.

```
threshold_hPa = THS_P[15:0] / 16
THS_P[15:0]   = round(threshold_hPa × 16)
```

### Pressure offset (RPDS)

RPDS is a 16-bit signed integer stored in RPDS_H:RPDS_L. Effective offset in the output register is 256×RPDS raw counts:

```
offset_hPa  = RPDS[15:0] / 16        (1 RPDS LSB = 1/16 hPa = 6.25 Pa)
RPDS[15:0]  = round(offset_hPa × 16)
```

### FIFO burst read

Each FIFO level is 5 bytes (pressure XL/L/H + temperature L/H). To read all 32 levels in one burst, issue a multi-byte read from 0x28 (sub-address 0xA8 over I²C) for 160 bytes. The address wraps from 0x2C back to 0x28 automatically for subsequent levels.

## Node-RED

Node name: `periph-lps33hw`  
Package: `node-red-contrib-periph-pressure`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ pressure_Pa: float, temperature_C: float }` | Reads one sample; waits for P_DA and T_DA |

Config panel fields: I²C bus number, I²C address (`0x5C` or `0x5D`), ODR selection.

### Demo flow

Inject node (5-second repeat) → LPS33HW node → two debug nodes showing `msg.payload.pressure_Pa` and `msg.payload.temperature_C`. A second inject node wired to the LPS33HW node's config input resets the sensor to one-shot mode and prints the single-sample result.

## Examples

### Demo

Altimeter scenario: read pressure and temperature at 10 Hz and compute altitude above sea level using the barometric formula, printing a new altitude estimate and temperature once per second. Every 10 seconds, trigger an AUTOZERO to re-zero the sensor to the current ambient pressure and print "Reference updated." Use BDU=1 and EN_LPFP=1/LPFP_CFG=1 (ODR/20 bandwidth) for stable altitude estimates.

```
sea_level_Pa = 101325
altitude_m = 44330 × (1 − (pressure_Pa / sea_level_Pa) ^ (1/5.255))
```

Section boundary comments:
1. **Initialization and configuration** — explain ODR, BDU, and LPF choices for altitude application.
2. **Main loop** — explain why pressure is polled on P_DA rather than by fixed delay.
3. **Altitude calculation** — explain the barometric formula approximation and its validity range.
4. **Autozero** — explain that re-zeroing removes slow atmospheric pressure drift for relative altitude measurements.

## Timing Constraints

- Software reset (`SWRESET=1`): self-clears within one ODR clock cycle; poll until clear before proceeding.
- Boot (`BOOT=1`): takes up to one ODR cycle; poll until self-cleared.
- ONE_SHOT measurement: poll `STATUS` bits P_DA and T_DA until both are 1; typically completes in <1 ms. **Conformance-checked**: `one_shot_conversion` — sigrok annotation pair `one_shot_start` (write of CTRL_REG2 with ONE_SHOT=1 reaches the bus) → `one_shot_done` (STATUS read with both P_DA=1 and T_DA=1). At ODR=000 (power-down), the one-shot cycle is bounded by ~1 ms per the datasheet. See `specs/pressure/lps33hw_timing.conf`.
- BDU=1: `PRESS_OUT_H` (0x2A) must be the last register accessed in a pressure read sequence to release the output-register latch and allow the next measurement to be stored.
- LC_EN: must only be modified when the device is in power-down (ODR=000). Takes effect for both one-shot and continuous modes.
- LPF transitory: after enabling EN_LPFP, read `LPFP_RES` (0x33) before starting measurements to flush the filter pipeline.

## Implementation Notes

- **BDU and auto-increment:** The datasheet recommends disabling auto-increment (IF_ADD_INC=0) when reading output registers without FIFO, so bytes can be read individually in the correct order and the BDU latch releases properly. Alternatively, with auto-increment enabled, always read PRESS_OUT_XL→PRESS_OUT_L→PRESS_OUT_H→TEMP_OUT_L→TEMP_OUT_H in one burst so PRESS_OUT_H is encountered and the latch releases mid-read. For safety, drivers should always read PRESS_OUT_H last.
- **I²C multi-byte read:** The sub-address MSB (bit 7) must be 1 to enable auto-increment over I²C. E.g., to burst-read from 0x28: send sub-address `0xA8`.
- **CTRL_REG1 bit 7** must always be 0; never set it.
- **CTRL_REG2 bit 1** must always be 0; read-modify-write when changing any CTRL_REG2 field.
- **RES_CONF bit 1** is reserved — preserve it on read-modify-write; bits [7:2] must be 0.
- **RPDS offset formula:** The datasheet expresses the subtracted quantity as `256 × RPDS` raw pressure counts, which equals `RPDS/16 hPa`. A one-point calibration stores the residual post-soldering offset here.
- **AUTOZERO vs AUTORIFP:** AUTOZERO stores the current reading in the internal REF_P registers (affects differential interrupt, not the raw output). AUTORIFP stores the value in the user-accessible RPDS registers so the offset persists across resets. Both self-clear AUTORIFP/AUTOZERO bits after one conversion.
- **Water-resistance:** The CCLGA-10L package is tested to IPx8 (immersion to 1 m for 30 min) and is suitable for use in waterproof products. The pressure port must not be blocked by adhesive during assembly.

## Sigrok Decoder

Decoder id `lps33hw`, input `['i2c']` (or `['spi']` — same address framing rules). Matches I²C addresses `0x5C` / `0x5D`. The decoder sits on top of the `i2c` sigrok decoder and translates raw I²C transactions into chip-specific register names and decoded field values. It annotates every register defined in the Register Map:

- `INTERRUPT_CFG` (`0x0B`) byte decoded to named bits (AUTORIFP, RESET_ARP, AUTOZERO, RESET_AZ, DIFF_EN, LIR, PLE, PHE).
- `THS_P_L` / `THS_P_H` (`0x0C`–`0x0D`) shown as a single 16-bit pressure threshold in hPa (LSB = 1/16 hPa).
- `WHO_AM_I` (`0x0F`) read annotated as expected value `0xB1`.
- `CTRL_REG1` (`0x10`) byte decoded to ODR value in Hz (Power-down / 1 / 10 / 25 / 50 / 75), BDU flag, EN_LPFP / LPFP_CFG bandwidth (ODR/9 or ODR/20 when enabled), SIM (SPI mode).
- `CTRL_REG2` (`0x11`) byte decoded to BOOT/SWRESET/ONE_SHOT pulses, FIFO_EN, STOP_ON_FTH, IF_ADD_INC, I2C_DIS.
- `CTRL_REG3` (`0x12`) byte decoded to INT_H_L polarity, PP_OD drive mode, and the four routing flags (F_FSS5, F_FTH, F_OVR, DRDY) plus INT_S[1:0] signal selection.
- `FIFO_CTRL` (`0x14`) byte decoded to F_MODE name (Bypass / FIFO / Stream / Stream-to-FIFO / Bypass-to-Stream / Dynamic-Stream / Bypass-to-FIFO) and watermark value (0–31).
- `REF_P_XL` / `REF_P_L` / `REF_P_H` (`0x15`–`0x17`) auto-increment read shown as a single 24-bit reference pressure in hPa.
- `RPDS_L` / `RPDS_H` (`0x18`–`0x19`) auto-increment read shown as a single 16-bit signed pressure offset in hPa (LSB = 1/16 hPa).
- `RES_CONF` (`0x1A`) byte decoded to LC_EN low-current mode flag.
- `INT_SOURCE` (`0x25`) byte decoded to named flags (BOOT_STATUS, IA, PL, PH) as "Pressure low"/"Pressure high"/"Interrupt active".
- `FIFO_STATUS` (`0x26`) byte decoded to FTH_FIFO flag, OVR flag, and FSS[5:0] count (0=empty, 32=full).
- `STATUS` (`0x27`) byte decoded to named flags (T_OR, P_OR, T_DA, P_DA) annotated as "Pressure ready"/"Temperature ready".
- `PRESS_OUT_XL` / `PRESS_OUT_L` / `PRESS_OUT_H` (`0x28`–`0x2A`) auto-increment read shown as a single 24-bit two's complement signed raw count, plus the converted pressure in Pa and hPa (1 count = 100/4096 Pa). Note that with BDU=1 the latch only releases when `PRESS_OUT_H` is the last register read; the decoder emits the burst-read end timestamp at the end of the `PRESS_OUT_H` byte.
- `TEMP_OUT_L` / `TEMP_OUT_H` (`0x2B`–`0x2C`) auto-increment read shown as a single 16-bit two's complement signed raw count, plus the converted temperature in °C (1 count = 0.01 °C).
- `LPFP_RES` (`0x33`) read annotated as "LPF reset" (read to flush transitory state).

For each Timing Constraint flagged for conformance, the decoder emits the named annotation pair the Timing Constraints section names — the checker reads these timestamps directly from the decoded capture:

- `one_shot_start` / `one_shot_done` — `one_shot_conversion` conformance check (≤ 1.4 ms max at ODR=000).

These annotation names are referenced verbatim from `specs/pressure/lps33hw_timing.conf`.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/pressure/lps33hw.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/pressure/lps33hw/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/pressure/lps33hw/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/pressure/lps33hw/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/pressure/lps33hw_test.py` (MicroPython)
- [ ] Tests `python/tests/pressure/lps33hw_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/pressure/lps33hw_test_linux.py` (Linux)
- [ ] Unit test `python/tests/pressure/lps33hw_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/pressure/lps33hw/lps33hw.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/pressure/lps33hw/lps33hw_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/pressure/lps33hw/lps33hw.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/pressure/lps33hw/Lps33hw.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/pressure/lps33hw/Lps33hw.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/pressure/Lps33hw.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/pressure/Lps33hw.cpp`
- [ ] Examples `cpp/examples/arduino/pressure/Lps33hw/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/pressure/Lps33hw/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/pressure/Lps33hw/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/pressure/Lps33hw/minimal/{main.cpp,CMakeLists.txt,prj.conf}` — Tier-1
- [ ] Examples `cpp/examples/zephyr/pressure/Lps33hw/complete/{main.cpp,CMakeLists.txt,prj.conf}` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/pressure/Lps33hw/demo/{main.cpp,CMakeLists.txt,prj.conf}` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/pressure/Lps33hw/minimal/{CMakeLists.txt,sdkconfig.defaults,main/CMakeLists.txt,main/main.cpp}` — Tier-1
- [ ] Examples `cpp/examples/espidf/pressure/Lps33hw/complete/{...}` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/pressure/Lps33hw/demo/{...}` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/pressure/Lps33hw/minimal/{CMakeLists.txt,src/main.cpp}` — Tier-1
- [ ] Examples `cpp/examples/picosdk/pressure/Lps33hw/complete/{...}` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/pressure/Lps33hw/demo/{...}` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/pressure/lps33hw_test/lps33hw_test.ino` (Arduino)
- [ ] Tests `cpp/tests/pressure/lps33hw_test_linux/lps33hw_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/pressure/lps33hw_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Unit test `cpp/tests/pressure/lps33hw_test_unit/lps33hw_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/pressure/lps33hw.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/pressure/lps33hw/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/pressure/lps33hw/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/pressure/lps33hw/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/pressure/lps33hw_test.js`
- [ ] Unit test `nodejs/tests/pressure/lps33hw_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-pressure/nodes/lps33hw/lps33hw.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-pressure/nodes/lps33hw/lps33hw.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-pressure/examples/lps33hw/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/pressure/lps33hw.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/pressure/lps33hw/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/pressure/lps33hw/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/pressure/lps33hw/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Examples `rust/examples/embedded/esp32s3/pressure/lps33hw/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/embedded/esp32s3/pressure/lps33hw/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/embedded/esp32s3/pressure/lps33hw/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/pressure/lps33hw_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/pressure/lps33hw_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/pressure/lps33hw.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/pressure/lps33hw.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/pressure/lps33hw/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/pressure/lps33hw/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/pressure/lps33hw/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/pressure/lps33hw/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/pressure/lps33hw/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/pressure/lps33hw/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/pressure/lps33hw_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/pressure/lps33hw_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/pressure/lps33hw_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/pressure/Lps33hwMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/pressure/Lps33hwFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/pressure/Lps33hwMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/pressure/Lps33hwFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/pressure/Lps33hwMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/pressure/Lps33hwFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/pressure/lps33hw/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/pressure/lps33hw/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/pressure/lps33hw/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/pressure/lps33hw/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/pressure/lps33hw/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/pressure/lps33hw/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/pressure/lps33hw/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/pressure/lps33hw/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/pressure/lps33hw/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/pressure/lps33hw/Lps33hwTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/pressure/Lps33hwTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/pressure/Lps33hwTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/pressure/Lps33hwSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/lps33hw/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/lps33hw/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [ ] Checker `conformance/pressure/lps33hw_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/pressure/lps33hw_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/lps33hw/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` only

# Chip Spec: LPS28DFW

**Manufacturer:** STMicroelectronics  
**Datasheet:** `datasheets/pressure/lps28dfw.pdf`  
**Category:** pressure  
**Transports:** I²C

## Overview

The LPS28DFW is an ultra-compact, dual full-scale, absolute digital barometer with a water-resistant CCLGA-7L package. It measures absolute pressure in two selectable full-scale ranges: Mode 1 (260–1260 hPa, ±0.5 hPa accuracy) and Mode 2 (260–4060 hPa, suited for water depth up to 30 m). Temperature is read back as a by-product. Factory-trimmed calibration coefficients are loaded automatically at power-up — no host compensation is required. Configurable averaging (4–512 samples), an optional IIR low-pass filter, and a 128-sample FIFO support both high-precision weather logging and very-low-power wearable applications. Typical current consumption is 1.7 µA at 1 Hz with AVG=4.

## Transport Configuration

### I²C

- **Address:** `0x5C` (SA0 = GND) — `0x5D` (SA0 = VDD)
- **Max clock:** 1 MHz (fast mode+); 400 kHz (fast mode)
- **Address auto-increment:** enabled by default (IF_ADD_INC=1 in CTRL_REG3); supports burst reads

## Register Map

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x0B` | INTERRUPT_CFG | R/W | `0x00` | Interrupt configuration |
| `0x0C` | THS_P_L | R/W | `0x00` | Pressure threshold, bits 7:0 |
| `0x0D` | THS_P_H | R/W | `0x00` | Pressure threshold, bits 14:8 |
| `0x0E` | IF_CTRL | R/W | `0x00` | Interface control |
| `0x0F` | WHO_AM_I | R | `0xB4` | Fixed device identifier |
| `0x10` | CTRL_REG1 | R/W | `0x00` | ODR and averaging |
| `0x11` | CTRL_REG2 | R/W | `0x00` | Full-scale, filter, BDU, reset, one-shot |
| `0x12` | CTRL_REG3 | R/W | `0x01` | Interrupt pin polarity and address increment |
| `0x13` | CTRL_REG4 | R/W | `0x00` | Interrupt pin output sources |
| `0x14` | FIFO_CTRL | R/W | `0x00` | FIFO mode and watermark stop |
| `0x15` | FIFO_WTM | R/W | `0x00` | FIFO watermark level, bits 6:0 |
| `0x16` | REF_P_L | R | `0x00` | Reference pressure LSB (AUTOZERO/AUTOREFP) |
| `0x17` | REF_P_H | R | `0x00` | Reference pressure MSB |
| `0x19` | I3C_IF_CTRL | R/W | `0x80` | I3C interface control |
| `0x1A` | RPDS_L | R/W | `0x00` | Pressure offset (OPC) LSB |
| `0x1B` | RPDS_H | R/W | `0x00` | Pressure offset (OPC) MSB |
| `0x24` | INT_SOURCE | R | — | Interrupt source; cleared on read |
| `0x25` | FIFO_STATUS1 | R | — | FIFO unread sample count (FSS[7:0]) |
| `0x26` | FIFO_STATUS2 | R | — | FIFO watermark/overrun/full flags |
| `0x27` | STATUS | R | — | Data-available and overrun flags |
| `0x28` | PRESSURE_OUT_XL | R | — | Pressure bits 7:0 |
| `0x29` | PRESSURE_OUT_L | R | — | Pressure bits 15:8 |
| `0x2A` | PRESSURE_OUT_H | R | — | Pressure bits 23:16 |
| `0x2B` | TEMP_OUT_L | R | — | Temperature bits 7:0 |
| `0x2C` | TEMP_OUT_H | R | — | Temperature bits 15:8 |
| `0x78` | FIFO_DATA_OUT_PRESS_XL | R | — | FIFO pressure bits 7:0 |
| `0x79` | FIFO_DATA_OUT_PRESS_L | R | — | FIFO pressure bits 15:8 |
| `0x7A` | FIFO_DATA_OUT_PRESS_H | R | — | FIFO pressure bits 23:16 |

### Bit Fields

#### `INTERRUPT_CFG` (`0x0B`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | AUTOREFP | 1 = enable AUTOREFP (measures REF_P once, then generates P_DIFF_IN = measured − REF_P for interrupt) |
| 6 | RESET_ARP | 1 = reset AUTOREFP (self-clears) |
| 5 | AUTOZERO | 1 = enable AUTOZERO (captures REF_P, output becomes differential from that point) |
| 4 | RESET_AZ | 1 = reset AUTOZERO (self-clears) |
| 2 | LIR | 1 = latch interrupt until INT_SOURCE read |
| 1 | PLE | 1 = enable interrupt on pressure below threshold |
| 0 | PHE | 1 = enable interrupt on pressure above threshold |

#### `CTRL_REG1` (`0x10`)

| Bits | Name | Description |
|------|------|-------------|
| 6:3 | ODR[3:0] | Output data rate (see table below) |
| 2:0 | AVG[2:0] | Averaging selection (see table below) |

#### ODR Selection

| ODR[3:0] | Output rate |
|----------|-------------|
| `0000` | Power-down / one-shot |
| `0001` | 1 Hz |
| `0010` | 4 Hz |
| `0011` | 10 Hz |
| `0100` | 25 Hz |
| `0101` | 50 Hz |
| `0110` | 75 Hz |
| `0111` | 100 Hz |
| `1xxx` | 200 Hz |

#### Averaging Selection

| AVG[2:0] | Samples averaged | Pressure noise (Pa rms, Mode 1) |
|----------|------------------|---------------------------------|
| `000` | 4 | 3.8 |
| `001` | 8 | 2.9 |
| `010` | 16 | 2.1 |
| `011` | 32 | 1.5 |
| `100` | 64 | 1.1 |
| `101` | 128 | 0.86 |
| `111` | 512 | 0.56 |

#### `CTRL_REG2` (`0x11`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | BOOT | 1 = reload factory trimming from NVM (self-clears) |
| 6 | FS_MODE | Full-scale: 0 = Mode 1 (0–1260 hPa), 1 = Mode 2 (0–4060 hPa) |
| 5 | LFPF_CFG | Low-pass filter bandwidth: 0 = ODR/4, 1 = ODR/9 |
| 4 | EN_LPFP | 1 = enable IIR low-pass filter on pressure output |
| 3 | BDU | 1 = block data update (output regs frozen until both MSB and LSB read) |
| 1 | SWRESET | 1 = software reset (self-clears) |
| 0 | ONESHOT | 1 = trigger one-shot measurement when ODR=0000 (self-clears when done) |

#### `CTRL_REG3` (`0x12`)

| Bits | Name | Description |
|------|------|-------------|
| 3 | INT_H_L | Interrupt pin polarity: 0 = active-high, 1 = active-low |
| 1 | PP_OD | INT_DRDY pin output: 0 = push-pull, 1 = open-drain |
| 0 | IF_ADD_INC | 1 = auto-increment address on burst (default 1) |

#### `CTRL_REG4` (`0x13`)

| Bits | Name | Description |
|------|------|-------------|
| 6 | DRDY_PLS | 1 = data-ready pulsed (~5 µs) on INT_DRDY |
| 5 | DRDY | 1 = route data-ready to INT_DRDY |
| 4 | INT_EN | 1 = route pressure threshold interrupt to INT_DRDY |
| 2 | INT_F_FULL | 1 = route FIFO full flag to INT_DRDY |
| 1 | INT_F_WTM | 1 = route FIFO watermark flag to INT_DRDY |
| 0 | INT_F_OVR | 1 = route FIFO overrun flag to INT_DRDY |

#### `FIFO_CTRL` (`0x14`)

| Bits | Name | Description |
|------|------|-------------|
| 3 | STOP_ON_WTM | 1 = FIFO depth limited to WTM[6:0] samples |
| 2 | TRIG_MODES | 1 = enable triggered FIFO modes |
| 1:0 | F_MODE[1:0] | FIFO mode selection (see table below) |

#### FIFO Mode Selection

| TRIG_MODES | F_MODE[1:0] | Mode |
|------------|-------------|------|
| x | `00` | Bypass (FIFO disabled) |
| 0 | `01` | FIFO mode (fills to 128, stops) |
| 0 | `1x` | Continuous / dynamic-stream (ring buffer) |
| 1 | `01` | Bypass-to-FIFO |
| 1 | `10` | Bypass-to-continuous |
| 1 | `11` | Continuous-to-FIFO |

To reset FIFO: write Bypass mode (TRIG_MODES=0, F_MODE=00), then re-write the desired mode.

#### `STATUS` (`0x27`)

| Bits | Name | Description |
|------|------|-------------|
| 5 | T_OR | Temperature overrun (new data overwrote previous) |
| 4 | P_OR | Pressure overrun |
| 1 | T_DA | New temperature data available |
| 0 | P_DA | New pressure data available |

#### `INT_SOURCE` (`0x24`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | BOOT_ON | 1 = boot/reboot phase in progress |
| 2 | IA | 1 = one or more interrupt events generated |
| 1 | PL | 1 = low differential pressure event |
| 0 | PH | 1 = high differential pressure event |

#### `FIFO_STATUS1` (`0x25`) / `FIFO_STATUS2` (`0x26`)

| Register | Bits | Name | Description |
|----------|------|------|-------------|
| `0x25` | 7:0 | FSS[7:0] | Number of unread samples in FIFO (0=empty, 128=full) |
| `0x26` | 7 | FIFO_WTM_IA | 1 = FIFO filled to or above watermark |
| `0x26` | 6 | FIFO_OVR_IA | 1 = FIFO full and at least one sample overwritten |
| `0x26` | 5 | FIFO_FULL_IA | 1 = FIFO completely filled, no overwrite |

## Initialization Sequence

1. Power-up; device boots automatically (factory calibration loaded).
2. Wait for BOOT_ON bit in INT_SOURCE (`0x24`) to clear, or wait 2 ms.
3. Verify WHO_AM_I (`0x0F`) == `0xB4`; abort if mismatch.
4. Write CTRL_REG2 (`0x11`): set FS_MODE, EN_LPFP, LFPF_CFG, BDU.
5. Write CTRL_REG1 (`0x10`): set AVG and ODR.
6. Device begins continuous measurements at the selected ODR.

## Implementation Stages

### Minimal

Goal: read barometric pressure and temperature with no configuration required beyond the transport.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | transport | — | Verifies WHO_AM_I; applies defaults; starts continuous mode |
| `read_pressure` | — | float hPa | Reads 0x28–0x2A; converts signed 24-bit |
| `read_temperature` | — | float °C | Reads 0x2B–0x2C; converts signed 16-bit |

**Sensible defaults:** FS_MODE=0 (Mode 1, 0–1260 hPa), AVG=`010` (16 samples), ODR=`0100` (25 Hz), BDU=1, EN_LPFP=1, LFPF_CFG=0 (ODR/4).

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `configure` | `odr: int` (0–8), `avg: int` (0–6), `fs_mode: int` (0–1), `lpf_en: bool`, `lpf_cfg: int` (0–1) | — | Writes CTRL_REG1 and CTRL_REG2 |
| `read` | — | dict | `{"pressure": float, "temperature": float}`; burst-reads 0x28–0x2C |
| `read_oneshot` | — | dict | Sets ODR=0, triggers ONESHOT, polls P_DA, reads |
| `is_data_ready` | — | bool | True if STATUS.P_DA set |
| `set_offset` | `offset_hpa: float` | — | Writes signed 16-bit OPC value to RPDS_L/H for post-soldering one-point calibration |
| `softreset` | — | — | Sets SWRESET bit; waits 2 ms |
| `fifo_configure` | `mode: int` (0–2: bypass/fifo/continuous), `wtm: int` (0–127), `stop_on_wtm: bool` | — | Writes FIFO_CTRL and FIFO_WTM |
| `fifo_read` | `count: int` | list of float hPa | Reads `count` × 3 bytes from 0x78; converts each to hPa |
| `fifo_level` | — | int | Returns FSS[7:0] from FIFO_STATUS1 |
| `set_threshold` | `threshold_hpa: float`, `high: bool`, `low: bool` | — | Writes THS_P_L/H; enables PHE/PLE in INTERRUPT_CFG |

**Additional configuration options:**
- Mode 2 full-scale (260–4060 hPa) for submersible/high-pressure applications
- Averaging from 4 to 512 samples
- ODR from 1 Hz to 200 Hz or one-shot
- IIR low-pass filter (ODR/4 or ODR/9 bandwidth)
- Block data update (BDU) for coherent pressure+temperature reads
- 128-sample pressure FIFO with six operating modes; watermark and overrun interrupts
- Pressure threshold interrupt (high/low) with AUTOZERO and AUTOREFP differential modes
- One-point calibration offset (RPDS) for post-soldering trim
- Data-ready, FIFO-full, FIFO-watermark, FIFO-overrun signals on INT_DRDY pin

## Data Conversion

```
# Pressure (Mode 1, FS_MODE=0)
pressure_hpa = signed_24bit / 4096.0

# Pressure (Mode 2, FS_MODE=1)
pressure_hpa = signed_24bit / 2048.0

# Temperature
temperature_c = signed_16bit / 100.0

# Altitude (approximate barometric formula)
altitude_m = 44330.0 * (1.0 - (P / P0) ** (1.0 / 5.255))
# P = pressure in hPa, P0 = 1013.25 hPa (ISA sea-level)

# Pressure threshold register (THS_P, 15-bit unsigned)
THS_P = int(threshold_hpa * 16)   # for Mode 1 (FS_MODE=0)
THS_P = int(threshold_hpa * 8)    # for Mode 2 (FS_MODE=1)

# One-point calibration offset register (RPDS, 16-bit signed)
RPDS = int(offset_hpa * 4096)     # for Mode 1
RPDS = int(offset_hpa * 2048)     # for Mode 2
```

The 24-bit pressure value is signed (two's complement). Burst-reading registers `0x28`–`0x2A` in that order returns XL, L, H; assemble as: `value = (H << 16) | (L << 8) | XL`, then sign-extend from 24 bits.

## Node-RED

Node name: `periph-lps28dfw`  
Package: `node-red-contrib-periph-pressure`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ pressure: float, temperature: float }` | pressure in hPa, temperature in °C |

Config panel fields:
- **I²C bus** — bus number (e.g. 1 for `/dev/i2c-1`)
- **I²C address** — `0x5C` / `0x5D` (SA0 pin)
- **Full-scale mode** — Mode 1 (0–1260 hPa) / Mode 2 (0–4060 hPa)
- **Averaging** — 4 / 8 / 16 / 32 / 64 / 128 / 512
- **ODR** — 1 / 4 / 10 / 25 / 50 / 75 / 100 / 200 Hz (default 25 Hz)

### Demo flow

An inject node fires every 5 seconds, triggering the `periph-lps28dfw` node. A function node converts the pressure reading to estimated altitude using the barometric formula (P₀ = 1013.25 hPa) and adds an `altitude` field to `msg.payload`. A debug node displays pressure (hPa), temperature (°C), and altitude (m).

## Examples

### Demo

Depth/altitude logger: configure Mode 1 with 64-sample averaging and 25 Hz ODR. Read pressure and temperature every 500 ms. Compute estimated altitude from the barometric formula (P₀ = 1013.25 hPa). Print a formatted table row every iteration: elapsed time (s), pressure (hPa, 2 decimal places), temperature (°C, 2 decimal places), altitude (m, 1 decimal place). Run for 30 seconds and report total sample count. This demonstrates the ultra-low noise at AVG=64 and the 0.244 Pa (0.02 hPa) pressure resolution of Mode 1.

## Timing Constraints

- **Boot time:** ≤2 ms from power-up before WHO_AM_I can be read.
- **SWRESET recovery:** ~2 ms; poll BOOT_ON bit in INT_SOURCE, or wait 2 ms.
- **One-shot conversion time:** depends on ODR-equivalent measurement bandwidth and averaging; poll STATUS.P_DA or wait 1/ODR_equivalent after writing ONESHOT=1.
- **BDU important:** with BDU=1, PRESS_OUT_H (`0x2A`) must be the last register read in a pressure burst — do not change the burst order (XL → L → H).
- **FIFO reset:** to switch FIFO modes, always pass through Bypass mode first (write FIFO_CTRL=0x00), then write the new mode.

## Implementation Notes

- **No compensation code needed** — factory calibration is applied entirely in hardware; the 24-bit output is already compensated. Simply divide by sensitivity (4096 or 2048) to get hPa.
- **BDU=1 for safe reads** — without BDU, it is possible to read MSB from one sample and LSB from the next. Always enable BDU and read XL → L → H in order, ending on H.
- **Signed 24-bit sign-extension** — after assembling `(H<<16)|(L<<8)|XL`, check bit 23; if set, sign-extend: `value |= ~0xFFFFFF`. In Python: `value = struct.unpack_from('>i', bytes([H, L, XL, 0]))[0] >> 8`.
- **FIFO pressure only** — the FIFO stores only 24-bit pressure samples (128 slots × 3 bytes = 384 bytes). Temperature is not stored in the FIFO. Read temperature from TEMP_OUT_L/H separately if needed.
- **Burst-reading all FIFO samples:** read 384 bytes starting at `0x78` in one transaction (IF_ADD_INC=1 wraps from 0x7A back to 0x78 automatically).
- **Mode 2 extended range** — at pressures above 1260 hPa (e.g., water depth monitoring), set FS_MODE=1. Sensitivity halves to 2048 LSB/hPa; accuracy degrades to ±0.13–±0.36% at higher pressures.
- **WHO_AM_I is 0xB4**, not the older LPS22/25/28 family value. Check this in init.
- **IF_ADD_INC is 1 by default** (CTRL_REG3 reset = `0x01`). Burst reads work without any setup.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/pressure/lps28dfw.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/pressure/lps28dfw/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/pressure/lps28dfw/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/pressure/lps28dfw/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/pressure/lps28dfw_test.py` (MicroPython)
- [ ] Tests `python/tests/pressure/lps28dfw_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/pressure/lps28dfw_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/pressure/LPS28DFW.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/pressure/LPS28DFW.cpp`
- [ ] Examples `cpp/examples/LPS28DFW_Minimal/LPS28DFW_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/LPS28DFW_Complete/LPS28DFW_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/LPS28DFW_Demo/LPS28DFW_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/LPS28DFW_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/LPS28DFW_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/LPS28DFW_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/pressure/lps28dfw_test/lps28dfw_test.ino` (Arduino)
- [ ] Tests `cpp/tests/pressure/lps28dfw_test_linux/lps28dfw_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/pressure/lps28dfw_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/pressure/lps28dfw.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/pressure/lps28dfw/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/pressure/lps28dfw/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/pressure/lps28dfw/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/pressure/lps28dfw_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-pressure/nodes/lps28dfw/lps28dfw.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-pressure/nodes/lps28dfw/lps28dfw.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-pressure/examples/lps28dfw/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/pressure/lps28dfw.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/lps28dfw_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/lps28dfw_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/lps28dfw_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/pressure/lps28dfw_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/pressure/lps28dfw_test_esp32s3/src/main.rs` (ESP32-S3)

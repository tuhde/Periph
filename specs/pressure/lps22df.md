# Chip Spec: LPS22DF

**Manufacturer:** STMicroelectronics  
**Datasheet:** `datasheets/pressure/lps22df.pdf`  
**Category:** `pressure`  
**Transports:** I²C, SPI

## Overview

The LPS22DF is an ultracompact, piezoresistive absolute pressure sensor that functions as a digital output barometer. It covers 260–1260 hPa absolute pressure with 24-bit output resolution (4096 LSB/hPa, ~0.244 Pa), an integrated temperature channel (16-bit, 100 LSB/°C), and an output data rate from 1 Hz to 200 Hz. An embedded 128-sample FIFO, configurable low-pass filter, interrupt engine (data-ready, FIFO thresholds, pressure threshold events), and AUTOZERO / AUTOREFP modes for differential pressure measurement round out the feature set. Factory calibrated — no user calibration step required. Supplied in a 2.0 × 2.0 × 0.73 mm holed HLGA-10L package; the hole allows external pressure to reach the MEMS sensing element. Suitable for portable altimeters, GPS receivers, weather stations, drones, and similar pressure/altitude applications.

## Transport Configuration

### I²C

- **Address:** `0x5C` (SA0=0, SDO/SA0 pin tied to GND) — `0x5D` (SA0=1, SDO/SA0 pin tied to Vdd_IO)
- **Max clock:** 1000 kHz (fast mode plus); 400 kHz (fast mode)
- **CS pin:** must be tied HIGH (to Vdd_IO) to enable I²C mode
- **Register auto-increment:** enabled by default (IF_ADD_INC=1 in CTRL_REG3); supports multi-byte burst reads

### SPI

- **Mode:** CPOL=1 CPHA=1 (Mode 3) — clock idles high; data driven on falling edge, captured on rising edge
- **Max clock:** 10 MHz (≤8 MHz recommended for ODR >50 Hz)
- **Bit order:** MSB first
- **CS active:** low
- **Wiring:** 4-wire (default) or 3-wire (set SIM=1 in IF_CTRL)
- **SPI frame:** bit 0 = R/W̄, bits 1–7 = address, bits 8–15 = data (read: chip drives SDO; write: host drives SDI)

## Register Map

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00`–`0x0A` | — | — | — | Reserved |
| `0x0B` | INTERRUPT_CFG | R/W | `0x00` | Interrupt mode configuration |
| `0x0C` | THS_P_L | R/W | `0x00` | Pressure threshold LSB |
| `0x0D` | THS_P_H | R/W | `0x00` | Pressure threshold MSB |
| `0x0E` | IF_CTRL | R/W | `0x00` | Interface control (SPI 3-wire, pull-ups) |
| `0x0F` | WHO_AM_I | R | `0xB4` | Device ID |
| `0x10` | CTRL_REG1 | R/W | `0x00` | ODR and averaging selection |
| `0x11` | CTRL_REG2 | R/W | `0x00` | Boot, low-pass filter, BDU, reset, one-shot |
| `0x12` | CTRL_REG3 | R/W | `0x01` | Interrupt polarity, push-pull, address auto-increment |
| `0x13` | CTRL_REG4 | R/W | `0x00` | Interrupt pin routing |
| `0x14` | FIFO_CTRL | R/W | `0x00` | FIFO mode and trigger settings |
| `0x15` | FIFO_WTM | R/W | `0x00` | FIFO watermark level |
| `0x16` | REF_P_L | R | `0x00` | Reference pressure LSB (AUTOZERO/AUTOREFP result) |
| `0x17` | REF_P_H | R | `0x00` | Reference pressure MSB |
| `0x18` | — | — | — | Reserved |
| `0x19` | I3C_IF_CTRL | R/W | `0x80` | MIPI I3C interface configuration |
| `0x1A` | RPDS_L | R/W | `0x00` | Pressure offset (one-point calibration) LSB |
| `0x1B` | RPDS_H | R/W | `0x00` | Pressure offset (one-point calibration) MSB |
| `0x1C`–`0x23` | — | — | — | Reserved |
| `0x24` | INT_SOURCE | R | output | Interrupt source (read clears register) |
| `0x25` | FIFO_STATUS1 | R | output | FIFO stored sample count |
| `0x26` | FIFO_STATUS2 | R | output | FIFO watermark / overrun / full flags |
| `0x27` | STATUS | R | output | Data available and overrun flags |
| `0x28` | PRESS_OUT_XL | R | output | Pressure output bits 7:0 (LSB) |
| `0x29` | PRESS_OUT_L | R | output | Pressure output bits 15:8 |
| `0x2A` | PRESS_OUT_H | R | output | Pressure output bits 23:16 (MSB) |
| `0x2B` | TEMP_OUT_L | R | output | Temperature output bits 7:0 (LSB) |
| `0x2C` | TEMP_OUT_H | R | output | Temperature output bits 15:8 (MSB) |
| `0x2D`–`0x77` | — | — | — | Reserved |
| `0x78` | FIFO_DATA_OUT_PRESS_XL | R | output | FIFO pressure LSB |
| `0x79` | FIFO_DATA_OUT_PRESS_L | R | output | FIFO pressure middle byte |
| `0x7A` | FIFO_DATA_OUT_PRESS_H | R | output | FIFO pressure MSB |

### Bit Fields

#### `CTRL_REG1` (`0x10`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | — | Reserved; must be 0 |
| 6:3 | ODR[3:0] | Output data rate (see ODR table) |
| 2:0 | AVG[2:0] | Averaging filter (see AVG table) |

**ODR table:**

| ODR[3:0] | Rate |
|----------|------|
| `0000` | Power-down / one-shot |
| `0001` | 1 Hz |
| `0010` | 4 Hz |
| `0011` | 10 Hz |
| `0100` | 25 Hz |
| `0101` | 50 Hz |
| `0110` | 75 Hz |
| `0111` | 100 Hz |
| `1xxx` | 200 Hz |

**AVG table:**

| AVG[2:0] | Samples averaged |
|----------|-----------------|
| `000` | 4 |
| `001` | 8 |
| `010` | 16 |
| `011` | 32 |
| `100` | 64 |
| `101` | 128 |
| `111` | 512 |

#### `CTRL_REG2` (`0x11`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | BOOT | Reboot memory content (0: normal; 1: reboot — self-clears) |
| 6 | — | Reserved |
| 5 | LFPF_CFG | Low-pass filter cutoff (0: ODR/4; 1: ODR/9) |
| 4 | EN_LPFP | Enable low-pass filter on pressure (0: disabled; 1: enabled) |
| 3 | BDU | Block data update (0: continuous; 1: hold until both MSB and LSB read) |
| 2 | SWRESET | Software reset (0: normal; 1: reset — self-clears when complete) |
| 1 | — | Reserved |
| 0 | ONESHOT | Trigger one measurement in power-down mode (self-clears when done) |

#### `CTRL_REG3` (`0x12`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4 | — | Reserved |
| 3 | INT_H_L | Interrupt pin polarity (0: active-high; 1: active-low) |
| 2 | — | Reserved |
| 1 | PP_OD | Interrupt pin type (0: push-pull; 1: open-drain) |
| 0 | IF_ADD_INC | Auto-increment address on multi-byte access (0: disabled; 1: enabled) — **default 1** |

#### `CTRL_REG4` (`0x13`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | — | Reserved |
| 6 | DRDY_PLS | Data-ready pulsed (~5 µs pulse on INT pin; only active when DRDY=1) |
| 5 | DRDY | Route data-ready signal to INT pin |
| 4 | INT_EN | Route pressure threshold interrupt to INT pin |
| 3 | — | Reserved |
| 2 | INT_F_FULL | Route FIFO-full flag to INT pin |
| 1 | INT_F_WTM | Route FIFO watermark flag to INT pin |
| 0 | INT_F_OVR | Route FIFO overrun flag to INT pin |

#### `FIFO_CTRL` (`0x14`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4 | — | Reserved |
| 3 | STOP_ON_WTM | Stop FIFO filling at watermark level |
| 2 | TRIG_MODES | Enable triggered FIFO modes |
| 1:0 | F_MODE[1:0] | FIFO mode selection (see FIFO mode table) |

**FIFO mode table:**

| TRIG_MODES | F_MODE[1:0] | Mode |
|------------|-------------|------|
| x | `00` | Bypass (FIFO disabled) |
| 0 | `01` | FIFO mode |
| 0 | `1x` | Continuous (dynamic-stream) |
| 1 | `01` | Bypass-to-FIFO |
| 1 | `10` | Bypass-to-continuous |
| 1 | `11` | Continuous-to-FIFO |

#### `INTERRUPT_CFG` (`0x0B`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | AUTOREFP | Enable AUTOREFP mode (self-clears after first conversion) |
| 6 | RESET_ARP | Reset AUTOREFP function |
| 5 | AUTOZERO | Enable AUTOZERO mode (self-clears after first conversion) |
| 4 | RESET_AZ | Reset AUTOZERO function (also clears REF_P registers) |
| 3 | — | Reserved |
| 2 | LIR | Latch interrupt request in INT_SOURCE (0: not latched; 1: latched) |
| 1 | PLE | Enable interrupt on pressure low event |
| 0 | PHE | Enable interrupt on pressure high event |

#### `STATUS` (`0x27`)

| Bits | Name | Description |
|------|------|-------------|
| 7:6 | — | Reserved |
| 5 | T_OR | Temperature data overrun |
| 4 | P_OR | Pressure data overrun |
| 3:2 | — | Reserved |
| 1 | T_DA | Temperature data available |
| 0 | P_DA | Pressure data available |

#### `INT_SOURCE` (`0x24`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | BOOT_ON | Boot phase in progress |
| 6:3 | — | Reserved |
| 2 | IA | Interrupt active (at least one event generated) |
| 1 | PL | Differential pressure low event |
| 0 | PH | Differential pressure high event |

## Initialization Sequence

1. Power up; POR completes and factory calibration is loaded automatically (~5 ms for VDD ramp)
2. Read WHO_AM_I (`0x0F`); verify value is `0xB4`
3. Write `SWRESET=1` to CTRL_REG2 (`0x11`); poll until SWRESET reads 0 (self-clears in < 5 µs)
4. Write CTRL_REG1 (`0x10`): set desired ODR[3:0] and AVG[2:0]
5. Write CTRL_REG2 (`0x11`): set BDU=1 (block data update)
6. Poll STATUS (`0x27`) P_DA bit; when set, burst-read 3 bytes from `0x28`–`0x2A` (pressure) and 2 bytes from `0x2B`–`0x2C` (temperature)

IF_ADD_INC is 1 by default (CTRL_REG3 reset value = `0x01`), so burst reads auto-increment the address.

When BDU=1, PRESS_OUT_H (`0x2A`) **must be the last address read** to release the hold and allow registers to update.

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits everything and adds the rest.

### Minimal

Goal: expose continuous pressure and temperature measurement with a simple, user-friendly interface. No configuration required beyond the transport.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | transport | — | Verifies WHO_AM_I, resets, enables 10 Hz / AVG=4 / BDU=1 continuous mode |
| `pressure` | — | float | Pa; reads STATUS.P_DA then burst-reads 0x28–0x2A |
| `temperature` | — | float | °C; reads STATUS.T_DA then burst-reads 0x2B–0x2C |

**Sensible defaults baked into Minimal:**
- ODR: 10 Hz (ODR[3:0] = `0011`)
- AVG: 4 (AVG[2:0] = `000`)
- BDU: 1 (block data update enabled)
- Low-pass filter: disabled
- FIFO: bypass mode

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `configure` | `odr` (0–8), `avg` (0–6), `en_lpfp` (bool), `lfpf_cfg` (0–1), `bdu` (bool) | — | Writes CTRL_REG1 and CTRL_REG2 |
| `oneshot` | — | — | Sets ODR to power-down then triggers ONESHOT; blocks until STATUS.P_DA=1 |
| `altitude` | `sea_level_Pa=101325.0` | float | m; barometric altitude from current pressure |
| `software_reset` | — | — | Asserts SWRESET, waits for self-clear |
| `set_pressure_offset` | `offset_Pa` (float) | — | Writes 16-bit two's complement to RPDS_L/H for one-point calibration after soldering |
| `set_pressure_threshold` | `threshold_Pa` (float) | — | Writes 15-bit unsigned to THS_P_L/H; enables interrupt when exceeds threshold |
| `configure_interrupt` | `int_h_l` (bool), `pp_od` (bool), `drdy` (bool), `drdy_pls` (bool), `int_en` (bool), `int_f_wtm` (bool), `int_f_full` (bool), `int_f_ovr` (bool) | — | Writes CTRL_REG3 and CTRL_REG4 |
| `configure_pressure_event` | `phe` (bool), `ple` (bool), `lir` (bool) | — | Writes PHE, PLE, LIR bits in INTERRUPT_CFG |
| `autozero` | — | — | Sets AUTOZERO=1; current reading becomes reference; subsequent PRESS_OUT reflects differential |
| `autorefp` | — | — | Sets AUTOREFP=1; current reading stored in REF_P for interrupt generation |
| `reset_reference` | — | — | Writes RESET_AZ=1 and RESET_ARP=1 to clear REF_P and return to absolute mode |
| `reference_pressure` | — | float | Pa; reads REF_P_L/H (stored AUTOZERO/AUTOREFP reference) |
| `set_fifo_mode` | `mode` (0–5) | — | Writes TRIG_MODES and F_MODE to FIFO_CTRL |
| `set_fifo_watermark` | `level` (0–127) | — | Writes WTM[6:0] to FIFO_WTM |
| `fifo_sample_count` | — | int | Reads FSS[7:0] from FIFO_STATUS1 |
| `read_fifo` | — | list[float] | Pa; reads all available FIFO samples via 0x78–0x7A burst |
| `interrupt_source` | — | dict | Reads INT_SOURCE; keys `boot_on`, `ia`, `ph`, `pl` |

**Additional configuration options:**
- `odr`: 0 (power-down), 1 (1 Hz), 2 (4 Hz), 3 (10 Hz), 4 (25 Hz), 5 (50 Hz), 6 (75 Hz), 7 (100 Hz), 8 (200 Hz)
- `avg`: 0 (4), 1 (8), 2 (16), 3 (32), 4 (64), 5 (128), 6 (512)
- `en_lpfp`: enable low-pass filter on pressure output
- `lfpf_cfg`: 0 = ODR/4, 1 = ODR/9 cutoff
- `bdu`: block data update — recommend keeping enabled
- FIFO modes: 0=bypass, 1=FIFO, 2=continuous, 3=bypass-to-FIFO, 4=bypass-to-continuous, 5=continuous-to-FIFO

## Data Conversion

### Pressure

```
raw = sign_extend_24bit((PRESS_OUT_H << 16) | (PRESS_OUT_L << 8) | PRESS_OUT_XL)
pressure_hPa = raw / 4096          # 4096 LSB/hPa sensitivity
pressure_Pa  = pressure_hPa * 100  # convert to SI Pa
```

The 24-bit raw value is a signed two's complement integer. The 4096 LSB/hPa sensitivity is fixed and factory-trimmed — no user scaling.

### Temperature

```
raw = sign_extend_16bit((TEMP_OUT_H << 8) | TEMP_OUT_L)
temperature_C = raw / 100           # 100 LSB/°C sensitivity
```

The 16-bit raw value is a signed two's complement integer. Resolution is 0.01 °C (1 LSB).

### Pressure threshold (THS_P registers)

```
THS_P (15-bit unsigned) = desired_threshold_hPa * 16
```

Write to THS_P_H[6:0] (bits 14:8) and THS_P_L (bits 7:0).

### Pressure offset (RPDS registers)

```
RPDS (16-bit signed two's complement) = offset_hPa * 4096
```

Write to RPDS_H (bits 15:8) and RPDS_L (bits 7:0). The offset is added to the compensated pressure output in hardware.

### Altitude (barometric formula)

```
altitude_m = 44330 * (1 - (pressure_Pa / sea_level_Pa) ** (1 / 5.255))
```

### FIFO burst read

To read all samples from FIFO, read 3 bytes per sample (XL, L, H) in a single burst starting at `0x78`. For 128 full samples, read 384 bytes; the address auto-wraps from `0x7A` back to `0x78`.

## Node-RED

Node name: `periph-lps22df`  
Package: `node-red-contrib-periph-pressure`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ pressure: Pa, temperature: degC, altitude: m }` | Altitude computed from sea-level reference in config |

Config panel fields:
- Transport type (I²C / SPI)
- I²C address (dropdown: `0x5C` / `0x5D`; default `0x5C`)
- I²C bus number (Linux `/dev/i2c-N`)
- ODR (dropdown: 1/4/10/25/50/75/100/200 Hz; default 10 Hz)
- Averaging (dropdown: 4/8/16/32/64/128/512; default 4)
- Sea-level reference pressure (Pa, default `101325`)

### Demo flow

An inject node fires every 5 seconds into the `periph-lps22df` node. Outputs go to a debug node (raw payload) and three `ui_chart` nodes plotting pressure (Pa), temperature (°C), and altitude (m) over time. A second inject node labeled "Set sea-level reference" reads current pressure and writes it back to the node's sea-level config, zeroing the altimeter at the current location. Demonstrates continuous monitoring and real-time altitude tracking.

## Examples

### Demo

An indoor altimeter that detects elevation changes as small as a few centimetres. The demo runs continuously at 25 Hz with AVG=4 and the low-pass filter enabled at ODR/9. After an initial 2-second stabilization period, the baseline pressure is captured and treated as the zero reference. The loop prints absolute pressure (Pa), temperature (°C), and altitude delta (m relative to baseline) at 1 Hz using averaged readings. When the sensor is moved vertically (e.g., raised from desk to eye level — approximately 1 m), the altitude delta updates visibly. After 30 seconds the demo prints min/max/mean of all three channels. A trailing comment at the FIFO section explains how to enable FIFO mode and burst-read 128 samples for batch logging.

## Timing Constraints

- Power-up POR and factory calibration download: allow 5 ms before first communication
- Software reset self-clear time: < 5 µs (poll SWRESET bit)
- First sample available after enabling ODR: 1/ODR seconds (one full output period)
- BDU=1 and PRESS_OUT_H must be last byte read in a burst; otherwise the register bank can be updated mid-read at high ODR
- VDD must remain below 0.7 V for at least 10 ms during power-off for correct POR on next power-up

## Implementation Notes

- The LPS22DF has **no factory calibration read step** — unlike BMP180, trimming values are loaded automatically at power-up and used internally. The driver never needs to read calibration registers.
- WHO_AM_I fixed value `0xB4` is the mandatory presence check. Fail initialization if it doesn't match.
- IF_ADD_INC (CTRL_REG3 bit 0) is `1` by default and must not be cleared — burst reads depend on it. Do not write `0x00` to CTRL_REG3.
- BDU=1 is strongly recommended for all ODR settings. With BDU=1 and burst reads, **read XL then L then H** (natural address order 0x28→0x2A); PRESS_OUT_H at `0x2A` must be the last byte read to release the latch and allow the next measurement to be written.
- On sign extension: PRESS_OUT is 24-bit two's complement. Languages without native 24-bit integer types (Python, JS, Java, Kotlin, Groovy) must manually sign-extend: if `raw >= 0x800000`, `raw -= 0x1000000`.
- The RPDS (pressure offset) registers implement one-point calibration after soldering and are stored in non-volatile memory. Changes persist across power cycles. The driver exposes `set_pressure_offset()` in Full but the value should be set once during board bring-up, not on every init.
- In one-shot mode (ODR=0000), set ONESHOT=1 to trigger a single conversion. The bit self-clears when data is ready and STATUS.P_DA becomes 1. Maximum conversion time depends on AVG: ~10 ms at AVG=4.
- FIFO stores only pressure (not temperature); burst-reading from `0x78` auto-wraps back to `0x78` after `0x7A` in continuous reads.
- Switching between FIFO modes requires passing through bypass mode first (write `0x00` to FIFO_CTRL).
- AUTOZERO and AUTOREFP differ: AUTOZERO subtracts REF_P from PRESS_OUT (output is differential); AUTOREFP keeps PRESS_OUT as absolute but uses REF_P for interrupt threshold comparison only.
- The SIM bit in IF_CTRL (`0x0E`) selects 3-wire vs 4-wire SPI; default is 4-wire. In 3-wire mode, SDI/SDO share a single pin (SDI/SDO on pin 4).

## Sigrok Decoder

Decoder id `lps22df`, input `['i2c']` (primary) and `['spi']` (secondary). For I²C, matches addresses `0x5C` and `0x5D`. Annotates: WHO_AM_I read verified against `0xB4`; CTRL_REG1 writes decoded to ODR rate (Hz) and AVG count; CTRL_REG2 writes showing BDU, EN_LPFP, LFPF_CFG, SWRESET, ONESHOT; STATUS reads showing P_DA/T_DA/overrun flags; burst pressure reads (`0x28`–`0x2A`) showing raw LSB value and computed pressure in Pa/hPa; burst temperature reads (`0x2B`–`0x2C`) showing computed °C; FIFO_STATUS1 reads showing sample count; FIFO burst reads showing per-sample Pa. For SPI, matches on CS assertion, decodes the same register set from the R/W̄ + address byte framing.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/pressure/lps22df.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/pressure/lps22df/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/pressure/lps22df/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/pressure/lps22df/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/pressure/lps22df_test.py` (MicroPython)
- [ ] Tests `python/tests/pressure/lps22df_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/pressure/lps22df_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/pressure/LPS22DF.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/pressure/LPS22DF.cpp`
- [ ] Examples `cpp/examples/LPS22DF_Minimal/LPS22DF_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/LPS22DF_Complete/LPS22DF_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/LPS22DF_Demo/LPS22DF_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/LPS22DF_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/LPS22DF_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/LPS22DF_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/pressure/lps22df_test/lps22df_test.ino` (Arduino)
- [ ] Tests `cpp/tests/pressure/lps22df_test_linux/lps22df_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/pressure/lps22df_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/pressure/lps22df.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/pressure/lps22df/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/pressure/lps22df/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/pressure/lps22df/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/pressure/lps22df_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-pressure/nodes/lps22df/lps22df.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-pressure/nodes/lps22df/lps22df.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-pressure/examples/lps22df/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/pressure/lps22df.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/lps22df_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/lps22df_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/lps22df_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/pressure/lps22df_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/pressure/lps22df_test_esp32s3/src/main.rs` (ESP32-S3)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/pressure/Lps22dfMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/pressure/Lps22dfFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/pressure/Lps22dfMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/pressure/Lps22dfFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/pressure/Lps22dfMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/pressure/Lps22dfFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/pressure/lps22df/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/pressure/lps22df/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/pressure/lps22df/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/pressure/lps22df/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/pressure/lps22df/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/pressure/lps22df/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/pressure/lps22df/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/pressure/lps22df/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/pressure/lps22df/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/pressure/lps22df/Lps22dfTest.java` (Pi hardware, JBang)

### Sigrok
- [ ] Decoder `sigrok/lps22df/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/lps22df/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` only

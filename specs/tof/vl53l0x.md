# Chip Spec: VL53L0X

**Manufacturer:** STMicroelectronics
**Datasheet:** `datasheets/tof/vl53l0x.pdf` (DS11555 Rev 6, June 2024)
**Category:** tof
**Transports:** I²C

## Overview

The VL53L0X is a Time-of-Flight laser-ranging module: a 940 nm Class 1 VCSEL emitter, a
SPAD (single photon avalanche diode) receiving array, and an embedded microcontroller
running ST's ranging firmware, all in a 4.4 × 2.4 × 1.0 mm optical LGA12 package. It
measures absolute distance up to ~2 m (long-range profile, white target, indoor) largely
independent of target reflectance, with a 25° field of view. Ranging runs in single-shot,
back-to-back continuous, or timed continuous mode; the per-measurement *timing budget*
(default ~33 ms, minimum 20 ms) trades speed for accuracy. The chip exposes a GPIO1
open-drain interrupt (new sample ready or distance-threshold events), an active-low XSHUT
hardware-standby pin, and a volatile programmable I²C address so several sensors can share
one bus.

**Source of the register-level information.** The datasheet deliberately contains no
register map: ST documents the chip only through its C API (STSW-IMG005, described in user
manual UM2039, BSD-3-Clause licensed). Every register address, tuning table, and sequence in
this spec is taken from that API's `vl53l0x_device.h` / `vl53l0x_tuning.h` /
`vl53l0x_api*.c` sources — the same derivation used by Pololu's widely deployed
`VL53L0X` Arduino library (MIT). Register names below follow the API's `VL53L0X_REG_*`
names with the prefix dropped. Where the datasheet *does* give a hard fact (address,
reference register values in Table 5, big-endian multi-byte order in Table 6, tBOOT,
timing-budget default, temperature-recalibration rule), this spec cites it. The driver
**does not** port the full ST API (≈10 k lines, host-side sigma/RIT checks, offset/crosstalk
calibration *procedures*); it implements the register-level subset needed for accurate
ranging, as Pololu's library does.

## Transport Configuration

### I²C
- **Address:** `0x29` (7-bit; the datasheet quotes the 8-bit write form `0x52`/read `0x53`).
  Programmable at runtime via `I2C_SLAVE_DEVICE_ADDRESS` (`0x8A`); the new address is
  **volatile** — it reverts to `0x29` on power-up or an XSHUT low pulse.
- **Max clock:** 400 kHz (Fast mode); Standard mode (100 kHz) also supported.
- **Register addressing:** 8-bit index, auto-increment on multi-byte access.
- **Byte order:** multi-byte registers (16/32-bit) are **big-endian**, MSB at the lowest
  address (datasheet Table 6).

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| GPIO1 | low, open-drain | Interrupt output — requires external pull-up (10 kΩ recommended). Polarity is set active-low by `init` (`GPIO_HV_MUX_ACTIVE_HIGH` bit 4 = 0). Leave unconnected if unused. Wired as the `Connection`'s `int_pin` |
| XSHUT | low (shutdown) | Hardware standby when low; must always be driven or pulled up (10 kΩ) to avoid leakage. Wired as the `Connection`'s `en_pin` (high = enabled). Required for multi-sensor address assignment |

`XSHUT` must only be high while `AVDD` is applied (datasheet Table 8 note 1). After XSHUT
rises, the firmware boots (tBOOT ≤ 1.2 ms) before the first I²C access is allowed.

## Register Map

Only registers the driver touches are listed. Registers marked *(page 1)* are accessed
after writing `0x01` to `0xFF` (page select) and must be followed by writing `0x00` back to
`0xFF`; all others are on page 0. Addresses not listed are undocumented firmware
internals, written only as part of the opaque tuning table in the Initialization Sequence.

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00` | SYSRANGE_START | R/W | `0x00` | Ranging mode/start — see bit fields |
| `0x01` | SYSTEM_SEQUENCE_CONFIG | R/W | `0xFF` | Enables for each ranging sequence step |
| `0x04` | SYSTEM_INTERMEASUREMENT_PERIOD | R/W | — | 32-bit, timed-mode period in oscillator ticks (ms × `OSC_CALIBRATE_VAL`) |
| `0x09` | SYSTEM_RANGE_CONFIG | R/W | — | Written `0x00` by the tuning table |
| `0x0A` | SYSTEM_INTERRUPT_CONFIG_GPIO | R/W | — | GPIO1 interrupt function, bits 2:0 |
| `0x0B` | SYSTEM_INTERRUPT_CLEAR | W | — | Write `0x01` to clear the pending interrupt |
| `0x0C` | SYSTEM_THRESH_HIGH | R/W | — | 16-bit, high threshold in units of 2 mm (bits 11:0) |
| `0x0E` | SYSTEM_THRESH_LOW | R/W | — | 16-bit, low threshold in units of 2 mm (bits 11:0) |
| `0x13` | RESULT_INTERRUPT_STATUS | R | — | Bits 2:0 interrupt source pending; bits 4:3 range error |
| `0x14` | RESULT_RANGE_STATUS | R | — | Start of the 12-byte result block — see Data Conversion |
| `0x20` | CROSSTALK_COMPENSATION_PEAK_RATE_MCPS | R/W | `0x0000` | 16-bit, 3.13 fixed-point MCPS; `0` = compensation off |
| `0x28` | ALGO_PART_TO_PART_RANGE_OFFSET_MM | R/W | NVM | 16-bit, bits 11:0 two's-complement offset in units of 0.25 mm |
| `0x30` | ALGO_PHASECAL_CONFIG_TIMEOUT | R/W | — | Final-range VCSEL period dependent (page 0) |
| `0x30` | ALGO_PHASECAL_LIM *(page 1)* | R/W | — | Final-range VCSEL period dependent |
| `0x32` | GLOBAL_CONFIG_VCSEL_WIDTH | R/W | — | Final-range VCSEL period dependent |
| `0x44` | FINAL_RANGE_CONFIG_MIN_COUNT_RATE_RTN_LIMIT | R/W | — | 16-bit, 9.7 fixed-point MCPS — return signal-rate limit |
| `0x46` | MSRC_CONFIG_TIMEOUT_MACROP | R/W | — | MSRC/DSS/TCC step timeout, macro periods − 1 |
| `0x47` | FINAL_RANGE_CONFIG_VALID_PHASE_LOW | R/W | — | VCSEL-period dependent |
| `0x48` | FINAL_RANGE_CONFIG_VALID_PHASE_HIGH | R/W | — | VCSEL-period dependent |
| `0x4E` | DYNAMIC_SPAD_NUM_REQUESTED_REF_SPAD *(page 1)* | R/W | — | Written `0x2C` |
| `0x4F` | DYNAMIC_SPAD_REF_EN_START_OFFSET *(page 1)* | R/W | — | Written `0x00` |
| `0x50` | PRE_RANGE_CONFIG_VCSEL_PERIOD | R/W | `0x06` | Encoded pre-range VCSEL period (PCLKs/2 − 1) |
| `0x51` | PRE_RANGE_CONFIG_TIMEOUT_MACROP_HI | R/W | `0x0099` | 16-bit encoded pre-range timeout (datasheet Table 5 reference value) |
| `0x56` | PRE_RANGE_CONFIG_VALID_PHASE_LOW | R/W | — | Written `0x08` |
| `0x57` | PRE_RANGE_CONFIG_VALID_PHASE_HIGH | R/W | — | VCSEL-period dependent |
| `0x60` | MSRC_CONFIG_CONTROL | R/W | — | Bit 1 disables MSRC signal-rate check, bit 4 disables pre-range signal-rate check |
| `0x61` | PRE_RANGE_CONFIG_SIGMA_THRESH_HI | R/W | `0x0000` | 16-bit (datasheet Table 5 reference value) |
| `0x70` | FINAL_RANGE_CONFIG_VCSEL_PERIOD | R/W | `0x04` | Encoded final-range VCSEL period (PCLKs/2 − 1) |
| `0x71` | FINAL_RANGE_CONFIG_TIMEOUT_MACROP_HI | R/W | — | 16-bit encoded final-range timeout |
| `0x80` | POWER_MANAGEMENT_GO1_POWER_FORCE | R/W | — | Part of the private-register access preamble (see Implementation Notes) |
| `0x83` | *(page 6/7 internal)* | R/W | — | SPAD-info NVM read handshake |
| `0x84` | GPIO_HV_MUX_ACTIVE_HIGH | R/W | — | Bit 4: GPIO1 polarity, `0` = active low |
| `0x88` | *(I²C mode)* | W | — | Written `0x00` ("set I²C standard mode") |
| `0x89` | VHV_CONFIG_PAD_SCL_SDA__EXTSUP_HV | R/W | — | Bit 0: I/O voltage, `0` = 1V8, `1` = 2V8 |
| `0x8A` | I2C_SLAVE_DEVICE_ADDRESS | R/W | `0x29` | 7-bit address, bits 6:0 (volatile) |
| `0x91` | *(stop variable, page 1 internal)* | R/W | — | Read once at init; written back before every ranging start |
| `0x92` | *(SPAD info, page 7 internal)* | R | — | Bit 7 = aperture SPAD type, bits 6:0 = reference SPAD count |
| `0xB0`–`0xB5` | GLOBAL_CONFIG_SPAD_ENABLES_REF_0..5 | R/W | NVM | 48-bit reference SPAD enable map |
| `0xB6` | GLOBAL_CONFIG_REF_EN_START_SELECT | R/W | — | Written `0xB4` |
| `0xC0` | IDENTIFICATION_MODEL_ID | R | `0xEE` | Model ID (datasheet Table 5) |
| `0xC1` | *(reference)* | R | `0xAA` | Fixed reference value (datasheet Table 5) |
| `0xC2` | IDENTIFICATION_REVISION_ID | R | `0x10` | Revision ID (datasheet Table 5) |
| `0xF8` | OSC_CALIBRATE_VAL | R | — | 16-bit, internal-oscillator ticks per ms |
| `0xFF` | *(page select)* | W | `0x00` | `0x00` = page 0, `0x01` = page 1, `0x06`/`0x07` = NVM pages |

### Bit Fields

#### `SYSRANGE_START` (`0x00`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | — | Reserved |
| 6 | VHV_INIT | Set together with START only for the VHV reference calibration (`0x41`) |
| 5:4 | — | Reserved |
| 3 | HISTOGRAM | Not used |
| 2 | MODE_TIMED | `0x04` = start continuous timed ranging |
| 1 | MODE_BACKTOBACK | `0x02` = start continuous back-to-back ranging |
| 0 | START_STOP | `0x01` = start single-shot (self-clears when the measurement starts); in continuous modes, writing `0x01` stops ranging |

#### `SYSTEM_SEQUENCE_CONFIG` (`0x01`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | FINAL_RANGE | Final-range step enable |
| 6 | PRE_RANGE | Pre-range step enable |
| 5 | — | Reserved (set in `0xE8`) |
| 4 | TCC | Target centre check enable |
| 3 | DSS | Dynamic SPAD selection enable |
| 2 | MSRC | Minimum signal rate check enable |
| 1 | — | Selects phase reference calibration when written alone (`0x02`) |
| 0 | — | Selects VHV reference calibration when written alone (`0x01`) |

The driver's operating value is `0xE8` (FINAL_RANGE + PRE_RANGE + DSS; MSRC and TCC off).

#### `SYSTEM_INTERRUPT_CONFIG_GPIO` (`0x0A`)

| Value (bits 2:0) | Constant | GPIO1 asserts when |
|------------------|----------|--------------------|
| `0` | — | Disabled |
| `1` | `SOURCE_LEVEL_LOW` | Range < low threshold |
| `2` | `SOURCE_LEVEL_HIGH` | Range > high threshold |
| `3` | `SOURCE_OUT_OF_WINDOW` | Range < low threshold **or** > high threshold |
| `4` | `SOURCE_NEW_SAMPLE_READY` | A new measurement is available (driver default) |

#### `RESULT_INTERRUPT_STATUS` (`0x13`)

| Bits | Name | Description |
|------|------|-------------|
| 4:3 | RANGE_ERROR | Non-zero = range error flagged by firmware |
| 2:0 | INT_STATUS | Non-zero = interrupt pending; value equals the triggering `SOURCE_*` constant. Polled for data-ready (`& 0x07 != 0`) |

#### `RESULT_RANGE_STATUS` (`0x14`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | — | Reserved |
| 6:3 | DEVICE_RANGE_STATUS | Firmware status for the last measurement — see Data Conversion |
| 2:1 | — | Reserved |
| 0 | — | Internal ready flag |

## Initialization Sequence

This is ST API `VL53L0X_DataInit` + `VL53L0X_StaticInit` + `VL53L0X_PerformRefCalibration`,
with the NVM reference-SPAD values assumed valid (no `PerformRefSpadManagement`). Write
`wr(reg, val)`, read `rd(reg)`; 16-bit accesses are big-endian.

1. If an `en_pin` is present: drive XSHUT high and wait ≥ 1.2 ms (tBOOT). Otherwise wait
   1.2 ms anyway (the host may have just powered the module).
2. Read `IDENTIFICATION_MODEL_ID` (`0xC0`); fail with a chip-not-found error unless it is
   `0xEE`.
3. **I/O voltage (2V8 mode):** `wr(0x89, rd(0x89) | 0x01)`. Breakout boards run the I/O at
   2.8–3.3 V; the chip's power-on 1V8 mode misreads logic highs above 1.9 V.
4. **Standard I²C mode:** `wr(0x88, 0x00)`.
5. **Read the stop variable:** `wr(0x80,0x01) wr(0xFF,0x01) wr(0x00,0x00)`;
   `stop_variable = rd(0x91)`; `wr(0x00,0x01) wr(0xFF,0x00) wr(0x80,0x00)`. Store it in the
   driver instance.
6. **Disable MSRC and pre-range signal-rate limit checks:** `wr(0x60, rd(0x60) | 0x12)`.
7. **Signal-rate limit 0.25 MCPS:** write16(`0x44`, `0.25 × 128` = `0x0020`).
8. `wr(0x01, 0xFF)`.
9. **Read reference-SPAD info from NVM:**
   `wr(0x80,0x01) wr(0xFF,0x01) wr(0x00,0x00) wr(0xFF,0x06) wr(0x83, rd(0x83)|0x04)
   wr(0xFF,0x07) wr(0x81,0x01) wr(0x80,0x01) wr(0x94,0x6B) wr(0x83,0x00)`;
   poll `rd(0x83)` until non-zero (timeout → error); `wr(0x83,0x01)`; `t = rd(0x92)`:
   `spad_count = t & 0x7F`, `spad_is_aperture = (t >> 7) & 1`;
   `wr(0x81,0x00) wr(0xFF,0x06) wr(0x83, rd(0x83) & ~0x04) wr(0xFF,0x01) wr(0x00,0x01)
   wr(0xFF,0x00) wr(0x80,0x00)`.
10. **Set reference SPADs:** read 6 bytes `ref_map` from `0xB0`;
    `wr(0xFF,0x01) wr(0x4F,0x00) wr(0x4E,0x2C) wr(0xFF,0x00) wr(0xB6,0xB4)`;
    `first = 12 if spad_is_aperture else 0`, `enabled = 0`; for `i` in `0..47`: if
    `i < first` or `enabled == spad_count` clear bit `i%8` of `ref_map[i/8]`, else if that
    bit is set, `enabled += 1`. Write the 6 bytes back to `0xB0`.
11. **Load default tuning settings** (ST `DefaultTuningSettings`, opaque — write verbatim,
    in this order, each pair is `reg, value`):
    ```
    FF 01  00 00  FF 00  09 00  10 00  11 00  24 01  25 FF  75 00
    FF 01  4E 2C  48 00  30 20  FF 00  30 09  54 00  31 04  32 03
    40 83  46 25  60 00  27 00  50 06  51 00  52 96  56 08  57 30
    61 00  62 00  64 00  65 00  66 A0  FF 01  22 32  47 14  49 FF
    4A 00  FF 00  7A 0A  7B 00  78 21  FF 01  23 34  42 00  44 FF
    45 26  46 05  40 40  0E 06  20 1A  43 40  FF 00  34 03  35 44
    FF 01  31 04  4B 09  4C 05  4D 04  FF 00  44 00  45 20  47 08
    48 28  67 00  70 04  71 01  72 FE  76 00  77 00  FF 01  0D 01
    FF 00  80 01  01 F8  FF 01  8E 01  00 01  FF 00  80 00
    ```
12. **GPIO1 = new sample ready, active low:** `wr(0x0A, 0x04)`;
    `wr(0x84, rd(0x84) & ~0x10)`; `wr(0x0B, 0x01)`.
13. `budget = timing_budget()` (compute from current registers — see Data Conversion).
14. **Disable MSRC and TCC steps:** `wr(0x01, 0xE8)`.
15. `set_timing_budget(budget)` — recomputes the final-range timeout for the new step set
    (≈ 33 000 µs result).
16. **Reference calibration (VHV):** `wr(0x01, 0x01)`, then *single reference calibration*
    with `vhv_init = 0x40`: `wr(0x00, 0x01 | vhv_init)`; poll `rd(0x13) & 0x07` until
    non-zero (timeout → error); `wr(0x0B, 0x01)`; `wr(0x00, 0x00)`.
17. **Reference calibration (phase):** `wr(0x01, 0x02)`, single reference calibration with
    `vhv_init = 0x00`.
18. `wr(0x01, 0xE8)` (restore sequence config).

Minimal leaves the chip idle (software standby) after `init`; ranging starts on demand.

## Interrupt

| Property | Value |
|----------|-------|
| INT pin | GPIO1 — active-low, open-drain — requires external pull-up |
| Level | 2 |
| Condition(s) | new sample ready (default); distance below / above / outside a threshold window |
| Clear mechanism | write `0x01` to `SYSTEM_INTERRUPT_CLEAR` (`0x0B`) |

The sources are **mutually exclusive**: `SYSTEM_INTERRUPT_CONFIG_GPIO` holds one mode at a
time. `enable_interrupt(source)` therefore *replaces* the active source, and
`disable_interrupt(source)` sets the register to `0` only if `source` is the active one.
Threshold sources are evaluated by firmware per measurement, so they only fire while
ranging is running (continuous or timed mode is the intended use).

**Important:** the driver's own data-ready polling uses the same `RESULT_INTERRUPT_STATUS`
bits. With a threshold source selected, `data_ready()`/`read_continuous()` only see a
pending status when the threshold condition is met — switch back to
`SOURCE_NEW_SAMPLE_READY` before using the blocking read methods.

### Interrupt sources

| Constant | Value | Condition |
|----------|-------|-----------|
| `SOURCE_LEVEL_LOW` | `0x01` | Range < low threshold |
| `SOURCE_LEVEL_HIGH` | `0x02` | Range > high threshold |
| `SOURCE_OUT_OF_WINDOW` | `0x03` | Range < low **or** > high threshold |
| `SOURCE_NEW_SAMPLE_READY` | `0x04` | New measurement available |

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback)` | Subscribe; callback receives the status int (`RESULT_INTERRUPT_STATUS & 0x07`, i.e. the `SOURCE_*` value that fired). The driver reads and clears before invoking |
| `off_interrupt` | `off_interrupt()` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> int` | Read `RESULT_INTERRUPT_STATUS & 0x07`; if non-zero write `0x01` to `0x0B`; return the value (`0` = nothing pending) |
| `enable_interrupt` | `enable_interrupt(source)` | Write `source` to `0x0A` (replaces the active source) |
| `disable_interrupt` | `disable_interrupt(source)` | Write `0` to `0x0A` if `source` is currently active |

### Status register bit layout

`RESULT_INTERRUPT_STATUS` bits 2:0 hold a *value*, not a bitmask: it equals the
`SOURCE_*` constant of the active source when that condition fired. Bits 4:3 (range error)
are not part of the returned status.

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits
everything and adds the rest.

### Minimal

Goal: single-shot distance in millimetres with no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Runs the full Initialization Sequence (boot wait, ID check, 2V8 I/O, tuning, reference calibration). Leaves the chip idle |
| `distance` | — | int | Unit: mm. Triggers one single-shot measurement and blocks until it completes (≈ timing budget, 33 ms by default). Returns the raw range value even when the status is not "range complete" — typically `8190`/`8191` with no target in range; check `range_valid()` |
| `range_valid` | — | bool | `True` iff the device range status of the most recent measurement was `11` (range complete) |

**Single-shot sequence (`distance`):**
1. Stop-variable preamble: `wr(0x80,0x01) wr(0xFF,0x01) wr(0x00,0x00) wr(0x91,stop_variable)
   wr(0x00,0x01) wr(0xFF,0x00) wr(0x80,0x00)`.
2. `wr(0x00, 0x01)`; poll `rd(0x00) & 0x01` until clear (measurement has started; timeout →
   error).
3. Poll `rd(0x13) & 0x07` until non-zero (timeout → error).
4. Read the 12-byte result block from `0x14` in one burst; store the device range status and
   extract the range (see Data Conversion).
5. `wr(0x0B, 0x01)`.

**Sensible defaults baked into Minimal:**
- 2V8 I/O mode
- Final-range signal-rate limit 0.25 MCPS; MSRC and pre-range signal-rate checks disabled
- Sequence steps: DSS + pre-range + final-range (`0xE8`); MSRC and TCC off
- VCSEL periods: pre-range 14 PCLKs, final-range 10 PCLKs (tuning-table values)
- Timing budget ≈ 33 ms ("default mode" profile, datasheet Table 14)
- GPIO1 = new sample ready, active low
- Single-shot ranging, no offset/crosstalk override (NVM offset kept)
- I/O timeout 500 ms for every poll loop; expiry raises/returns the language's timeout error

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `start_continuous` | `period_ms: int = 0` | — | `0` → back-to-back mode (`wr(0x00,0x02)`); `>0` → timed mode: `osc = rd16(0xF8)`, write32(`0x04`, `period_ms × osc` if `osc ≠ 0` else `period_ms`), `wr(0x00,0x04)`. Both preceded by the stop-variable preamble. `period_ms` should be ≥ the timing budget |
| `stop_continuous` | — | — | `wr(0x00,0x01) wr(0xFF,0x01) wr(0x00,0x00) wr(0x91,0x00) wr(0x00,0x01) wr(0xFF,0x00)` |
| `read_continuous` | — | int | mm. Blocks until data ready (steps 3–5 of the single-shot sequence) |
| `data_ready` | — | bool | `rd(0x13) & 0x07 != 0` — non-blocking |
| `read_measurement` | — | record | Non-blocking read of the 12-byte result block + interrupt clear: `{ distance_mm: int, range_status: int, signal_rate_mcps: float, ambient_rate_mcps: float, effective_spad_count: float }` |
| `range_status` | — | int | Device range status (0–15) of the most recent measurement — see Data Conversion |
| `set_timing_budget` | `budget_us: int` | — | 20 000 µs ≤ budget; see Data Conversion. Error if below minimum or smaller than the enabled steps' fixed overhead |
| `timing_budget` | — | int | µs, computed from current registers |
| `set_signal_rate_limit` | `limit_mcps: float` | — | 0 ≤ limit ≤ 511.99; write16(`0x44`, `round(limit × 128)`). Lower = longer range, more noise |
| `signal_rate_limit` | — | float | MCPS, `rd16(0x44) / 128` |
| `set_vcsel_pulse_period` | `period_type: "pre_range"\|"final_range"`, `pclks: int` | — | Pre-range ∈ {12, 14, 16, 18}; final-range ∈ {8, 10, 12, 14}. Error otherwise. See Implementation Notes for the full register sequence; re-applies the timing budget and redoes phase calibration |
| `vcsel_pulse_period` | `period_type` | int | PCLKs, `(rd(reg) + 1) × 2` |
| `set_profile` | `profile: "default"\|"long_range"\|"high_speed"\|"high_accuracy"` | — | Convenience wrapper — see table below |
| `set_offset` | `offset_mm: float` | — | −512.0 ≤ offset ≤ 511.75 mm; write16(`0x28`, `round(offset_mm × 4) & 0x0FFF`). Overrides the NVM factory offset until power-off |
| `offset` | — | float | mm; bits 11:0 of `rd16(0x28)` as 12-bit two's complement, × 0.25 |
| `set_crosstalk_compensation` | `rate_mcps: float` | — | 0 disables; otherwise write16(`0x20`, `round(rate_mcps × 8192)`), 0 < rate < 8.0. Use for a cover glass, value from the host's own calibration |
| `recalibrate` | — | — | Re-run VHV + phase reference calibration (Initialization steps 16–18), preserving `SYSTEM_SEQUENCE_CONFIG`. Must be called in software standby (not while continuous ranging). Datasheet §3.3.1: required after a temperature change > 8 °C |
| `set_address` | `address: int` | — | 0x08–0x77; `wr(0x8A, address & 0x7F)`. The chip answers on the new address immediately; this driver instance becomes unusable — construct a new `Connection` at the new address and a new driver (re-`init` is safe) |
| `set_interrupt_thresholds` | `low_mm: int`, `high_mm: int` | — | 0 ≤ low ≤ high ≤ 8190; write16(`0x0E`, `(low_mm // 2) & 0x0FFF`), write16(`0x0C`, `(high_mm // 2) & 0x0FFF`) |
| `interrupt_thresholds` | — | (int, int) | (low_mm, high_mm) = `(rd16(0x0E) & 0xFFF) × 2`, `(rd16(0x0C) & 0xFFF) × 2` |
| `model_id` | — | int | `rd(0xC0)` — `0xEE` |
| `revision_id` | — | int | `rd(0xC2)` — `0x10` on current silicon |
| *interrupt API* | | | `on_interrupt`, `off_interrupt`, `poll_interrupt`, `enable_interrupt`, `disable_interrupt` — see Interrupt |

**Ranging profiles (`set_profile`)** — mirrors the ST API example profiles (datasheet
Table 14); the host-side sigma check of the ST API is not implemented.

| Profile | Signal-rate limit | VCSEL pre / final | Timing budget |
|---------|-------------------|-------------------|---------------|
| `default` | 0.25 MCPS | 14 / 10 PCLKs | 33 000 µs |
| `long_range` | 0.10 MCPS | 18 / 14 PCLKs | 33 000 µs |
| `high_speed` | 0.25 MCPS | 14 / 10 PCLKs | 20 000 µs |
| `high_accuracy` | 0.25 MCPS | 14 / 10 PCLKs | 200 000 µs |

`set_profile` applies signal-rate limit, then both VCSEL periods (pre first), then the
timing budget. Long range is intended for dark conditions (no IR); in sunlight it
increases invalid readings.

**Additional configuration options:** everything above; I/O voltage mode is fixed at 2V8
and GPIO1 polarity at active-low (neither exposed — changing them breaks typical wiring).

**Language naming:** snake_case in Python/Rust/Go-internal; camelCase in C++/JS/JVM
(`startContinuous`, `readMeasurement`, `setTimingBudget` …); Go exported PascalCase.
`read_measurement` returns a struct/record/object/dict per language idiom.
`period_type` is an enum where the language has one (`VcselPeriodType::PreRange`/
`FinalRange`), a string otherwise; `profile` likewise.

## Data Conversion

### Result block (12 bytes from `0x14`)

| Byte offset | Field | Conversion |
|-------------|-------|------------|
| 0 | range status | `range_status = (b[0] & 0x78) >> 3` |
| 2–3 | effective SPAD return count | `((b[2] << 8) \| b[3]) / 256` (8.8 fixed point) |
| 6–7 | signal rate | `((b[6] << 8) \| b[7]) / 128` MCPS (9.7 fixed point) |
| 8–9 | ambient rate | `((b[8] << 8) \| b[9]) / 128` MCPS (9.7 fixed point) |
| 10–11 | range | `distance_mm = (b[10] << 8) \| b[11]` |

MCPS = mega counts per second (10⁶ photon counts/s).

### Device range status (`range_status`)

| Value | Meaning (ST `VL53L0X_DEVICEERROR_*`) |
|-------|---------------------------------------|
| 0 | None / no update |
| 1 | VCSEL continuity test failure |
| 2 | VCSEL watchdog test failure |
| 3 | No VHV value found |
| 4 | MSRC no target (typical "nothing in range") |
| 5 | SNR check |
| 6 | Range phase check |
| 7 | Sigma threshold check |
| 8 | TCC |
| 9 | Phase consistency |
| 10 | Min clip |
| 11 | **Range complete — valid** |
| 12 | Algo underflow |
| 13 | Algo overflow |
| 14 | Range ignore threshold |

`range_valid()` ≡ `range_status == 11`.

### Timing budget

Helper functions (integer arithmetic, all times in µs unless noted):

```
decode_vcsel(reg)            = (reg + 1) << 1                           # PCLKs
encode_vcsel(pclks)          = (pclks >> 1) - 1
macro_period_ns(pclks)       = (2304 * pclks * 1655 + 500) // 1000
mclks_to_us(mclks, pclks)    = (mclks * macro_period_ns(pclks) + 500) // 1000
us_to_mclks(us, pclks)       = (us * 1000 + macro_period_ns(pclks) // 2) // macro_period_ns(pclks)
decode_timeout(reg16)        = ((reg16 & 0xFF) << (reg16 >> 8)) + 1       # MCLKs
encode_timeout(mclks)        = 0 if mclks == 0 else:
                                 ls = mclks - 1; ms = 0
                                 while ls > 0xFF: ls >>= 1; ms += 1
                                 (ms << 8) | (ls & 0xFF)
```

Step enables from `SYSTEM_SEQUENCE_CONFIG` (`0x01`): tcc = bit 4, dss = bit 3, msrc = bit 2,
pre_range = bit 6, final_range = bit 7.

Step timeouts:
```
pre_pclks      = decode_vcsel(rd(0x50))
msrc_us        = mclks_to_us(rd(0x46) + 1, pre_pclks)
pre_mclks      = decode_timeout(rd16(0x51))
pre_us         = mclks_to_us(pre_mclks, pre_pclks)
final_pclks    = decode_vcsel(rd(0x70))
final_mclks    = decode_timeout(rd16(0x71)) - (pre_mclks if pre_range else 0)
final_us       = mclks_to_us(final_mclks, final_pclks)
```

Overheads: Start 1910, End 960, MSRC 660, TCC 590, DSS 690, PreRange 660, FinalRange 550.

`timing_budget()`:
```
b = 1910 + 960
if tcc:        b += msrc_us + 590
if dss:        b += 2 * (msrc_us + 690)
elif msrc:     b += msrc_us + 660
if pre_range:  b += pre_us + 660
if final_range:b += final_us + 550
```

`set_timing_budget(budget_us)`: reject `budget_us < 20000`. Compute `used` exactly as above
but *without* the `final_us` term (keep its 550 overhead). Reject if `used > budget_us`.
Then `final_mclks = us_to_mclks(budget_us - used, final_pclks)` (+ `pre_mclks` if
pre_range) and write16(`0x71`, `encode_timeout(final_mclks)`). Store `budget_us`.

(The ST API's setter uses a 1320 µs start overhead while its getter uses 1910 µs; this spec
uses 1910 in both, as Pololu's library does, so `timing_budget()` round-trips the value
passed to `set_timing_budget()`.)

## Node-RED

Node name: `periph-vl53l0x`
Package: `node-red-contrib-periph-tof`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ distance_mm, valid, range_status, signal_rate_mcps, ambient_rate_mcps }` | Single-shot measurement (or latest continuous result when continuous mode is configured) |
| threshold interrupt (when configured) | `{ source: "level_low" \| "level_high" \| "out_of_window", distance_mm }` | Emitted from the interrupt callback when an `int_pin` is wired |

Config panel fields:
- **I²C bus** — bus number (e.g. `1` for `/dev/i2c-1`)
- **Address** — default `0x29`
- **Profile** — dropdown default / long range / high speed / high accuracy
- **Mode** — single-shot (on input) / continuous (with period in ms; node starts continuous
  mode on deploy and stops it on close)
- **Threshold interrupt** — optional mode dropdown + low/high mm fields; requires a GPIO
  line for GPIO1

### Demo flow

An Inject node fires every 200 ms into a `periph-vl53l0x` node in single-shot mode with the
`high_speed` profile. A Switch node routes on `msg.payload.valid`: valid readings go to a
Function node that classifies the distance into `near` (< 150 mm), `mid` (150–600 mm), and
`far` (> 600 mm) and forwards only *changes* of zone (RBE-style) to a Debug node; invalid
readings go to a second Debug node labelled "no target". Demonstrates presence/proximity
zoning — e.g. a hand approaching a touchless dispenser.

## Examples

### Demo

**Touchless presence gate with multi-rate ranging.** The demo configures `long_range` in the
dark-room case only if the first `read_measurement()` reports an ambient rate below
0.5 MCPS, otherwise `default` — printing which profile it chose and why. It then starts
timed continuous ranging at 100 ms (`start_continuous(100)`), selects
`SOURCE_OUT_OF_WINDOW` with `set_interrupt_thresholds(100, 800)` and subscribes with
`on_interrupt`: whenever something enters closer than 10 cm or the scene clears beyond
80 cm, the callback prints an "ENTER"/"LEAVE" event with the distance (polling fallback
via `poll_interrupt` when no `int_pin` is wired). Between events the main loop idles. After
20 events or 60 s, it switches back to `SOURCE_NEW_SAMPLE_READY`, collects 10
`read_continuous()` samples and prints mean, min, max and the mean signal rate, calls
`stop_continuous()`, then `recalibrate()` to show the temperature-drift procedure, and
exits after `off_interrupt()`.

## Timing Constraints

- **Boot (tBOOT):** ≤ 1.2 ms from XSHUT rising (or AVDD applied with XSHUT pulled up) to
  software standby; no I²C access before that (datasheet §3.9). The driver waits 1.2 ms in
  `init`. Not conformance-checked (the XSHUT edge is not on the I²C capture).
- **Single-shot ranging duration (`single_ranging`):** from the `SYSRANGE_START` = `0x01`
  write (issued while `SYSTEM_SEQUENCE_CONFIG` = `0xE8`, i.e. not a reference calibration)
  to the burst read of the result block at `0x14` that consumes it: bounded by the timing
  budget. At the default 33 ms budget the datasheet states 33 ms per range sequence (§3.6.2,
  Figure 12) of which 23 ms is the actual measurement. Conformance-checked at the default
  budget: min 20 ms, max 100 ms (host polling jitter allowance). Minimum possible range
  measurement period is 8 ms and the minimum timing budget is 20 ms.
- **Timed-mode period:** `period_ms` should be ≥ timing budget; otherwise the chip runs
  effectively back-to-back.
- **Stop during timed mode:** if the stop request lands in the inter-measurement period,
  ranging stops immediately; during a measurement, that measurement completes first
  (datasheet §3.4). `stop_continuous` does not wait.
- **Reference calibration:** each of the two calibration ranges in `init`/`recalibrate`
  completes within a few ms; covered by the 500 ms poll timeout.
- **Temperature recalibration:** `recalibrate()` must be called after the die temperature
  changes > 8 °C from the last calibration (datasheet §3.3.1). The driver does not track
  temperature — application responsibility.
- **I²C:** 400 kHz max; tBUF ≥ 1.3 µs between transactions (datasheet Table 4).

## Implementation Notes

- **Private-register access.** The sequences using `0x80`/`0xFF`/`0x00` (stop-variable
  preamble, SPAD info, tuning table) switch the device into an internal register bank.
  Note that `0x00` on page 1 is *not* `SYSRANGE_START`. Always return to page 0
  (`wr(0xFF,0x00)`) and `wr(0x80,0x00)` exactly as the sequences show — a driver that
  aborts mid-sequence on an I/O error leaves the chip in an unusable state until XSHUT/power
  cycle.
- **Stop variable.** `stop_variable` read at init (step 5) must be written back to `0x91`
  before *every* ranging start (single-shot and continuous). Omitting it causes stale/zero
  readings on some parts.
- **`set_vcsel_pulse_period` register sequence** (ST `VL53L0X_set_vcsel_pulse_period`):
  first capture the current step enables and step timeouts (µs). Then
  - **pre_range**, `pclks` ∈ {12,14,16,18}: `wr(0x57, {12:0x18, 14:0x30, 16:0x40, 18:0x50})`,
    `wr(0x56, 0x08)`, `wr(0x50, encode_vcsel(pclks))`,
    write16(`0x51`, `encode_timeout(us_to_mclks(pre_us, pclks))`),
    `m = us_to_mclks(msrc_us, pclks)`; `wr(0x46, 255 if m > 256 else m - 1)`.
  - **final_range**, `pclks` ∈ {8,10,12,14}: write, in order,
    `0x48` (VALID_PHASE_HIGH), `0x47` (VALID_PHASE_LOW), `0x32` (VCSEL_WIDTH),
    `0x30` (PHASECAL_CONFIG_TIMEOUT), then `wr(0xFF,0x01) wr(0x30, LIM) wr(0xFF,0x00)`:

    | pclks | `0x48` | `0x47` | `0x32` | `0x30` | LIM (page 1 `0x30`) |
    |-------|--------|--------|--------|--------|---------------------|
    | 8  | `0x10` | `0x08` | `0x02` | `0x0C` | `0x30` |
    | 10 | `0x28` | `0x08` | `0x03` | `0x09` | `0x20` |
    | 12 | `0x38` | `0x08` | `0x03` | `0x08` | `0x20` |
    | 14 | `0x48` | `0x08` | `0x03` | `0x07` | `0x20` |

    then `wr(0x70, encode_vcsel(pclks))`,
    `f = us_to_mclks(final_us, pclks)` (+ `pre_mclks` if pre_range enabled),
    write16(`0x71`, `encode_timeout(f)`).
  - Afterwards (both types): `set_timing_budget(stored_budget)`, then phase calibration:
    `s = rd(0x01)`; `wr(0x01, 0x02)`; single reference calibration with `vhv_init=0x00`;
    `wr(0x01, s)`.
- **Timeouts.** Every poll loop (SPAD-info `0x83`, start-bit clear, data ready, reference
  calibration) uses the same 500 ms timeout; expiry is an error, never an infinite loop.
- **Out-of-range readings.** With no target, the firmware typically reports status 4 and a
  range of `8190` or `8191` mm. Minimal returns this raw value; callers must check
  `range_valid()`. The datasheet guarantees range only up to 1.2 m (white, indoor, default
  mode) / 2 m (long range profile) — Table 12.
- **Offset / crosstalk.** The ST API's offset and crosstalk *calibration procedures*
  (UM2039: target at 100 mm, averaging, etc.) are host-side algorithms and are out of scope.
  `set_offset`/`set_crosstalk_compensation` let the application apply values it measured
  itself (e.g. mean error at 100 mm → `set_offset(-error)`). Both are volatile.
- **Multiple sensors on one bus.** All sensors power up at `0x29`. Hold every sensor's
  XSHUT low (via each `Connection`'s `en_pin` disabled), then for each sensor in turn:
  enable its XSHUT, create a driver on `0x29`, `set_address(new)`, and build the real driver
  on a `Connection` at `new`. Document this in the driver's module doc; the complete
  example demonstrates `set_address` on a single sensor and restores `0x29` at the end.
- **Continuous-mode reads.** `read_continuous` may return the previous value if called
  faster than the timing budget; it always waits for a fresh data-ready.
- **SPAD management.** The ST API's `PerformRefSpadManagement` (re-select reference SPADs
  under a cover glass) is not implemented; the NVM factory SPAD map is used, which is
  correct for bare modules and thin cover glass.
- **Rust:** `no_std`; poll loops use an `embedded-hal` `DelayNs` supplied at construction
  for the 1.2 ms boot wait and a bounded poll count (500 ms worth of 1 ms delays) for
  timeouts. `on_interrupt`/`off_interrupt` are not exposed (poll_interrupt only), per the
  repo interrupt design.
- **Kotlin:** 16-bit reads of signed data (offset register) need 12-bit sign extension;
  unsigned values (range, rates) must be masked with `and 0xFFFF` — do not use `.toShort()`
  there.

## Sigrok Decoder

Decoder `vl53l0x` (`sigrok/vl53l0x/`) stacks on the `i2c` decoder and matches address
`0x29` by default (option `address` for re-addressed sensors). It tracks the register
pointer with auto-increment, the page-select register `0xFF`, and the `0x80` power-force
state, so page-1/NVM accesses are labelled as private-bank accesses rather than as page-0
registers (in particular, `0x00` written with `0xFF`=`0x01` is *not* `SYSRANGE_START`).

| Row | Annotation | Meaning |
|-----|------------|---------|
| Data | `data` | Named register reads/writes on page 0: `SYSRANGE_START` mode decode (single/back-to-back/timed/stop, VHV flag), `SYSTEM_SEQUENCE_CONFIG` step flags, interrupt config source name, thresholds in mm, timing-budget registers with VCSEL periods in PCLKs and decoded timeouts in MCLKs, signal-rate limit in MCPS, offset in mm, `I2C_SLAVE_DEVICE_ADDRESS` changes; result-block reads decoded to distance mm, range-status name, signal/ambient rate MCPS |
| Status | `status` | `RESULT_INTERRUPT_STATUS` reads (source value + range-error bits), `SYSTEM_INTERRUPT_CLEAR` writes, `IDENTIFICATION_MODEL_ID`/`REVISION_ID` reads |
| Private | `private` | Accesses inside the `0x80`/`0xFF` private-bank sequences (stop variable, SPAD info, tuning table), summarised as one annotation per sequence where possible |
| Timing | `timing` | `single_ranging_start` at the `SYSRANGE_START` = `0x01` write on page 0 while the last written `SYSTEM_SEQUENCE_CONFIG` was not `0x01`/`0x02` (excludes reference calibrations); `single_ranging_done` at the `STOP` of the next read transaction starting at register `0x14` |
| Warnings | `warning` | Model ID ≠ `0xEE`; `SYSRANGE_START` write without a preceding stop-variable (`0x91`) write since the last stop; transaction left on page ≠ 0 at a ranging start |

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/tof/vl53l0x.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/tof/vl53l0x/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/tof/vl53l0x/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/tof/vl53l0x/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/tof/vl53l0x_test.py` (MicroPython)
- [ ] Tests `python/tests/tof/vl53l0x_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/tof/vl53l0x_test_linux.py` (Linux)
- [ ] Unit test `python/tests/tof/vl53l0x_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/tof/vl53l0x/vl53l0x.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/tof/vl53l0x/vl53l0x_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/tof/vl53l0x/vl53l0x.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/tof/vl53l0x/VL53L0X.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/tof/vl53l0x/VL53L0X.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/tof/VL53L0X.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/tof/VL53L0X.cpp`
- [ ] Examples `cpp/examples/arduino/tof/VL53L0X/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/tof/VL53L0X/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/tof/VL53L0X/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/linux/tof/VL53L0X/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/tof/VL53L0X/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/tof/VL53L0X/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/tof/VL53L0X/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/tof/VL53L0X/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/tof/VL53L0X/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/tof/VL53L0X/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/tof/VL53L0X/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/tof/VL53L0X/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/tof/VL53L0X/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/tof/VL53L0X/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/tof/VL53L0X/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/tof/vl53l0x_test/vl53l0x_test.ino` (Arduino)
- [ ] Tests `cpp/tests/tof/vl53l0x_test_linux/vl53l0x_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/tof/vl53l0x_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/tof/vl53l0x_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/tof/vl53l0x_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Unit test `cpp/tests/tof/vl53l0x_test_unit/vl53l0x_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/tof/vl53l0x.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/tof/vl53l0x/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/tof/vl53l0x/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/tof/vl53l0x/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/tof/vl53l0x_test.js`
- [ ] Unit test `nodejs/tests/tof/vl53l0x_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-tof/nodes/vl53l0x/vl53l0x.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-tof/nodes/vl53l0x/vl53l0x.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-tof/examples/vl53l0x/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/tof/vl53l0x.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/tof/vl53l0x/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/tof/vl53l0x/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/tof/vl53l0x/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/tof/vl53l0x_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/tof/vl53l0x_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/tof/vl53l0x.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/tof/vl53l0x.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/tof/vl53l0x/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/tof/vl53l0x/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/tof/vl53l0x/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/tof/vl53l0x/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/tof/vl53l0x/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/tof/vl53l0x/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/tof/vl53l0x_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/tof/vl53l0x_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/tof/vl53l0x_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/tof/VL53L0XMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/tof/VL53L0XFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/tof/VL53L0XMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/tof/VL53L0XFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/tof/VL53L0XMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/tof/VL53L0XFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/tof/vl53l0x/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/tof/vl53l0x/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/tof/vl53l0x/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/tof/vl53l0x/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/tof/vl53l0x/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/tof/vl53l0x/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/tof/vl53l0x/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/tof/vl53l0x/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/tof/vl53l0x/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/tof/vl53l0x/VL53L0XTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/tof/VL53L0XTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/tof/VL53L0XTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/tof/VL53L0XSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/vl53l0x/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/vl53l0x/pd.py` — for the Timing Constraint above with a conformance check, emits the named `single_ranging_start`/`single_ranging_done` annotation pair

### Conformance
- [ ] Checker `conformance/tof/vl53l0x_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [x] Timing config `specs/tof/vl53l0x_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/vl53l0x/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` only

# Chip Spec: VL53L1X

**Manufacturer:** STMicroelectronics
**Datasheet:** `datasheets/tof/vl53l1x.pdf` (DS12385 Rev 8, August 2024)
**Category:** tof
**Transports:** I²C
**Base spec:** `specs/tof/_vl53_base.md` — shared register access, polling, boot, interrupt delivery, re-addressing, constants and the family API contract (shared with the VL53L0X)

## Overview

The VL53L1X is ST's long-distance Time-of-Flight laser-ranging module: a 940 nm Class 1
VCSEL emitter, a 16 × 16 SPAD receiving array behind an integrated lens, and an embedded
microcontroller running ST's ranging firmware, in a 4.9 × 2.5 × 1.56 mm optical LGA12
package. It measures absolute distance up to 4 m (long distance mode, dark, white target) at
up to 50 Hz, largely independent of target colour and reflectance. Two distance modes trade
range against ambient-light immunity (short ≈ 1.3 m but robust in sunlight, long ≈ 3.6–4 m in
the dark); a timing budget of 15–500 ms trades speed for range and repeatability. Unlike the
VL53L0X, the receiving **region of interest (ROI)** is programmable from 4 × 4 to 16 × 16
SPADs and can be moved across the array, narrowing the 27° field of view or sweeping it for
coarse multizone sensing. The chip is **pin-to-pin compatible with the VL53L0X** (datasheet
p. 1) — same GPIO1 open-drain interrupt, same active-low XSHUT, same default address `0x29`
— which is why both drivers share `VL53Base`.

**Source of the register-level information.** As with the VL53L0X, the datasheet exposes no
register map: "full register details are not exposed. The customer should refer to the
VL53L1X API user manual (UM2356)" (datasheet §4.2). Every register address, the default
configuration block and every sequence in this spec is taken from ST's **Ultra Lite Driver**
(ULD, STSW-IMG009, user manual UM2510, BSD-3-Clause) — `VL53L1X_api.c` / `VL53L1X_api.h` /
`VL53L1X_calibration.c`. The ULD is ST's own minimal register-level driver for the firmware's
autonomous ranging mode; it is also the basis of Adafruit's and SparkFun's VL53L1X libraries.
Register names follow the ULD's names. Where the datasheet gives a hard fact (address,
reference registers Table 8, big-endian multi-byte order Table 9, tBOOT, timing-budget range,
distance-mode ranges Table 5, ROI minimum 4 × 4), this spec cites it.

## Transport Configuration

### I²C
- **Address:** `0x29` (7-bit; the datasheet quotes the 8-bit write form `0x52`/read `0x53`).
  Programmable at runtime via `I2C_SLAVE__DEVICE_ADDRESS` (`0x0001`); the new address is
  **volatile** — it reverts to `0x29` on power-up or an XSHUT low pulse.
- **Max clock:** 400 kHz (Fast mode); Standard mode also supported.
- **Register addressing:** **16-bit index**, sent MSB first (datasheet Figures 13/14), 8-bit
  registers, auto-increment on multi-byte access. Base `index_bytes = 2`.
- **Byte order:** multi-byte registers (16/32-bit) are **big-endian**, MSB at the lowest
  index (datasheet §4.2, Table 9).

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| GPIO1 | low, open-drain | Interrupt output — requires external pull-up (10 kΩ recommended). The chip's power-on/ULD default is active-**high**; `init` reconfigures it active-**low** (`GPIO_HV_MUX__CTRL` bit 4 = 1) to match the VL53L0X and the family's FALLING-edge delivery. Leave unconnected if unused. Wired as the `Connection`'s `int_pin` |
| XSHUT | low (shutdown) | Hardware standby when low; must always be driven or pulled up (10 kΩ) to avoid leakage. Wired as the `Connection`'s `en_pin` (high = enabled). Required for multi-sensor address assignment |

`XSHUT` must only be high while `AVDD` is applied (datasheet §3.6, Table 13 note 1). After
XSHUT rises, the firmware boots (tBOOT ≤ 1.2 ms) before the first I²C access is allowed.

## Register Map

Only registers the driver touches individually are listed; `0x002D`–`0x0087` are
additionally written as one opaque block by the Initialization Sequence (ULD
`VL51L1X_DEFAULT_CONFIGURATION`). All indices are 16-bit.

| Index | Name (ULD) | R/W | Reset / default | Description |
|-------|------------|-----|-----------------|-------------|
| `0x0001` | I2C_SLAVE__DEVICE_ADDRESS | R/W | `0x29` | 7-bit address, bits 6:0 (volatile) |
| `0x0008` | VHV_CONFIG__TIMEOUT_MACROP_LOOP_BOUND | R/W | — | `0x09` = two-bound VHV (normal), `0x81` = full VHV (temperature update) |
| `0x000B` | *(VHV init — ULD writes it by raw index)* | W | — | `0x00` = start VHV from the previous temperature, `0x92` = temperature-update request |
| `0x0016` | ALGO__CROSSTALK_COMPENSATION_PLANE_OFFSET_KCPS | R/W | `0x0000` | 16-bit, crosstalk per SPAD, **kcps in 7.9 fixed point** |
| `0x0018` | ALGO__CROSSTALK_COMPENSATION_X_PLANE_GRADIENT_KCPS | R/W | `0x0000` | 16-bit, always written `0` |
| `0x001A` | ALGO__CROSSTALK_COMPENSATION_Y_PLANE_GRADIENT_KCPS | R/W | `0x0000` | 16-bit, always written `0` |
| `0x001E` | ALGO__PART_TO_PART_RANGE_OFFSET_MM | R/W | NVM | 16-bit, bits 12:0 two's-complement offset in units of 0.25 mm |
| `0x0020` | MM_CONFIG__INNER_OFFSET_MM | R/W | — | 16-bit, written `0` whenever the offset is set |
| `0x0022` | MM_CONFIG__OUTER_OFFSET_MM | R/W | — | 16-bit, written `0` whenever the offset is set |
| `0x002E` | PAD_I2C_HV__EXTSUP_CONFIG | R/W | `0x00` | Bit 0: I²C pads, `0` = 1V8 pull-ups, `1` = pull-ups to AVDD (2V8 mode) |
| `0x002F` | GPIO__EXTSUP_HV | R/W | `0x00` | Bit 0: GPIO1 pad, `0` = 1V8, `1` = AVDD (2V8 mode) |
| `0x0030` | GPIO_HV_MUX__CTRL | R/W | `0x01` | Bit 4: GPIO1 polarity, `0` = active high, `1` = active low; bits 3:0 must stay `0x1` |
| `0x0031` | GPIO__TIO_HV_STATUS | R | — | Bit 0: GPIO1 interrupt line state (compare with polarity for data-ready) |
| `0x0046` | SYSTEM__INTERRUPT_CONFIG_GPIO | R/W | `0x20` | Interrupt mode — see bit fields |
| `0x004B` | PHASECAL_CONFIG__TIMEOUT_MACROP | R/W | `0x0A` | Distance-mode dependent; also identifies the current mode (`0x14` short, `0x0A` long) |
| `0x005E` | RANGE_CONFIG__TIMEOUT_MACROP_A_HI | R/W | `0x01CC` | 16-bit, encoded range timeout A (timing-budget table) |
| `0x0060` | RANGE_CONFIG__VCSEL_PERIOD_A | R/W | `0x0F` | Distance-mode dependent |
| `0x0061` | RANGE_CONFIG__TIMEOUT_MACROP_B_HI | R/W | `0x01F1` | 16-bit, encoded range timeout B (timing-budget table) |
| `0x0063` | RANGE_CONFIG__VCSEL_PERIOD_B | R/W | `0x0D` | Distance-mode dependent |
| `0x0064` | RANGE_CONFIG__SIGMA_THRESH | R/W | `0x0168` | 16-bit, sigma threshold in mm, 14.2 fixed point (default 90 mm) |
| `0x0066` | RANGE_CONFIG__MIN_COUNT_RATE_RTN_LIMIT_MCPS | R/W | `0x0080` | 16-bit, return signal-rate limit, MCPS 9.7 fixed point (default 1.0 MCPS) |
| `0x0069` | RANGE_CONFIG__VALID_PHASE_HIGH | R/W | `0xB8` | Distance-mode dependent |
| `0x006C` | SYSTEM__INTERMEASUREMENT_PERIOD | R/W | `0x00000F89` | 32-bit, inter-measurement period in oscillator ticks (see Data Conversion) |
| `0x0072` | SYSTEM__THRESH_HIGH | R/W | `0x0000` | 16-bit, high distance threshold in mm |
| `0x0074` | SYSTEM__THRESH_LOW | R/W | `0x0000` | 16-bit, low distance threshold in mm |
| `0x0078` | SD_CONFIG__WOI_SD0 | R/W | `0x0F0D` | 16-bit, distance-mode dependent |
| `0x007A` | SD_CONFIG__INITIAL_PHASE_SD0 | R/W | `0x0E0E` | 16-bit, distance-mode dependent |
| `0x007F` | ROI_CONFIG__USER_ROI_CENTRE_SPAD | R/W | `0xC7` | ROI centre SPAD number (0–255; `199` = array centre) |
| `0x0080` | ROI_CONFIG__USER_ROI_REQUESTED_GLOBAL_XY_SIZE | R/W | `0xFF` | Bits 7:4 = height − 1, bits 3:0 = width − 1 (SPADs) |
| `0x0086` | SYSTEM__INTERRUPT_CLEAR | W | — | Write `0x01` to clear the interrupt / release the next result |
| `0x0087` | SYSTEM__MODE_START | R/W | `0x00` | `0x00` stop, `0x10` single-shot, `0x40` timed continuous |
| `0x0089` | RESULT__RANGE_STATUS | R | — | Start of the 17-byte result block — see Data Conversion |
| `0x008C` | RESULT__DSS_ACTUAL_EFFECTIVE_SPADS_SD0 | R | — | 16-bit, 8.8 fixed point (in result block) |
| `0x0090` | RESULT__AMBIENT_COUNT_RATE_MCPS_SD | R | — | 16-bit, MCPS 9.7 (in result block) |
| `0x0096` | RESULT__FINAL_CROSSTALK_CORRECTED_RANGE_MM_SD0 | R | — | 16-bit, distance in mm (in result block) |
| `0x0098` | RESULT__PEAK_SIGNAL_COUNT_RATE_CROSSTALK_CORRECTED_MCPS_SD0 | R | — | 16-bit, MCPS 9.7 (in result block) |
| `0x00DE` | RESULT__OSC_CALIBRATE_VAL | R | — | 16-bit, bits 9:0 = oscillator ticks per ms (inter-measurement scaling) |
| `0x00E5` | FIRMWARE__SYSTEM_STATUS | R | — | Bit 0 = firmware booted |
| `0x010F` | IDENTIFICATION__MODEL_ID | R | `0xEA` | Model ID (datasheet Table 8); read as 16-bit with `0x0110` → `0xEACC` |
| `0x0110` | IDENTIFICATION__MODULE_TYPE | R | `0xCC` | Module type (datasheet Table 8) |
| `0x0111` | IDENTIFICATION__REVISION_ID | R | `0x10` | Mask revision (datasheet Table 8) |
| `0x013E` | ROI_CONFIG__MODE_ROI_CENTRE_SPAD | R | NVM | Factory-measured optical-centre SPAD (datasheet §3.8) |

### Bit Fields

#### `SYSTEM__INTERRUPT_CONFIG_GPIO` (`0x0046`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | — | Reserved (0) |
| 6 | INT_ON_NO_TARGET | `1` = threshold interrupt also fires when there is no target. The driver always writes `0` |
| 5 | NEW_SAMPLE_READY | `1` = interrupt on every new sample (overrides bits 1:0). Default `0x20` |
| 4:2 | — | Reserved (0) |
| 1:0 | WINDOW | Threshold mode when bit 5 = 0: `0` below low, `1` above high, `2` outside window, `3` inside window |

#### `GPIO_HV_MUX__CTRL` (`0x0030`)

| Bits | Name | Description |
|------|------|-------------|
| 7:5 | — | Reserved (0) |
| 4 | POLARITY | `0` = active high (power-on default), `1` = active low (driver) |
| 3:0 | — | Must be `0x1` |

#### `SYSTEM__MODE_START` (`0x0087`)

| Value | Meaning |
|-------|---------|
| `0x00` | Stop ranging |
| `0x10` | Single-shot: one measurement, then back to software standby |
| `0x40` | Timed continuous (autonomous) ranging at the inter-measurement period |

## Initialization Sequence

This is ULD `VL53L1X_BootState` + `VL53L1X_GetSensorId` + `VL53L1X_SensorInit`, followed by
the family's 2V8 / active-low overrides. `wr8`/`rd8`/`wr16`/`rd16`/`wr32` are the base
helpers with 16-bit indices.

1. `boot_wait()` (base): XSHUT high via `en_pin` if present, wait 1.2 ms.
2. `wait_until(rd8(0x00E5) & 0x01, "boot")` — firmware booted.
3. `rd16(0x010F)` must be `0xEACC` (model `0xEA`, module type `0xCC`); otherwise fail with a
   chip-not-found error.
4. **Default configuration:** write the 91 bytes below to `0x002D`…`0x0087`, **one `wr8`
   per register, in ascending order** (as the ULD does; keeps traffic deterministic and fits
   every platform's I²C buffer). Opaque ST values — write verbatim:
   ```
   002D: 00 00 00 01 02 00 02 08 00 08 10 01 01 00 00 00
   003D: 00 FF 00 0F 00 00 00 00 00 20 0B 00 00 02 0A 21
   004D: 00 00 05 00 00 00 00 C8 00 00 38 FF 01 00 08 00
   005D: 00 01 CC 0F 01 F1 0D 01 68 00 80 08 B8 00 00 00
   006D: 00 0F 89 00 00 00 00 00 00 00 01 0F 0D 0E 0E 00
   007D: 00 02 C7 FF 9B 00 00 01 00 00 00
   ```
   (Row label = index of the first byte in that row.) This leaves: long distance mode,
   100 ms timing budget, GPIO1 = new sample ready, ROI 16 × 16 centred on SPAD 199,
   signal-rate limit 1.0 MCPS, sigma threshold 90 mm, ranging stopped.
5. **Family overrides** (after the block, not by editing it, so the block stays diffable
   against the ULD):
   - 2V8 I/O mode: `wr8(0x002E, 0x01)`, `wr8(0x002F, 0x01)` — breakout boards pull I²C and
     GPIO1 up to 2.8–3.3 V; the chip's default 1V8 pad mode (datasheet Table 13 note 3) is
     not meant for that. Same policy as the VL53L0X.
   - GPIO1 active low: `wr8(0x0030, 0x11)`.
6. **Settling ranging** (ULD): `wr8(0x0087, 0x40)`; `wait_until(data_ready)`;
   `wr8(0x0086, 0x01)`; `wr8(0x0087, 0x00)`. Data-ready is `(rd8(0x0031) & 0x01) == 0`
   (active-low line asserted) — see Data Conversion.
7. `wr8(0x0008, 0x09)` (two-bound VHV); `wr8(0x000B, 0x00)` (start VHV from the previous
   temperature).

Minimal leaves the chip idle (software standby) after `init`; ranging starts on demand. No
soft reset is issued — `SOFT_RESET` would make re-`init` on a re-addressed sensor
unpredictable, and step 4's block ends with `MODE_START = 0x00`, so re-`init` on a ranging
sensor stops it cleanly.

## Interrupt

| Property | Value |
|----------|-------|
| INT pin | GPIO1 — active-low (set by `init`), open-drain — requires external pull-up |
| Level | 2 |
| Condition(s) | new sample ready (default); distance below / above / outside / inside a threshold window |
| Clear mechanism | write `0x01` to `SYSTEM__INTERRUPT_CLEAR` (`0x0086`) |

As on the VL53L0X, the sources are **mutually exclusive** (one mode in `0x0046`), and the
driver's own data-ready polling observes the same GPIO1 line state: with a threshold source
active, `data_ready()`/`read_continuous()` only see a pending result when the threshold
condition is met. Switch back to `SOURCE_NEW_SAMPLE_READY` before using the blocking read
methods. Threshold sources are evaluated per measurement, so they only fire while ranging.

The chip has **no disabled state** for GPIO1 (a sample-ready or threshold mode is always
active) and **no source-status register** — only "line asserted". Hence:

- `enable_interrupt(source)` replaces the active mode (register encoding below).
- `disable_interrupt(source)`: if `source` is the active *threshold* source, revert to
  `SOURCE_NEW_SAMPLE_READY` (`wr8(0x0046, 0x20)`); if `source` is
  `SOURCE_NEW_SAMPLE_READY` or not the active source, do nothing. Use `off_interrupt()` to
  stop callbacks.
- `poll_interrupt()` returns the **active** source's logical value when the line is asserted.

### Interrupt sources

Logical values are the family constants from the base spec.

| Constant | Value | `0x0046` written | Condition |
|----------|-------|------------------|-----------|
| `SOURCE_LEVEL_LOW` | `1` | `0x00` | Range < low threshold |
| `SOURCE_LEVEL_HIGH` | `2` | `0x01` | Range > high threshold |
| `SOURCE_OUT_OF_WINDOW` | `3` | `0x02` | Range < low **or** > high threshold |
| `SOURCE_NEW_SAMPLE_READY` | `4` | `0x20` | New measurement available (default) |
| `SOURCE_IN_WINDOW` | `5` | `0x03` | low ≤ range ≤ high (VL53L1X only) |

Active-source readback: `v = rd8(0x0046)`; `v & 0x20` → `SOURCE_NEW_SAMPLE_READY`; else map
`v & 0x03` → `0:LEVEL_LOW, 1:LEVEL_HIGH, 2:OUT_OF_WINDOW, 3:IN_WINDOW`.

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback, int_pin=None)` | Base `subscribe` (FALLING edge / polling fallback); callback receives the logical `SOURCE_*` value |
| `off_interrupt` | `off_interrupt()` | Base `unsubscribe` |
| `poll_interrupt` | `poll_interrupt() -> int` | Hook `poll_interrupt_status`: under the lock, if `data_ready()` then `wr8(0x0086, 0x01)` and return the active source value, else `0` |
| `enable_interrupt` | `enable_interrupt(source)` | Write the table encoding to `0x0046`; invalid source → error |
| `disable_interrupt` | `disable_interrupt(source)` | See rules above |

### Status register bit layout

There is no status register; `GPIO__TIO_HV_STATUS` (`0x0031`) bit 0 is the raw line state,
`0` = asserted with the driver's active-low polarity.

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits
everything and adds the rest. `VL53L1XMinimal` extends the family base `VL53Base`
(`index_bytes = 2`, `chip_name = "VL53L1X"`).

### Minimal

Goal: single-shot distance in millimetres with no configuration beyond the connection.
Identical API to `VL53L0XMinimal`.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Runs the Initialization Sequence. Leaves the chip idle |
| `distance` | — | int | Unit: mm. One single-shot measurement, blocks ≈ timing budget (100 ms default). Returns the raw range even when the status is not valid; check `range_valid()` |
| `range_valid` | — | bool | `True` iff the (mapped) range status of the most recent measurement is `0` |

**Single-shot sequence (`distance`):**
1. `wr8(0x0086, 0x01)` (drop any stale result).
2. `wr8(0x0087, 0x10)`.
3. `wait_until(data_ready, "data ready")`.
4. Read the 17-byte result block from `0x0089` in one burst; store the mapped range status
   and extract the distance (see Data Conversion).
5. `wr8(0x0086, 0x01)`.

The chip returns to software standby by itself after a single-shot measurement.

**Sensible defaults baked into Minimal:**
- 2V8 I/O mode; GPIO1 = new sample ready, active low
- Long distance mode, 100 ms timing budget (ULD defaults — reaches ≈ 3.6 m in the dark,
  datasheet Table 5 / Figure 6)
- Full 16 × 16 ROI at SPAD 199 (27° FoV)
- Signal-rate limit 1.0 MCPS, sigma threshold 90 mm
- NVM offset kept, crosstalk compensation 0
- I/O timeout 500 ms for every poll loop (base `TIMEOUT_MS`)

### Full

Goal: expose complete chip functionality. Extends Minimal. Family-API methods behave as in
`specs/tof/_vl53_base.md`; chip-specific details below.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `start_continuous` | `period_ms: int = 0` | — | Timed mode. `p = max(period_ms, timing_budget_ms)` (`0` → the timing budget, i.e. back-to-back; the chip requires period ≥ budget); `set_inter_measurement(p)`; `wr8(0x0086, 0x01)`; `wr8(0x0087, 0x40)` |
| `stop_continuous` | — | — | `wr8(0x0087, 0x00)`. Does not wait |
| `read_continuous` | — | int | mm. Blocks until data ready, then steps 4–5 of the single-shot sequence |
| `data_ready` | — | bool | `(rd8(0x0031) & 0x01) == 0` — non-blocking |
| `read_measurement` | — | record | Non-blocking burst read of the result block + `wr8(0x0086, 0x01)`: `{ distance_mm, range_status, signal_rate_mcps, ambient_rate_mcps, effective_spad_count }` (family record) |
| `range_status` | — | int | Mapped status (0 = valid) of the most recent measurement — see Data Conversion |
| `set_timing_budget` | `budget_us: int` | — | One of `15000` (short mode only), `20000`, `33000`, `50000`, `100000`, `200000`, `500000`; otherwise, or `15000` in long mode, error. Writes the mode's A/B pair (Data Conversion). Changes take effect at the next ranging start |
| `timing_budget` | — | int | µs, decoded from `rd16(0x005E)` via the table; `0` if the register holds no table value |
| `set_distance_mode` | `mode: "short"\|"long"` | — | Writes the mode's register set (Data Conversion), then re-applies the current timing budget. Switching to long while the budget is 15 ms → error before any write |
| `distance_mode` | — | str | `rd8(0x004B)`: `0x14` → `"short"`, `0x0A` → `"long"`, otherwise error |
| `set_inter_measurement` | `period_ms: int` | — | 1 ≤ period ≤ 60 000; see Data Conversion. Must be ≥ the timing budget for correct operation (not enforced here — `start_continuous` enforces it) |
| `inter_measurement` | — | int | ms, see Data Conversion |
| `set_signal_rate_limit` | `limit_mcps: float` | — | 0 ≤ limit ≤ 511.99; `wr16(0x0066, round(limit × 128))` |
| `signal_rate_limit` | — | float | `rd16(0x0066) / 128` |
| `set_sigma_threshold` | `sigma_mm: int` | — | 0 ≤ sigma ≤ 16383; `wr16(0x0064, sigma_mm << 2)`. Results with a larger estimated standard deviation get status 1 |
| `sigma_threshold` | — | int | mm, `rd16(0x0064) >> 2` |
| `set_roi` | `width: int`, `height: int` | — | SPADs, each 4–16 (datasheet §3.4 minimum 4 × 4); error otherwise. If width > 10 or height > 10, first `wr8(0x007F, 199)` (re-centre, as the ULD does, so the ROI stays on the array) — otherwise the centre is unchanged. `wr8(0x0080, ((height − 1) << 4) \| (width − 1))` |
| `roi` | — | (int, int) | `(width, height)` = `(v & 0x0F) + 1`, `(v >> 4) + 1` of `rd8(0x0080)` |
| `set_roi_center` | `spad: int` | — | 0–255; `wr8(0x007F, spad)`. SPAD numbering per ST UM2555; `199` = array centre. The caller is responsible for keeping the ROI inside the array |
| `roi_center` | — | int | `rd8(0x007F)` |
| `optical_center` | — | int | `rd8(0x013E)` — factory-measured optical-centre SPAD (datasheet §3.8); pass to `set_roi_center` to align the ROI with this part's lens |
| `set_offset` | `offset_mm: float` | — | −1024.0 ≤ offset ≤ 1023.75; `wr16(0x001E, round(offset_mm × 4) & 0x1FFF)`, `wr16(0x0020, 0)`, `wr16(0x0022, 0)`. Overrides the NVM offset until power-off |
| `offset` | — | float | mm; `rd16(0x001E) & 0x1FFF` as 13-bit two's complement, × 0.25 |
| `set_crosstalk_compensation` | `rate_mcps: float` | — | Per-SPAD crosstalk rate, 0 ≤ rate < 0.128 MCPS (`0` disables). `wr16(0x0018, 0)`, `wr16(0x001A, 0)`, `wr16(0x0016, round(rate_mcps × 512000))` |
| `crosstalk_compensation` | — | float | MCPS, `rd16(0x0016) / 512000` |
| `calibrate_offset` | `target_mm: int` | float | Offset calibration (ULD `VL53L1X_CalibrateOffset`) — see Implementation Notes. Returns and applies the offset in mm |
| `calibrate_crosstalk` | `target_mm: int` | float | Crosstalk calibration (ULD `VL53L1X_CalibrateXtalk`) — see Implementation Notes. Returns and applies the rate in MCPS |
| `recalibrate` | — | — | Temperature update (ULD `VL53L1X_StartTemperatureUpdate`): `wr8(0x0008, 0x81)`, `wr8(0x000B, 0x92)`, `wr8(0x0087, 0x40)`, `wait_until(data_ready)`, `wr8(0x0086, 0x01)`, `wr8(0x0087, 0x00)`, `wr8(0x0008, 0x09)`, `wr8(0x000B, 0x00)`. Must not be called while ranging. Call after large temperature changes (> 8 °C, the family rule from the VL53L0X datasheet) |
| `set_address` | `address: int` | — | Base `set_address_reg(0x0001, address)` |
| `set_interrupt_thresholds` | `low_mm: int`, `high_mm: int` | — | 0 ≤ low ≤ high ≤ 65535; `wr16(0x0072, high_mm)`, `wr16(0x0074, low_mm)` |
| `interrupt_thresholds` | — | (int, int) | `(rd16(0x0074), rd16(0x0072))` |
| `model_id` | — | int | `rd8(0x010F)` — `0xEA` |
| `module_type` | — | int | `rd8(0x0110)` — `0xCC` |
| `revision_id` | — | int | `rd8(0x0111)` — `0x10` |
| *interrupt API* | | | `on_interrupt`, `off_interrupt`, `poll_interrupt`, `enable_interrupt`, `disable_interrupt` — see Interrupt |

**Additional configuration options:** everything above. I/O voltage mode (2V8) and GPIO1
polarity (active low) are fixed and not exposed, as on the VL53L0X. The ULD's
"interrupt on no target" flag is not exposed (always off).

**Language naming:** snake_case in Python/Rust/Go-internal; camelCase in C++/JS/JVM
(`startContinuous`, `setDistanceMode`, `setRoi`, `calibrateOffset` …); Go exported
PascalCase. `mode` is an enum where the language has one (`DistanceMode::Short`/`Long`), a
string otherwise. `read_measurement` returns the language's record/struct/object/dict with
the family field names.

## Data Conversion

### Result block (17 bytes from `0x0089`)

| Byte offset | Register | Field | Conversion |
|-------------|----------|-------|------------|
| 0 | `0x0089` | raw status | `raw = b[0] & 0x1F`; `range_status = STATUS_MAP[raw]` if `raw < 24` else `255` |
| 3–4 | `0x008C` | effective SPAD count | `((b[3] << 8) \| b[4]) / 256` (8.8 fixed point) |
| 7–8 | `0x0090` | ambient rate | `((b[7] << 8) \| b[8]) / 128` MCPS (9.7) |
| 13–14 | `0x0096` | distance | `distance_mm = (b[13] << 8) \| b[14]` |
| 15–16 | `0x0098` | signal rate | `((b[15] << 8) \| b[16]) / 128` MCPS (9.7) |

(The ULD reports rates in kcps as `raw × 8`, an approximation of `raw / 128 × 1000`; this
spec uses the exact MCPS value, matching the VL53L0X record.)

### Range status

```
STATUS_MAP = [255, 255, 255, 5, 2, 4, 1, 7, 3, 0, 255, 255, 9, 13, 255, 255,
              255, 255, 10, 6, 255, 255, 11, 12]          # ULD status_rtn[24]
```

| Mapped value | Meaning (ST `VL53L1_RANGESTATUS_*`) |
|--------------|-------------------------------------|
| 0 | **Range valid** |
| 1 | Sigma fail — estimated std deviation above the sigma threshold |
| 2 | Signal fail — return signal below the signal-rate limit (typical "no target") |
| 3 | Range valid, but below the minimum range (clipped) |
| 4 | Out of bounds — phase outside the valid limits (typical "no target" in long mode) |
| 5 | Hardware fail (VCSEL) |
| 6 | Range valid, wrap-around check not performed |
| 7 | Wrap-around target fail — phase does not match |
| 9 | Crosstalk signal fail |
| 10 | Synchronisation interrupt (first measurement after start; ignore) |
| 11 | Range valid, merged pulse |
| 12 | Target present but lack of signal |
| 13 | Minimum range fail |
| 255 | No update / unknown |

`range_valid()` ≡ `range_status == 0`.

### Timing budget (ULD `SetTimingBudgetInMs` table)

`wr16(0x005E, A)` then `wr16(0x0061, B)`:

| Budget | Short A | Short B | Long A | Long B |
|--------|---------|---------|--------|--------|
| 15 ms | `0x001D` | `0x0027` | — | — |
| 20 ms | `0x0051` | `0x006E` | `0x001E` | `0x0022` |
| 33 ms | `0x00D6` | `0x006E` | `0x0060` | `0x006E` |
| 50 ms | `0x01AE` | `0x01E8` | `0x00AD` | `0x00C6` |
| 100 ms | `0x02E1` | `0x0388` | `0x01CC` | `0x01EA` |
| 200 ms | `0x03E1` | `0x0496` | `0x02D9` | `0x02F8` |
| 500 ms | `0x0591` | `0x05C1` | `0x048F` | `0x04A4` |

`timing_budget()` decodes `rd16(0x005E)` against **all** A values in the table (they are
unique across both modes). Note the power-on block sets B = `0x01F1` for long/100 ms
(ULD table: `0x01EA`); `timing_budget()` only looks at A, so it reports 100 000 µs either way.

The datasheet (§3.5.2) recommends ≥ 33 ms for long mode and 140 ms to reach 4 m; the ULD
still accepts 20 ms in long mode, and so does this driver.

### Distance mode (ULD `SetDistanceMode`)

| Register | Short | Long |
|----------|-------|------|
| `wr8(0x004B, …)` PHASECAL_CONFIG__TIMEOUT_MACROP | `0x14` | `0x0A` |
| `wr8(0x0060, …)` RANGE_CONFIG__VCSEL_PERIOD_A | `0x07` | `0x0F` |
| `wr8(0x0063, …)` RANGE_CONFIG__VCSEL_PERIOD_B | `0x05` | `0x0D` |
| `wr8(0x0069, …)` RANGE_CONFIG__VALID_PHASE_HIGH | `0x38` | `0xB8` |
| `wr16(0x0078, …)` SD_CONFIG__WOI_SD0 | `0x0705` | `0x0F0D` |
| `wr16(0x007A, …)` SD_CONFIG__INITIAL_PHASE_SD0 | `0x0606` | `0x0E0E` |

Sequence: read the current budget (`timing_budget()`), write the six registers in the order
above, then `set_timing_budget(budget)` to rewrite A/B for the new mode.

Maximum distance (datasheet Table 5, 100 ms, 88 % white): short 136 cm dark / 135 cm strong
ambient; long 360 cm dark / 73 cm strong ambient.

### Inter-measurement period

```
clock_pll          = rd16(0x00DE) & 0x03FF                          # ticks per ms
set: wr32(0x006C, (clock_pll * period_ms * 1075) // 1000)
get: period_ms     = (rd32(0x006C) * 1000) // (clock_pll * 1075)    # 0 if clock_pll == 0
```

(The ULD's setter scales by 1.075 but its getter divides by 1.065; this spec uses 1.075 in
both, as the VL53L0X spec did for its analogous discrepancy, so the getter round-trips.)

## Node-RED

Node name: `periph-vl53l1x`
Package: `node-red-contrib-periph-tof`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ distance_mm, valid, range_status, signal_rate_mcps, ambient_rate_mcps }` | Single-shot measurement (or latest continuous result when continuous mode is configured) — same shape as `periph-vl53l0x` |
| threshold interrupt (when configured) | `{ source: "level_low" \| "level_high" \| "out_of_window" \| "in_window", distance_mm }` | Emitted from the interrupt callback when an `int_pin` is wired |

Config panel fields:
- **I²C bus** — bus number (e.g. `1` for `/dev/i2c-1`)
- **Address** — default `0x29`
- **Distance mode** — short / long (default long)
- **Timing budget** — dropdown 15 (short only) / 20 / 33 / 50 / 100 / 200 / 500 ms, default 100
- **ROI** — width and height (4–16, default 16 × 16)
- **Mode** — single-shot (on input) / continuous (with period in ms; node starts continuous
  mode on deploy and stops it on close)
- **Threshold interrupt** — optional mode dropdown (below / above / outside / inside) +
  low/high mm fields; requires a GPIO line for GPIO1

### Demo flow

An Inject node fires every 250 ms into a `periph-vl53l1x` node in single-shot mode (long
distance mode, 50 ms budget). A Switch node routes on `msg.payload.valid`: valid readings go
to a Function node that computes a rolling fill level for a 2 m deep bin
(`fill_pct = 100 × (2000 − distance_mm) / 2000`, clamped 0–100) and a Change node that only
passes changes ≥ 5 % to a Debug node labelled "bin fill"; invalid readings go to a second
Debug node labelled "no echo". Demonstrates long-range level monitoring — the use case the
VL53L0X cannot cover beyond ~1.2 m.

## Examples

### Demo

**Long-range doorway people counter with a split ROI.** The demo sets long distance mode and
a 33 ms budget, then uses two narrow 8 × 16 ROIs (left and right halves of the array:
`set_roi(8, 16)` with `set_roi_center` alternating between SPAD `167` and SPAD `231`, the
half-array centres used by ST's own VL53L1X people-counting reference code) to form two
virtual beams across a doorway, sensor mounted overhead up to 2.5 m high. It prints
`optical_center()` at start-up for reference. It
alternates single measurements between the two zones (`distance()` + `range_valid()`),
builds a baseline floor distance per zone from the first 20 samples, and treats a zone as
"occupied" when the distance is more than 300 mm shorter than its baseline. The order in
which zones become occupied (left→right vs right→left) increments an IN or OUT counter,
printed on each event with the two distances. Every 30 s it prints a `read_measurement()`
snapshot (signal and ambient rate) to show how sunlight through the door affects the
signal; if ambient exceeds 5 MCPS it switches to short distance mode and prints why. After
60 s (or 50 events) it restores the full 16 × 16 ROI and exits.

## Timing Constraints

- **Boot (tBOOT):** ≤ 1.2 ms from XSHUT rising (or AVDD applied with XSHUT pulled up) to
  software standby; no I²C access before that (datasheet §3.6, Figures 7/8). The driver
  waits 1.2 ms (base `boot_wait`) and then polls `FIRMWARE__SYSTEM_STATUS`. Not
  conformance-checked (the XSHUT edge is not on the I²C capture).
- **Single-shot ranging duration (`single_ranging`):** from the `SYSTEM__MODE_START`
  (`0x0087`) = `0x10` write to the STOP of the burst read of the result block at `0x0089`
  that consumes it — bounded by the timing budget. Conformance-checked at the Minimal
  default (long mode, 100 ms budget): min 80 ms, max 250 ms (host polling jitter + firmware
  overhead allowance).
- **Timing budget:** 15 ms (short mode only) to 500 ms via the ULD table; the datasheet
  (§3.5.2) gives 20–1000 ms, 33 ms minimum for all modes.
- **Ranging frequency:** up to 50 Hz (datasheet p. 1) — i.e. a 20 ms budget with
  back-to-back timed mode.
- **Inter-measurement period:** must be ≥ the timing budget (ULD); `start_continuous`
  enforces it.
- **Clear before next result:** "A clear interrupt is mandatory to allow the next ranging
  data to be updated" (datasheet §3.4) — every read path writes `0x0086 = 0x01`.
- **I²C:** 400 kHz max; tBUF ≥ 1.3 µs between transactions (datasheet Table 7).

## Implementation Notes

- **16-bit register index.** Every access sends two index bytes MSB first — use the base
  helpers with `index_bytes = 2`. Forgetting this (e.g. copying VL53L0X code) produces
  plausible-looking but wrong reads, since index `0x00xx` on the wire as one byte is
  misinterpreted as the high index byte.
- **Data-ready polarity.** The ULD derives the data-ready comparison from the current GPIO1
  polarity (`(rd8(0x0031) & 1) == !(rd8(0x0030) >> 4 & 1)`). The driver fixes the polarity
  to active-low in `init`, so data-ready is simply `(rd8(0x0031) & 0x01) == 0`. Do not read
  `0x0030` on every poll.
- **Offset calibration (`calibrate_offset(target_mm)`).** Place a target (ST: 88 % white,
  140 mm recommended) at `target_mm`. Sequence: `wr16(0x001E, 0)`, `wr16(0x0020, 0)`,
  `wr16(0x0022, 0)`; `wr8(0x0086, 0x01)`; `wr8(0x0087, 0x40)`; collect 50 results (wait data
  ready → read distance from the result block → `wr8(0x0086, 0x01)`); `wr8(0x0087, 0x00)`;
  `offset = target_mm − mean`; `set_offset(offset)`; return `offset` (float mm). Must not
  be called while ranging. Uses the current inter-measurement period, so call
  `set_inter_measurement(timing_budget_ms)` first if it has been changed; the Minimal
  default period (block value `0x0F89`) is ≈ 100 ms on typical parts.
- **Crosstalk calibration (`calibrate_crosstalk(target_mm)`).** With a cover glass fitted,
  place a grey (17 %) target at `target_mm` — the distance at which the sensor starts to
  under-range (ST UM2510 procedure). Sequence: `wr16(0x0016, 0)`; start timed ranging as
  above; collect 50 results accumulating distance, signal rate (MCPS) and effective SPAD
  count; stop; `rate = mean_signal × (1 − mean_distance / target_mm) / mean_spads`, clamped
  to `[0, 0.127]`; `set_crosstalk_compensation(rate)`; return `rate`.
- **Calibration persistence.** Offset and crosstalk values are volatile; the application
  stores the returned values and re-applies them with `set_offset` /
  `set_crosstalk_compensation` after every power-up (datasheet §3.3). RefSPAD calibration
  (datasheet §3.3) is a full-API procedure and is not implemented — the factory NVM values
  are used.
- **First measurement after `start_continuous`** may carry status 10 (synchronisation);
  applications should ignore it. `read_continuous` does not filter it.
- **Out-of-range readings.** With no target the firmware reports status 2 or 4 and an
  arbitrary distance (often small). Minimal returns the raw value; callers must check
  `range_valid()`.
- **ROI numbering.** SPAD numbers for `set_roi_center` follow ST's UM2555 map (not a simple
  row-major index); the driver does not translate coordinates. `optical_center()` gives the
  per-part NVM centre (datasheet §3.8, ±2 SPAD from nominal).
- **Multiple sensors on one bus.** Same procedure as the VL53L0X (base spec re-addressing):
  hold all XSHUT low, release one at a time, `set_address` each, rebuild the driver on a
  `Connection` at the new address. VL53L0X and VL53L1X can share a bus this way (both boot
  at `0x29`).
- **Rust:** `no_std`; `Vl53Bus` from the base with `index_bytes = 2`; the 1.2 ms boot wait and
  poll timeouts use the `DelayNs` supplied at construction. `on_interrupt`/`off_interrupt`
  are not exposed (`poll_interrupt` only), per the repo interrupt design.
- **Kotlin:** extends the Java `VL53Base` (base spec). The offset register needs **13-bit**
  sign extension (not 16); unsigned values (distance, rates, thresholds) must be masked with
  `and 0xFFFF` — do not use `.toShort()` there.

## Sigrok Decoder

Decoder `vl53l1x` (`sigrok/vl53l1x/`) stacks on the `i2c` decoder and matches address `0x29`
by default (option `address` for re-addressed sensors). It assembles the **two-byte register
index** from each write transaction and tracks it with auto-increment, so burst reads of the
result block are split into their named fields. It does not share code with the `vl53l0x`
decoder (different index width and register map).

| Row | Annotation | Meaning |
|-----|------------|---------|
| Data | `data` | Named register reads/writes: `SYSTEM__MODE_START` (stop / single-shot / timed), interrupt config (source name), thresholds in mm, timing-budget A/B registers decoded to ms + mode, distance-mode register set decoded to short/long, inter-measurement period (raw ticks), signal-rate limit in MCPS, sigma threshold in mm, ROI size/centre, offset in mm, crosstalk in MCPS, `I2C_SLAVE__DEVICE_ADDRESS` changes; result-block reads decoded to distance mm, range-status name, signal/ambient MCPS, SPAD count |
| Status | `status` | `GPIO__TIO_HV_STATUS` reads (data ready yes/no), `SYSTEM__INTERRUPT_CLEAR` writes, `FIRMWARE__SYSTEM_STATUS` reads, model/module/revision ID reads |
| Config | `config` | The 91-byte default-configuration write run (`0x002D`–`0x0087`) summarised as one annotation ("default config block, N/91 bytes") rather than 91 register annotations |
| Timing | `timing` | `single_ranging_start` at the `0x0087` = `0x10` write; `single_ranging_done` at the STOP of the next read transaction starting at index `0x0089` |
| Warnings | `warning` | Model ID ≠ `0xEACC`; single-byte register index (VL53L0X-style access to a VL53L1X); ranging start without an interrupt clear since the last result; `0x0030` bits 3:0 ≠ `0x1` |

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### VL53 family base (shared with VL53L0X — see `specs/tof/_vl53_base.md`)
- [ ] Python `python/periph/chips/tof/_vl53_base.py` — `_VL53Base`
- [ ] C++ `cpp/src/chips/tof/VL53Base.h` / `VL53Base.cpp` — `VL53Base` (add to `cpp/CMakeLists.txt` / Arduino library build as needed)
- [ ] Node.js `nodejs/packages/periph/src/chips/tof/_vl53_base.js` — `VL53Base`
- [ ] Rust `rust/periph/src/chips/tof/vl53_base.rs` — `pub(crate) Vl53Bus<I2C>`
- [ ] Go `go/periph/chips/tof/vl53_base.go` — unexported `vl53Base`
- [ ] Java `jvm/periph-java/src/main/java/it/uhde/periph/chips/tof/VL53Base.java` — used by Java **and Kotlin**
- [ ] Groovy `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/tof/VL53Base.groovy`
- [ ] Refactor `python/periph/chips/tof/vl53l0x.py` onto `_VL53Base`
- [ ] Refactor `cpp/src/chips/tof/VL53L0X.h/.cpp` onto `VL53Base`
- [ ] Refactor `nodejs/packages/periph/src/chips/tof/vl53l0x.js` onto `VL53Base`
- [ ] Refactor `rust/periph/src/chips/tof/vl53l0x.rs` onto `Vl53Bus`
- [ ] Refactor `go/periph/chips/tof/vl53l0x.go` to embed `vl53Base`
- [ ] Refactor JVM VL53L0X drivers: Java + Kotlin onto Java `VL53Base`, Groovy onto Groovy `VL53Base`
- [ ] All existing VL53L0X unit tests pass **unmodified** in every language (behavior-preserving refactor; byte-identical I²C traffic)

### Python
- [ ] Driver `python/periph/chips/tof/vl53l1x.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/tof/vl53l1x/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/tof/vl53l1x/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/tof/vl53l1x/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/tof/vl53l1x_test.py` (MicroPython)
- [ ] Tests `python/tests/tof/vl53l1x_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/tof/vl53l1x_test_linux.py` (Linux)
- [ ] Unit test `python/tests/tof/vl53l1x_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`). The mock must model 16-bit register indices

### UIFlow 1
- [ ] Manifest `python/uiflow1/tof/vl53l1x/vl53l1x.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/tof/vl53l1x/vl53l1x_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/tof/vl53l1x/vl53l1x.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/tof/vl53l1x/VL53L1X.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/tof/vl53l1x/VL53L1X.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/tof/VL53L1X.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/tof/VL53L1X.cpp`
- [ ] Examples `cpp/examples/arduino/tof/VL53L1X/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/tof/VL53L1X/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/tof/VL53L1X/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/linux/tof/VL53L1X/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/tof/VL53L1X/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/tof/VL53L1X/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/tof/VL53L1X/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/tof/VL53L1X/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/tof/VL53L1X/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/tof/VL53L1X/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/tof/VL53L1X/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/tof/VL53L1X/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/tof/VL53L1X/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/tof/VL53L1X/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/tof/VL53L1X/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/tof/vl53l1x_test/vl53l1x_test.ino` (Arduino)
- [ ] Tests `cpp/tests/tof/vl53l1x_test_linux/vl53l1x_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/tof/vl53l1x_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/tof/vl53l1x_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/tof/vl53l1x_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Unit test `cpp/tests/tof/vl53l1x_test_unit/vl53l1x_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/tof/vl53l1x.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/tof/vl53l1x/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/tof/vl53l1x/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/tof/vl53l1x/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/tof/vl53l1x_test.js`
- [ ] Unit test `nodejs/tests/tof/vl53l1x_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-tof/nodes/vl53l1x/vl53l1x.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-tof/nodes/vl53l1x/vl53l1x.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-tof/examples/vl53l1x/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/tof/vl53l1x.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/tof/vl53l1x/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/tof/vl53l1x/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/tof/vl53l1x/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/tof/vl53l1x_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/tof/vl53l1x_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/tof/vl53l1x.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`). Check for `const` name collisions with `vl53l0x.rs` in the same `tof` module

### Go
- [ ] Driver `go/periph/chips/tof/vl53l1x.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/tof/vl53l1x/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/tof/vl53l1x/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/tof/vl53l1x/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/tof/vl53l1x/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/tof/vl53l1x/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/tof/vl53l1x/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/tof/vl53l1x_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/tof/vl53l1x_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/tof/vl53l1x_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/tof/VL53L1XMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/tof/VL53L1XFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/tof/VL53L1XMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/tof/VL53L1XFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/tof/VL53L1XMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/tof/VL53L1XFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/tof/vl53l1x/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/tof/vl53l1x/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/tof/vl53l1x/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/tof/vl53l1x/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/tof/vl53l1x/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/tof/vl53l1x/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/tof/vl53l1x/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/tof/vl53l1x/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/tof/vl53l1x/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/tof/vl53l1x/VL53L1XTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/tof/VL53L1XTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/tof/VL53L1XTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/tof/VL53L1XSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/vl53l1x/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/vl53l1x/pd.py` — for the Timing Constraint above with a conformance check, emits the named `single_ranging_start`/`single_ranging_done` annotation pair

### Conformance
- [ ] Checker `conformance/tof/vl53l1x_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/tof/vl53l1x_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/vl53l1x/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` only

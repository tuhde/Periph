# Chip Spec: ADXL362

**Manufacturer:** Analog Devices
**Datasheet:** `datasheets/accelerometer/adxl362.pdf`
**Category:** accelerometer
**Transports:** SPI

## Overview

The ADXL362 is an ultralow power, 3-axis MEMS accelerometer that samples the full bandwidth of the sensor at all data rates (no power duty-cycling, so no aliasing from undersampling). It consumes less than 2 µA at a 100 Hz output data rate and just 270 nA in a motion-triggered wake-up mode, making it suitable for coin-cell and energy-harvested designs. Measurement ranges of ±2 *g*, ±4 *g*, and ±8 *g* are user-selectable via SPI, with a fixed 12-bit output resolution (an 8-bit MSB-only format is also available for lower-power/lower-bandwidth transfers). The chip includes a 512-sample FIFO, an on-chip temperature sensor, autonomous activity/inactivity (motion) detection with two independent interrupt pins, and a dedicated wake-up mode that can drive a power switch for downstream circuitry without host intervention. Unlike the ADXL345, the ADXL362 is SPI-only — there is no I²C mode.

## Transport Configuration

### SPI

- **Mode:** CPOL=0 CPHA=0 (Mode 0)
- **Max clock:** 8 MHz (2.4 kHz minimum, required only when reading the FIFO — register access has no minimum clock)
- **Bit order:** MSB first
- **CS active:** low
- **Wiring:** 4-wire only (no 3-wire mode)

SPI command structure: the first byte is a command (`0x0A`=write register, `0x0B`=read register, `0x0D`=read FIFO), optionally followed by an 8-bit register address (register read/write only — FIFO read has no address byte) and then one or more data bytes. Register reads/writes auto-increment the address on multi-byte (burst) transfers; the auto-increment halts at the invalid register address `0x3F` rather than wrapping. `</CS down> <command> [<address>] <data...> </CS up>`.

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| INT1 | active high (default), open output driven low-impedance (~500 Ω); polarity invertible per-pin | Interrupt 1 output. Also doubles as an external clock input when `POWER_CTL.EXT_CLK`=1 — using this alternate function loses INT1 as an interrupt output. |
| INT2 | active high (default), open output driven low-impedance (~500 Ω); polarity invertible per-pin | Interrupt 2 output. Also doubles as an external sample-sync trigger input when `FILTER_CTL.EXT_SAMPLE`=1 — using this alternate function loses INT2 as an interrupt output. |

Both INT pins are push-pull with bus keepers (not open-drain) and go to a high-impedance state only when no function is mapped to them (`INTMAP1`/`INTMAP2` = `0x00`) or immediately after reset. `Reserved` pins (3, 5, 10) and `NC` pins (2, 15) must be left unconnected or grounded per the datasheet; they carry no signal the driver needs.

## Register Map

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00` | DEVID_AD | R | `0xAD` | Analog Devices device ID |
| `0x01` | DEVID_MST | R | `0x1D` | Analog Devices MEMS device ID |
| `0x02` | PARTID | R | `0xF2` | Device part ID (362 octal) |
| `0x03` | REVID | R | `0x01` | Silicon revision, increments per die revision |
| `0x08` | XDATA | R | `0x00` | X-axis acceleration, 8 MSBs only |
| `0x09` | YDATA | R | `0x00` | Y-axis acceleration, 8 MSBs only |
| `0x0A` | ZDATA | R | `0x00` | Z-axis acceleration, 8 MSBs only |
| `0x0B` | STATUS | R | `0x40` | Device status flags |
| `0x0C` | FIFO_ENTRIES_L | R | `0x00` | FIFO sample count, bits [7:0] |
| `0x0D` | FIFO_ENTRIES_H | R | `0x00` | FIFO sample count, bits [9:8] |
| `0x0E` | XDATA_L | R | `0x00` | X-axis acceleration, LSB |
| `0x0F` | XDATA_H | R | `0x00` | X-axis acceleration, MSB + sign extension |
| `0x10` | YDATA_L | R | `0x00` | Y-axis acceleration, LSB |
| `0x11` | YDATA_H | R | `0x00` | Y-axis acceleration, MSB + sign extension |
| `0x12` | ZDATA_L | R | `0x00` | Z-axis acceleration, LSB |
| `0x13` | ZDATA_H | R | `0x00` | Z-axis acceleration, MSB + sign extension |
| `0x14` | TEMP_L | R | `0x00` | Temperature, LSB |
| `0x15` | TEMP_H | R | `0x00` | Temperature, MSB + sign extension |
| `0x1F` | SOFT_RESET | W | `0x00` | Write `0x52` (ASCII 'R') to reset |
| `0x20` | THRESH_ACT_L | R/W | `0x00` | Activity threshold, LSB |
| `0x21` | THRESH_ACT_H | R/W | `0x00` | Activity threshold, bits [10:8] |
| `0x22` | TIME_ACT | R/W | `0x00` | Activity time, in samples |
| `0x23` | THRESH_INACT_L | R/W | `0x00` | Inactivity threshold, LSB |
| `0x24` | THRESH_INACT_H | R/W | `0x00` | Inactivity threshold, bits [10:8] |
| `0x25` | TIME_INACT_L | R/W | `0x00` | Inactivity time, LSB (16-bit, in samples) |
| `0x26` | TIME_INACT_H | R/W | `0x00` | Inactivity time, MSB |
| `0x27` | ACT_INACT_CTL | R/W | `0x00` | Activity/inactivity detection control |
| `0x28` | FIFO_CONTROL | R/W | `0x00` | FIFO mode and options |
| `0x29` | FIFO_SAMPLES | R/W | `0x80` | FIFO watermark sample count |
| `0x2A` | INTMAP1 | R/W | `0x00` | INT1 pin function mapping |
| `0x2B` | INTMAP2 | R/W | `0x00` | INT2 pin function mapping |
| `0x2C` | FILTER_CTL | R/W | `0x13` | Measurement range, filter bandwidth, ODR |
| `0x2D` | POWER_CTL | R/W | `0x00` | Power mode, noise mode, wake-up, autosleep |
| `0x2E` | SELF_TEST | R/W | `0x00` | Self-test enable |

Registers `0x20`–`0x2E` are protected by an internal Hamming-type error-correcting code against single-event upsets (SEUs); `STATUS.ERR_USER_REGS` flags a detected mismatch.

### Bit Fields

#### `STATUS` (`0x0B`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | ERR_USER_REGS | SEU error detected in registers `0x20`–`0x2E`, or device not yet configured. Set on power-up/reset; clears on the first write to a protected register. |
| 6 | AWAKE | 1 = device is awake (per activity/inactivity state); 0 = asleep. Only meaningful when linked or loop mode is active — otherwise defaults to 1 and must be ignored. |
| 5 | INACT | 1 = inactivity (or free-fall) condition detected |
| 4 | ACT | 1 = activity (overthreshold) condition detected |
| 3 | FIFO_OVERRUN | 1 = FIFO overrun — new data replaced unread data |
| 2 | FIFO_WATERMARK | 1 = FIFO sample count ≥ `FIFO_SAMPLES` |
| 1 | FIFO_READY | 1 = at least one sample available in the FIFO |
| 0 | DATA_READY | 1 = new sample ready; clears when a FIFO read, or a read of the data registers, is performed |

#### `FIFO_ENTRIES_H` (`0x0D`)

| Bits | Name | Description |
|------|------|-------------|
| 7:2 | — | Unused |
| 1:0 | FIFO_ENTRIES_H | Bits [9:8] of the 10-bit FIFO entry count (0–512) |

#### `ACT_INACT_CTL` (`0x27`)

| Bits | Name | Description |
|------|------|-------------|
| 7:6 | — | Reserved |
| 5:4 | LINKLOOP | `00`/`10`=default mode (both functions enabled, host must clear via STATUS read); `01`=linked mode; `11`=loop mode |
| 3 | INACT_REF | 1 = inactivity detection is referenced (relative to orientation at engagement); 0 = absolute |
| 2 | INACT_EN | 1 = enables inactivity (under-threshold) detection |
| 1 | ACT_REF | 1 = activity detection is referenced; 0 = absolute |
| 0 | ACT_EN | 1 = enables activity (over-threshold) detection |

Both ACT_EN and INACT_EN must be 1 to engage linked or loop mode; otherwise the device falls back to default mode regardless of the LINKLOOP setting.

#### `FIFO_CONTROL` (`0x28`)

| Bits | Name | Description |
|------|------|-------------|
| 7:4 | — | Unused |
| 3 | AH | MSB (bit 9) of the `FIFO_SAMPLES` watermark value, range 0–511 |
| 2 | FIFO_TEMP | 1 = temperature data is stored in the FIFO along with x/y/z |
| 1:0 | FIFO_MODE | `00`=disabled (FIFO cleared); `01`=oldest saved; `10`=stream; `11`=triggered |

#### `FIFO_SAMPLES` (`0x29`)

8-bit watermark sample count, LSBs of the 9-bit value whose MSB is `FIFO_CONTROL.AH`. Reset value `0x80` avoids spuriously triggering the FIFO watermark interrupt at power-on (watermark=0 would fire immediately).

#### `INTMAP1` / `INTMAP2` (`0x2A` / `0x2B`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | INT_LOW | 1 = this INT pin is active low; 0 = active high (default) |
| 6 | AWAKE | 1 = maps the AWAKE status to this pin |
| 5 | INACT | 1 = maps the inactivity status to this pin |
| 4 | ACT | 1 = maps the activity status to this pin |
| 3 | FIFO_OVERRUN | 1 = maps the FIFO overrun status to this pin |
| 2 | FIFO_WATERMARK | 1 = maps the FIFO watermark status to this pin |
| 1 | FIFO_READY | 1 = maps the FIFO ready status to this pin |
| 0 | DATA_READY | 1 = maps the data ready status to this pin |

Any number of sources may be mapped to a pin simultaneously; their conditions are OR'ed together.

#### `FILTER_CTL` (`0x2C`)

| Bits | Name | Description |
|------|------|-------------|
| 7:6 | RANGE | `00`=±2 *g* (reset default); `01`=±4 *g*; `1X`=±8 *g* |
| 5 | — | Reserved |
| 4 | HALF_BW | 1 (reset default) = antialiasing filter bandwidth = ODR/4 (more conservative); 0 = ODR/2 (wider) |
| 3 | EXT_SAMPLE | 1 = INT2 is repurposed as the external synchronized-sampling trigger input |
| 2:0 | ODR | `000`=12.5 Hz; `001`=25 Hz; `010`=50 Hz; `011`=100 Hz (reset default); `100`=200 Hz; `101`–`111`=400 Hz |

#### `POWER_CTL` (`0x2D`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | — | Reserved |
| 6 | EXT_CLK | 1 = the device runs off an external clock supplied on INT1 (25.6 kHz–51.2 kHz), instead of the internal 51.2 kHz nominal clock |
| 5:4 | LOW_NOISE | `00`=normal (lowest power); `01`=low noise; `10`=ultralow noise; `11`=reserved |
| 3 | WAKEUP | 1 = wake-up mode (270 nA typical, ~6 Hz sampling, single-sample activity detection only) |
| 2 | AUTOSLEEP | 1 = autonomously switches to wake-up mode on inactivity and back to measurement mode on activity. Requires linked or loop mode (`ACT_INACT_CTL.LINKLOOP`); ignored in default mode. |
| 1:0 | MEASURE | `00`=standby (reset default); `10`=measurement mode; `01`/`11`=reserved |

#### `SELF_TEST` (`0x2E`)

| Bits | Name | Description |
|------|------|-------------|
| 7:1 | — | Unused |
| 0 | ST | 1 = applies an electrostatic self-test force to all three axes |

## Initialization Sequence

1. Power up; wait ≥5 ms (typical Power-Up-to-Standby turn-on time) before the first SPI transaction.
2. Burst-read DEVID_AD, DEVID_MST, PARTID (`0x00`–`0x02`); verify `0xAD`, `0x1D`, `0xF2` — fail if any mismatch (indicates wiring or SPI-mode issues, since this chip has no I²C fallback to confuse with).
3. Write FILTER_CTL (`0x2C`) ← `0x13` (RANGE=±2 *g*, HALF_BW=1, ODR=100 Hz) — the reset value; written explicitly for clarity/idempotency rather than relied upon.
4. Write POWER_CTL (`0x2D`) ← `0x02` (LOW_NOISE=normal, WAKEUP=0, MEASURE=10 → measurement mode).
5. Wait ≥4/ODR (40 ms at 100 Hz) for the Measurement-Mode-Instruction-to-Valid-Data turn-on time before reading data.

## Implementation Stages

### Minimal

Goal: read X, Y, Z acceleration in *g* with sensible defaults; no configuration required beyond the transport.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Runs the initialization sequence above; verifies DEVID_AD/DEVID_MST/PARTID |
| `read` | — | `tuple(float, float, float)` | (x, y, z) in *g*; burst-reads 6 bytes from XDATA_L (`0x0E`) through ZDATA_H (`0x13`) |

**Sensible defaults:** ±2 *g* range, ODR=100 Hz, HALF_BW=1 (25 Hz antialiasing bandwidth), normal noise mode, continuous measurement mode (not wake-up), FIFO disabled, no interrupts mapped.

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `device_id` | — | `tuple(int, int, int, int)` | (DEVID_AD, DEVID_MST, PARTID, REVID) raw register bytes |
| `soft_reset` | — | — | Writes `0x52` to SOFT_RESET; waits ≥0.5 ms. All registers return to reset defaults; caller must re-run `init` |
| `set_range` | `range_g: int` (2, 4, 8) | — | Sets FILTER_CTL.RANGE |
| `set_odr` | `odr_hz: float` (12.5–400) | — | Sets FILTER_CTL.ODR to the nearest supported rate |
| `set_half_bandwidth` | `enabled: bool` | — | Sets FILTER_CTL.HALF_BW |
| `set_noise_mode` | `mode: int` (0=normal, 1=low, 2=ultralow) | — | Sets POWER_CTL.LOW_NOISE |
| `set_wakeup_mode` | `enabled: bool` | — | Sets POWER_CTL.WAKEUP |
| `set_autosleep` | `enabled: bool` | — | Sets POWER_CTL.AUTOSLEEP; effective only in linked/loop mode |
| `set_external_clock` | `enabled: bool` | — | Sets POWER_CTL.EXT_CLK; repurposes INT1 as clock input when enabled |
| `set_external_sample_trigger` | `enabled: bool` | — | Sets FILTER_CTL.EXT_SAMPLE; repurposes INT2 as sync trigger input when enabled |
| `read_8bit` | — | `tuple(float, float, float)` | (x, y, z) in *g*, ~16-LSB (12-bit-equivalent) resolution; burst-reads 3 bytes from XDATA (`0x08`) through ZDATA (`0x0A`) — lower bus/power cost than `read` |
| `temperature` | — | `float` | °C, from TEMP_L/TEMP_H (`0x14`–`0x15`), using typical bias/sensitivity — see Implementation Notes for calibration caveat |
| `status` | — | `int` | Raw STATUS register byte |
| `awake` | — | `bool` | STATUS.AWAKE |
| `data_ready` | — | `bool` | STATUS.DATA_READY |
| `fifo_entries` | — | `int` | 10-bit sample count from FIFO_ENTRIES_L/H (`0x0C`–`0x0D`) |
| `configure_fifo` | `mode: int` (0=disabled, 1=oldest saved, 2=stream, 3=triggered), `store_temp: bool=False`, `watermark: int=128` | — | Writes FIFO_CONTROL and FIFO_SAMPLES (+AH bit) |
| `read_fifo` | — | `list[tuple(int, float)]` | Reads all available FIFO entries via the FIFO read command; each entry decoded to `(axis, value)` where axis is 0=X/1=Y/2=Z/3=temperature and value is in *g* or °C per Data Conversion |
| `set_activity_threshold` | `threshold_g: float`, `referenced: bool=False` | — | Writes THRESH_ACT_L/H; sets ACT_INACT_CTL.ACT_REF |
| `set_activity_time` | `samples: int` | — | Writes TIME_ACT (0–255 samples; ignored in wake-up mode, which uses single-sample detection) |
| `set_inactivity_threshold` | `threshold_g: float`, `referenced: bool=False` | — | Writes THRESH_INACT_L/H; sets ACT_INACT_CTL.INACT_REF |
| `set_inactivity_time` | `samples: int` | — | Writes TIME_INACT_L/H (0–65535 samples) |
| `enable_activity_detection` | `enabled: bool` | — | Sets ACT_INACT_CTL.ACT_EN |
| `enable_inactivity_detection` | `enabled: bool` | — | Sets ACT_INACT_CTL.INACT_EN |
| `set_link_loop_mode` | `mode: int` (0=default, 1=linked, 3=loop) | — | Sets ACT_INACT_CTL.LINKLOOP |
| `set_interrupt` | `pin: int` (1 or 2), `source: int`, `enabled: bool` | — | Sets/clears the matching bit in INTMAP1 or INTMAP2 |
| `set_interrupt_polarity` | `pin: int` (1 or 2), `active_low: bool` | — | Sets INT_LOW bit in INTMAP1 or INTMAP2 |
| `self_test` | `enabled: bool` | — | Sets/clears SELF_TEST.ST |

**Interrupt source constants** (used by `set_interrupt`): `SOURCE_DATA_READY=0`, `SOURCE_FIFO_READY=1`, `SOURCE_FIFO_WATERMARK=2`, `SOURCE_FIFO_OVERRUN=3`, `SOURCE_ACT=4`, `SOURCE_INACT=5`, `SOURCE_AWAKE=6`. **Noise mode constants:** `NOISE_NORMAL=0`, `NOISE_LOW=1`, `NOISE_ULTRALOW=2`. **Link/loop constants:** `LINKLOOP_DEFAULT=0`, `LINKLOOP_LINKED=1`, `LINKLOOP_LOOP=3`. **FIFO mode constants:** `FIFO_DISABLED=0`, `FIFO_OLDEST_SAVED=1`, `FIFO_STREAM=2`, `FIFO_TRIGGERED=3`. **FIFO axis constants** (used by `read_fifo`): `AXIS_X=0`, `AXIS_Y=1`, `AXIS_Z=2`, `AXIS_TEMP=3`.

**Additional configuration options:**
- Measurement range: ±2/±4/±8 *g*
- Output data rate: 12.5 Hz to 400 Hz, with selectable antialiasing bandwidth (ODR/2 or ODR/4)
- Normal / low-noise / ultralow-noise power-noise tradeoff
- Wake-up mode (270 nA) with autonomous autosleep
- External clock input and external sample-sync trigger (both repurpose an INT pin)
- 8-bit low-resolution reads for minimal bus/power cost
- On-chip temperature sensor
- Referenced or absolute activity/inactivity (motion) detection with default/linked/loop linking
- 512-sample FIFO in disabled/oldest-saved/stream/triggered mode, with optional temperature interleaving
- Independent INT1/INT2 mapping of 7 status sources each, with per-pin active-high/active-low polarity
- Self test

## Data Conversion

**12-bit acceleration** (XDATA_L/XDATA_H, YDATA_L/YDATA_H, ZDATA_L/ZDATA_H — each an LSB/MSB register pair; the MSB register's bits [7:4] are sign-extension bits mirroring bit 11, not data):

```
raw = sign_extend_12((MSB_reg & 0x0F) << 8 | LSB_reg)   # bits 11:0, two's complement
acceleration_g = raw * sensitivity[range_g]
```

| Range | Sensitivity (typical) |
|-------|------------------------|
| ±2 *g* | 0.001 g/LSB (1 mg/LSB, ≈1000 LSB/g) |
| ±4 *g* | 0.002 g/LSB (2 mg/LSB, ≈500 LSB/g) |
| ±8 *g* | 0.004255 g/LSB (4.255 mg/LSB, ≈235 LSB/g) |

Note the ±8 *g* sensitivity is **not** exactly 4× the ±2 *g* value — use the table above, not a doubling formula.

**8-bit acceleration** (XDATA/YDATA/ZDATA — top 8 bits of the 12-bit value):

```
raw8 = int8(XDATA)   # signed 8-bit, two's complement
acceleration_g = raw8 * sensitivity[range_g] * 16   # each LSB here represents 16 codes of the 12-bit scale
```

**Temperature** (TEMP_L/TEMP_H, same sign-extended 12-bit layout as acceleration):

```
raw_temp = sign_extend_12((TEMP_H & 0x0F) << 8 | TEMP_L)
temperature_degC = 25.0 + (raw_temp - 350) * 0.065   # typical bias=350 LSB @25°C, sensitivity=0.065 °C/LSB
```

This is a **typical, uncalibrated** conversion — see Implementation Notes.

**FIFO entries** (16-bit, LSB byte first over the wire):

```
raw16 = (byte_hi << 8) | byte_lo
axis  = (raw16 >> 14) & 0x03        # 0=X, 1=Y, 2=Z, 3=temperature
raw12 = sign_extend_12(raw16 & 0x0FFF)
value = raw12 * sensitivity[range_g]           # axis 0-2: acceleration in g
      = 25.0 + (raw12 - 350) * 0.065           # axis 3: temperature in °C
```

`sign_extend_12(v)`: if bit 11 of `v` is set, subtract `0x1000` (4096).

## Node-RED

Node name: `periph-adxl362`
Package: `node-red-contrib-periph-accelerometer`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ x: float, y: float, z: float, temperature: float, awake: bool }` | Acceleration in *g* per axis, temperature in °C, awake from STATUS |

Config panel fields:
- **SPI device path** — Linux `/dev/spidev-N`
- **Measurement range** — ±2 / ±4 / ±8 *g* selector
- **Data rate** — ODR selector (12.5 Hz to 400 Hz)
- **Noise mode** — normal / low / ultralow
- **Antialiasing bandwidth** — ODR/2 or ODR/4 (HALF_BW)

### Demo flow

An inject node fires every 100 ms into the `periph-adxl362` node. A function node computes the magnitude √(x²+y²+z²) and appends it to the payload; a debug node and a `ui_chart` node display x, y, z, magnitude, and temperature over a rolling window. A second, separate flow demonstrates the chip's power-saving feature: an inject node fires every 2 s into a second `periph-adxl362` node configured to only call `awake()` (a lightweight STATUS read) rather than a full acceleration read, feeding a `ui_led` node that lights when the board is in motion — illustrating how a host can poll cheaply for activity without pulling full 12-bit samples every cycle.

## Examples

### Demo

An ultralow-power motion-activated wake demo, mirroring the datasheet's Autonomous Motion Switch application (Applications Information § Start-Up Routine, wake-up mode). Configure `set_activity_threshold(0.25, referenced=True)`, `set_inactivity_threshold(0.15, referenced=True)`, `set_inactivity_time(30)` (≈5 s at wake-up mode's ~6 Hz sample rate), `enable_activity_detection(True)`, `enable_inactivity_detection(True)`, `set_link_loop_mode(LINKLOOP_LOOP)` so the device autonomously toggles between activity and inactivity detection without host servicing, `set_interrupt(2, SOURCE_AWAKE, True)` to map AWAKE to INT2, and `set_wakeup_mode(True)`. Poll `awake()` every 200 ms for 60 seconds and print a timestamped line each time the board transitions asleep→awake (user picks up or taps the board) or awake→asleep (board settles). At the end, print the number of transitions observed and note that during "asleep" periods the accelerometer alone drew ~270 nA — roughly two orders of magnitude below the ~1.8 µA of the continuous 100 Hz measurement mode used by the Minimal `read()` example. This exercises `init`, `awake`, and the full activity/inactivity/link-mode/interrupt-mapping API.

## Timing Constraints

- **Power-up to standby:** ≤5 ms typical (100 Hz ODR, 50 Hz bandwidth test condition) before the first register access is guaranteed valid.
- **Measurement-mode instruction to valid data:** 4/ODR (e.g. 40 ms at 100 Hz, 320 ms at 12.5 Hz) after writing POWER_CTL.MEASURE=10.
- **Soft reset latency:** ≈0.5 ms after writing `0x52` to SOFT_RESET before the device is ready for further commands.
- **Self-test settle time:** wait 4/ODR after asserting or deasserting SELF_TEST.ST before reading the updated acceleration data.
- **Data-ready clear latency:** up to 80 µs delay between a data-register read (`0x08`–`0x0A` or `0x0E`–`0x15`) and STATUS.DATA_READY clearing.
- **SPI timing:** clock 2.4 kHz–8 MHz (the 2.4 kHz floor applies only to FIFO reads); CS setup ≥100 ns; CS hold ≥20 ns; CS disable ≥20 ns; data setup/hold ≥20 ns each; clock high/low ≥50 ns each; clock enable ≥25 ns; output valid from clock low ≤35 ns; output disable ≤25 ns.
- **External clock:** 25.6 kHz–51.2 kHz (nominal internal clock is 51.2 kHz); ODR and bandwidth scale proportionally to the supplied frequency (`ODR_actual = ODR_selected × f_ext / 51.2 kHz`).
- **External sample trigger (EXT_SAMPLE via INT2):** active-high pulse width ≥25 µs; deasserted ≥25 µs between pulses; maximum trigger frequency 625 Hz typical.
- **Power cycling (board-level, not firmware-controlled):** VS/VDDIO must discharge to ≤100 mV (V_RESET) and remain below it for ≥200 ms (hold time) before re-powering; rise time from 0 V to 1.6 V must be ≤600 µs (or ≤250 µs in the worst case of V_RESET=100 mV / hold=200 ms).

## Implementation Notes

- **Device ID triple check:** verify DEVID_AD=`0xAD`, DEVID_MST=`0x1D`, and PARTID=`0xF2` (362 in octal) after init; raise an error on any mismatch. Because this chip is SPI-only (no I²C mode to fall back into), a mismatch almost always means a wiring or SPI-mode problem, not an address conflict.
- **SPI-only, no I²C:** unlike the ADXL345 and many other ADI accelerometers, the ADXL362 has no I²C mode at all — there is no CS-tied-to-VDD trick to enable one.
- **Non-linear ±8 g sensitivity:** ±2 g→±4 g is an exact 2× sensitivity scale, but ±8 g (4.255 mg/LSB) is not 4× the ±2 g value (1 mg/LSB) due to analog front-end scaling. Always use the datasheet's per-range typical table (also given in Data Conversion), never a computed `2^n` multiplier.
- **8-bit registers trade resolution for bus/power cost:** XDATA/YDATA/ZDATA return only the top 8 of the 12 data bits (≈16-LSB granularity at the 12-bit scale). Useful for coarse, power-conscious polling via `read_8bit()`, not as a drop-in replacement for `read()`.
- **Temperature needs calibration for absolute accuracy:** both the bias (typical 350 LSB @ 25 °C, ±290 LSB standard deviation) and the sensitivity (typical 0.065 °C/LSB, ±0.0025 std dev) vary part-to-part. `temperature()` uses the typical values and should be treated as relative/uncalibrated unless the application measures and stores its own part-specific bias against a known reference, per the datasheet's Temperature Sensor section.
- **SEU protection and post-reset state:** `STATUS.ERR_USER_REGS` is expected to read 1 immediately after power-up or soft reset (the device isn't configured yet) and clears on the first write to any protected register (`0x20`–`0x2E`); don't treat that transient post-reset state as a fault.
- **FIFO reads have no address byte** and can be burst-read continuously at the SPI clock rate; always read an even number of bytes (each sample entry is 16 bits) — an odd byte count silently discards the second half of the last entry. Reading past the available entries returns `0x00` bytes, not an error.
- **Wake-up mode ignores TIME_ACT:** activity detection in wake-up mode is single-sample (no timer-based filtering); `set_activity_time()` only affects measurement mode.
- **Address folding at the 6-bit boundary:** the register address space is 64 registers (`0x00`–`0x3F`); addresses above 64 fold to `0x3F` (not to a repeat of `0x00`–`0x3F`), and burst auto-increment halts at `0x3F` rather than wrapping. No implemented register sits near this boundary (the highest used is `0x2E`), so this only matters if a caller manually issues an out-of-range burst.
- **Bus keepers hold last-driven state:** MOSI, SCLK, CS, INT1, and INT2 all have on-chip bus keepers, so they don't need external pull resistors to avoid floating — but this doesn't substitute for correct board-level SPI wiring.

## Sigrok Decoder

Decoder id `adxl362`, input `['spi']`. Decodes the three-command SPI protocol (`0x0A`=write, `0x0B`=read, `0x0D`=FIFO read) and annotates: the command byte and target register name (looked up from the register map) for register read/write transactions; decoded register contents for FILTER_CTL (range, HALF_BW, ODR in Hz), POWER_CTL (noise mode, wake-up/autosleep/measure state), ACT_INACT_CTL (link/loop mode name, referenced/absolute flags), STATUS (named flags), and INTMAP1/INTMAP2 (mapped source names + polarity); computed *g* values for XDATA/YDATA/ZDATA and XDATA_L…ZDATA_H reads (annotation pair `data_read_start`/`data_read_done` marks a data-register read for the data-ready-clear-latency conformance check); computed °C for TEMP_L/TEMP_H; per-entry axis + decoded value for FIFO reads (`0x0D`); and a `SOFT_RESET=0x52` write flagged distinctly from other register writes.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/accelerometer/adxl362.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/accelerometer/adxl362/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/accelerometer/adxl362/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/accelerometer/adxl362/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/accelerometer/adxl362_test.py` (MicroPython)
- [ ] Tests `python/tests/accelerometer/adxl362_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/accelerometer/adxl362_test_linux.py` (Linux)
- [ ] Unit test `python/tests/accelerometer/adxl362_test_unit.py` — deferred; no SPI-connection mock exists yet in this codebase (see `specs/testing_framework.md`, landed after this driver)

### UIFlow 1
- [ ] Manifest `python/uiflow1/accelerometer/adxl362/adxl362.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/accelerometer/adxl362/adxl362_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/accelerometer/adxl362/adxl362.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/accelerometer/adxl362/ADXL362.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color
- [ ] Exported `python/uiflow2/accelerometer/adxl362/ADXL362.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/accelerometer/ADXL362.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/accelerometer/ADXL362.cpp`
- [ ] Examples `cpp/examples/arduino/accelerometer/ADXL362/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/accelerometer/ADXL362/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/accelerometer/ADXL362/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/accelerometer/ADXL362/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/accelerometer/ADXL362/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/accelerometer/ADXL362/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/accelerometer/ADXL362/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/accelerometer/ADXL362/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/accelerometer/ADXL362/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/accelerometer/ADXL362/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/accelerometer/ADXL362/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/accelerometer/ADXL362/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/accelerometer/adxl362_test/adxl362_test.ino` (Arduino)
- [ ] Tests `cpp/tests/accelerometer/adxl362_test_linux/adxl362_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/accelerometer/adxl362_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/accelerometer/adxl362_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/accelerometer/adxl362_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Unit test `cpp/tests/accelerometer/adxl362_test_unit/adxl362_test_unit.cpp` — deferred; `cpp/test_linux.sh` and its `I2CConnectionMock` convention don't yet support SPI-transport chips (repo-wide gap, not specific to this chip)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/accelerometer/adxl362.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/accelerometer/adxl362/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/accelerometer/adxl362/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/accelerometer/adxl362/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/accelerometer/adxl362_test.js`
- [ ] Unit test `nodejs/tests/accelerometer/adxl362_test_unit.js` — deferred, same reason as Python/C++

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-accelerometer/nodes/adxl362/adxl362.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-accelerometer/nodes/adxl362/adxl362.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-accelerometer/examples/adxl362/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/accelerometer/adxl362.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/accelerometer/adxl362/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/accelerometer/adxl362/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/accelerometer/adxl362/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/accelerometer/adxl362_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/accelerometer/adxl362_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` — deferred, same reason as above

### Go
- [ ] Driver `go/periph/chips/accelerometer/adxl362.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/accelerometer/adxl362/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/accelerometer/adxl362/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/accelerometer/adxl362/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/accelerometer/adxl362/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/accelerometer/adxl362/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/accelerometer/adxl362/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/accelerometer/adxl362_test/main.go` — PASS/FAIL/===DONE=== protocol (Linux host)
- [ ] Tests `go/tests/accelerometer/adxl362_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/accelerometer/adxl362_test.go` — deferred, same reason as above

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/accelerometer/Adxl362Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/accelerometer/Adxl362Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/accelerometer/Adxl362Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/accelerometer/Adxl362Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/accelerometer/Adxl362Minimal.groovy` — Groovydoc + @CompileStatic
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/accelerometer/Adxl362Full.groovy` — Groovydoc + @CompileStatic
- [ ] Examples `jvm/examples/java/accelerometer/adxl362/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/accelerometer/adxl362/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/accelerometer/adxl362/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/accelerometer/adxl362/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/accelerometer/adxl362/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/accelerometer/adxl362/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/accelerometer/adxl362/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/accelerometer/adxl362/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/accelerometer/adxl362/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/accelerometer/adxl362/Adxl362Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/accelerometer/Adxl362Test.java` (JUnit) — deferred, same reason as above
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/accelerometer/Adxl362Test.kt` (Kotest/JUnit5) — deferred, same reason as above
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/accelerometer/Adxl362Spec.groovy` (Spock) — deferred, same reason as above

### Sigrok
- [ ] Decoder `sigrok/adxl362/__init__.py` — module docstring describing transport input (`spi`) and what is annotated
- [ ] Decoder `sigrok/adxl362/pd.py` — emits the `data_read_start`/`data_read_done` annotation pair the Sigrok Decoder section names, plus all named register/field/FIFO annotations

### Conformance
- [ ] Checker `conformance/accelerometer/adxl362_conformance.py` — see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/accelerometer/adxl362_timing.conf` — machine-readable mirror of this spec's Timing Constraints section

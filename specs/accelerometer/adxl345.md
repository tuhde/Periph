# Chip Spec: ADXL345

**Manufacturer:** Analog Devices  
**Datasheet:** `datasheets/accelerometer/adxl345.pdf`  
**Category:** accelerometer  
**Transports:** SPI | I²C

## Overview

The ADXL345 is a small, ultralow-power, 3-axis MEMS accelerometer with high resolution (13-bit) measurement at up to ±16 *g*. Acceleration data is output as 16-bit two's complement values accessible over SPI (3- or 4-wire) or I²C. The chip offers user-selectable measurement ranges (±2/±4/±8/±16 *g*) and a full-resolution mode that maintains a constant 3.9 mg/LSB scale factor across all ranges. A 32-level FIFO buffer, two configurable interrupt outputs, and built-in motion detection functions (tap, double-tap, activity/inactivity, free-fall) make it suitable for motion-triggered data logging, tilt sensing, and gesture detection.

## Transport Configuration

### I²C

- **Address:** `0x53` (SDO/ALT ADDRESS pin = GND, default) — `0x1D` (SDO/ALT ADDRESS pin = V_DD I/O)
- **Max clock:** 400 kHz

### SPI

- **Mode:** CPOL=1 CPHA=1 (Mode 3)
- **Max clock:** 5 MHz
- **Bit order:** MSB first
- **CS active:** low
- **Wire modes:** 4-wire (default) or 3-wire (SPI bit = 1 in DATA_FORMAT)

SPI frame: first byte = R/W | MB | A5 | A4 | A3 | A2 | A1 | A0, where R/W=1 for read, MB=1 for multi-byte transfer.

## Register Map

| Address | Name           | R/W | Reset      | Description                                  |
|---------|----------------|-----|------------|----------------------------------------------|
| `0x00`  | DEVID          | R   | `0xE5`     | Device ID (fixed 0xE5)                       |
| `0x1D`  | THRESH_TAP     | R/W | `0x00`     | Tap threshold, 62.5 mg/LSB                   |
| `0x1E`  | OFSX           | R/W | `0x00`     | X-axis offset, twos complement, 15.6 mg/LSB  |
| `0x1F`  | OFSY           | R/W | `0x00`     | Y-axis offset, twos complement, 15.6 mg/LSB  |
| `0x20`  | OFSZ           | R/W | `0x00`     | Z-axis offset, twos complement, 15.6 mg/LSB  |
| `0x21`  | DUR            | R/W | `0x00`     | Tap duration, 625 µs/LSB                     |
| `0x22`  | Latent         | R/W | `0x00`     | Tap latency, 1.25 ms/LSB                     |
| `0x23`  | Window         | R/W | `0x00`     | Double-tap window, 1.25 ms/LSB               |
| `0x24`  | THRESH_ACT     | R/W | `0x00`     | Activity threshold, 62.5 mg/LSB              |
| `0x25`  | THRESH_INACT   | R/W | `0x00`     | Inactivity threshold, 62.5 mg/LSB            |
| `0x26`  | TIME_INACT     | R/W | `0x00`     | Inactivity time, 1 sec/LSB                   |
| `0x27`  | ACT_INACT_CTL  | R/W | `0x00`     | Axis enable for activity/inactivity           |
| `0x28`  | THRESH_FF      | R/W | `0x00`     | Free-fall threshold, 62.5 mg/LSB             |
| `0x29`  | TIME_FF        | R/W | `0x00`     | Free-fall time, 5 ms/LSB                     |
| `0x2A`  | TAP_AXES       | R/W | `0x00`     | Axis enable for tap detection                 |
| `0x2B`  | ACT_TAP_STATUS | R   | `0x00`     | Source of tap/activity events                 |
| `0x2C`  | BW_RATE        | R/W | `0x0A`     | Data rate and power mode                      |
| `0x2D`  | POWER_CTL      | R/W | `0x00`     | Power-saving and measurement mode control     |
| `0x2E`  | INT_ENABLE     | R/W | `0x00`     | Interrupt enable                              |
| `0x2F`  | INT_MAP        | R/W | `0x00`     | Interrupt pin mapping (0=INT1, 1=INT2)        |
| `0x30`  | INT_SOURCE     | R   | `0x02`     | Interrupt source flags                        |
| `0x31`  | DATA_FORMAT    | R/W | `0x00`     | Data format control                           |
| `0x32`  | DATAX0         | R   | `0x00`     | X-axis data LSB                               |
| `0x33`  | DATAX1         | R   | `0x00`     | X-axis data MSB                               |
| `0x34`  | DATAY0         | R   | `0x00`     | Y-axis data LSB                               |
| `0x35`  | DATAY1         | R   | `0x00`     | Y-axis data MSB                               |
| `0x36`  | DATAZ0         | R   | `0x00`     | Z-axis data LSB                               |
| `0x37`  | DATAZ1         | R   | `0x00`     | Z-axis data MSB                               |
| `0x38`  | FIFO_CTL       | R/W | `0x00`     | FIFO control                                  |
| `0x39`  | FIFO_STATUS    | R   | `0x00`     | FIFO status                                   |

### Bit Fields

#### `DATA_FORMAT` (`0x31`)

| Bits | Name       | Description                                                          |
|------|------------|----------------------------------------------------------------------|
| 7    | SELF_TEST  | 1 = apply electrostatic self-test force                              |
| 6    | SPI        | 1 = 3-wire SPI; 0 = 4-wire SPI                                      |
| 5    | INT_INVERT | 0 = interrupts active high; 1 = active low                          |
| 4    | —          | Reserved, must be 0                                                  |
| 3    | FULL_RES   | 1 = full resolution (3.9 mg/LSB at all ranges); 0 = 10-bit mode     |
| 2    | Justify    | 1 = left-justified (MSB); 0 = right-justified with sign extension   |
| 1:0  | Range      | 00=±2 *g*; 01=±4 *g*; 10=±8 *g*; 11=±16 *g*                       |

#### `BW_RATE` (`0x2C`)

| Bits | Name      | Description                                     |
|------|-----------|-------------------------------------------------|
| 4    | LOW_POWER | 1 = low power (higher noise); 0 = normal        |
| 3:0  | Rate      | Output data rate (see table below)              |

Output data rate codes (Rate bits):

| Code   | ODR (Hz) |
|--------|----------|
| `0x0F` | 3200     |
| `0x0E` | 1600     |
| `0x0D` | 800      |
| `0x0C` | 400      |
| `0x0B` | 200      |
| `0x0A` | 100 (default) |
| `0x09` | 50       |
| `0x08` | 25       |
| `0x07` | 12.5     |
| `0x06` | 6.25     |

#### `POWER_CTL` (`0x2D`)

| Bits | Name       | Description                                                    |
|------|------------|----------------------------------------------------------------|
| 6    | Link       | 1 = activity/inactivity functions linked serially              |
| 5    | AUTO_SLEEP | 1 = auto-sleep when inactivity detected (requires Link=1)      |
| 4    | Measure    | 1 = measurement mode; 0 = standby mode                         |
| 3    | Sleep      | 1 = sleep mode (activity detection only at wakeup rate)        |
| 2:1  | Wakeup     | Sleep mode sample rate: 00=8 Hz, 01=4 Hz, 10=2 Hz, 11=1 Hz   |

#### `INT_ENABLE` / `INT_MAP` / `INT_SOURCE` (`0x2E` / `0x2F` / `0x30`)

| Bit | Function    |
|-----|-------------|
| 7   | DATA_READY  |
| 6   | SINGLE_TAP  |
| 5   | DOUBLE_TAP  |
| 4   | Activity    |
| 3   | Inactivity  |
| 2   | FREE_FALL   |
| 1   | Watermark   |
| 0   | Overrun     |

INT_MAP: 0 routes to INT1; 1 routes to INT2.

#### `ACT_INACT_CTL` (`0x27`)

| Bits | Name         | Description                                           |
|------|--------------|-------------------------------------------------------|
| 7    | ACT ac/dc    | 1 = ac-coupled; 0 = dc-coupled activity detection    |
| 6    | ACT_X enable | 1 = X axis participates in activity detection         |
| 5    | ACT_Y enable | 1 = Y axis participates                               |
| 4    | ACT_Z enable | 1 = Z axis participates                               |
| 3    | INACT ac/dc  | 1 = ac-coupled; 0 = dc-coupled inactivity detection  |
| 2    | INACT_X enable | 1 = X axis participates in inactivity detection     |
| 1    | INACT_Y enable | 1 = Y axis participates                             |
| 0    | INACT_Z enable | 1 = Z axis participates                             |

#### `TAP_AXES` (`0x2A`)

| Bits | Name     | Description                                        |
|------|----------|----------------------------------------------------|
| 3    | Suppress | 1 = suppress double-tap if acceleration present between taps |
| 2    | TAP_X enable | 1 = X axis participates in tap detection       |
| 1    | TAP_Y enable | 1 = Y axis participates                        |
| 0    | TAP_Z enable | 1 = Z axis participates                        |

#### `FIFO_CTL` (`0x38`)

| Bits | Name      | Description                                                |
|------|-----------|------------------------------------------------------------|
| 7:6  | FIFO_MODE | 00=Bypass, 01=FIFO, 10=Stream, 11=Trigger                 |
| 5    | Trigger   | 0 = trigger event linked to INT1; 1 = linked to INT2      |
| 4:0  | Samples   | Watermark level (FIFO/Stream) or retained samples (Trigger) |

## Initialization Sequence

1. Power up; device enters standby mode (POWER_CTL = 0x00).
2. Write `DATA_FORMAT` (`0x31`): set FULL_RES=1, Range=00 (±2 *g*) → value `0x08`.
3. Write `BW_RATE` (`0x2C`): set Rate=0x0A (100 Hz) → value `0x0A` (default, write optional).
4. Write `POWER_CTL` (`0x2D`): set Measure=1 → value `0x08`.
5. Wait at least one sample period (≥ 10 ms at 100 Hz) before reading data.

## Implementation Stages

### Minimal

Goal: read X, Y, Z acceleration in *g* with sensible defaults; no configuration required beyond the transport.

| Operation    | Parameters  | Returns | Notes |
|--------------|-------------|---------|-------|
| `init`       | transport   | —       | Writes DATA_FORMAT=0x08 (FULL_RES, ±2 g), POWER_CTL=0x08 (Measure); verifies DEVID=0xE5 |
| `read`       | —           | `tuple(float, float, float)` | Returns (x, y, z) in *g*; reads 6 bytes burst from 0x32 |

**Sensible defaults:** FULL_RES=1, Range=±2 *g*, ODR=100 Hz, FIFO bypass mode, all interrupts disabled, no offsets.

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation              | Parameters                                     | Returns | Notes |
|------------------------|------------------------------------------------|---------|-------|
| *(inherits Minimal)*   |                                                |         |       |
| `set_range`            | `range_g: int` (2, 4, 8, 16)                  | —       | Sets Range bits in DATA_FORMAT; FULL_RES stays 1 |
| `set_data_rate`        | `rate_hz: float` (6.25–3200)                  | —       | Sets BW_RATE Rate bits; nearest valid rate |
| `set_low_power`        | `enabled: bool`                               | —       | Sets LOW_POWER bit in BW_RATE |
| `set_offset`           | `x: float, y: float, z: float`               | —       | Offsets in *g* (±2 *g* max); converts to register scale (15.6 mg/LSB) and writes OFSX/OFSY/OFSZ |
| `calibrate_offset`     | `target_x: float=0.0, target_y: float=0.0, target_z: float=1.0` | — | Averages N samples, computes and writes offset registers to null sensor bias |
| `set_tap_detection`    | `threshold_g: float, duration_ms: float, axes: int=0x07, suppress: bool=False` | — | Configures THRESH_TAP, DUR, TAP_AXES for single-tap; enables SINGLE_TAP interrupt |
| `set_double_tap`       | `latency_ms: float, window_ms: float`         | —       | Configures Latent and Window registers; enables DOUBLE_TAP interrupt |
| `set_activity`         | `threshold_g: float, axes: int=0x70, ac_coupled: bool=True` | — | Configures THRESH_ACT and ACT_INACT_CTL |
| `set_inactivity`       | `threshold_g: float, time_sec: float, axes: int=0x07, ac_coupled: bool=False` | — | Configures THRESH_INACT, TIME_INACT, and ACT_INACT_CTL inactivity bits |
| `set_free_fall`        | `threshold_g: float, time_ms: float`          | —       | Configures THRESH_FF and TIME_FF; enables FREE_FALL interrupt |
| `set_interrupt`        | `source: int, enabled: bool, pin: int=1`      | —       | Sets/clears bit in INT_ENABLE; routes to INT1 or INT2 via INT_MAP |
| `read_interrupt_source`| —                                              | `int`   | Returns INT_SOURCE register byte; reading clears latched interrupts |
| `set_fifo_mode`        | `mode: int` (0=bypass, 1=FIFO, 2=stream, 3=trigger), `samples: int=16` | — | Configures FIFO_CTL |
| `read_fifo`            | —                                              | `list[tuple]` | Reads all available samples from FIFO; returns list of (x, y, z) tuples in *g* |
| `fifo_count`           | —                                              | `int`   | Returns number of samples available in FIFO (from FIFO_STATUS Entries bits) |
| `set_sleep`            | `enabled: bool, wakeup_hz: int=8`             | —       | Sets Sleep and Wakeup bits in POWER_CTL |
| `set_link_mode`        | `enabled: bool`                               | —       | Sets Link bit in POWER_CTL |
| `set_auto_sleep`       | `enabled: bool`                               | —       | Sets AUTO_SLEEP bit; requires Link=1 |
| `self_test`            | `enabled: bool`                               | —       | Sets/clears SELF_TEST bit in DATA_FORMAT |

**Additional configuration options:**
- Measurement range: ±2/±4/±8/±16 *g*
- Output data rate: 6.25 Hz to 3200 Hz
- Low-power mode
- Per-axis offset calibration
- Single/double-tap detection with configurable threshold, duration, latency, window
- Activity and inactivity detection with ac/dc coupling
- Free-fall detection
- 32-level FIFO in bypass/FIFO/stream/trigger mode
- Interrupt routing (INT1 / INT2), active-high or active-low
- Sleep, auto-sleep, and link mode for power management

## Data Conversion

**Full-resolution mode (FULL_RES=1, recommended):**
```
scale = 3.9e-3  # g/LSB, constant regardless of range
acceleration_g = raw_signed_16 * scale
```

**10-bit mode (FULL_RES=0):**
```
scale = {2: 3.9e-3, 4: 7.8e-3, 8: 15.6e-3, 16: 31.2e-3}[range_g]
acceleration_g = raw_signed_16 * scale
```

Data is read as 6 consecutive bytes starting at 0x32 (DATAX0). Each axis is little-endian 16-bit two's complement:
```
raw_x = int16(DATAX1 << 8 | DATAX0)
raw_y = int16(DATAY1 << 8 | DATAY0)
raw_z = int16(DATAZ1 << 8 | DATAZ0)
```

**Offset registers (OFSX/OFSY/OFSZ):**
```
offset_raw = round(offset_g / 15.6e-3)   # signed, clamp to [-128, 127]
```

**Tap threshold / Activity / Inactivity / Free-fall threshold registers:**
```
raw = round(threshold_g / 62.5e-3)   # unsigned 8-bit
```

## Node-RED

Node name: `periph-adxl345`  
Package: `node-red-contrib-periph-accelerometer`

| Input trigger | Output `msg.payload` fields            | Notes                         |
|---------------|----------------------------------------|-------------------------------|
| any message   | `{ x: float, y: float, z: float }`    | Acceleration in *g* per axis  |

Config panel fields:
- **Bus** — I²C bus number or SPI device path
- **Transport** — I²C / SPI selector
- **I²C address** — `0x53` (SDO=GND) or `0x1D` (SDO=VDD)
- **Range** — ±2 / ±4 / ±8 / ±16 *g* selector
- **Data rate** — ODR selector (6.25 Hz to 3200 Hz)

### Demo flow

An inject node triggers every 100 ms, feeding the `periph-adxl345` node. A function node computes the magnitude √(x²+y²+z²) and a debug node displays x, y, z, and magnitude. The flow demonstrates continuous tilt/orientation monitoring.

## Examples

### Demo

Mount the ADXL345 flat on a table. Print x, y, z, and the total vector magnitude at 10 Hz (100 ms interval). Tilt the board to show how the static gravity component shifts between axes. After 50 samples, print the minimum and maximum magnitude seen — ideally both close to 1.0 *g* — then exit. This exercises `init`, `read`, and demonstrates that full-resolution output tracks orientation continuously.

## Timing Constraints

- **Start-up:** device powers up in standby; write Measure bit before reading. Allow at least one ODR period after setting Measure before expecting valid data (≈ 10 ms at 100 Hz; turn-on time = 1/ODR + 1.1 ms typical).
- **Multi-byte burst reads:** always read all 6 data bytes (DATAX0–DATAZ1) in a single burst; single-byte reads of individual data registers can miss a simultaneous update and produce inconsistent X/Y/Z samples.
- **FIFO reads:** at least 5 µs must elapse between the end of one FIFO read (0x37) and the start of the next FIFO read, to allow FIFO to advance the next sample. At SPI speeds > 1.6 MHz, deassert CS before re-asserting for each FIFO entry.
- **Interrupt latency:** DATA_READY and watermark interrupts are latched and cleared by reading the data registers (0x32–0x37); all other interrupts are cleared by reading INT_SOURCE (0x30).
- **ODR vs. I²C speed:** maximum ODR at 400 kHz I²C is 800 Hz; maximum ODR at 100 kHz I²C is 200 Hz. Operating above these limits results in dropped samples.

## Implementation Notes

- **DEVID check:** read register 0x00 after init and verify it returns 0xE5 (= 345 octal); raise an error if not, as this catches wiring errors and wrong I²C address selection.
- **SPI multi-byte:** set the MB bit (bit 6) of the first address byte to 1 for burst reads; without MB the chip does not auto-increment the register pointer.
- **I²C mode selection:** the CS pin must be tied to V_DD I/O to enable I²C mode. With CS left floating, the chip has no defined default and may fail to respond.
- **SDO/ALT ADDRESS pin:** in I²C mode this pin selects the address (GND=0x53, VDD=0x1D); it must never be left floating.
- **Sign extension:** data registers are right-justified with sign extension in the default (FULL_RES or 10-bit right-justify) mode. Reading as a signed 16-bit integer handles this correctly without any masking.
- **Offset calibration:** the offset registers add to the output after all filtering. Calibration procedure: place sensor flat (Z-axis up), average ≥ 100 samples at low ODR, compute residual, then write (−residual / 15.6 mg/LSB) clamped to ±2 *g* into the offset registers.
- **Free-fall thresholds:** THRESH_FF values between 300 mg and 600 mg (codes 0x05–0x09) and TIME_FF values between 100 ms and 350 ms (codes 0x14–0x46) are recommended by the datasheet to avoid false triggers.
- **3-wire SPI:** set the SPI bit in DATA_FORMAT to 1 for 3-wire mode; the SDO pin becomes bidirectional SDIO. The chip must be wired accordingly.
- **Data rate at 3200/1600 Hz:** these ODRs are only recommended with SPI communication speeds ≥ 2 MHz. The output format is also different at these rates — see the "Data Formatting of Upper Data Rates" section of the datasheet for details; driver implementations should document this limitation.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/accelerometer/adxl345.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/accelerometer/adxl345/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/accelerometer/adxl345/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/accelerometer/adxl345/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/accelerometer/adxl345_test.py` (MicroPython)
- [ ] Tests `python/tests/accelerometer/adxl345_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/accelerometer/adxl345_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/accelerometer/ADXL345.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/accelerometer/ADXL345.cpp`
- [ ] Examples `cpp/examples/ADXL345_Minimal/ADXL345_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/ADXL345_Complete/ADXL345_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/ADXL345_Demo/ADXL345_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/ADXL345_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/ADXL345_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/ADXL345_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/accelerometer/adxl345_test/adxl345_test.ino` (Arduino)
- [ ] Tests `cpp/tests/accelerometer/adxl345_test_linux/adxl345_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/accelerometer/adxl345_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/accelerometer/adxl345.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/accelerometer/adxl345/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/accelerometer/adxl345/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/accelerometer/adxl345/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/accelerometer/adxl345_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-accelerometer/nodes/adxl345/adxl345.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-accelerometer/nodes/adxl345/adxl345.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-accelerometer/examples/adxl345/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/accelerometer/adxl345.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/adxl345_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/adxl345_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/adxl345_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/accelerometer/adxl345_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/accelerometer/adxl345_test_esp32s3/src/main.rs` (ESP32-S3)

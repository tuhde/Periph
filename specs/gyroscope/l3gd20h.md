# Chip Spec: L3GD20H (L3GD20)

**Manufacturer:** STMicroelectronics  
**Datasheet:** `datasheets/gyroscope/l3gd20h.pdf`  
**Category:** `gyroscope`  
**Transports:** I²C, SPI

## Overview

The L3GD20H (and its predecessor L3GD20) is a MEMS 3-axis digital gyroscope that measures angular rate on X, Y, and Z axes in the range ±250, ±500, or ±2000 dps. It outputs 16-bit two's-complement values and includes a relative 8-bit temperature sensor, a 32-slot FIFO, configurable high-pass filtering, and hardware interrupt outputs. The "H" variant differs from the L3GD20 only in WHO_AM_I (0xD7 vs 0xD4) and minor electrical specs; the register map and driver API are identical. Primary use case: orientation sensing, stabilization, and motion detection where absolute orientation is not needed.

## Transport Configuration

### I²C
- **Address:** `0x6A` (SA0/SDO = GND) — `0x6B` (SA0/SDO = VCC)
- **Max clock:** 400 kHz
- **CS pin:** must be tied HIGH to select I²C mode
- **Auto-increment:** bit 7 of the sub-address byte must be set for multi-byte burst reads in I²C

### SPI
- **Mode:** CPOL=1 CPHA=1 (Mode 3)
- **Max clock:** 10 MHz
- **Bit order:** MSB first
- **CS active:** low
- **First byte:** `[RW̄][M̄S̄][AD5..AD0]` — RW̄=1 for read, 0 for write; set M̄S̄=1 (bit 6) for multi-byte burst

## Register Map

| Address | Name          | R/W | Reset  | Description |
|---------|---------------|-----|--------|-------------|
| `0x0F`  | WHO_AM_I      | R   | `0xD4` / `0xD7` | Device ID: 0xD4 (L3GD20), 0xD7 (L3GD20H) |
| `0x20`  | CTRL_REG1     | R/W | `0x07` | ODR, bandwidth, power mode, axis enable |
| `0x21`  | CTRL_REG2     | R/W | `0x00` | High-pass filter mode and cutoff |
| `0x22`  | CTRL_REG3     | R/W | `0x00` | Interrupt enable (INT1/DRDY/FIFO/boot) |
| `0x23`  | CTRL_REG4     | R/W | `0x00` | Full-scale, BLE (endianness), BDU, SPI mode |
| `0x24`  | CTRL_REG5     | R/W | `0x00` | FIFO enable, HP filter enable, out select |
| `0x25`  | REFERENCE     | R/W | `0x00` | Reference value for interrupt generation |
| `0x26`  | OUT_TEMP      | R   | —      | Temperature output, 8-bit two's complement, 1 LSB/°C relative |
| `0x27`  | STATUS_REG    | R   | `0x00` | ZYXDA overrun/data-available flags |
| `0x28`  | OUT_X_L       | R   | —      | X-axis angular rate, low byte |
| `0x29`  | OUT_X_H       | R   | —      | X-axis angular rate, high byte |
| `0x2A`  | OUT_Y_L       | R   | —      | Y-axis angular rate, low byte |
| `0x2B`  | OUT_Y_H       | R   | —      | Y-axis angular rate, high byte |
| `0x2C`  | OUT_Z_L       | R   | —      | Z-axis angular rate, low byte |
| `0x2D`  | OUT_Z_H       | R   | —      | Z-axis angular rate, high byte |
| `0x2E`  | FIFO_CTRL_REG | R/W | `0x00` | FIFO mode and watermark threshold |
| `0x2F`  | FIFO_SRC_REG  | R   | `0x00` | FIFO status, watermark flag, sample count |
| `0x30`  | INT1_CFG      | R/W | `0x00` | Interrupt 1 configuration |
| `0x31`  | INT1_SRC      | R   | `0x00` | Interrupt 1 source |
| `0x32`  | INT1_TSH_XH   | R/W | `0x00` | X-axis threshold high byte |
| `0x33`  | INT1_TSH_XL   | R/W | `0x00` | X-axis threshold low byte |
| `0x34`  | INT1_TSH_YH   | R/W | `0x00` | Y-axis threshold high byte |
| `0x35`  | INT1_TSH_YL   | R/W | `0x00` | Y-axis threshold low byte |
| `0x36`  | INT1_TSH_ZH   | R/W | `0x00` | Z-axis threshold high byte |
| `0x37`  | INT1_TSH_ZL   | R/W | `0x00` | Z-axis threshold low byte |
| `0x38`  | INT1_DURATION | R/W | `0x00` | Interrupt duration |

### Bit Fields

#### `CTRL_REG1` (`0x20`)

| Bits | Name  | Description |
|------|-------|-------------|
| 7:6  | DR[1:0] | Output data rate: 00=95 Hz, 01=190 Hz, 10=380 Hz, 11=760 Hz |
| 5:4  | BW[1:0] | Bandwidth selection (cutoff depends on ODR; see Table 21 in datasheet) |
| 3    | PD    | Power-down: 0=power-down, 1=normal/sleep mode |
| 2    | Zen   | Z-axis enable |
| 1    | Yen   | Y-axis enable |
| 0    | Xen   | X-axis enable |

#### `CTRL_REG2` (`0x21`)

| Bits | Name      | Description |
|------|-----------|-------------|
| 5:4  | HPM[1:0]  | High-pass filter mode: 00=normal (reset by reading REFERENCE), 01=reference signal, 10=normal, 11=autoreset on interrupt |
| 3:0  | HPCF[3:0] | High-pass cutoff frequency selection (ODR-dependent) |

#### `CTRL_REG4` (`0x23`)

| Bits | Name    | Description |
|------|---------|-------------|
| 7    | BDU     | Block data update: 0=continuous, 1=hold until both bytes read |
| 6    | BLE     | Big/little endian: 0=LSB at lower address (default), 1=MSB at lower address |
| 5:4  | FS[1:0] | Full-scale: 00=±250 dps, 01=±500 dps, 10/11=±2000 dps |
| 0    | SIM     | SPI interface mode: 0=4-wire, 1=3-wire |

#### `CTRL_REG5` (`0x24`)

| Bits | Name       | Description |
|------|------------|-------------|
| 7    | BOOT       | Reboot memory content |
| 6    | FIFO_EN    | FIFO enable |
| 4    | HPen       | High-pass filter enable |
| 3:2  | INT1_Sel   | INT1 output source selection |
| 1:0  | OUT_Sel    | Output data selection (after HP/LP filters) |

#### `STATUS_REG` (`0x27`)

| Bits | Name   | Description |
|------|--------|-------------|
| 7    | ZYXOR  | X, Y, Z-axis data overrun |
| 6    | ZOR    | Z-axis data overrun |
| 5    | YOR    | Y-axis data overrun |
| 4    | XOR    | X-axis data overrun |
| 3    | ZYXDA  | X, Y, Z-axis new data available |
| 2    | ZDA    | Z-axis new data available |
| 1    | YDA    | Y-axis new data available |
| 0    | XDA    | X-axis new data available |

#### `FIFO_CTRL_REG` (`0x2E`)

| Bits | Name    | Description |
|------|---------|-------------|
| 7:5  | FM[2:0] | FIFO mode: 000=Bypass, 001=FIFO, 010=Stream, 011=Bypass-to-Stream, 111=Stream-to-FIFO |
| 4:0  | WTM[4:0]| Watermark threshold (0–31) |

## Initialization Sequence

1. Verify WHO_AM_I (`0x0F`) returns `0xD4` (L3GD20) or `0xD7` (L3GD20H); raise error otherwise
2. Write CTRL_REG1 (`0x20`): set PD=1 (normal mode), enable all axes (Zen=Yen=Xen=1), select ODR and bandwidth
3. Write CTRL_REG4 (`0x23`): set BDU=1, select full-scale
4. Wait 250 ms for gyroscope to stabilize after power-on (data unreliable during startup)

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits everything and adds the rest.

### Minimal

Goal: expose 3-axis angular rate with sensible defaults. No configuration required beyond the transport.

| Operation  | Parameters      | Returns               | Notes |
|------------|-----------------|-----------------------|-------|
| `init`     | transport       | —                     | WHO_AM_I check; power on, all axes enabled, BDU=1; 250 ms startup wait |
| `gyro`     | —               | `(float, float, float)` | Angular rate (x, y, z) in rad/s |

**Sensible defaults baked into Minimal:**
- ODR: 95 Hz (DR=00, BW=00)
- Full-scale: ±245 dps → use ±250 dps (FS=00); sensitivity 8.75 mdps/digit
- BDU = 1 (block data update — prevents reading of mismatched high/low bytes)
- All axes enabled

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation       | Parameters                                    | Returns                  | Notes |
|-----------------|-----------------------------------------------|--------------------------|-------|
| *(inherits Minimal)* | | | |
| `configure`     | `odr=0`, `bw=0`, `full_scale=0`              | —                        | ODR: 0–3 (95/190/380/760 Hz); BW: 0–3 (datasheet Table 21); full_scale: 0=±250, 1=±500, 2=±2000 dps |
| `gyro_raw`      | —                                             | `(int, int, int)`        | Raw 16-bit signed (x, y, z) |
| `temperature`   | —                                             | `int`                    | Relative temperature, 1 LSB/°C, 8-bit signed; not calibrated absolute |
| `data_ready`    | —                                             | `bool`                   | True if ZYXDA bit set in STATUS_REG |
| `configure_hp_filter` | `mode=0`, `cutoff=0`                   | —                        | HPM[1:0] and HPCF[3:0] in CTRL_REG2 |
| `enable_hp_filter` | `enable=True`                             | —                        | HPen bit in CTRL_REG5 |
| `configure_fifo` | `mode=0`, `watermark=0`                     | —                        | FM[2:0] and WTM[4:0] in FIFO_CTRL_REG |
| `enable_fifo`   | `enable=True`                                | —                        | FIFO_EN bit in CTRL_REG5 |
| `fifo_level`    | —                                             | `int`                    | Number of unread samples in FIFO (0–31) |
| `read_fifo`     | —                                             | `list[(float,float,float)]` | Read all available FIFO samples, each in rad/s |
| `set_power_mode` | `mode`                                       | —                        | `'normal'` (PD=1, all axes on), `'sleep'` (PD=1, all axes off), `'power_down'` (PD=0) |

**Additional configuration options:**
- ODR: 95 / 190 / 380 / 760 Hz
- Bandwidth: 4 settings per ODR (see datasheet Table 21 for exact cutoff frequencies)
- Full-scale: ±250 / ±500 / ±2000 dps
- High-pass filter: 4 modes, 10 cutoff settings per ODR
- FIFO: 5 modes, 32-slot depth, watermark threshold

## Data Conversion

### Angular rate (dps → rad/s)

Sensitivity depends on full-scale selection (CTRL_REG4 FS[1:0]):

| FS[1:0] | Full-scale | Sensitivity     |
|---------|------------|-----------------|
| `00`    | ±250 dps   | 8.75 mdps/digit |
| `01`    | ±500 dps   | 17.50 mdps/digit |
| `10/11` | ±2000 dps  | 70.0 mdps/digit |

```
angular_rate_dps = raw_signed_16 * sensitivity_mdps_per_digit / 1000.0
angular_rate_rad_s = angular_rate_dps * π / 180.0
```

Output registers are 16-bit two's complement. With BLE=0 (default): low byte at lower address, high byte at higher address. With BDU=1: both bytes held until both are read, preventing split reads.

To combine bytes (BLE=0, default little-endian):
```
raw = (OUT_H << 8) | OUT_L
# interpret as signed 16-bit
if raw >= 0x8000: raw -= 0x10000
```

### Temperature

OUT_TEMP is an 8-bit two's complement value with 1 LSB/°C sensitivity. It is not calibrated to an absolute reference — it represents change from the device's power-on temperature baseline. Do not convert to absolute Celsius; expose as a signed integer.

## Node-RED

Node name: `periph-l3gd20h`  
Package: `node-red-contrib-periph-gyroscope`

| Input trigger | Output `msg.payload` fields                        | Notes |
|---------------|----------------------------------------------------|-------|
| any message   | `{ x: float, y: float, z: float }`                | Angular rates in rad/s |

Config panel fields: I²C address (0x6A / 0x6B), bus number, full-scale selection (±250/±500/±2000 dps), ODR (95/190/380/760 Hz).

### Demo flow

Inject node (repeat every 100 ms) → `periph-l3gd20h` → Function node (format `"ω: x=%.3f y=%.3f z=%.3f rad/s"`) → Debug node. Demonstrates continuous angular rate streaming at 10 Hz update rate.

## Examples

### Demo

Gyroscope "shake detector": configure at 190 Hz, ±500 dps; continuously poll angular rate; compute vector magnitude `sqrt(x²+y²+z²)`; if magnitude exceeds a threshold (e.g. 1.0 rad/s) print "SHAKE DETECTED" with the magnitude and timestamp, otherwise print the three axis values at ~10 Hz. This makes the detector visually interesting when rotating or shaking the device.

Use the Full class: call `configure(odr=1, bw=0, full_scale=1)` (190 Hz, ±500 dps), `data_ready()` to gate reads, and `gyro()` to get calibrated rad/s values.

## Timing Constraints

- **Power-on to stable data:** 250 ms (power-down to normal mode)
- **ODR 95 Hz:** new data available every ~10.5 ms
- **ODR 190 Hz:** new data available every ~5.3 ms
- **ODR 380 Hz:** new data available every ~2.6 ms
- **ODR 760 Hz:** new data available every ~1.3 ms
- **BDU=1:** guarantees consistent high/low byte pairing; must read both bytes before next update is latched

## Implementation Notes

- **WHO_AM_I dual value:** L3GD20 returns `0xD4`; L3GD20H returns `0xD7`. Accept both as valid; raise a descriptive error for any other value.
- **I²C auto-increment:** In I²C mode, set bit 7 of the sub-address to enable address auto-increment for multi-byte reads (e.g., reading all 6 output registers in one transaction). Equivalent to the SPI MS bit.
- **SPI multi-byte:** Set bit 6 of the address byte (MS=1) for burst reads.
- **BDU strongly recommended:** Without BDU=1, the OUT_X_H/L bytes can belong to different measurements if an update occurs between the two reads. Always set BDU=1 in `init`.
- **Sleep vs power-down:** Sleep (PD=1, axes disabled) preserves register state and resumes quickly; power-down (PD=0) saves more power but requires a full 250 ms re-initialization.
- **Temperature is relative:** Do not expose as absolute Celsius. The datasheet does not define a zero-degree reference; OUT_TEMP drifts with junction temperature. Expose the raw signed integer only.
- **No absolute calibration needed in Minimal:** gyro() output in rad/s is suitable for orientation tracking without user-supplied offsets; any static bias can be measured by the user by averaging at rest.

## Sigrok Decoder

The `l3gd20h` decoder matches I²C addresses `0x6A` and `0x6B` (or any SPI CS channel). It annotates each register write with the register name and decoded field values (e.g., "CTRL_REG1: ODR=190Hz BW=50Hz PD=normal Zen=1 Yen=1 Xen=1"), and each burst read of OUT_X/Y/Z as computed angular rates in rad/s for all three axes. Temperature reads from OUT_TEMP are annotated as signed integers. STATUS_REG reads annotate the ZYXDA and ZYXOR flags. FIFO reads annotate the sample count and each sample's computed rate values.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/gyroscope/l3gd20h.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/gyroscope/l3gd20h/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/gyroscope/l3gd20h/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/gyroscope/l3gd20h/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/gyroscope/l3gd20h_test.py` (MicroPython)
- [ ] Tests `python/tests/gyroscope/l3gd20h_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/gyroscope/l3gd20h_test_linux.py` (Linux)

### C++
- [ ] Driver `cpp/src/chips/gyroscope/L3gd20h.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/gyroscope/L3gd20h.cpp`
- [ ] Examples `cpp/examples/L3gd20h_Minimal/L3gd20h_Minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/L3gd20h_Complete/L3gd20h_Complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/L3gd20h_Demo/L3gd20h_Demo.ino` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/L3gd20h_Minimal_Zephyr/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/L3gd20h_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/L3gd20h_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/gyroscope/l3gd20h_test/l3gd20h_test.ino` (Arduino)
- [ ] Tests `cpp/tests/gyroscope/l3gd20h_test_linux/l3gd20h_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/gyroscope/l3gd20h_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/gyroscope/l3gd20h.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/gyroscope/l3gd20h/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/gyroscope/l3gd20h/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/gyroscope/l3gd20h/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/gyroscope/l3gd20h_test.js`

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-gyroscope/nodes/l3gd20h/l3gd20h.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-gyroscope/nodes/l3gd20h/l3gd20h.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-gyroscope/examples/l3gd20h/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/gyroscope/l3gd20h.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/l3gd20h_minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/l3gd20h_complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/l3gd20h_demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/gyroscope/l3gd20h_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/gyroscope/l3gd20h_test_esp32s3/src/main.rs` (ESP32-S3)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gyroscope/L3gd20hMinimal.java`
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gyroscope/L3gd20hFull.java`
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gyroscope/L3gd20hMinimal.kt`
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gyroscope/L3gd20hFull.kt`
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gyroscope/L3gd20hMinimal.groovy`
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gyroscope/L3gd20hFull.groovy`
- [ ] Examples `jvm/examples/java/gyroscope/l3gd20h/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/gyroscope/l3gd20h/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/gyroscope/l3gd20h/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/gyroscope/l3gd20h/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/gyroscope/l3gd20h/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/gyroscope/l3gd20h/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/gyroscope/l3gd20h/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/gyroscope/l3gd20h/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/gyroscope/l3gd20h/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/gyroscope/l3gd20h/L3gd20hTest.java`

### Sigrok
- [ ] Decoder `sigrok/l3gd20h/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/l3gd20h/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` only

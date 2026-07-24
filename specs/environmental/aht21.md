# Chip Spec: AHT21

**Manufacturer:** ASAIR (Guangzhou Aosong Electronics)  
**Datasheet:** `datasheets/environmental/aht21.pdf`  
**Category:** environmental  
**Transports:** I²C

> **Note:** The AHT21 measures temperature and humidity only (no pressure). It is placed in the `environmental` category as the closest fit for a combined T+RH sensor.

## Overview

The AHT21 is a fully calibrated digital temperature and humidity sensor in a compact 3×3×0.8 mm SMD package. An on-chip ASIC processes raw MEMS capacitive humidity and temperature element signals and outputs calibrated values directly over I²C — no host-side calibration or signal processing required. Designed for high volume applications, it supports supply voltages from 2.2–5.5 V and operates across -40 to 120 °C.

## Transport Configuration

### I²C
- **Address:** `0x38` (fixed — not configurable)
- **Max clock:** 400 kHz (Fast Mode); Standard Mode 100 kHz also supported
- **Note:** SCL has no minimum frequency. Data collection cycle should be ≥1 second to avoid self-heating errors.

## Command Set

The AHT21 uses a command-based protocol rather than a register map. All interaction is via one-byte commands (with optional parameter bytes).

| Command | Bytes sent | Description |
|---------|------------|-------------|
| Read status | *(read 1 byte)* | Returns status byte at any time |
| `0xAC` `0x33` `0x00` | 3 | Trigger measurement |
| `0xBA` | 1 | Soft reset — returns device to idle; wait ≥20 ms |
| `0x1B` `0x00` `0x00` | 3 | Calibration init register 1 (only if uncalibrated) |
| `0x1C` `0x00` `0x00` | 3 | Calibration init register 2 (only if uncalibrated) |
| `0x1E` `0x00` `0x00` | 3 | Calibration init register 3 (only if uncalibrated) |

### Status Byte

Returned as the first byte of any read transaction, or when reading immediately after addressing the device.

| Bit | Name | Description |
|-----|------|-------------|
| 7 | BUSY | 1=device busy (measurement in progress); 0=idle/ready |
| 6:4 | — | Reserved |
| 3 | CAL | 1=calibrated; 0=uncalibrated |
| 2:0 | — | Reserved |

Check: `(status & 0x18) == 0x18` confirms device is idle and calibrated.

## Initialization Sequence

1. Wait ≥100 ms after power-on (SCL should be held high during this time)
2. Read 1 byte (status): send I²C address `0x38` with read bit
3. If `(status & 0x18) != 0x18`: send soft reset `0xBA`, wait ≥20 ms, then re-check; if still uncalibrated write calibration init commands `0x1B`, `0x1C`, `0x1E` (each followed by `0x00 0x00`), waiting 10 ms between each

## Measurement Sequence

1. Send trigger command: write `0xAC 0x33 0x00` to address `0x38`
2. Wait ≥80 ms
3. Read 6 bytes (7 bytes to include CRC)
4. Check byte 0 bit 7 (BUSY): if 1, wait additional time and retry read
5. Decode bytes 1–5 into humidity and temperature values

### Response Frame (6 bytes, 7 with CRC)

| Byte | Content |
|------|---------|
| 0 | Status |
| 1 | RH raw bits [19:12] |
| 2 | RH raw bits [11:4] |
| 3 | RH raw bits [3:0] (upper nibble) \| T raw bits [19:16] (lower nibble) |
| 4 | T raw bits [15:8] |
| 5 | T raw bits [7:0] |
| 6 | CRC-8 (optional) |

## Implementation Stages

Each chip is implemented in two stages. The Full class extends Minimal — it inherits everything and adds the rest.

### Minimal

Goal: trigger a measurement and return temperature and humidity with no configuration required beyond the transport.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | transport | — | Power-on wait, status check, calibration check; soft reset if needed |
| `read` | — | `{temperature_c, humidity_pct}` | Triggers measurement, waits ≥80 ms, decodes and returns both values |

**Sensible defaults baked into Minimal:**
- No CRC verification (reduces complexity; CRC check is Full-only)
- Measurement triggered on every `read()` call (no continuous mode)
- 80 ms fixed wait after trigger (no busy-polling)

### Full

Goal: expose all device capabilities. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_temperature` | — | float | °C only |
| `read_humidity` | — | float | %RH only |
| `read_with_crc` | — | `{temperature_c, humidity_pct, crc_ok}` | Reads 7 bytes; verifies CRC-8 |
| `soft_reset` | — | — | Sends 0xBA, waits 20 ms |
| `is_calibrated` | — | bool | Returns true if CAL bit set in status |
| `is_busy` | — | bool | Returns true if BUSY bit set in status |

**Additional capabilities:**
- CRC verification (polynomial x^8 + x^5 + x^4 + 1 = 0x31, initial value 0xFF)
- Explicit soft reset
- Calibration status inspection

## Data Conversion

### Raw value extraction from 6-byte response

```
raw_rh = (byte1 << 12) | (byte2 << 4) | (byte3 >> 4)    # 20-bit value
raw_t  = ((byte3 & 0x0F) << 16) | (byte4 << 8) | byte5  # 20-bit value
```

### Humidity

```
rh_pct = (raw_rh / 1048576.0) * 100.0    # 2^20 = 1048576
Range: 0–100 %RH
Resolution: 0.024 %RH  (100 / 2^20 * 256 ≈ 0.024)
```

### Temperature

```
temp_c = (raw_t / 1048576.0) * 200.0 - 50.0
Range: -50 to 150 °C (sensor specified -40 to 120 °C)
Resolution: ~0.01 °C
Example: raw_t = 0x6B700 → (439040 / 1048576) * 200 - 50 = 33.8 °C
```

### CRC-8

```
Polynomial: x^8 + x^5 + x^4 + 1  (0x31)
Initial value: 0xFF
Covers: bytes 0–5 of the response frame
Compare computed CRC against byte 6
```

## Node-RED

Node name: `periph-aht21`  
Package: `node-red-contrib-periph-environmental`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature_c, humidity_pct }` | Triggers one measurement per input message |

Config panel fields: I²C bus number.

### Demo flow

Inject node fires every 5 seconds → AHT21 node → Function node formats a display string ("25.3 °C / 48.1 %RH") → Debug node. A second branch feeds two Gauge dashboard widgets (temperature and humidity) for live visual monitoring.

## Examples

### Demo

A weather station logger. The demo initializes the sensor, prints a startup message showing calibration status, then loops every 5 seconds reading temperature and humidity. Each reading is printed with a timestamp and a computed dew point (derived from T and RH using the Magnus formula). Why-comments explain the mandatory 80 ms measurement wait, the calibration check, and the dew point formula.

## Timing Constraints

| Event | Time | Notes |
|-------|------|-------|
| Power-on to ready | ≥100 ms | Hold SCL high during this period |
| Trigger to data ready | ≥80 ms | Busy bit (byte 0 bit 7) may be polled after this delay |
| Soft reset recovery | ≥20 ms | |
| Minimum measurement interval | ≥1 s | More frequent reads cause self-heating, raising temperature readings |
| Temperature response time (τ63) | 5–30 s | Depends on thermal conductivity of substrate |
| Humidity response time (τ63) | ~8 s | At 25°C, 1 m/s airflow |

## Implementation Notes

- **Fixed I²C address:** The AHT21 has no address-select pin. Only one AHT21 can be on a single I²C bus.
- **Calibration init bytes:** The datasheet refers to the ASAIR website for the exact calibration register contents; the sequence `0x1B/0x00/0x00`, `0x1C/0x00/0x00`, `0x1E/0x00/0x00` is the commonly used pattern from open-source implementations. In practice, most AHT21 modules ship pre-calibrated and the CAL bit is set at power-on without any intervention.
- **Self-heating:** The sensor's own power dissipation raises the temperature slightly. The datasheet recommends measurement intervals ≥1 second and I²C clock between 10–400 kHz to keep self-heating to ≤0.1°C. Do not use the sensor in a sealed air pocket on the PCB.
- **Busy polling:** The 80 ms wait is the minimum. If busy polling is implemented, poll in a loop with short delays until bit 7 of status byte = 0. A timeout of ~200 ms is a reasonable guard.
- **6 vs 7 bytes:** Reading 6 bytes skips CRC. Reading 7 bytes includes the CRC byte. If only 6 bytes are requested, the sensor sends NACK on the 7th byte ACK — this is expected and not an error.
- **Post-reflow rehydration:** After soldering, store the sensor at 25°C / >75%RH for 12–72 hours (or 60–85°C / >85%RH for 2–6 hours) to restore humidity calibration accuracy.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] Driver `python/periph/chips/environmental/aht21.py` — Google-style docstring on every class and public method
- [x] Examples `python/examples/environmental/aht21/minimal.py` — Tier-1 signature comment on every call
- [x] Examples `python/examples/environmental/aht21/complete.py` — Tier-1 + Tier-2
- [x] Examples `python/examples/environmental/aht21/demo.py` — Tier-1 + Tier-3
- [x] Tests `python/tests/environmental/aht21_test.py` (MicroPython)
- [x] Tests `python/tests/environmental/aht21_test_cp.py` (CircuitPython)
- [x] Tests `python/tests/environmental/aht21_test_linux.py` (Linux)

### C++
- [x] Driver `cpp/src/chips/environmental/AHT21.h` — Doxygen `/** @brief */` on every class and public method
- [x] Driver `cpp/src/chips/environmental/AHT21.cpp`
- [x] Examples `cpp/examples/AHT21_Minimal/AHT21_Minimal.ino` — Tier-1
- [x] Examples `cpp/examples/AHT21_Complete/AHT21_Complete.ino` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/AHT21_Demo/AHT21_Demo.ino` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/AHT21_Minimal_Zephyr/src/main.cpp` — Tier-1
- [x] Examples `cpp/examples/AHT21_Complete_Zephyr/src/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/AHT21_Demo_Zephyr/src/main.cpp` — Tier-1 + Tier-3
- [x] Tests `cpp/tests/environmental/aht21_test/aht21_test.ino` (Arduino)
- [x] Tests `cpp/tests/environmental/aht21_test_linux/aht21_test_linux.cpp` (Linux GCC)
- [x] Tests `cpp/tests/environmental/aht21_test_zephyr/src/main.cpp` (Zephyr)

### Node.js
- [x] Driver `nodejs/packages/periph/src/chips/environmental/aht21.js` — JSDoc on every class and exported method
- [x] Examples `nodejs/packages/periph/examples/environmental/aht21/minimal.js` — Tier-1
- [x] Examples `nodejs/packages/periph/examples/environmental/aht21/complete.js` — Tier-1 + Tier-2
- [x] Examples `nodejs/packages/periph/examples/environmental/aht21/demo.js` — Tier-1 + Tier-3
- [x] Tests `nodejs/tests/environmental/aht21_test.js`

### Node-RED
- [x] Node runtime `nodejs/packages/node-red-contrib-periph-environmental/nodes/aht21/aht21.js`
- [x] Node editor `nodejs/packages/node-red-contrib-periph-environmental/nodes/aht21/aht21.html` — `data-help-name` section with inputs, outputs, and config description
- [x] Demo flow `nodejs/packages/node-red-contrib-periph-environmental/examples/aht21/demo.json` — tab `info` field describes the scenario

### Rust
- [x] Driver `rust/periph/src/chips/environmental/aht21.rs` — `//!` module doc + `///` on every `pub` item
- [x] Examples `rust/examples/aht21_minimal/src/main.rs` — Tier-1
- [x] Examples `rust/examples/aht21_complete/src/main.rs` — Tier-1 + Tier-2
- [x] Examples `rust/examples/aht21_demo/src/main.rs` — Tier-1 + Tier-3
- [x] Tests `rust/tests/environmental/aht21_test/src/main.rs` (Linux)
- [x] Tests `rust/tests/environmental/aht21_test_esp32s3/src/main.rs` (ESP32-S3)

### JVM
- [x] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/environmental/Aht21Minimal.java` — Javadoc on every class and public method
- [x] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/environmental/Aht21Full.java` — Javadoc on every class and public method
- [x] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/environmental/Aht21Minimal.kt` — KDoc on every class and public method
- [x] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/environmental/Aht21Full.kt` — KDoc on every class and public method
- [x] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/environmental/Aht21Minimal.groovy` — Groovydoc on every class and public method
- [x] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/environmental/Aht21Full.groovy` — Groovydoc on every class and public method
- [x] Examples `jvm/examples/java/environmental/aht21/Minimal.java` — Tier-1
- [x] Examples `jvm/examples/java/environmental/aht21/Complete.java` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/java/environmental/aht21/Demo.java` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/kotlin/environmental/aht21/Minimal.kt` — Tier-1
- [x] Examples `jvm/examples/kotlin/environmental/aht21/Complete.kt` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/kotlin/environmental/aht21/Demo.kt` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/groovy/environmental/aht21/Minimal.groovy` — Tier-1
- [x] Examples `jvm/examples/groovy/environmental/aht21/Complete.groovy` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/groovy/environmental/aht21/Demo.groovy` — Tier-1 + Tier-3
- [x] Tests `jvm/tests/environmental/aht21/Aht21Test.java`
- [x] Tests `jvm/tests/environmental/aht21/Aht21Test.kt`
- [x] Tests `jvm/tests/environmental/aht21/Aht21Test.groovy`

### Sigrok
- [x] Decoder `sigrok/aht21/pd.py`
- [x] Init `sigrok/aht21/__init__.py`

### Go
- [x] Driver `go/periph/chips/environmental/aht21.go` — Go doc comment on every exported type and method
- [x] Examples `go/examples/environmental/aht21/minimal/main.go` — Tier-1 signature comment on every call
- [x] Examples `go/examples/environmental/aht21/complete/main.go` — Tier-1 + Tier-2
- [x] Examples `go/examples/environmental/aht21/demo/main.go` — Tier-1 + Tier-3
- [x] Examples `go/examples/environmental/aht21/minimal_tinygo/main.go` — Tier-1
- [x] Examples `go/examples/environmental/aht21/complete_tinygo/main.go` — Tier-1 + Tier-2
- [x] Examples `go/examples/environmental/aht21/demo_tinygo/main.go` — Tier-1 + Tier-3
- [x] Tests `go/tests/environmental/aht21_test/main.go` (Linux)
- [x] Tests `go/tests/environmental/aht21_test_tinygo/main.go` (TinyGo / Pico W)

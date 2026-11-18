# Chip Spec: SCD41

**Manufacturer:** Sensirion  
**Datasheet:** `datasheets/gas/scd4x.pdf` (Sensirion SCD4x datasheet — CO₂ sensors SCD40 and SCD41, Version 1.1, April 2021; shared with `scd40`)  
**Category:** gas  
**Transports:** I²C

## Overview

The SCD41 is a photoacoustic NDIR CO₂ sensor (PASens) with an integrated Sensirion SHT4x humidity and temperature sensor, in a small LGA package. The SCD41 is the high-accuracy variant: specified range 400–5 000 ppm with ±(40 ppm + 5 % of reading) accuracy. Humidity accuracy is ±6 %RH (15–35 °C, 20–65 %RH), temperature accuracy ±0.8 °C (15–35 °C). The CO₂ output range is 0–40 000 ppm; repeatability is ±10 ppm; the response time (τ63 %) is 60 s for CO₂. In the default periodic mode a new sample is available every 5 s; a low-power periodic mode updates about every 30 s. Built-in on-chip compensation uses a configurable temperature offset, sensor altitude or an ambient pressure that may be fed continuously; automatic self-calibration (ASC, enabled by default, assumes ≥ 400 ppm fresh air at least once a week) and forced recalibration (FRC) keep the sensor accurate. Unlike the SCD40 it also supports a low-power **single-shot** mode (on-demand measurement, 243 mJ per CO₂ measurement, 0.45 mA average at 3.3 V for one measurement every 5 minutes). Interface: I²C, 16-bit commands, 16-bit data words each followed by a CRC-8. Supply 2.4–5.5 V (3.3 V or 5 V typical), 175 mA peak at 3.3 V (115 mA at 5 V), 15 mA average in periodic mode and 3.2 mA in low-power mode at 3.3 V. Typical uses: indoor air quality, demand-controlled ventilation, HVAC, IoT environment monitors.

## Transport Configuration

### I²C
- **Address:** `0x62` (7-bit, fixed; one sensor per bus)
- **Max clock:** 100 kHz (standard mode only)
- **Command format:** 16-bit command, MSB first, written in one transaction; commands with an argument append a 16-bit word and its CRC-8. Reads return 16-bit words (MSB first), each followed by a CRC-8 (polynomial `0x31`, init `0xFF`, no reflection, no final XOR; `CRC(0xBEEF) = 0x92`). Checksums are mandatory in the write direction and verified by the driver in the read direction
- **Register addressing:** none (16-bit command pointers, no register file); for a read, write the command, wait the command execution time (table below), then read the response. Commands must not be sent while a previous command is being processed
- **Identity register:** none; the 48-bit serial number (`get_serial_number`, `0x3682`) identifies the chip but is a command, not a register. Registered in `registry/chips.json` with `id_probe: null`
- **Probe safety:** `write_sensitive` — every written byte sequence is a command (`0x3646` reinit, `0x3632` factory reset, `0x21b1` start measurement, …). A probe should only write the read-only command `0x3682` (idle mode only) and read the response

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| SCL, SDA | — | I²C, open-drain, external pull-ups (10 kΩ typical); the controller only drives low |
| VDD, VDDH | — | must be connected to the same supply (2.4–5.5 V, ripple ≤ 30 mV p-p); low-noise LDO recommended, large peak currents |
| GND | — | ground |
| DNC | — | do not connect (soldered to a floating pad) |

## Command Set

All commands are 16-bit; the "execution time" is the minimum wait between writing the command and reading the response (or before the next command).

| Command | Code | Type | Execution time | During measurement | Description |
|---------|------|------|----------------|--------------------|-------------|
| `start_periodic_measurement` | `0x21b1` | send | — | no | Start periodic measurement, new data every 5 s |
| `read_measurement` | `0xec05` | read (9 bytes) | 1 ms | yes | CO₂ (word 0), temperature (word 1), humidity (word 2), each + CRC; returns NACK on the read header if no new data is ready |
| `stop_periodic_measurement` | `0x3f86` | send | 500 ms | yes | Return to idle |
| `set_temperature_offset` | `0x241d` | write (1 word) | 1 ms | no | Offset in °C × 65536 / 175; default 4 °C |
| `get_temperature_offset` | `0x2318` | read | 1 ms | no | Read back |
| `set_sensor_altitude` | `0x2427` | write (1 word) | 1 ms | no | Metres above sea level (default 0) |
| `get_sensor_altitude` | `0x2322` | read | 1 ms | no | Read back |
| `set_ambient_pressure` | `0xe000` | write (1 word) | 1 ms | yes | Pressure in Pa / 100 (hPa); overrides the altitude compensation; may be sent during periodic measurement |
| `perform_forced_recalibration` | `0x362f` | write (1 word) + read | 400 ms | no | Target concentration in ppm; the response is `0x8000 + correction` (ppm), `0xffff` = failed |
| `set_automatic_self_calibration_enabled` | `0x2416` | write (1 word) | 1 ms | no | `0x0001` on (default), `0x0000` off |
| `get_automatic_self_calibration_enabled` | `0x2313` | read | 1 ms | no | Read back |
| `start_low_power_periodic_measurement` | `0x21ac` | send | — | no | Low-power periodic mode, new data about every 30 s |
| `get_data_ready_status` | `0xe4b8` | read | 1 ms | yes | Data ready when the least significant 11 bits of the word are not all 0 |
| `persist_settings` | `0x3615` | send | 800 ms | no | Store offset, altitude and ASC setting in the EEPROM (≥ 2000 write cycles — use sparingly) |
| `get_serial_number` | `0x3682` | read (3 words) | 1 ms | no | 48-bit serial number, `word0 << 32 \| word1 << 16 \| word2` |
| `perform_self_test` | `0x3639` | read | 10 000 ms | no | Response `0x0000` = no malfunction |
| `perform_factory_reset` | `0x3632` | send | 1200 ms | no | Reset EEPROM settings and the FRC/ASC history to factory values |
| `reinit` | `0x3646` | send | 20 ms | no | Re-read the settings from the EEPROM (soft reset) |
| `measure_single_shot` | `0x219d` | send | 5000 ms | no | On-demand CO₂, temperature and humidity measurement (SCD41 only); read with `read_measurement` after the execution time |
| `measure_single_shot_rht_only` | `0x2196` | send | 50 ms | no | On-demand temperature and humidity only (SCD41 only); the CO₂ word reads 0 ppm |

Settings written with the setter commands live in RAM until `persist_settings` is sent. Settings may only be read or written in idle mode (after `stop_periodic_measurement`, wait 500 ms), except `set_ambient_pressure`. While a periodic measurement runs only `read_measurement`, `get_data_ready_status`, `stop_periodic_measurement` and `set_ambient_pressure` may be sent.

## Initialization Sequence

1. Power VDD/VDDH (2.4–5.5 V); the sensor reaches idle within 1000 ms (VDD ≥ 2.25 V).
2. Send `stop_periodic_measurement` (`0x3f86`) and wait 500 ms (a sensor left in measurement mode by a previous run is put back to idle; harmless in idle).
3. Optionally read the serial number (`0x3682`) as a presence check, set temperature offset / altitude (idle only), and `persist_settings` if the values must survive power cycles.
4. Start periodic measurement (`0x21b1`) or low-power periodic (`0x21ac`), or leave the sensor idle and use the single-shot commands (`0x219d`, `0x2196`).
5. Poll `get_data_ready_status` (`0xe4b8`) (or wait ≥ 5 s the first time); when ready send `read_measurement` (`0xec05`), wait 1 ms, read 9 bytes, verify the three CRCs and convert.

## Implementation Stages

### Minimal

Goal: read CO₂, temperature and humidity with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Stores the plain `Connection`; stops any running measurement (wait 500 ms), reads the serial number as a presence check, starts the periodic measurement (5 s interval) |
| `read` | — | `{co2_ppm, temperature_c, humidity_rh}` | Waits for the data-ready status (polls `0xe4b8` every 100 ms for up to 6 s), sends `read_measurement`, verifies the CRCs, converts |

**Sensible defaults:** standard periodic measurement (5 s), ASC enabled (the chip default), temperature offset 4 °C (chip default, not rewritten), altitude 0 m, no pressure compensation.

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_co2` / `read_temperature` / `read_humidity` | — | float | One value of a fresh measurement |
| `data_ready` | — | bool | `0xe4b8` |
| `read_measurement` | — | `(co2_ppm, temperature_c, humidity_rh)` | `0xec05` without waiting (call after `data_ready`) |
| `start_periodic` | — | — | `0x21b1` |
| `start_low_power_periodic` | — | — | `0x21ac` (about 30 s updates) |
| `stop_periodic` | — | — | `0x3f86`, waits 500 ms |
| `set_temperature_offset` / `get_temperature_offset` | `kelvin: float` / — | — / float | `0x241d` / `0x2318`; value × 65536 / 175; idle only (the driver stops and restarts a running measurement) |
| `set_altitude` / `get_altitude` | `metres: int` / — | — / int | `0x2427` / `0x2322` |
| `set_ambient_pressure` | `pressure_pa: int` | — | `0xe000` with `round(pressure_pa / 100)` hPa (allowed during measurement); the word must fit 16 bits (the datasheet gives no further range) |
| `set_auto_calibration` / `get_auto_calibration` | `enabled: bool` / — | — / bool | `0x2416` / `0x2313` |
| `force_recalibration` | `ppm: int` | int | `0x362f`; returns the FRC correction in ppm (`word − 0x8000`); raises on `0xffff` |
| `persist_settings` | — | — | `0x3615` (EEPROM write, wait 800 ms) |
| `serial_number` | — | int | `0x3682`, 48-bit |
| `self_test` | — | bool | `0x3639` (blocks 10 s), True if no malfunction |
| `factory_reset` | — | — | `0x3632` (wait 1.2 s) |
| `reinit` | — | — | `0x3646` (wait 20 ms) |
| `measure_single_shot` | — | `(co2_ppm, temperature_c, humidity_rh)` | `0x219d`, waits 5 s, reads the measurement; only in idle mode (the driver stops a running periodic measurement first) |
| `measure_single_shot_rht_only` | — | `(temperature_c, humidity_rh)` | `0x2196`, waits 50 ms, reads the measurement (CO₂ word is 0) |
| `read_word` / `write_word` | `command: int` / `command: int, value: int` | int / — | Raw command access with the CRC handled (diagnostics) |

**Additional configuration options:** periodic vs. low-power periodic mode, single-shot mode (CO₂ + RHT or RHT only), temperature offset, altitude, ambient pressure, ASC on/off, FRC, persisted settings.

## Data Conversion

```
# read_measurement response: 9 bytes = 3 words, each word followed by CRC-8 (poly 0x31, init 0xFF)
co2_ppm       = word[0]
temperature_c = -45 + 175 * word[1] / 65536
humidity_rh   = 100 * word[2] / 65536

# setters
temperature offset word = offset_c * 65536 / 175        # get: offset_c = 175 * word / 65536
altitude word           = metres above sea level
ambient pressure word   = pressure_pa / 100             # hPa
FRC response            = word - 0x8000  ppm            # 0xffff = failed
serial number           = word0 << 32 | word1 << 16 | word2
```

Example: response `01 F4 7B 66 67 A2 5E B9 3C` = 500 ppm, 25 °C, 37 %RH. Example setter: temperature offset 5.4 °C = `0x07E6`, CRC `0x48`. CO₂ accuracy: ±(40 ppm + 5 % of reading) at 400–5 000 ppm at 25 °C, 50 %RH. All units are SI (ppm as mole fraction; pressure in pascals in the API, converted to hPa for the chip).

## Node-RED

Node name: `periph-scd41`  
Package: `node-red-contrib-periph-gas`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ co2_ppm, temperature_c, humidity_rh }` | latest measurement (waits for the next sample if none is ready) |

Config panel fields: I²C bus number, measurement mode (periodic / low power), temperature offset, altitude.

### Demo flow

Inject node (every 5 s) → `periph-scd41` → function node classifying the CO₂ level (good < 1000 ppm, poor > 1500 ppm) → gauge and debug nodes.

## Examples

### Demo

**Room air-quality monitor.** With the Full driver print the serial number and the settings (temperature offset, altitude, ASC), read CO₂, temperature and humidity every 5 s for 30 s with a CO₂ classification (good/moderate/poor), set a 4 K temperature offset and show its effect on temperature/humidity, set altitude and then ambient-pressure compensation, show `data_ready`, switch to low-power periodic mode and take one sample (about 30 s), enable/disable ASC, demonstrate `persist_settings` (documented, skipped by default to spare the EEPROM) and forced recalibration (documented, skipped unless a reference gas is attached), take a single-shot CO₂ measurement and an RHT-only single shot (discarding the first two readings after power-up) and compare with the periodic values, run the self test and finish with `reinit`. Exercises `init`, `read`, `read_co2`, `read_temperature`, `read_humidity`, `data_ready`, `read_measurement`, `start_periodic`, `start_low_power_periodic`, `stop_periodic`, `set_temperature_offset`, `get_temperature_offset`, `set_altitude`, `get_altitude`, `set_ambient_pressure`, `set_auto_calibration`, `get_auto_calibration`, `force_recalibration`, `persist_settings`, `serial_number`, `self_test`, `factory_reset` (documented, skipped), `reinit`, `measure_single_shot`, `measure_single_shot_rht_only`, `read_word`, `write_word`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| Power-up to idle | ≤ 1000 ms (VDD ≥ 2.25 V) |
| After `reinit` | ≤ 1000 ms (soft reset time; command execution time 20 ms) |
| Periodic measurement interval | 5 s (default), about 30 s (low power) |
| Command execution time | 1 ms (reads/writes), 500 ms (`stop_periodic_measurement`), 400 ms (FRC), 800 ms (`persist_settings`), 1200 ms (factory reset), 10 000 ms (self test), 5000 ms (`measure_single_shot`), 50 ms (`measure_single_shot_rht_only`) |
| I²C clock | ≤ 100 kHz |
| FRC | operate in the target mode for > 3 minutes in a homogeneous, constant CO₂ concentration before `perform_forced_recalibration` |

The execution times are minimum host waits from the datasheet; there is no bus-decodable start/end pair with a datasheet bound, so `scd41_timing.conf` defines no checks.

## Implementation Notes

- **Plain Connection, command protocol:** commands are 16-bit and written with `write`; responses are read with `read` in a separate transaction after the execution time. Do not use a register-addressed connection (no register file).
- **CRC on every word:** compute the CRC-8 (`0x31`, init `0xFF`) for each 16-bit word written, and verify it for each word read; reject the whole read on a CRC error.
- **Data-ready before reading:** `read_measurement` returns a NACK on the read header when no new data is available, and a measurement can be read only once; poll `get_data_ready_status` (11 low bits ≠ 0) first. The driver reads once per sample.
- **Idle-only configuration:** settings commands are only accepted in idle mode; `stop_periodic_measurement` takes 500 ms. The Full driver tracks the running mode and transparently stops and restarts the measurement around setter/getter calls, and documents the 500 ms and the lost sample.
- **EEPROM wear:** `persist_settings` is only sent on request (at least 2000 writes guaranteed). Unpersisted settings are lost at power-cycle (and `reinit` restores the stored values).
- **ASC needs fresh air:** the algorithm assumes the sensor sees 400 ppm outdoor air at least once a week (about 7 days of operation); in sealed rooms disable ASC and use FRC, or CO₂ readings drift low. The first readings after power-up have reduced accuracy until the sensor has warmed up and (for the single-shot mode) the first two readings are discarded.
- **Temperature offset only affects T/RH:** the offset does not change the CO₂ value; determine it inside the final enclosure in thermal equilibrium and in the operating mode that will be used (self-heating differs between periodic, low-power and single-shot modes). Best T/RH accuracy is reached in low-power periodic mode.
- **Pressure vs. altitude:** `set_ambient_pressure` overrides the altitude compensation until the next power cycle/`reinit`; the driver converts pascals to hPa because the chip's argument unit is hPa and never exposes hPa.
- **FRC:** needs the sensor to have operated (not freshly powered) in the target mode for > 3 minutes at a homogeneous known concentration, `stop_periodic_measurement` + 500 ms before the command, and returns `0xffff` on failure.
- **Supply:** peak current is 175 mA at 3.3 V; use a low-noise LDO (≤ 30 mV ripple) and connect VDD and VDDH together.
- **Single-shot mode (SCD41):** send `measure_single_shot` in idle mode, wait 5 s, then `read_measurement`; the two first readings after a power-up should be discarded. ASC is optimised for one single shot every 5 minutes; longer intervals make self-calibration less frequent and no self-calibration runs if the sensor is power-cycled between shots. `measure_single_shot_rht_only` returns 0 ppm for CO₂.
- **Presence check:** there is no ID register; use `get_serial_number` (idle only) to confirm presence, and probe with `active=True` only (a write is interpreted as a command).

## Sigrok Decoder

Decoder id `scd41`, input `['i2c']`. Matches I²C address `0x62`. Annotates every 16-bit command with its name, decodes arguments with CRC verification (temperature offset, altitude, ambient pressure, ASC flag, FRC target) and the data-ready status, the 9-byte measurement into CO₂ (ppm), temperature (°C) and humidity (%RH) with a CRC check on every word, the serial number, the FRC correction and the self-test result; warns on CRC errors, on commands that are not allowed during a periodic measurement (anything but `read_measurement`, `get_data_ready_status`, `stop_periodic_measurement`, `set_ambient_pressure`), on a read issued earlier than the execution time after its command, and on a `0xffff` FRC result. No conformance annotation pair is emitted (no checked timing constraint).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/gas/scd41.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/gas/scd41/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/gas/scd41/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/gas/scd41/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/gas/scd41_test.py` (MicroPython)
- [ ] Tests `python/tests/gas/scd41_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/gas/scd41_test_linux.py` (Linux)
- [ ] Unit test `python/tests/gas/scd41_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/gas/scd41/scd41.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/gas/scd41/scd41_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/gas/scd41/scd41.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/gas/scd41/Scd41.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/gas/scd41/Scd41.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/gas/Scd41.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/gas/Scd41.cpp`
- [ ] Examples `cpp/examples/arduino/gas/Scd41/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/gas/Scd41/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/gas/Scd41/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/gas/Scd41/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/gas/Scd41/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/gas/Scd41/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/gas/Scd41/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/gas/Scd41/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/gas/Scd41/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/gas/Scd41/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/gas/Scd41/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/gas/Scd41/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/gas/Scd41/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/gas/Scd41/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/gas/Scd41/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/gas/Scd41/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/gas/Scd41/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/gas/Scd41/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/gas/scd41_test/scd41_test.ino` (Arduino)
- [ ] Tests `cpp/tests/gas/scd41_test_linux/scd41_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/gas/scd41_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/gas/scd41_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/gas/scd41_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/gas/scd41_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/gas/scd41_test_unit/scd41_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/gas/scd41.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/gas/scd41/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/gas/scd41/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/gas/scd41/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/gas/scd41_test.js`
- [ ] Unit test `nodejs/tests/gas/scd41_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-gas/nodes/scd41/scd41.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-gas/nodes/scd41/scd41.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-gas/examples/scd41/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/gas/scd41.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/gas/scd41/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/gas/scd41/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/gas/scd41/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/gas/scd41_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/gas/scd41_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/gas/scd41.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/gas/scd41.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/gas/scd41/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/gas/scd41/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/gas/scd41/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/gas/scd41/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/scd41/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/gas/scd41/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/gas/scd41_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/gas/scd41_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/gas/scd41_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Scd41Minimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/gas/Scd41Full.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Scd41Minimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/gas/Scd41Full.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Scd41Minimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/gas/Scd41Full.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/gas/scd41/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/gas/scd41/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/gas/scd41/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/gas/scd41/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/gas/scd41/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/gas/scd41/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/gas/scd41/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/gas/scd41/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/gas/scd41/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/gas/scd41/Scd41Test.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/gas/Scd41Test.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/gas/Scd41Test.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/gas/Scd41Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/scd41/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/scd41/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [ ] Checker `conformance/gas/scd41_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/gas/scd41_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [ ] Decoder `sigrok/scd41/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

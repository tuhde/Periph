# Chip Spec: SPL06-007

**Manufacturer:** Goertek  
**Datasheet:** `datasheets/pressure/spl06.pdf` (SPL06-007 Digital pressure sensor, Version 1.0, 2017-05-17)  
**Category:** pressure  
**Transports:** I²C

## Overview

The SPL06-007 is a small digital barometric pressure and temperature sensor (300–1100 hPa, absolute accuracy ±1 hPa, relative ±6 Pa, noise 0.5 Pa RMS in standard mode) with a 24-bit ADC, factory calibration coefficients, a 32-entry result FIFO and a programmable interrupt. Measurement rate (1–128 Hz) and oversampling (1× to 128×) are set independently for pressure and temperature; the host reads raw 24-bit two's-complement values and compensates them with the 18-byte coefficient block (c0, c1, c00, c10, c01, c11, c20, c21, c30). Three operating modes: standby (default), command (one pressure or temperature measurement) and background (continuous, optionally via the FIFO). It supports I²C (up to 3.4 MHz), 3-wire and 4-wire SPI; this spec covers I²C. Typical uses: weather stations, indoor navigation and floor detection, altimeters, drones, wearables. Supply 1.7–3.6 V (VDD), I/O 1.2–3.6 V.

## Transport Configuration

### I²C
- **Address:** `0x77` (default; `SDO` high or floating, 7-bit `1110111`) or `0x76` (`SDO` low); `CSB` must be tied high (or left open: internal pull-up) to select I²C
- **Max clock:** 3.4 MHz (the driver uses ≤ 400 kHz)
- **Register addressing:** 8-bit register address, auto-increment on multi-byte reads/writes; the 24-bit results are big-endian across three consecutive registers
- **Identity register:** `ID` (`0x0D`) = `0x10` (`PROD_ID = 0x1`, `REV_ID = 0x0`); registered in `registry/chips.json` as `id_probe` register `0x0D`, `reg_bytes` 1, `length` 1, mask `0xFF`, expected `[0x10]`. The DPS310 uses the same `0x0D`/`0x10` ID layout and addresses
- **Probe safety:** `register_pointer` — a 1-byte write only sets the register pointer (the `RESET` register `0x0C` is only changed by a full write)

SPI (3-wire/4-wire, up to 10 MHz, address bit 7 = read) and the 3-wire `SPI_MODE` bit in `CFG_REG` are out of scope for this spec; the interrupt pin shares `SDO`, so it is **not available in 4-wire SPI** and in I²C it requires `SDO` to be used as INT, see Pin Configuration.

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| SDO | — | I²C: address select (`0x77` high / `0x76` low) **and**, when `CFG_REG.INT_*` is set, the interrupt output (active level `INT_HL`); the address pin has to be hard-wired to the level that matches the wanted address |
| CSB | — | tie high (VDDIO) or leave open for I²C |
| SDA, SCK | — | I²C data/clock; pull-ups required |
| VDD, VDDIO, GND | — | 1.7–3.6 V core, 1.2–3.6 V I/O |

## Register Map

| Address | Name | R/W | Reset | Description |
|---------|------|-----|-------|-------------|
| `0x00` | PRS_B2 | R | `0x00` | Pressure bits 23:16 |
| `0x01` | PRS_B1 | R | `0x00` | Pressure bits 15:8 |
| `0x02` | PRS_B0 | R | `0x00` | Pressure bits 7:0 |
| `0x03` | TMP_B2 | R | `0x00` | Temperature bits 23:16 |
| `0x04` | TMP_B1 | R | `0x00` | Temperature bits 15:8 |
| `0x05` | TMP_B0 | R | `0x00` | Temperature bits 7:0 |
| `0x06` | PRS_CFG | R/W | `0x00` | Pressure rate and oversampling |
| `0x07` | TMP_CFG | R/W | `0x00` | Temperature source, rate and oversampling |
| `0x08` | MEAS_CFG | R/W | `0x00` | Mode, ready flags |
| `0x09` | CFG_REG | R/W | `0x00` | Interrupt, result shift, FIFO enable, SPI mode |
| `0x0A` | INT_STS | R | `0x00` | Interrupt status (cleared on read) |
| `0x0B` | FIFO_STS | R | `0x00` | FIFO empty/full |
| `0x0C` | RESET | W | `0x00` | Soft reset and FIFO flush |
| `0x0D` | ID | R | `0x10` | Product and revision ID |
| `0x10`–`0x21` | COEF | R | factory | Calibration coefficients (18 bytes) |

### Bit Fields

#### `PRS_CFG` (`0x06`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | — | reserved |
| 6:4 | PM_RATE | background rate: `000` 1, `001` 2, `010` 4, `011` 8, `100` 16, `101` 32, `110` 64, `111` 128 measurements per second |
| 3:0 | PM_PRC | oversampling: `0000` 1× (single), `0001` 2×, `0010` 4×, `0011` 8×, `0100` 16×, `0101` 32×, `0110` 64×, `0111` 128× (16× and above need `CFG_REG.P_SHIFT = 1`) |

#### `TMP_CFG` (`0x07`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | TMP_EXT | 0 = temperature from the ASIC sensor, 1 = from the pressure MEMS element (**the datasheet requires the external sensor setting, `1`, which the coefficients c0/c1 refer to**) |
| 6:4 | TMP_RATE | background rate, same encoding as `PM_RATE` |
| 3 | — | reserved |
| 2:0 | TMP_PRC | oversampling, same encoding as `PM_PRC` (`000` = 1× … `111` = 128×; above 8× needs `CFG_REG.T_SHIFT = 1`) |

#### `MEAS_CFG` (`0x08`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | COEF_RDY | 1 = coefficients can be read (≤ 40 ms after power-up) |
| 6 | SENSOR_RDY | 1 = sensor initialisation complete (≤ 12 ms after power-up) |
| 5 | TMP_RDY | 1 = temperature result ready (cleared on read of the result; command mode) |
| 4 | PRS_RDY | 1 = pressure result ready |
| 3 | — | reserved |
| 2:0 | MEAS_CTRL | `000` idle / stop background; `001` pressure (command); `010` temperature (command); `101` continuous pressure; `110` continuous temperature; `111` continuous pressure and temperature |

#### `CFG_REG` (`0x09`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | INT_HL | interrupt active level on SDO: 0 = low, 1 = high |
| 6 | INT_FIFO | interrupt when the FIFO is full |
| 5 | INT_PRS | interrupt when a pressure result is ready |
| 4 | INT_TMP | interrupt when a temperature result is ready |
| 3 | T_SHIFT | shift temperature results right (required for TMP_PRC above 8×) |
| 2 | P_SHIFT | shift pressure results right (required for PM_PRC above 8×) |
| 1 | FIFO_EN | enable the FIFO (results are read from `PRS_B2..0` in order) |
| 0 | SPI_MODE | 0 = 4-wire SPI, 1 = 3-wire SPI (unused for I²C) |

#### `INT_STS` (`0x0A`, cleared on read)

| Bits | Name | Description |
|------|------|-------------|
| 2 | INT_FIFO_FULL | FIFO interrupt active |
| 1 | INT_TMP | temperature interrupt active |
| 0 | INT_PRS | pressure interrupt active |

#### `FIFO_STS` (`0x0B`)

| Bits | Name | Description |
|------|------|-------------|
| 1 | FIFO_FULL | the FIFO is full |
| 0 | FIFO_EMPTY | the FIFO is empty |

#### `RESET` (`0x0C`)

| Bits | Name | Description |
|------|------|-------------|
| 7 | FIFO_FLUSH | write 1 to flush the FIFO |
| 3:0 | SOFT_RST | write `1001` (`0x09`) to soft-reset the chip |

#### Calibration coefficients (`0x10`–`0x21`, two's complement)

| Coefficient | Width | Address / bits |
|-------------|-------|----------------|
| c0 | 12 | `0x10` [11:4], `0x11` [7:4] = [3:0] |
| c1 | 12 | `0x11` [3:0] = [11:8], `0x12` [7:0] |
| c00 | 20 | `0x13` [19:12], `0x14` [11:4], `0x15` [7:4] = [3:0] |
| c10 | 20 | `0x15` [3:0] = [19:16], `0x16` [15:8], `0x17` [7:0] |
| c01 | 16 | `0x18`, `0x19` |
| c11 | 16 | `0x1A`, `0x1B` |
| c20 | 16 | `0x1C`, `0x1D` |
| c21 | 16 | `0x1E`, `0x1F` |
| c30 | 16 | `0x20`, `0x21` |

## Initialization Sequence

1. Power up VDD/VDDIO; wait for `MEAS_CFG.SENSOR_RDY` and `COEF_RDY` (≤ 12 ms and ≤ 40 ms).
2. Read the soft-reset result by checking `ID = 0x10`; optionally write `RESET = 0x09` and wait for the ready flags again.
3. Read the 18 coefficient bytes (`0x10`–`0x21`) in one transaction and sign-extend them.
4. Write `TMP_CFG` with `TMP_EXT = 1` and the wanted rate/oversampling, `PRS_CFG`, and `CFG_REG` with `P_SHIFT`/`T_SHIFT` for oversampling above 8×.
5. Start a measurement: command mode (`MEAS_CFG = 0x02` temperature, then `0x01` pressure, each polled via `TMP_RDY`/`PRS_RDY`) or background mode (`MEAS_CFG = 0x07`).

## Interrupt

| Property | Value |
|----------|-------|
| INT pin | `SDO` — shared with the I²C address select; active level set by `CFG_REG.INT_HL` |
| Level | 2 (selectable sources) |
| Condition(s) | pressure ready, temperature ready, FIFO full |
| Clear mechanism | read `INT_STS` (cleared on read) |

### Interrupt sources

| Constant | Value | Condition |
|----------|-------|-----------|
| `SOURCE_PRESSURE` | `0x01` | `INT_STS.INT_PRS`: a pressure result is ready |
| `SOURCE_TEMPERATURE` | `0x02` | `INT_STS.INT_TMP`: a temperature result is ready |
| `SOURCE_FIFO_FULL` | `0x04` | `INT_STS.INT_FIFO_FULL`: the FIFO is full |

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback, int_pin=None)` | Subscribe on the interrupt pin (`connection.int_pin` or `int_pin`, edge matching `INT_HL`), otherwise a polling task calling `poll_interrupt`; `callback(status)` receives the masked `INT_STS` value |
| `off_interrupt` | `off_interrupt()` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> int` | Reads `INT_STS` (clears it) and returns the masked sources (0 = none) |
| `enable_interrupt` | `enable_interrupt(source)` | Sets `CFG_REG.INT_PRS` / `INT_TMP` / `INT_FIFO` |
| `disable_interrupt` | `disable_interrupt(source)` | Clears the bit |

## Implementation Stages

### Minimal

Goal: read pressure and temperature with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | `connection` | — | Stores the register-addressed `Connection`; verifies `ID = 0x10`, waits for `SENSOR_RDY`/`COEF_RDY`, reads and decodes the coefficients, sets `TMP_CFG` = `0x80` (external sensor, 1×), `PRS_CFG` = `0x04` (16×, standard precision), `CFG_REG` = `0x04` (`P_SHIFT`) |
| `read` | — | `{temperature_c, pressure_pa}` | Command mode: temperature measurement (≈ 4 ms), then pressure measurement (28 ms typ., 35 ms max at 16×); compensated results, temperature in °C and pressure in pascals |

**Sensible defaults:** standby between reads (command mode, no background measurement), pressure 16× (standard mode, 0.5 Pa RMS noise), temperature 1×, external temperature sensor.

### Full

Goal: complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_pressure` | — | float | Pressure in Pa (temperature measured first for the compensation) |
| `read_temperature` | — | float | Temperature in °C |
| `read_raw` | — | `(praw, traw)` | Raw 24-bit two's-complement values from the result registers |
| `set_pressure_config` | `oversampling: int = 16`, `rate: int = 1` | — | Writes `PRS_CFG` and the `P_SHIFT` bit; raises for values other than powers of two 1–128 |
| `set_temperature_config` | `oversampling: int = 1`, `rate: int = 1` | — | Writes `TMP_CFG` (always `TMP_EXT = 1`) and `T_SHIFT` |
| `start_continuous` | `pressure: bool = True`, `temperature: bool = True` | — | `MEAS_CTRL = 101/110/111` (background mode) |
| `stop` | — | — | `MEAS_CTRL = 000` (standby) |
| `read_continuous` | — | `{temperature_c, pressure_pa}` | Latest background results (waits for `PRS_RDY`/`TMP_RDY`) without starting a measurement |
| `data_ready` | — | `(pressure_ready, temperature_ready)` | `MEAS_CFG` flags |
| `enable_fifo` / `disable_fifo` | — | — | `CFG_REG.FIFO_EN`; with the FIFO, results are read from `PRS_B2..0` in the measured order |
| `read_fifo` | — | `list[(kind, value)]` | Drains the FIFO (up to 32 entries) until `FIFO_EMPTY`; `kind` is `"pressure"` or `"temperature"` (LSB of the raw value distinguishes them), values compensated |
| `flush_fifo` | — | — | `RESET.FIFO_FLUSH` |
| `fifo_status` | — | `(empty, full)` | `FIFO_STS` |
| `reset` | — | — | `RESET = 0x09`, waits for the ready flags, re-reads the coefficients |
| `coefficients` | — | `dict` | Decoded c0, c1, c00, c10, c01, c11, c20, c21, c30 |
| `compensate` | `praw: int`, `traw: int`, `p_oversampling: int`, `t_oversampling: int` | `{temperature_c, pressure_pa}` | Pure computation with the stored coefficients |
| `altitude` | `sea_level_pa: float = 101325.0` | float | Altitude in m from one pressure reading (barometric formula) |
| interrupt API | | | see Interrupt section |

**Additional configuration options:** pressure/temperature rate and oversampling, background vs. command mode, FIFO, interrupt sources and polarity, temperature source (external only).

## Data Conversion

```
# results: 24-bit two's complement
raw = (B2 << 16) | (B1 << 8) | B0 ; if raw & 0x800000: raw -= 1 << 24

# scale factor kP / kT from the oversampling rate (Table 4)
#   1x 524288, 2x 1572864, 4x 3670016, 8x 7864320,
#   16x 253952, 32x 516096, 64x 1040384, 128x 2088960
Praw_sc = Praw / kP
Traw_sc = Traw / kT

# temperature (deg C)
Tcomp = c0 * 0.5 + c1 * Traw_sc

# pressure (Pa)
Pcomp = c00 + Praw_sc * (c10 + Praw_sc * (c20 + Praw_sc * c30))
      + Traw_sc * c01 + Traw_sc * Praw_sc * (c11 + Praw_sc * c21)

# altitude (international barometric formula, SI units)
altitude_m = 44330 * (1 - (pressure_pa / sea_level_pa) ** (1 / 5.255))
```

The pressure compensation uses the temperature scaled with the **temperature** oversampling (`kT`), so a temperature measurement must precede or accompany each pressure reading. The coefficients c0/c1 are 12-bit and c00/c10 20-bit two's-complement values assembled as in the coefficient table. Resolution: 0.06 Pa and 0.01 °C.

## Node-RED

Node name: `periph-spl06`  
Package: `node-red-contrib-periph-pressure`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature_c, pressure_pa }` | °C and Pa, one measurement per input message |

Config panel fields: I²C bus number, address (`0x76` / `0x77`), pressure oversampling.

### Demo flow

Inject node (every 2 s) → `periph-spl06` → function node computing the altitude → gauge and debug nodes.

## Examples

### Demo

**Barometric altimeter and variometer.** With the Full driver print the ID and the decoded coefficients, read temperature and pressure once per second for 20 s with the altitude relative to the first reading, compare noise and measurement time at 1×, 16× and 128× oversampling (ten readings each, with the shift bits), run background mode at 8 Hz and print `read_continuous`, collect 32 results through the FIFO and `read_fifo`, use the data-ready interrupt (`on_interrupt`) to print results, demonstrate `compensate` with raw values, and finish with `stop`, `flush_fifo` and `reset`. Exercises `init`, `read`, `read_pressure`, `read_temperature`, `read_raw`, `set_pressure_config`, `set_temperature_config`, `start_continuous`, `stop`, `read_continuous`, `data_ready`, `enable_fifo`, `disable_fifo`, `read_fifo`, `flush_fifo`, `fifo_status`, `reset`, `coefficients`, `compensate`, `altitude`, `on_interrupt`, `off_interrupt`, `poll_interrupt`, `enable_interrupt`, `disable_interrupt`.

## Timing Constraints

| Constraint | Value |
|------------|-------|
| `SENSOR_RDY` after power-up | ≤ 12 ms |
| `COEF_RDY` after power-up | ≤ 40 ms |
| Temperature measurement (single) | ≈ 3.6 ms |
| Pressure measurement, low power / standard / high precision | 5 / 28 / 105 ms typ., 8 / 35 / 115 ms max |
| Background measurement rate | 1–128 Hz (the rate must not exceed what the oversampling allows) |
| Supply ramp-up | 1 µs … 5 ms to 90 % |
| I²C clock | ≤ 3.4 MHz (driver ≤ 400 kHz) |

The result-ready flags are polled; the datasheet gives times only as typ./max for measurements and power-up, with no bus-decodable end-of-event pair beyond the status flags, so `spl06_timing.conf` defines no checks.

## Implementation Notes

- **Register-addressed connection:** accept `RegisterConnection` and use `read(reg, n)` / `write(reg, data)` directly; no chip-local helpers (see `specs/feature_register_access_design.md`). Read `0x10`–`0x21` in one burst.
- **Always set `TMP_EXT = 1`:** the coefficients are determined with the MEMS element's temperature sensor; the datasheet states "please use the external sensor setting". Writing `TMP_CFG` without bit 7 gives wrong compensated values.
- **Shift bits:** for oversampling above 8× the matching `P_SHIFT`/`T_SHIFT` bit must be set, otherwise the result register overflows; the scale factors `kP`/`kT` assume the shifted result.
- **Command mode needs a temperature first:** the pressure compensation uses `Traw_sc`; `read` measures temperature, then pressure. In background mode (`MEAS_CTRL = 111`) the temperature follows each pressure measurement automatically.
- **FIFO:** with `FIFO_EN`, results are taken from `PRS_B2..0` regardless of kind; the LSB of each entry flags whether it is a temperature (0) or pressure (1) value (datasheet FIFO section), and the driver clears it before scaling. Read the FIFO before it overruns (32 entries); an empty FIFO returns `0x800000` and sets `FIFO_EMPTY`.
- **Interrupt pin is SDO:** in I²C the interrupt shares the address pin; choose `INT_HL` so the idle level matches the address strap (low for `0x76`, high for `0x77`), or leave the interrupt disabled and poll `MEAS_CFG`.
- **Units:** the driver returns °C and pascals only; no hPa or mbar.
- **Wait for `COEF_RDY`:** coefficients read before `COEF_RDY` are invalid (up to 40 ms after power-up).
- **Address overlap:** `0x76`/`0x77` are shared with BMP280/BME280/BME680 (different ID values), MS5607/MS5611 (no ID register) and DPS310 (same ID value `0x10` at `0x0D`; distinguish with the coefficient layout or by user configuration); probe the SPL06 only with the `ID` value check.

## Sigrok Decoder

Decoder id `spl06`, input `['i2c']`. Matches I²C addresses `0x76` and `0x77`. Annotates every register access with the register name, decodes `PRS_CFG`/`TMP_CFG` (rate and oversampling), `MEAS_CFG` (mode and ready flags), `CFG_REG`, `INT_STS`, `FIFO_STS`, `RESET` and `ID`, assembles the 24-bit results and the coefficient block, and (once the coefficients and configuration were seen) the compensated temperature in °C and pressure in Pa. Warns on `TMP_EXT = 0`, an oversampling above 8× without the shift bit and a result read before the ready flag. No conformance annotation pair is emitted (no checked timing constraint).

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [x] Driver `python/periph/chips/pressure/spl06.py` — Google-style docstring on every class and public method
- [x] Examples `python/examples/pressure/spl06/minimal.py` — Tier-1 signature comment on every call
- [x] Examples `python/examples/pressure/spl06/complete.py` — Tier-1 + Tier-2
- [x] Examples `python/examples/pressure/spl06/demo.py` — Tier-1 + Tier-3
- [x] Tests `python/tests/pressure/spl06_test.py` (MicroPython)
- [x] Tests `python/tests/pressure/spl06_test_cp.py` (CircuitPython)
- [x] Tests `python/tests/pressure/spl06_test_linux.py` (Linux)
- [x] Unit test `python/tests/pressure/spl06_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [x] Manifest `python/uiflow1/pressure/spl06/spl06.json` — `Periph` category, `#C084FC` color
- [x] Blocks `python/uiflow1/pressure/spl06/spl06_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [x] Generated `python/uiflow1/pressure/spl06/spl06.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [x] Wrapper class `python/uiflow2/pressure/spl06/Spl06.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [x] Exported `python/uiflow2/pressure/spl06/Spl06.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [x] Driver `cpp/src/chips/pressure/Spl06.h` — Doxygen `/** @brief */` on every class and public method
- [x] Driver `cpp/src/chips/pressure/Spl06.cpp`
- [x] Examples `cpp/examples/arduino/pressure/Spl06/minimal/minimal.ino` — Tier-1
- [x] Examples `cpp/examples/arduino/pressure/Spl06/complete/complete.ino` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/arduino/pressure/Spl06/demo/demo.ino` — Tier-1 + Tier-3
- [x] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [x] Examples `cpp/examples/linux/pressure/Spl06/minimal/main.cpp` — Tier-1
- [x] Examples `cpp/examples/linux/pressure/Spl06/complete/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/linux/pressure/Spl06/demo/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/zephyr/pressure/Spl06/minimal/main.cpp` — Tier-1
- [x] Examples `cpp/examples/zephyr/pressure/Spl06/complete/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/zephyr/pressure/Spl06/demo/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/espidf/pressure/Spl06/minimal/main/main.cpp` — Tier-1
- [x] Examples `cpp/examples/espidf/pressure/Spl06/complete/main/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/espidf/pressure/Spl06/demo/main/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/picosdk/pressure/Spl06/minimal/src/main.cpp` — Tier-1
- [x] Examples `cpp/examples/picosdk/pressure/Spl06/complete/src/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/picosdk/pressure/Spl06/demo/src/main.cpp` — Tier-1 + Tier-3
- [x] Examples `cpp/examples/stm32cube/pressure/Spl06/minimal/Core/Src/main.cpp` — Tier-1
- [x] Examples `cpp/examples/stm32cube/pressure/Spl06/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [x] Examples `cpp/examples/stm32cube/pressure/Spl06/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [x] Tests `cpp/tests/pressure/spl06_test/spl06_test.ino` (Arduino)
- [x] Tests `cpp/tests/pressure/spl06_test_linux/spl06_test_linux.cpp` (Linux GCC)
- [x] Tests `cpp/tests/pressure/spl06_test_zephyr/src/main.cpp` (Zephyr)
- [x] Tests `cpp/tests/pressure/spl06_test_espidf/main/main.cpp` (ESP-IDF)
- [x] Tests `cpp/tests/pressure/spl06_test_picosdk/src/main.cpp` (Pico SDK)
- [x] Tests `cpp/tests/pressure/spl06_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [x] Unit test `cpp/tests/pressure/spl06_test_unit/spl06_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [x] Driver `nodejs/packages/periph/src/chips/pressure/spl06.js` — JSDoc on every class and exported method
- [x] Examples `nodejs/packages/periph/examples/pressure/spl06/minimal.js` — Tier-1
- [x] Examples `nodejs/packages/periph/examples/pressure/spl06/complete.js` — Tier-1 + Tier-2
- [x] Examples `nodejs/packages/periph/examples/pressure/spl06/demo.js` — Tier-1 + Tier-3
- [x] Tests `nodejs/tests/pressure/spl06_test.js`
- [x] Unit test `nodejs/tests/pressure/spl06_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [x] Node runtime `nodejs/packages/node-red-contrib-periph-pressure/nodes/spl06/spl06.js`
- [x] Node editor `nodejs/packages/node-red-contrib-periph-pressure/nodes/spl06/spl06.html` — `data-help-name` section with inputs, outputs, and config description
- [x] Demo flow `nodejs/packages/node-red-contrib-periph-pressure/examples/spl06/demo.json` — tab `info` field describes the scenario

### Rust
- [x] Driver `rust/periph/src/chips/pressure/spl06.rs` — `//!` module doc + `///` on every `pub` item
- [x] Examples `rust/examples/linux/pressure/spl06/minimal/src/main.rs` — Tier-1
- [x] Examples `rust/examples/linux/pressure/spl06/complete/src/main.rs` — Tier-1 + Tier-2
- [x] Examples `rust/examples/linux/pressure/spl06/demo/src/main.rs` — Tier-1 + Tier-3
- [x] Tests `rust/tests/pressure/spl06_test/src/main.rs` (Linux)
- [x] Tests `rust/tests/pressure/spl06_test_esp32s3/src/main.rs` (ESP32-S3)
- [x] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/pressure/spl06.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [x] Driver `go/periph/chips/pressure/spl06.go` — Go doc comment on every exported type and method
- [x] Examples `go/examples/linux/pressure/spl06/minimal/minimal.go` — Tier-1 signature comment on every call
- [x] Examples `go/examples/linux/pressure/spl06/complete/complete.go` — Tier-1 + Tier-2
- [x] Examples `go/examples/linux/pressure/spl06/demo/demo.go` — Tier-1 + Tier-3
- [x] Examples `go/examples/tinygo/pressure/spl06/minimal/minimal.go` — Tier-1 (TinyGo)
- [x] Examples `go/examples/tinygo/pressure/spl06/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [x] Examples `go/examples/tinygo/pressure/spl06/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [x] Tests `go/tests/pressure/spl06_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [x] Tests `go/tests/pressure/spl06_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [x] Unit test `go/periph/chips/pressure/spl06_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [x] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/pressure/Spl06Minimal.java` — Javadoc on every class and public method
- [x] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/pressure/Spl06Full.java` — Javadoc on every class and public method
- [x] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/pressure/Spl06Minimal.kt` — KDoc on every class and public method
- [x] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/pressure/Spl06Full.kt` — KDoc on every class and public method
- [x] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/pressure/Spl06Minimal.groovy` — Groovydoc on every class and public method
- [x] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/pressure/Spl06Full.groovy` — Groovydoc on every class and public method
- [x] Examples `jvm/examples/java/pressure/spl06/Minimal.java` — Tier-1
- [x] Examples `jvm/examples/java/pressure/spl06/Complete.java` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/java/pressure/spl06/Demo.java` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/kotlin/pressure/spl06/Minimal.kt` — Tier-1
- [x] Examples `jvm/examples/kotlin/pressure/spl06/Complete.kt` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/kotlin/pressure/spl06/Demo.kt` — Tier-1 + Tier-3
- [x] Examples `jvm/examples/groovy/pressure/spl06/Minimal.groovy` — Tier-1
- [x] Examples `jvm/examples/groovy/pressure/spl06/Complete.groovy` — Tier-1 + Tier-2
- [x] Examples `jvm/examples/groovy/pressure/spl06/Demo.groovy` — Tier-1 + Tier-3
- [x] Tests `jvm/tests/pressure/spl06/Spl06Test.java` (Pi hardware, JBang)
- [x] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/pressure/Spl06Test.java` (JUnit)
- [x] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/pressure/Spl06Test.kt` (Kotest/JUnit5)
- [x] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/pressure/Spl06Spec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [x] Decoder `sigrok/spl06/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [x] Decoder `sigrok/spl06/pd.py` — for every Timing Constraint above with a conformance check, emits the named start/end annotation pair the Sigrok Decoder section names

### Conformance
- [x] Checker `conformance/pressure/spl06_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [x] Timing config `specs/pressure/spl06_timing.conf` — machine-readable mirror of this spec's Timing Constraints section, one entry per conformance-checked constraint
- [x] Decoder `sigrok/spl06/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

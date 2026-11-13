# Chip Spec: HS40xx

**Manufacturer:** Renesas (IDT)  
**Datasheet:** `datasheets/humidity/hs40xx.pdf` (HS40xx datasheet R36DS0036EU0103 Rev. 1.03, Jun 14, 2024 — HS4001, HS4002, HS4003)  
**Category:** humidity  
**Transports:** I²C

> **Note:** The HS40xx measures relative humidity and temperature (no pressure); it is placed in `humidity` next to the HS30xx, SHTC3 and HDC1080.

## Overview

The HS40xx series (HS4001 / HS4002 / HS4003, automotive grade, waterproof and non-waterproof variants) are fully calibrated relative humidity and temperature sensors with a 14-bit resolution (0.04 %RH, 0.01 °C), independently programmable 8/10/12/14-bit resolutions and very low power (0.62 µA average at 14 bit, 1 measurement/s, 3.3 V; supply 1.71–3.6 V). Compared with the HS30xx they add: **hold and no-hold measurement commands**, an **8-bit CRC** after every result, a **periodic measurement mode** (0.5 s / 1 s / 2.5 s), an **ALERT pin** with programmable high/low thresholds and hysteresis for humidity and temperature, **configuration registers** (read/write commands), a **sensor ID** command and a **soft reset**. The sensor sleeps between commands and wakes on its own address.

## Transport Configuration

### I²C
- **Address:** `0x54` (7-bit, default; the sensor responds only to this address)
- **Max clock:** 400 kHz (tLOW/tHIGH ≥ 0.6 µs); **hold-mode measurements need SCL ≥ 200 kHz**
- **Identity register:** none readable as a register; the 32-bit sensor ID is returned by command `0xD7` (unique per device, not a fixed value) — `registry/chips.json` entry has `"id_probe": null`
- **Probe safety:** `write_sensitive` — a written byte after the address is a command (measurement, register write, soft reset `0xFE`, stop-periodic `0x30`); there is no register pointer
- **Protocol:** commands are single bytes after the write address (`[addr+W] [cmd] …`); results are read with `[addr+R]` in a **separate** transaction. In **no-hold** mode the read header is NACKed while the measurement runs (retry); in **hold** mode the sensor stretches SCL until done. The drivers use the plain `Connection` (`write` / `read`), not `RegisterConnection`

## Pin Configuration

| Pin | Active | Notes |
|-----|--------|-------|
| ALERT | configurable (`0x02` bit 0) | Measurement-active indicator in periodic mode (default: high at power-up, low between periodic measurements, high during one), or threshold alert output when alerts are enabled |
| SDA, SCL | — | I²C; external pull-ups |
| VDD, GND | — | 1.71–3.6 V supply |

## Command Set

| Command | Code | Description |
|---------|------|-------------|
| Hold temperature measurement | `0xE3` | Temperature only, SCL held low until done (≥ 200 kHz SCL) |
| No-hold temperature measurement | `0xF3` | Temperature only; read after the conversion time |
| Hold humidity + temperature | `0xE5` | Humidity then temperature |
| No-hold humidity + temperature | `0xF5` | Humidity then temperature |
| Read register | `0xA7` | Followed by the register address; then a read of the register data |
| Write register | `0xA6` | Followed by the register address and the data |
| Stop periodic measurements | `0x30` | Leave periodic mode (the sensor then sleeps) |
| Read sensor ID | `0xD7` | Then a read of the 4-byte ID (SCL held ≈ 10 µs while it is fetched) |
| Soft reset | `0xFE` | Resets all writeable registers; wait ≥ 500 ms |

### Measurement result frames

| Mode | Bytes |
|------|-------|
| Temperature only (`0xE3`/`0xF3`) | `T[13:8]` (bits 5:0 of byte 0), `T[7:0]`, `CRC` (3 bytes) |
| Humidity + temperature (`0xE5`/`0xF5`) | `H[13:8]`, `H[7:0]`, `T[13:8]`, `T[7:0]`, `CRC` (5 bytes) |
| Periodic mode, alerts enabled | `status`, `H…`, `T…`, `CRC` (6 bytes): status = `XXXX TH TL HH HL` |

The result is always scaled to 14 bits whatever the resolution. The **CRC** covers all four data bytes (for a temperature-only measurement the two humidity bytes are taken as zero): CRC-8, polynomial `0x1D` (x⁸ + x⁴ + x³ + x² + 1), initial value `0xFF`, no reflection, final XOR `0x00`, example `CRC(0x05 0x80 0x05 0x80) = 0xF2`.

### Configuration registers (read `0xA7 addr`, write `0xA6 addr data`)

| Address | Bits | Description |
|---------|------|-------------|
| `0x00` | 1:0 | Temperature resolution: `00` 8 bit, `01` 10 bit, `10` 12 bit, `11` 14 bit (default) |
| `0x00` | 3:2 | Humidity resolution: same coding |
| `0x02` | 7 | Periodic measurements: `1` = active |
| `0x02` | 5:4 | Period: `00` 0.5 s, `01` 1 s (default), `10` 2.5 s |
| `0x02` | 0 | Alert polarity: `1` = active high when triggered (default), `0` = active low |
| `0x03` | 3:0 | Alert enables: bit 0 RH low, bit 1 RH high, bit 2 temperature low, bit 3 temperature high |
| `0x08`, `0x07` | 13:0 | Temperature **high** alert trigger threshold (high byte, low byte) |
| `0x06`, `0x05` | 13:0 | Temperature high alert **reset** threshold |
| `0x0A`, `0x09` | 13:0 | Temperature **low** alert trigger threshold |
| `0x0C`, `0x0B` | 13:0 | Temperature low alert reset threshold |
| `0x10`, `0x0F` | 13:0 | RH **high** alert trigger threshold |
| `0x0E`, `0x0D` | 13:0 | RH high alert reset threshold |
| `0x12`, `0x11` | 13:0 | RH **low** alert trigger threshold |
| `0x14`, `0x13` | 13:0 | RH low alert reset threshold |

Thresholds are 14-bit values (top two bits of the high byte 0) converted with the same equations as the measurements. While accessing a register, leave all other bits unchanged: read the register, mask, write it back. All registers return to their defaults when the supply is cut.

## Initialization Sequence

1. Power-up (or soft reset + ≥ 500 ms): the sensor sleeps and waits for commands; periodic mode is off, 14-bit resolution.
2. `init` stores the connection; no bus access.
3. One-shot reading: no-hold humidity + temperature command `0xF5`, wait the conversion time (1.70 ms at 14 bit, typical), read 5 bytes (retry the read header while NACKed), verify the CRC.

## Interrupt

| Property | Value |
|----------|-------|
| INT pin | `ALERT`, polarity per `0x02` bit 0 (default active high) |
| Level | 2 (selectable sources: RH high/low, temperature high/low) |
| Condition(s) | The measured value crossed the programmed trigger threshold (cleared when it returns past the reset threshold — hysteresis); only evaluated in periodic measurement mode |
| Clear mechanism | None in software: the condition clears itself when the value passes the reset threshold |

### Interrupt sources

| Constant | Value | Condition |
|----------|-------|-----------|
| `SOURCE_RH_LOW` | `0x01` | Humidity below the low trigger threshold |
| `SOURCE_RH_HIGH` | `0x02` | Humidity above the high trigger threshold |
| `SOURCE_TEMP_LOW` | `0x04` | Temperature below the low trigger threshold |
| `SOURCE_TEMP_HIGH` | `0x08` | Temperature above the high trigger threshold |

(The enable bits of register `0x03` and the status byte bits `HL`, `HH`, `TL`, `TH` use this same layout.)

### Full driver interrupt API

| Method | Signature | Description |
|--------|-----------|-------------|
| `on_interrupt` | `on_interrupt(callback, int_pin=None)` | Subscribe on the ALERT pin (edge per the polarity) via `connection.int_pin` (or `int_pin`), otherwise a polling task at the periodic interval; `callback(status)` receives the status bitmask |
| `off_interrupt` | `off_interrupt()` | Unsubscribe |
| `poll_interrupt` | `poll_interrupt() -> int` | Fetches the latest periodic result (`[addr+R]`, 6 bytes with the status byte), verifies the CRC and returns the status bitmask (`TH TL HH HL` → the source bits); requires periodic mode with alerts enabled |
| `enable_interrupt` / `disable_interrupt` | `(source)` | Set / clear the source bit(s) in register `0x03` |

## Implementation Stages

### Minimal

Goal: read temperature and humidity with one call, no configuration beyond the connection.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| `init` | connection | — | Stores the connection; no bus access |
| `read` | — | `{temperature_c, humidity_pct}` | No-hold `0xF5`, waits 3 ms (derived margin on the 1.70 ms typical), reads 5 bytes retrying the NACKed read header for up to 20 ms, verifies the CRC (raises on mismatch after one retry), converts |

**Sensible defaults:** 14-bit resolution, one-shot no-hold measurements, CRC checked, periodic mode and alerts off (power-on state).

### Full

Goal: expose complete chip functionality. Extends Minimal.

| Operation | Parameters | Returns | Notes |
|-----------|------------|---------|-------|
| *(inherits Minimal)* | | | |
| `read_temperature` | — | float | °C only (`0xF3`, 3 bytes) |
| `read_humidity` | — | float | %RH (uses `0xF5`) |
| `read_hold` | — | `{temperature_c, humidity_pct}` | `0xE5` with clock stretching (needs a stretching-capable controller and SCL ≥ 200 kHz) |
| `read_raw` | — | `(raw_humidity, raw_temperature)` | 14-bit values, CRC verified |
| `set_resolution` | `humidity_bits: int \| None`, `temperature_bits: int \| None` | — | Register `0x00` (read-modify-write); raises for values other than 8/10/12/14 |
| `get_resolution` | — | `(humidity_bits, temperature_bits)` | |
| `read_register` / `write_register` | `reg: int` / `reg, value` | int / — | `0xA7` / `0xA6` sequences |
| `start_periodic` | `period_s: float` (0.5, 1, 2.5) | — | Register `0x02` bit 7 + period |
| `stop_periodic` | — | — | Command `0x30` |
| `read_periodic` | — | `{temperature_c, humidity_pct, status}` | `[addr+R]` fetch of the latest result (6 bytes when alerts are enabled — status first — else 5), CRC verified |
| `set_alert_thresholds` | `rh_high=None, rh_high_reset=None, rh_low=None, rh_low_reset=None, temp_high=None, temp_high_reset=None, temp_low=None, temp_low_reset=None` | — | Natural units (%RH, °C) converted to the 14-bit threshold registers; only the given thresholds are written; `None` leaves a threshold unchanged |
| `set_alert_polarity` | `active_high: bool` | — | Register `0x02` bit 0 |
| `read_id` | — | int | Command `0xD7`, 4 bytes MSB first |
| `soft_reset` | — | — | `0xFE`, waits 500 ms (stops periodic mode first) |
| `dew_point` | — | float | °C from one measurement (Magnus formula) |
| `on_interrupt` / `off_interrupt` / `poll_interrupt` / `enable_interrupt` / `disable_interrupt` | see Interrupt | | |

**Additional configuration options:** resolutions, periodic mode and period, alert thresholds / hysteresis / enables / polarity.

## Data Conversion

```
humidity_pct  = H[13:0] / (2^14 - 1) * 100            # H[13:0] = ((b0 & 0x3F) << 8) | b1
temperature_c = T[13:0] / (2^14 - 1) * 165 - 40       # T[13:0] = ((b2 & 0x3F) << 8) | b3   (b0/b2 upper bits are 0)
threshold word (rh)   = round(rh_pct / 100 * (2^14 - 1))
threshold word (temp) = round((temp_c + 40) / 165 * (2^14 - 1))
register pair: (low byte, high byte) at the lower / higher address, e.g. temperature high trigger: 0x07 = low byte, 0x08 = high byte
crc8(4 data bytes): poly 0x1D, init 0xFF, MSB first, no reflect, xor-out 0x00      # CRC(05 80 05 80) = F2
```

Example: bytes `0x05 0x80 0x05 0x80` → humidity 8.59 %RH, temperature −25.8 °C, CRC `0xF2`.

## Node-RED

Node name: `periph-hs40xx`  
Package: `node-red-contrib-periph-humidity`

| Input trigger | Output `msg.payload` fields | Notes |
|---------------|-----------------------------|-------|
| any message | `{ temperature_c, humidity_pct }` | One no-hold measurement per input message |

Config panel fields: I²C bus number, address (`0x54`), resolution.

### Demo flow

Inject node (every 5 s) → `periph-hs40xx` → function node formatting `"23.4 °C / 41.2 %RH"` → debug node and gauges.

## Examples

### Demo

**Humidity watchdog.** With the Full driver, print the sensor ID, read single measurements for 10 s, then program RH-high alert thresholds (trigger 60 %RH, reset 55 %RH) and enable the RH-high alert, start periodic mode at 1 s and print `read_periodic()` with the alert status (breathing on the sensor raises RH), then `stop_periodic()` and `soft_reset()`.

## Timing Constraints

- **Conversion time (typical, including the digital core):** temperature only 0.37 / 0.45 / 0.60 / 0.91 ms (8 / 10 / 12 / 14 bit); humidity + temperature 0.64 / 0.80 / 1.04 / 1.70 ms. A no-hold read before completion is NACKed (retry); a hold read blocks SCL until done.
- **Sleep entry:** the sensor returns to sleep 20 µs after the data have been sent (not in periodic mode).
- **Periodic mode:** a measurement every 0.5 s / 1 s / 2.5 s; in this mode only the data-fetch read and the stop command (`0x30`) are accepted and the sensor does not sleep.
- **Soft reset:** wait ≥ 500 ms afterwards; leave periodic mode and read pending data first.
- **ID read:** the sensor holds SCL ≈ 10 µs while fetching the ID.
- **I²C:** ≤ 400 kHz (≥ 200 kHz in hold mode) — clock-rate properties, not event-to-event delays.
- **Conformance:** the conversion times are typical values only, so `specs/humidity/hs40xx_timing.conf` defines no checks.

## Implementation Notes

- **Plain Connection:** commands and results are separate transactions; use the read-header-NACK retry for no-hold measurements.
- **CRC details:** for temperature-only frames the CRC is computed over `00 00 T_hi T_lo`; always verify.
- **Alerts only in periodic mode:** the status byte is prepended to the periodic frame **only when an alert enable bit is set**; parse 6 bytes then, 5 otherwise.
- **Read-modify-write registers:** change only the documented bits; keep the rest.
- **Registers are volatile:** they reset to the defaults whenever the supply is cut or after `0xFE`.
- **Address:** `0x54` (the HS30xx uses `0x44`).
- **Conditioning:** same rehydration requirement as the HS30xx after soldering/storage.
- **Datasheet:** Renesas HS40xx Rev. 1.03, downloaded from `https://www.renesas.com/en/document/dst/hs40xx-datasheet`.

## Sigrok Decoder

Decoder id `hs40xx`, input `['i2c']`. Matches I²C address `0x54`. Decodes the commands by name, register reads/writes (decoded resolution, periodic, alert, threshold fields), measurement frames (humidity %RH, temperature °C, optional alert status byte) with the CRC-8 verified, and the ID frame; warns on a CRC mismatch, on a no-hold read NACK (data not ready) and on commands other than the data fetch / `0x30` while periodic mode is active (not detectable without state — no timing pair). No timing annotation pair is emitted.

## Implementation Checklist

Tick each box as the item is committed. The PR may not be opened until every box is ticked.

### Python
- [ ] Driver `python/periph/chips/humidity/hs40xx.py` — Google-style docstring on every class and public method
- [ ] Examples `python/examples/humidity/hs40xx/minimal.py` — Tier-1 signature comment on every call
- [ ] Examples `python/examples/humidity/hs40xx/complete.py` — Tier-1 + Tier-2
- [ ] Examples `python/examples/humidity/hs40xx/demo.py` — Tier-1 + Tier-3
- [ ] Tests `python/tests/humidity/hs40xx_test.py` (MicroPython)
- [ ] Tests `python/tests/humidity/hs40xx_test_cp.py` (CircuitPython)
- [ ] Tests `python/tests/humidity/hs40xx_test_linux.py` (Linux)
- [ ] Unit test `python/tests/humidity/hs40xx_test_unit.py` — mocked via `python/periph/connection/i2c_mock.py`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### UIFlow 1
- [ ] Manifest `python/uiflow1/humidity/hs40xx/hs40xx.json` — `Periph` category, `#C084FC` color
- [ ] Blocks `python/uiflow1/humidity/hs40xx/hs40xx_*.py` — one execute block for `init`, one value/execute block per other `Full`-class method wrapped
- [ ] Generated `python/uiflow1/humidity/hs40xx/hs40xx.m5b` — run `python/uiflow1/generate.sh`, commit the output

### UIFlow 2
- [ ] Wrapper class `python/uiflow2/humidity/hs40xx/HS40xx.py` — YAML docstrings per `python/uiflow2/UIFLOW2_BLOCKS.md`, `Periph` category, `#C084FC` color; one method for `init`, one method per other `Full`-class method wrapped, with a return annotation only on methods that return a value
- [ ] Exported `python/uiflow2/humidity/hs40xx/HS40xx.m5b2` — built by hand in the UiFlow 2 web IDE's Block Designer (no generator — see `python/uiflow2/UIFLOW2_BLOCKS.md` § Workflow), commit the output alongside the wrapper class

### C++
- [ ] Driver `cpp/src/chips/humidity/HS40xx.h` — Doxygen `/** @brief */` on every class and public method
- [ ] Driver `cpp/src/chips/humidity/HS40xx.cpp`
- [ ] Examples `cpp/examples/arduino/humidity/HS40xx/minimal/minimal.ino` — Tier-1
- [ ] Examples `cpp/examples/arduino/humidity/HS40xx/complete/complete.ino` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/arduino/humidity/HS40xx/demo/demo.ino` — Tier-1 + Tier-3
- [ ] Arduino sketches use `#include <Periph.h>`; `cpp/src/Periph.h` regenerated (`node cpp/scripts/generate-periph-header.js`); `cpp/test_arduino_examples.sh` passes for `esp32:esp32:esp32s3` and `arduino:avr:mega` (see AGENTS.md)
- [ ] Examples `cpp/examples/linux/humidity/HS40xx/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/linux/humidity/HS40xx/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/linux/humidity/HS40xx/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/zephyr/humidity/HS40xx/minimal/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/zephyr/humidity/HS40xx/complete/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/zephyr/humidity/HS40xx/demo/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/espidf/humidity/HS40xx/minimal/main/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/espidf/humidity/HS40xx/complete/main/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/espidf/humidity/HS40xx/demo/main/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/picosdk/humidity/HS40xx/minimal/src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/stm32cube/humidity/HS40xx/minimal/Core/Src/main.cpp` — Tier-1
- [ ] Examples `cpp/examples/picosdk/humidity/HS40xx/complete/src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/stm32cube/humidity/HS40xx/complete/Core/Src/main.cpp` — Tier-1 + Tier-2
- [ ] Examples `cpp/examples/picosdk/humidity/HS40xx/demo/src/main.cpp` — Tier-1 + Tier-3
- [ ] Examples `cpp/examples/stm32cube/humidity/HS40xx/demo/Core/Src/main.cpp` — Tier-1 + Tier-3
- [ ] Tests `cpp/tests/humidity/hs40xx_test/hs40xx_test.ino` (Arduino)
- [ ] Tests `cpp/tests/humidity/hs40xx_test_linux/hs40xx_test_linux.cpp` (Linux GCC)
- [ ] Tests `cpp/tests/humidity/hs40xx_test_zephyr/src/main.cpp` (Zephyr)
- [ ] Tests `cpp/tests/humidity/hs40xx_test_espidf/main/main.cpp` (ESP-IDF)
- [ ] Tests `cpp/tests/humidity/hs40xx_test_picosdk/src/main.cpp` (Pico SDK)
- [ ] Tests `cpp/tests/humidity/hs40xx_test_stm32cube/Core/Src/main.cpp` (STM32Cube)
- [ ] Unit test `cpp/tests/humidity/hs40xx_test_unit/hs40xx_test_unit.cpp` — mocked via `cpp/src/connection/I2CConnectionMock.h/.cpp`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node.js
- [ ] Driver `nodejs/packages/periph/src/chips/humidity/hs40xx.js` — JSDoc on every class and exported method
- [ ] Examples `nodejs/packages/periph/examples/humidity/hs40xx/minimal.js` — Tier-1
- [ ] Examples `nodejs/packages/periph/examples/humidity/hs40xx/complete.js` — Tier-1 + Tier-2
- [ ] Examples `nodejs/packages/periph/examples/humidity/hs40xx/demo.js` — Tier-1 + Tier-3
- [ ] Tests `nodejs/tests/humidity/hs40xx_test.js`
- [ ] Unit test `nodejs/tests/humidity/hs40xx_test_unit.js` — mocked via `nodejs/packages/periph/src/connection/i2c_mock.js`, run via `test_linux.sh` (see `specs/testing_framework.md`)

### Node-RED
- [ ] Node runtime `nodejs/packages/node-red-contrib-periph-humidity/nodes/hs40xx/hs40xx.js`
- [ ] Node editor `nodejs/packages/node-red-contrib-periph-humidity/nodes/hs40xx/hs40xx.html` — `data-help-name` section with inputs, outputs, and config description
- [ ] Demo flow `nodejs/packages/node-red-contrib-periph-humidity/examples/hs40xx/demo.json` — tab `info` field describes the scenario

### Rust
- [ ] Driver `rust/periph/src/chips/humidity/hs40xx.rs` — `//!` module doc + `///` on every `pub` item
- [ ] Examples `rust/examples/linux/humidity/hs40xx/minimal/src/main.rs` — Tier-1
- [ ] Examples `rust/examples/linux/humidity/hs40xx/complete/src/main.rs` — Tier-1 + Tier-2
- [ ] Examples `rust/examples/linux/humidity/hs40xx/demo/src/main.rs` — Tier-1 + Tier-3
- [ ] Tests `rust/tests/humidity/hs40xx_test/src/main.rs` (Linux)
- [ ] Tests `rust/tests/humidity/hs40xx_test_esp32s3/src/main.rs` (ESP32-S3)
- [ ] Unit tests `#[cfg(test)] mod tests` colocated in `rust/periph/src/chips/humidity/hs40xx.rs` — `embedded-hal-mock`, run via `cargo test -p periph --features std`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### Go
- [ ] Driver `go/periph/chips/humidity/hs40xx.go` — Go doc comment on every exported type and method
- [ ] Examples `go/examples/linux/humidity/hs40xx/minimal/minimal.go` — Tier-1 signature comment on every call
- [ ] Examples `go/examples/linux/humidity/hs40xx/complete/complete.go` — Tier-1 + Tier-2
- [ ] Examples `go/examples/linux/humidity/hs40xx/demo/demo.go` — Tier-1 + Tier-3
- [ ] Examples `go/examples/tinygo/humidity/hs40xx/minimal/minimal.go` — Tier-1 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/hs40xx/complete/complete.go` — Tier-1 + Tier-2 (TinyGo)
- [ ] Examples `go/examples/tinygo/humidity/hs40xx/demo/demo.go` — Tier-1 + Tier-3 (TinyGo)
- [ ] Tests `go/tests/humidity/hs40xx_test/main.go` — PASS/FAIL/===DONE=== protocol (host)
- [ ] Tests `go/tests/humidity/hs40xx_test_tinygo/main.go` — PASS/FAIL/===DONE=== protocol (TinyGo)
- [ ] Unit test `go/periph/chips/humidity/hs40xx_test.go` — struct literal implementing `Connection`, run via `go test ./periph/chips/...`, wrapped by `test_linux.sh` (see `specs/testing_framework.md`)

### JVM
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/HS40xxMinimal.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-java/src/main/java/it/uhde/periph/chips/humidity/HS40xxFull.java` — Javadoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/HS40xxMinimal.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-kotlin/src/main/kotlin/it/uhde/periph/chips/humidity/HS40xxFull.kt` — KDoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/HS40xxMinimal.groovy` — Groovydoc on every class and public method
- [ ] Driver `jvm/periph-groovy/src/main/groovy/it/uhde/periph/chips/humidity/HS40xxFull.groovy` — Groovydoc on every class and public method
- [ ] Examples `jvm/examples/java/humidity/hs40xx/Minimal.java` — Tier-1
- [ ] Examples `jvm/examples/java/humidity/hs40xx/Complete.java` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/java/humidity/hs40xx/Demo.java` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/kotlin/humidity/hs40xx/Minimal.kt` — Tier-1
- [ ] Examples `jvm/examples/kotlin/humidity/hs40xx/Complete.kt` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/kotlin/humidity/hs40xx/Demo.kt` — Tier-1 + Tier-3
- [ ] Examples `jvm/examples/groovy/humidity/hs40xx/Minimal.groovy` — Tier-1
- [ ] Examples `jvm/examples/groovy/humidity/hs40xx/Complete.groovy` — Tier-1 + Tier-2
- [ ] Examples `jvm/examples/groovy/humidity/hs40xx/Demo.groovy` — Tier-1 + Tier-3
- [ ] Tests `jvm/tests/humidity/hs40xx/HS40xxTest.java` (Pi hardware, JBang)
- [ ] Unit test `jvm/periph-java/src/test/java/it/uhde/periph/chips/humidity/HS40xxTest.java` (JUnit)
- [ ] Unit test `jvm/periph-kotlin/src/test/kotlin/it/uhde/periph/chips/humidity/HS40xxTest.kt` (Kotest/JUnit5)
- [ ] Unit test `jvm/periph-groovy/src/test/groovy/it/uhde/periph/chips/humidity/HS40xxSpec.groovy` (Spock) — all three reuse `MockConnection` from `periph-connection`'s test scope, run via `mvn test` per module, wrapped by `test_linux_<lang>.sh` (see `specs/testing_framework.md`)

### Sigrok
- [ ] Decoder `sigrok/hs40xx/__init__.py` — module docstring describing transport input, addresses, and what is annotated
- [ ] Decoder `sigrok/hs40xx/pd.py` — annotates every command, register access and measurement frame per the Sigrok Decoder section (no timing annotation pair: no conformance-checked constraints)

### Conformance
- [ ] Checker `conformance/humidity/hs40xx_conformance.py` — one per chip (not per language); see `specs/testing_framework.md`, "Conformance Implementation"
- [ ] Timing config `specs/humidity/hs40xx_timing.conf` — already committed; defines no checks (see its header)
- [ ] Decoder `sigrok/hs40xx/pd.py` — annotates all named registers / fields; produces `OUTPUT_ANN` and `OUTPUT_PYTHON` (see `specs/sigrok_annotations.md`)

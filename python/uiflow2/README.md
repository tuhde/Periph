# UIFlow 2 native custom blocks

Blockly custom-block definitions in the `.m5b2` format used by **M5Stack UIFlow 2**
(uiflow2.m5stack.com), wrapping [Periph](https://github.com/tuhde/Periph) MicroPython chip
drivers. See [`UIFLOW2_BLOCKS.md`](UIFLOW2_BLOCKS.md) for the full format reference and
authoring workflow — the short version: each chip gets one annotated Python wrapper class
(`<Chip>.py`) and one exported `<Chip>.m5b2`, hand-built in the UiFlow 2 web IDE's Block
Designer. There is no generator for this format, unlike [`python/uiflow1/`](../uiflow1/)'s
`.m5b` (UiFlow 1), which is a separate, non-interoperable deliverable — see
`UIFLOW2_BLOCKS.md` §0.

> **Verification status — read before trusting any chip but AHT21.** `AHT21.m5b2` is a
> **real export** from the UiFlow 2 Block Designer — `AHT21.py` was pasted in and the
> resulting `.m5b2` was downloaded and committed as-is. Every other chip below was produced
> by mechanically extending that verified template (same LANGUAGES/BlockRegExpList/dropdown
> machinery, same `_init()` block-definition shape) to new combinations AHT21 itself never
> exercised: **methods with parameters** (AHT21 only had no-arg getters plus an init with
> params) and **execute-type blocks with parameters** (AHT21's only execute block was
> `__init__`). Extending the args0/`valueToCode` machinery from "no params" to "N params" is
> low-risk — it's the same mechanism at a different array length — but none of these 26
> `.m5b2` files have been imported into the real Block Designer and checked. Treat every
> chip except AHT21 as **unverified** until you've done that; if the Designer produces
> something different for one, fix that file and note the correction (mirroring how AHT21's
> first, wrong hand-guess was corrected — see `UIFLOW2_BLOCKS.md` §7's "Resolved" list).
>
> A few deliberate, lower-risk choices worth knowing about while checking:
> - **Boolean-ish parameters are `type: int` (0/1), not a boolean field** — e.g. `RDA5807M.mute(enable: int)`, `APDS9960.enable_proximity(enabled: int)`, IO-expander `write_pin`/`set_pin_mode`. This mirrors how `python/uiflow1/`'s own manifests already represent the same booleans (plain `number`, not a Blockly boolean field) — no unverified field type was needed. The wrapper converts to a real Python `bool` before calling the driver.
> - **`str`/`bool` return annotations** (`read_uid() -> str`, `data_ready() -> bool`, etc.) are only confirmed by extension of rule 3.1 (§3.1) — AHT21 only exercised `-> float`. The rule itself ("any annotation → value block") is stated generally in the format, so this is a low-risk extension, not a new mechanism.
> - **I²C default addresses** are the chip's real default where confirmed from its driver docstring (e.g. `MPU6050` → 104/`0x68`, `AS5600` → 54/`0x36`); a handful with no clearly documented default (`RDA5807M`, `PCF8576`, `ENS160`) default to `0`, matching `python/uiflow1/`'s own convention of always defaulting to 0 — just a UI convenience value either way, not a correctness concern.

## Layout

```
uiflow2/
  <category>/
    <chip>/
      <Chip>.py      # annotated wrapper class — the source of truth (pyCode)
      <Chip>.m5b2     # exported block package
```

## Chips

| Chip | Category | Blocks |
|------|----------|--------|
| [HX711](adc_dac/hx711/) | adc_dac | init, `tare`, `set_scale`, `read_weight`, `read_raw` |
| [MCP4725](adc_dac/mcp4725/) | adc_dac | init, `set_voltage`, `set_raw` |
| [MCP4728](adc_dac/mcp4728/) | adc_dac | init, `set_voltage`, `set_raw`, `set_all` |
| [PCF8591](adc_dac/pcf8591/) | adc_dac | init, `read_channel`, `read_channel_voltage`, `set_dac_voltage` |
| [RDA5807M](comms/rda5807m/) | comms | init, `set_frequency`, `frequency`, `set_volume`, `mute`, `seek` |
| [PCF8576](display/pcf8576/) | display | init, `clear`, `set_digit` |
| [AHT21](environmental/aht21/) | environmental | init, `read_temperature`, `read_humidity` |
| [BME280](environmental/bme280/) | environmental | init, `temperature`, `pressure`, `humidity` |
| [BME680](environmental/bme680/) | environmental | init, `temperature`, `pressure`, `humidity`, `gas_resistance` |
| [ENS160](gas/ens160/) | gas | init, `read_aqi`, `read_tvoc`, `read_eco2` |
| [NEO6](gnss/neo6/) | gnss | init, `update`, `latitude`, `longitude`, `altitude` |
| [DHT11](humidity/dht11/) | humidity | init, `read_temperature`, `read_humidity` |
| [MPU6050](imu/mpu6050/) | imu | init, `accel_x`, `accel_y`, `accel_z`, `gyro_x`, `gyro_y`, `gyro_z`, `temperature`, `data_ready` |
| [MCP23017](io_expander/mcp23017/) | io_expander | init, `set_pin_mode`, `write_pin`, `read_pin` |
| [PCF8574](io_expander/pcf8574/) | io_expander | init, `set_pin_mode`, `write_pin`, `read_pin` |
| [PCF8575](io_expander/pcf8575/) | io_expander | init, `set_pin_mode`, `write_pin`, `read_pin` |
| [SK6812RGBW](led/sk6812rgbw/) | led | init, `fill`, `set_pixel`, `show`, `off` |
| [WS2812B](led/ws2812b/) | led | init, `fill`, `set_pixel`, `show`, `off` |
| [APDS9960](light/apds9960/) | light | init, `color_clear`, `color_red`, `color_green`, `color_blue`, `enable_proximity`, `proximity` |
| [AS5600](magnetometer/as5600/) | magnetometer | init, `angle`, `angle_raw`, `is_magnet_detected`, `is_magnet_too_strong`, `is_magnet_too_weak` |
| [EEPROM24AA02UID](memory/24aa02uid/) | memory | init, `read_uid`, `read_byte`, `write_byte` |
| [INA219](power/ina219/) | power | init, `voltage`, `shunt_voltage`, `current`, `power` |
| [INA226](power/ina226/) | power | init, `read_voltage`, `read_current`, `read_power` |
| [INA3221](power/ina3221/) | power | init, `read_voltage`, `read_current`, `read_power` (each takes a `channel` param) |
| [BMP180](pressure/bmp180/) | pressure | init, `read_temperature`, `read_pressure`, `read_altitude` |
| [BMP280](pressure/bmp280/) | pressure | init, `read_temperature`, `read_pressure`, `read_altitude` |
| [MFRC522](rfid/mfrc522/) | rfid | init, `is_card_present`, `read_uid` |

## Using these blocks

Import a chip's `<Chip>.m5b2` into UiFlow 2 via **Extension → Import**. The init block
constructs the driver over I²C (bus/address inputs) and names the instance; every other
block calls the matching method on that named instance, mirroring how UiFlow 2's own
device blocks work. Place the init block once, before any other block for that chip, in
your flow.

The `periph` package must be present on the device's filesystem (or frozen into the
firmware) for the wrapped driver to import at runtime.

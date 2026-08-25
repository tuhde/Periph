# UIFlow 2 custom blocks

Blockly custom-block definitions that wrap [Periph](https://github.com/tuhde/Periph) MicroPython chip
drivers for [M5Stack UIFlow 2](https://uiflow2.m5stack.com/). Each chip gets its own folder containing a
`<chip>.json` manifest plus one MicroPython file per block, following the format used by the
[UIFlow Block Maker](http://block-maker.m5stack.com/) and the community
[uiflow-custom-block-generator](https://github.com/3110/uiflow-custom-block-generator) tool. Verified
end-to-end against the real generator: all 27 manifests produce valid `.m5b` files with matching block
counts, and the generated Blockly/Python codegen correctly wires up each block's params.

This is source-level integration only — no `.m5b` binaries are committed here, and nothing is
pre-bundled into UIFlow's palette. Anyone can import a chip's blocks into their own project (see below).

## Layout

```
uiflow/
  <category>/
    <chip>/
      <chip>.json               # category, color, and block/param definitions
      <chip>_init.py            # execute block: opens the connection and constructs the driver
      <chip>_read_<value>.py    # value block(s): return one reading each
```

The manifest is named `<chip>.json`, not `blocks.json` — the generator names its output `.m5b` after the
manifest's filename, so a shared generic name would silently overwrite one chip's output with another's if
you batch-generate more than one into the same directory.

All blocks share the `Periph` category and the `#a6bbcf` color, matching the palette color already used by
the [Node-RED nodes](../../nodejs/packages/) so Periph-backed tooling looks consistent across integrations.

Each `<chip>_init` block constructs the driver via the matching `periph.connection.<transport>_auto` factory
(`i2c_auto`, `spi_auto`, `uart_auto`, `hx711_auto`, `dhtxx_auto`, or `neopixel_auto` — whichever transport the
chip uses; on UIFlow 2 these all resolve to the platform's `machine` module) and stores it in a module-level
global (e.g. `_periph_aht21`) that the chip's other blocks reference. Place the init block once, before any
other block for that chip, in your flow — matching how M5Stack's own device blocks work.

Value blocks call whichever *Full*-class getter returns a single value, so each block plugs directly into a
number/string slot elsewhere in the flow rather than returning a dict. Execute blocks (e.g. setting a DAC
voltage, writing an LED pixel, writing an expander pin) call the matching *Full*-class setter with its real
arguments. Every wrapped method already exists on the underlying driver in
`python/periph/chips/<category>/<chip>.py` — the blocks add no new logic.

## Chips

| Chip | Category | Blocks |
|------|----------|--------|
| [HX711](adc_dac/hx711/) | adc_dac | `hx711_init`, `hx711_tare`, `hx711_set_scale`, `hx711_read_weight`, `hx711_read_raw` |
| [MCP4725](adc_dac/mcp4725/) | adc_dac | `mcp4725_init`, `mcp4725_set_voltage`, `mcp4725_set_raw` |
| [MCP4728](adc_dac/mcp4728/) | adc_dac | `mcp4728_init`, `mcp4728_set_voltage`, `mcp4728_set_raw`, `mcp4728_set_all` |
| [PCF8591](adc_dac/pcf8591/) | adc_dac | `pcf8591_init`, `pcf8591_read_channel`, `pcf8591_read_channel_voltage`, `pcf8591_set_dac_voltage` |
| [RDA5807M](comms/rda5807m/) | comms | `rda5807m_init`, `rda5807m_set_frequency`, `rda5807m_frequency`, `rda5807m_set_volume`, `rda5807m_mute`, `rda5807m_seek` |
| [PCF8576](display/pcf8576/) | display | `pcf8576_init`, `pcf8576_clear`, `pcf8576_set_digit` |
| [AHT21](environmental/aht21/) | environmental | `aht21_init`, `aht21_read_temperature`, `aht21_read_humidity` |
| [BME280](environmental/bme280/) | environmental | `bme280_init`, `bme280_temperature`, `bme280_pressure`, `bme280_humidity` |
| [BME680](environmental/bme680/) | environmental | `bme680_init`, `bme680_temperature`, `bme680_pressure`, `bme680_humidity`, `bme680_gas_resistance` |
| [ENS160](gas/ens160/) | gas | `ens160_init`, `ens160_read_aqi`, `ens160_read_tvoc`, `ens160_read_eco2` |
| [NEO-6](gnss/neo6/) | gnss | `neo6_init`, `neo6_update`, `neo6_latitude`, `neo6_longitude`, `neo6_altitude` |
| [DHT11](humidity/dht11/) | humidity | `dht11_init`, `dht11_read_temperature`, `dht11_read_humidity` |
| [MPU6050](imu/mpu6050/) | imu | `mpu6050_init`, `mpu6050_accel_x`, `mpu6050_accel_y`, `mpu6050_accel_z`, `mpu6050_gyro_x`, `mpu6050_gyro_y`, `mpu6050_gyro_z`, `mpu6050_temperature`, `mpu6050_data_ready` |
| [MCP23017](io_expander/mcp23017/) | io_expander | `mcp23017_init`, `mcp23017_set_pin_mode`, `mcp23017_write_pin`, `mcp23017_read_pin` |
| [PCF8574](io_expander/pcf8574/) | io_expander | `pcf8574_init`, `pcf8574_set_pin_mode`, `pcf8574_write_pin`, `pcf8574_read_pin` |
| [PCF8575](io_expander/pcf8575/) | io_expander | `pcf8575_init`, `pcf8575_set_pin_mode`, `pcf8575_write_pin`, `pcf8575_read_pin` |
| [SK6812RGBW](led/sk6812rgbw/) | led | `sk6812rgbw_init`, `sk6812rgbw_fill`, `sk6812rgbw_set_pixel`, `sk6812rgbw_show`, `sk6812rgbw_off` |
| [WS2812B](led/ws2812b/) | led | `ws2812b_init`, `ws2812b_fill`, `ws2812b_set_pixel`, `ws2812b_show`, `ws2812b_off` |
| [APDS9960](light/apds9960/) | light | `apds9960_init`, `apds9960_color_clear`, `apds9960_color_red`, `apds9960_color_green`, `apds9960_color_blue`, `apds9960_enable_proximity`, `apds9960_proximity` |
| [AS5600](magnetometer/as5600/) | magnetometer | `as5600_init`, `as5600_angle`, `as5600_angle_raw`, `as5600_is_magnet_detected`, `as5600_is_magnet_too_strong`, `as5600_is_magnet_too_weak` |
| [24AA02UID](memory/24aa02uid/) | memory | `24aa02uid_init`, `24aa02uid_read_uid`, `24aa02uid_read_byte`, `24aa02uid_write_byte` |
| [INA219](power/ina219/) | power | `ina219_init`, `ina219_voltage`, `ina219_shunt_voltage`, `ina219_current`, `ina219_power` |
| [INA226](power/ina226/) | power | `ina226_init`, `ina226_read_voltage`, `ina226_read_current`, `ina226_read_power` |
| [INA3221](power/ina3221/) | power | `ina3221_init`, `ina3221_read_voltage`, `ina3221_read_current`, `ina3221_read_power` (each takes a `_channel` param) |
| [BMP180](pressure/bmp180/) | pressure | `bmp180_init`, `bmp180_read_temperature`, `bmp180_read_pressure`, `bmp180_read_altitude` |
| [BMP280](pressure/bmp280/) | pressure | `bmp280_init`, `bmp280_read_temperature`, `bmp280_read_pressure`, `bmp280_read_altitude` |
| [MFRC522](rfid/mfrc522/) | rfid | `mfrc522_init`, `mfrc522_is_card_present`, `mfrc522_read_uid` |

## Using these blocks

**Option A — UIFlow Block Maker.** Open [block-maker.m5stack.com](http://block-maker.m5stack.com/), create a
block using the same `category`/`color`/`params` as the chip's `<chip>.json`, and paste in the matching
`.py` file's contents for each block's code field.

**Option B — uiflow-custom-block-generator.** Install the generator and point it at a chip's `<chip>.json`
to produce an importable `.m5b` file:

```sh
pip install git+https://github.com/3110/uiflow-custom-block-generator
python -m uiflow_custom_block_generator python/uiflow/environmental/aht21/aht21.json
```

Then import the generated `.m5b` into your UIFlow 2 project via **Extension → Import**.

Either way, the `periph` package must be present on the device's filesystem (or frozen into the firmware)
for the generated blocks to import it at runtime.

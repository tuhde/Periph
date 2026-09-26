# periph

Peripheral chip drivers for MicroPython, CircuitPython, and Linux

- **Three targets** — MicroPython, CircuitPython, and Linux (`/dev/i2c-N` via `smbus2`)
- **Same driver code everywhere** — only the connection class changes per platform
- **Two-tier API** — `*Minimal` for the primary use case, `*Full` for complete chip functionality

## Install

```sh
pip install periph[linux]   # Linux host: also installs smbus2, spidev, gpiod, pyserial
```

On MicroPython, install with `mip`: the whole library, one category, or a single chip, each with the connection files it needs:

```sh
mpremote mip install github:tuhde/Periph/python/periph
mpremote mip install github:tuhde/Periph/python/periph/chips/power
mpremote mip install github:tuhde/Periph/python/periph/chips/power/ina226.json
```

Append `@vX.Y.Z` to pin a release. On CircuitPython, copy the `periph/` package onto the device's filesystem instead.

## Example

```python
from periph.connection.i2c_auto import I2CConnection
from periph.chips.power.ina226 import INA226Minimal
import time

connection = I2CConnection(0x40)
ina = INA226Minimal(connection)

while True:
    print(ina.voltage(), ina.current(), ina.power())
    time.sleep(1)
```

`I2CConnection` above resolves to the right platform implementation automatically (MicroPython `machine.I2C`, CircuitPython `busio.I2C`, or Linux `/dev/i2c-N`).

Each chip exposes two classes:

- `*Minimal` — primary use case, works out of the box with sensible defaults
- `*Full` — complete chip functionality, extends Minimal

## Supported chips

| Chip | Category | Description |
|------|----------|-------------|
| 24AA02UID | Memory | 2K I2C EEPROM with 32-bit unique serial number |
| AD7705 | ADC/DAC | 2-channel, 16-bit sigma-delta ADC |
| AD7706 | ADC/DAC | 3-channel, 16-bit sigma-delta ADC |
| ADE7953 | Power monitor | Single-phase metering IC |
| ADXL345 | Accelerometer | 3-axis accelerometer |
| ADXL362 | Accelerometer | Read X, Y, Z acceleration in *g*. |
| AHT21 | Environmental sensor | Temperature and humidity sensor |
| APA102 | LED driver | Addressable RGB LED strip |
| APDS-9930 | Light sensor | Ambient light and proximity sensor |
| APDS9960 | Light sensor | APDS-9960 digital proximity, ambient light, RGB and gesture sensor |
| AS5600 | Magnetometer | 12-bit programmable contactless rotary position sensor |
| BME280 | Environmental sensor | Combined humidity + pressure + temperature sensor |
| BME680 | Environmental sensor | 4-in-1 environmental sensor: temperature, pressure, humidity, gas resistance. |
| BMP085 | Pressure sensor | Piezo-resistive pressure + temperature sensor |
| BMP180 | Pressure sensor | Piezo-resistive pressure + temperature sensor |
| BMP280 | Pressure sensor | Piezo-resistive pressure + temperature sensor |
| BMP384 | Pressure sensor | High-precision barometric pressure and temperature sensor |
| BMP581 | Pressure sensor | MEMS barometric pressure + temperature sensor |
| DHT11 | Humidity sensor | Combined temperature and humidity sensor |
| DRV8830 | Motor driver | Low-voltage motor driver |
| DS3231 | RTC | Extremely accurate I2C RTC/TCXO/crystal |
| ENS160 | Gas sensor | Digital multi-gas sensor |
| HMC5883L | Magnetometer | 3-axis magnetometer |
| HX710A | ADC/DAC | 24-bit ADC |
| HX710B | ADC/DAC | 24-bit ADC |
| HX711 | ADC/DAC | 24-bit ADC |
| INA219 | Power monitor | 26V, 12-bit current/voltage/power monitor |
| INA226 | Power monitor | 36V, 16-bit current/voltage/power monitor |
| INA3221 | Power monitor | Three-channel 26V current/voltage/power monitor |
| L3G4200D | Gyroscope | Three-axis MEMS gyroscope |
| L3GD20H | Gyroscope | (and L3GD20) three-axis MEMS gyroscope |
| LPS22DF | Pressure sensor | Absolute pressure and temperature sensor |
| LPS28DFW | Pressure sensor | Dual full-scale digital barometer |
| LPS33HW | Pressure sensor | Water-resistant MEMS absolute pressure sensor |
| MCP23017 | IO expander | 16-bit I/O port expander |
| MCP2515 | Comms | Send/recv with default configuration. |
| MCP4725 | ADC/DAC | Single-channel 12-bit voltage-output DAC |
| MCP4728 | ADC/DAC | Quad-channel 12-bit voltage-output DAC |
| MCP9808 | Temperature sensor | ±0.5°C maximum accuracy digital temperature sensor |
| MFRC522 | RFID/NFC | 13.56 MHz RFID/NFC reader |
| MPR121 | Other | Capacitive touch controller |
| MPU-6050 | IMU | 6-axis MotionTracking device (accelerometer + gyroscope) |
| MPU-9250 | IMU | 9-axis MotionTracking device (accelerometer + gyroscope) |
| MPU-9255 | IMU | 9-axis MotionTracking device (accelerometer + gyroscope) |
| NEO-6 | GNSS/GPS | U-blox NEO-6 GNSS receiver: NMEA position, altitude, and fix status. |
| PCF8523 | RTC | Low-power I2C real-time clock |
| PCF8574 | IO expander | 8-bit quasi-bidirectional I/O port expander |
| PCF8575 | IO expander | 16-bit quasi-bidirectional I/O port expander |
| PCF8576 | Display driver | 40x4 universal LCD segment driver |
| PCF8591 | ADC/DAC | 8-bit quad ADC + DAC |
| RDA5807M | Comms | Single-chip FM stereo radio tuner |
| RFM9x | Comms | 868/915 MHz HF band, max SF=12. |
| SK6812RGBW | LED driver | Addressable RGBW LED strip |
| TMP117 | Temperature sensor | ±0.1°C high-accuracy digital temperature sensor |
| TPIC6B595 | IO expander | 8-bit power SIPO shift register |
| VL53L0X | Time-of-flight | Time-of-Flight ranging sensor |
| VL53L1X | Time-of-flight | Time-of-Flight ranging sensor |
| WS2812B | LED driver | Addressable RGB LED strip |
| WS2814 | LED driver | Addressable RGBW LED strip |

## Links

- [GitHub](https://github.com/tuhde/Periph)

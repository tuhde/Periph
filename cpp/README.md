# Periph

Peripheral chip drivers for Arduino (I2C/SPI).

Drivers for sensors and actuators connected via I2C or SPI transports.

## Install

Arduino IDE: **Sketch → Include Library → Manage Libraries…**, search for `Periph`.

Or manually: clone/download this repository into your `libraries/Periph` folder.

## Usage

```cpp
#include <Wire.h>
#include "I2CTransport.h"
#include "INA219.h"

I2CTransport transport(Wire, 0x40);
INA219Minimal ina(transport);

void setup() {
    Wire.begin();
}

void loop() {
    Serial.println(ina.power());  // watts
    delay(1000);
}
```

Each chip exposes two classes:

- `*Minimal` — primary use case, works out of the box with sensible defaults
- `*Full` — complete chip functionality, extends Minimal

## Supported chips

| Chip | Category | Header |
|------|----------|--------|
| 24AA02UID | Memory | `chips/memory/24AA02UID.h` |
| AHT21 | Environmental sensor | `chips/environmental/AHT21.h` |
| APDS9960 | Light sensor | `chips/light/APDS9960.h` |
| AS5600 | Magnetometer | `chips/magnetometer/AS5600.h` |
| BME280 | Environmental sensor | `chips/environmental/BME280.h` |
| BME680 | Environmental sensor | `chips/environmental/BME680.h` |
| BMP180 | Pressure sensor | `chips/pressure/BMP180.h` |
| BMP280 | Pressure sensor | `chips/pressure/BMP280.h` |
| DHT11 | Humidity sensor | `chips/humidity/DHT11.h` |
| ENS160 | Gas sensor | `chips/gas/ENS160.h` |
| HX711 | ADC/DAC | `chips/adc_dac/HX711.h` |
| INA219 | Power monitor | `chips/power/INA219.h` |
| INA226 | Power monitor | `chips/power/INA226.h` |
| INA3221 | Power monitor | `chips/power/INA3221.h` |
| MCP23017 | IO expander | `chips/io_expander/MCP23017.h` |
| MCP4725 | ADC/DAC | `chips/adc_dac/MCP4725.h` |
| MCP4728 | ADC/DAC | `chips/adc_dac/MCP4728.h` |
| Mpu6050 | IMU | `chips/imu/Mpu6050.h` |
| NEO6 | GNSS/GPS | `chips/gnss/NEO6.h` |
| PCF8574 | IO expander | `chips/io_expander/PCF8574.h` |
| PCF8575 | IO expander | `chips/io_expander/PCF8575.h` |
| PCF8576 | Display driver | `chips/display/PCF8576.h` |
| PCF8591 | ADC/DAC | `chips/adc_dac/PCF8591.h` |
| Rda5807m | Comms | `chips/comms/Rda5807m.h` |
| SK6812RGBW | LED driver | `chips/led/SK6812RGBW.h` |
| WS2812B | LED driver | `chips/led/WS2812B.h` |

## Examples

Each chip ships three examples under `examples/`: `<Chip>_Minimal`, `<Chip>_Complete`, and `<Chip>_Demo`.

## Links

- [GitHub](https://github.com/tuhde/Periph)

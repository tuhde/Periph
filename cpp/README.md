# Periph

Peripheral chip drivers for Arduino (I2C/SPI).

Drivers for sensors and actuators connected via I2C or SPI transports.

## Install

Arduino IDE: **Sketch → Include Library → Manage Libraries…**, search for `Periph`.

Or manually: clone/download this repository into your `libraries/Periph` folder.

## Usage

```cpp
#include <Wire.h>
#include "I2CConnection.h"
#include "INA219.h"

I2CConnection connection(Wire, 0x40);
INA219Minimal ina(connection);

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
| AD7705 | ADC/DAC | `chips/adc_dac/AD7705.h` |
| AD7706 | ADC/DAC | `chips/adc_dac/AD7706.h` |
| ADE7953 | Power monitor | `chips/power/ADE7953.h` |
| ADXL345 | Accelerometer | `chips/accelerometer/ADXL345.h` |
| ADXL362 | Accelerometer | `chips/accelerometer/ADXL362.h` |
| AHT21 | Environmental sensor | `chips/environmental/AHT21.h` |
| APA102 | LED driver | `chips/led/APA102.h` |
| Apds9930 | Light sensor | `chips/light/Apds9930.h` |
| APDS9960 | Light sensor | `chips/light/APDS9960.h` |
| AS5600 | Magnetometer | `chips/magnetometer/AS5600.h` |
| BME280 | Environmental sensor | `chips/environmental/BME280.h` |
| BME680 | Environmental sensor | `chips/environmental/BME680.h` |
| BMP085 | Pressure sensor | `chips/pressure/BMP085.h` |
| BMP180 | Pressure sensor | `chips/pressure/BMP180.h` |
| BMP280 | Pressure sensor | `chips/pressure/BMP280.h` |
| BMP384 | Pressure sensor | `chips/pressure/BMP384.h` |
| BMP581 | Pressure sensor | `chips/pressure/BMP581.h` |
| DHT11 | Humidity sensor | `chips/humidity/DHT11.h` |
| DS3231 | RTC | `chips/rtc/DS3231.h` |
| ENS160 | Gas sensor | `chips/gas/ENS160.h` |
| HMC5883L | Magnetometer | `chips/magnetometer/HMC5883L.h` |
| HX710A | ADC/DAC | `chips/adc_dac/HX710A.h` |
| HX710B | ADC/DAC | `chips/adc_dac/HX710B.h` |
| HX711 | ADC/DAC | `chips/adc_dac/HX711.h` |
| INA219 | Power monitor | `chips/power/INA219.h` |
| INA226 | Power monitor | `chips/power/INA226.h` |
| INA3221 | Power monitor | `chips/power/INA3221.h` |
| L3G4200D | Gyroscope | `chips/gyroscope/L3G4200D.h` |
| L3gd20h | Gyroscope | `chips/gyroscope/L3gd20h.h` |
| LPS22DF | Pressure sensor | `chips/pressure/LPS22DF.h` |
| LPS28DFW | Pressure sensor | `chips/pressure/LPS28DFW.h` |
| Lps33hw | Pressure sensor | `chips/pressure/Lps33hw.h` |
| MCP23017 | IO expander | `chips/io_expander/MCP23017.h` |
| MCP2515 | Comms | `chips/comms/MCP2515.h` |
| MCP4725 | ADC/DAC | `chips/adc_dac/MCP4725.h` |
| MCP4728 | ADC/DAC | `chips/adc_dac/MCP4728.h` |
| MFRC522 | RFID/NFC | `chips/rfid/MFRC522.h` |
| Mpr121 | Other | `chips/other/Mpr121.h` |
| MPU6050 | IMU | `chips/imu/MPU6050.h` |
| MPU9250 | IMU | `chips/imu/MPU9250.h` |
| MPU9255 | IMU | `chips/imu/MPU9255.h` |
| NEO6 | GNSS/GPS | `chips/gnss/NEO6.h` |
| NeoPixelRGBBase | LED driver | `chips/led/NeoPixelRGBBase.h` |
| NeoPixelRGBWBase | LED driver | `chips/led/NeoPixelRGBWBase.h` |
| PCF8574 | IO expander | `chips/io_expander/PCF8574.h` |
| PCF8575 | IO expander | `chips/io_expander/PCF8575.h` |
| PCF8576 | Display driver | `chips/display/PCF8576.h` |
| PCF8591 | ADC/DAC | `chips/adc_dac/PCF8591.h` |
| RDA5807M | Comms | `chips/comms/RDA5807M.h` |
| RFM9x | Comms | `chips/comms/RFM9x.h` |
| SK6812RGBW | LED driver | `chips/led/SK6812RGBW.h` |
| WS2812B | LED driver | `chips/led/WS2812B.h` |
| WS2814 | LED driver | `chips/led/WS2814.h` |

## Examples

Each chip ships three examples under `examples/`: `<Chip>_Minimal`, `<Chip>_Complete`, and `<Chip>_Demo`.

## Links

- [GitHub](https://github.com/tuhde/Periph)

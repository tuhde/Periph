# Periph

Peripheral chip drivers for Arduino (I2C/SPI).

Drivers for sensors and actuators connected via I2C or SPI transports.

> This library is generated from [tuhde/Periph](https://github.com/tuhde/Periph) on every release. Please open issues and pull requests there.

## Install

Arduino IDE: **Sketch → Include Library → Manage Libraries…**, search for `Periph`.

Or manually: clone/download this repository into your `libraries/Periph` folder.

## Usage

```cpp
#include <Wire.h>
#include <Periph.h>

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
| 23X1024 | Memory | `chips/memory/23X1024.h` |
| 24AA02UID | Memory | `chips/memory/24AA02UID.h` |
| AD5243 | ADC/DAC | `chips/adc_dac/AD5243.h` |
| AD5248 | ADC/DAC | `chips/adc_dac/AD5248.h` |
| AD7705 | ADC/DAC | `chips/adc_dac/AD7705.h` |
| AD7706 | ADC/DAC | `chips/adc_dac/AD7706.h` |
| ADE7953 | Power monitor | `chips/power/ADE7953.h` |
| ADS1013 | ADC/DAC | `chips/adc_dac/ADS1013.h` |
| ADS1014 | ADC/DAC | `chips/adc_dac/ADS1014.h` |
| ADS1113 | ADC/DAC | `chips/adc_dac/ADS1113.h` |
| ADS1114 | ADC/DAC | `chips/adc_dac/ADS1114.h` |
| ADS1115 | ADC/DAC | `chips/adc_dac/ADS1115.h` |
| ADS7828 | ADC/DAC | `chips/adc_dac/ADS7828.h` |
| ADXL345 | Accelerometer | `chips/accelerometer/ADXL345.h` |
| ADXL362 | Accelerometer | `chips/accelerometer/ADXL362.h` |
| AHT21 | Environmental sensor | `chips/environmental/AHT21.h` |
| APA102 | LED driver | `chips/led/APA102.h` |
| Apds9930 | Light sensor | `chips/light/Apds9930.h` |
| APDS9960 | Light sensor | `chips/light/APDS9960.h` |
| AS5600 | Magnetometer | `chips/magnetometer/AS5600.h` |
| AT24C64 | Memory | `chips/memory/AT24C64.h` |
| AXP2101 | Power monitor | `chips/power/AXP2101.h` |
| BMA150 | Accelerometer | `chips/accelerometer/BMA150.h` |
| BMA180 | Accelerometer | `chips/accelerometer/BMA180.h` |
| BME280 | Environmental sensor | `chips/environmental/BME280.h` |
| BME680 | Environmental sensor | `chips/environmental/BME680.h` |
| BMP085 | Pressure sensor | `chips/pressure/BMP085.h` |
| BMP180 | Pressure sensor | `chips/pressure/BMP180.h` |
| BMP280 | Pressure sensor | `chips/pressure/BMP280.h` |
| BMP384 | Pressure sensor | `chips/pressure/BMP384.h` |
| BMP581 | Pressure sensor | `chips/pressure/BMP581.h` |
| BNO055 | IMU | `chips/imu/BNO055.h` |
| CAT24C512 | Memory | `chips/memory/CAT24C512.h` |
| CAT5171 | ADC/DAC | `chips/adc_dac/CAT5171.h` |
| CS43L22 | ADC/DAC | `chips/adc_dac/CS43L22.h` |
| DHT11 | Humidity sensor | `chips/humidity/DHT11.h` |
| DRV2605 | Motor driver | `chips/motor/DRV2605.h` |
| DRV8830 | Motor driver | `chips/motor/DRV8830.h` |
| DS1881 | ADC/DAC | `chips/adc_dac/DS1881.h` |
| DS3231 | RTC | `chips/rtc/DS3231.h` |
| DS3502 | ADC/DAC | `chips/adc_dac/DS3502.h` |
| ENS160 | Gas sensor | `chips/gas/ENS160.h` |
| ES8311 | ADC/DAC | `chips/adc_dac/ES8311.h` |
| FXOS8700 | Accelerometer | `chips/accelerometer/FXOS8700.h` |
| HMC5883L | Magnetometer | `chips/magnetometer/HMC5883L.h` |
| HX710A | ADC/DAC | `chips/adc_dac/HX710A.h` |
| HX710B | ADC/DAC | `chips/adc_dac/HX710B.h` |
| HX711 | ADC/DAC | `chips/adc_dac/HX711.h` |
| HY3131 | ADC/DAC | `chips/adc_dac/HY3131.h` |
| ICM20948 | IMU | `chips/imu/ICM20948.h` |
| INA219 | Power monitor | `chips/power/INA219.h` |
| INA226 | Power monitor | `chips/power/INA226.h` |
| INA3221 | Power monitor | `chips/power/INA3221.h` |
| L3G4200D | Gyroscope | `chips/gyroscope/L3G4200D.h` |
| L3gd20h | Gyroscope | `chips/gyroscope/L3gd20h.h` |
| LIS3DH | Accelerometer | `chips/accelerometer/LIS3DH.h` |
| LPS22DF | Pressure sensor | `chips/pressure/LPS22DF.h` |
| LPS28DFW | Pressure sensor | `chips/pressure/LPS28DFW.h` |
| Lps33hw | Pressure sensor | `chips/pressure/Lps33hw.h` |
| LSM303 | Accelerometer | `chips/accelerometer/LSM303.h` |
| MAX44009 | Light sensor | `chips/light/MAX44009.h` |
| MB85RC | Memory | `chips/memory/MB85RC.h` |
| MCP23008 | IO expander | `chips/io_expander/MCP23008.h` |
| MCP23017 | IO expander | `chips/io_expander/MCP23017.h` |
| MCP2515 | Comms | `chips/comms/MCP2515.h` |
| MCP3422 | ADC/DAC | `chips/adc_dac/MCP3422.h` |
| MCP4725 | ADC/DAC | `chips/adc_dac/MCP4725.h` |
| MCP4728 | ADC/DAC | `chips/adc_dac/MCP4728.h` |
| MCP4XXX | ADC/DAC | `chips/adc_dac/MCP4XXX.h` |
| MCP4XXXParts | ADC/DAC | `chips/adc_dac/MCP4XXXParts.h` |
| MCP9808 | Temperature sensor | `chips/temperature/MCP9808.h` |
| MFRC522 | RFID/NFC | `chips/rfid/MFRC522.h` |
| MMA8451Q | Accelerometer | `chips/accelerometer/MMA8451Q.h` |
| Mpr121 | Other | `chips/other/Mpr121.h` |
| MPU6050 | IMU | `chips/imu/MPU6050.h` |
| MPU9250 | IMU | `chips/imu/MPU9250.h` |
| MPU9255 | IMU | `chips/imu/MPU9255.h` |
| MX25L12845E | Memory | `chips/memory/MX25L12845E.h` |
| NEO6 | GNSS/GPS | `chips/gnss/NEO6.h` |
| NeoPixelRGBBase | LED driver | `chips/led/NeoPixelRGBBase.h` |
| NeoPixelRGBWBase | LED driver | `chips/led/NeoPixelRGBWBase.h` |
| PCA6408A | IO expander | `chips/io_expander/PCA6408A.h` |
| PCA6416A | IO expander | `chips/io_expander/PCA6416A.h` |
| PCA9536 | IO expander | `chips/io_expander/PCA9536.h` |
| PCA9539 | IO expander | `chips/io_expander/PCA9539.h` |
| PCA9541 | Other | `chips/other/PCA9541.h` |
| PCF85063A | RTC | `chips/rtc/PCF85063A.h` |
| PCF8523 | RTC | `chips/rtc/PCF8523.h` |
| PCF8574 | IO expander | `chips/io_expander/PCF8574.h` |
| PCF8575 | IO expander | `chips/io_expander/PCF8575.h` |
| PCF8576 | Display driver | `chips/display/PCF8576.h` |
| PCF8578 | Display driver | `chips/display/PCF8578.h` |
| PCF8579 | Display driver | `chips/display/PCF8579.h` |
| PCF8591 | ADC/DAC | `chips/adc_dac/PCF8591.h` |
| QMI8658A | IMU | `chips/imu/QMI8658A.h` |
| RDA5807M | Comms | `chips/comms/RDA5807M.h` |
| RFM9x | Comms | `chips/comms/RFM9x.h` |
| SD_SPI | Memory | `chips/memory/SD_SPI.h` |
| SH1106 | Display driver | `chips/display/SH1106.h` |
| SHT31 | Humidity sensor | `chips/humidity/SHT31.h` |
| SK6812RGBW | LED driver | `chips/led/SK6812RGBW.h` |
| SSD1305 | Display driver | `chips/display/SSD1305.h` |
| SSD1306 | Display driver | `chips/display/SSD1306.h` |
| TMC2130 | Motor driver | `chips/motor/TMC2130.h` |
| TMC2208 | Motor driver | `chips/motor/TMC2208.h` |
| TMC2209 | Motor driver | `chips/motor/TMC2209.h` |
| TMC5160 | Motor driver | `chips/motor/TMC5160.h` |
| TMP117 | Temperature sensor | `chips/temperature/TMP117.h` |
| UC1611s | Display driver | `chips/display/UC1611s.h` |
| VL53L0X | Time-of-flight | `chips/tof/VL53L0X.h` |
| VL53L1X | Time-of-flight | `chips/tof/VL53L1X.h` |
| WS2812B | LED driver | `chips/led/WS2812B.h` |
| WS2814 | LED driver | `chips/led/WS2814.h` |

## Examples

Each chip ships three examples under `examples/<category>/`: `<Chip>_Minimal`, `<Chip>_Complete`, and `<Chip>_Demo` (in the Arduino IDE: File > Examples > Periph > <category>).

## Links

- [GitHub](https://github.com/tuhde/Periph)

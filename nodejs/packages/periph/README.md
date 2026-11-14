# periph

JavaScript drivers for peripheral chips (sensors, actuators, and other ICs) connected via I²C, SPI, or UART. Runs on Linux and any Node.js host.

## Install

```sh
npm install periph
```

## Usage

Drivers are accessed by path — require the chip module directly:

```js
const { INA226Minimal } = require('periph/src/chips/power/ina226');
const { I2CConnection } = require('periph/src/connection/i2c');

const connection = new I2CConnection(1, 0x40);  // I2C bus 1, address 0x40
const sensor     = new INA226Minimal(connection);
console.log(await sensor.power());  // watts
```

Each chip exposes two classes:

- `*Minimal` — primary use case, works out of the box with sensible defaults
- `*Full` — complete chip functionality, extends Minimal

## Supported chips

| Chip | Category | Require path |
|------|----------|-------------|
| 23X1024 | Memory | `periph/src/chips/memory/_23x1024` |
| 24AA02UID | Memory | `periph/src/chips/memory/_24aa02uid` |
| AD5243 | ADC/DAC | `periph/src/chips/adc_dac/ad5243` |
| AD5248 | ADC/DAC | `periph/src/chips/adc_dac/ad5248` |
| AD7705 | ADC/DAC | `periph/src/chips/adc_dac/ad7705` |
| AD7706 | ADC/DAC | `periph/src/chips/adc_dac/ad7706` |
| ADE7953 | Power monitor | `periph/src/chips/power/ade7953` |
| ADS1013 | ADC/DAC | `periph/src/chips/adc_dac/ads1013` |
| ADS1014 | ADC/DAC | `periph/src/chips/adc_dac/ads1014` |
| ADS1113 | ADC/DAC | `periph/src/chips/adc_dac/ads1113` |
| ADS1114 | ADC/DAC | `periph/src/chips/adc_dac/ads1114` |
| ADS1115 | ADC/DAC | `periph/src/chips/adc_dac/ads1115` |
| ADS7828 | ADC/DAC | `periph/src/chips/adc_dac/ads7828` |
| ADXL345 | Accelerometer | `periph/src/chips/accelerometer/adxl345` |
| ADXL362 | Accelerometer | `periph/src/chips/accelerometer/adxl362` |
| AHT21 | Environmental sensor | `periph/src/chips/environmental/aht21` |
| APA102 | LED driver | `periph/src/chips/led/apa102` |
| APDS-9930 | Light sensor | `periph/src/chips/light/apds-9930` |
| APDS9960 | Light sensor | `periph/src/chips/light/apds9960` |
| AS5600 | Magnetometer | `periph/src/chips/magnetometer/as5600` |
| AT24C64 | Memory | `periph/src/chips/memory/at24c64` |
| AXP2101 | Power monitor | `periph/src/chips/power/axp2101` |
| BMA150 | Accelerometer | `periph/src/chips/accelerometer/bma150` |
| BMA180 | Accelerometer | `periph/src/chips/accelerometer/bma180` |
| BME280 | Environmental sensor | `periph/src/chips/environmental/bme280` |
| BME680 | Environmental sensor | `periph/src/chips/environmental/bme680` |
| BMP085 | Pressure sensor | `periph/src/chips/pressure/bmp085` |
| BMP180 | Pressure sensor | `periph/src/chips/pressure/bmp180` |
| BMP280 | Pressure sensor | `periph/src/chips/pressure/bmp280` |
| BMP384 | Pressure sensor | `periph/src/chips/pressure/bmp384` |
| BMP581 | Pressure sensor | `periph/src/chips/pressure/bmp581` |
| BNO055 | IMU | `periph/src/chips/imu/bno055` |
| CAT24C512 | Memory | `periph/src/chips/memory/cat24c512` |
| CAT5171 | ADC/DAC | `periph/src/chips/adc_dac/cat5171` |
| CS43L22 | ADC/DAC | `periph/src/chips/adc_dac/cs43l22` |
| DHT11 | Humidity sensor | `periph/src/chips/humidity/dht11` |
| DRV2605 | Motor driver | `periph/src/chips/motor/drv2605` |
| DRV8830 | Motor driver | `periph/src/chips/motor/drv8830` |
| DS1881 | ADC/DAC | `periph/src/chips/adc_dac/ds1881` |
| DS3231 | RTC | `periph/src/chips/rtc/ds3231` |
| DS3502 | ADC/DAC | `periph/src/chips/adc_dac/ds3502` |
| ENS160 | Gas sensor | `periph/src/chips/gas/ens160` |
| ES8311 | ADC/DAC | `periph/src/chips/adc_dac/es8311` |
| FXOS8700 | Accelerometer | `periph/src/chips/accelerometer/fxos8700` |
| HMC5883L | Magnetometer | `periph/src/chips/magnetometer/hmc5883l` |
| HX710A | ADC/DAC | `periph/src/chips/adc_dac/hx710a` |
| HX710B | ADC/DAC | `periph/src/chips/adc_dac/hx710b` |
| HX711 | ADC/DAC | `periph/src/chips/adc_dac/hx711` |
| HY3131 | ADC/DAC | `periph/src/chips/adc_dac/hy3131` |
| ICM20948 | IMU | `periph/src/chips/imu/icm20948` |
| IL0373 | Display driver | `periph/src/chips/display/il0373` |
| INA219 | Power monitor | `periph/src/chips/power/ina219` |
| INA226 | Power monitor | `periph/src/chips/power/ina226` |
| INA3221 | Power monitor | `periph/src/chips/power/ina3221` |
| L3G4200D | Gyroscope | `periph/src/chips/gyroscope/l3g4200d` |
| L3GD20H | Gyroscope | `periph/src/chips/gyroscope/l3gd20h` |
| LIS3DH | Accelerometer | `periph/src/chips/accelerometer/lis3dh` |
| LPS22DF | Pressure sensor | `periph/src/chips/pressure/lps22df` |
| LPS28DFW | Pressure sensor | `periph/src/chips/pressure/lps28dfw` |
| LPS33HW | Pressure sensor | `periph/src/chips/pressure/lps33hw` |
| LSM303 | Accelerometer | `periph/src/chips/accelerometer/lsm303` |
| MAX44009 | Light sensor | `periph/src/chips/light/max44009` |
| MB85RC | Memory | `periph/src/chips/memory/mb85rc` |
| MCP23008 | IO expander | `periph/src/chips/io_expander/mcp23008` |
| MCP23017 | IO expander | `periph/src/chips/io_expander/mcp23017` |
| MCP2515 | Comms | `periph/src/chips/comms/mcp2515` |
| MCP3422 | ADC/DAC | `periph/src/chips/adc_dac/mcp3422` |
| MCP4725 | ADC/DAC | `periph/src/chips/adc_dac/mcp4725` |
| MCP4728 | ADC/DAC | `periph/src/chips/adc_dac/mcp4728` |
| MCP4XXX | ADC/DAC | `periph/src/chips/adc_dac/mcp4xxx` |
| MCP9808 | Temperature sensor | `periph/src/chips/temperature/mcp9808` |
| MFRC522 | RFID/NFC | `periph/src/chips/rfid/mfrc522` |
| MMA8451Q | Accelerometer | `periph/src/chips/accelerometer/mma8451q` |
| MPR121 | Other | `periph/src/chips/other/mpr121` |
| MPU6050 | IMU | `periph/src/chips/imu/mpu6050` |
| MPU9250 | IMU | `periph/src/chips/imu/mpu9250` |
| MPU9255 | IMU | `periph/src/chips/imu/mpu9255` |
| MX25L12845E | Memory | `periph/src/chips/memory/mx25l12845e` |
| NEO6 | GNSS/GPS | `periph/src/chips/gnss/neo6` |
| NEOPIXELRGBBASE | LED driver | `periph/src/chips/led/_NeoPixelRGBBase` |
| NEOPIXELRGBWBASE | LED driver | `periph/src/chips/led/_NeoPixelRGBWBase` |
| PCA6408A | IO expander | `periph/src/chips/io_expander/pca6408a` |
| PCA6416A | IO expander | `periph/src/chips/io_expander/pca6416a` |
| PCA9536 | IO expander | `periph/src/chips/io_expander/pca9536` |
| PCA9539 | IO expander | `periph/src/chips/io_expander/pca9539` |
| PCA9541 | Other | `periph/src/chips/other/pca9541` |
| PCF85063A | RTC | `periph/src/chips/rtc/pcf85063a` |
| PCF8523 | RTC | `periph/src/chips/rtc/pcf8523` |
| PCF8574 | IO expander | `periph/src/chips/io_expander/pcf8574` |
| PCF8575 | IO expander | `periph/src/chips/io_expander/pcf8575` |
| PCF8576 | Display driver | `periph/src/chips/display/pcf8576` |
| PCF8578 | Display driver | `periph/src/chips/display/pcf8578` |
| PCF8579 | Display driver | `periph/src/chips/display/pcf8579` |
| PCF8591 | ADC/DAC | `periph/src/chips/adc_dac/pcf8591` |
| QMI8658A | IMU | `periph/src/chips/imu/qmi8658a` |
| RDA5807M | Comms | `periph/src/chips/comms/rda5807m` |
| RFM9X | Comms | `periph/src/chips/comms/rfm9x` |
| SD_SPI | Memory | `periph/src/chips/memory/sd_spi` |
| SH1106 | Display driver | `periph/src/chips/display/sh1106` |
| SHT31 | Humidity sensor | `periph/src/chips/humidity/sht31` |
| SK6812RGBW | LED driver | `periph/src/chips/led/sk6812rgbw` |
| SSD1305 | Display driver | `periph/src/chips/display/ssd1305` |
| SSD1306 | Display driver | `periph/src/chips/display/ssd1306` |
| SSD1331 | Display driver | `periph/src/chips/display/ssd1331` |
| ST7036 | Display driver | `periph/src/chips/display/st7036` |
| TMC2130 | Motor driver | `periph/src/chips/motor/tmc2130` |
| TMC2208 | Motor driver | `periph/src/chips/motor/tmc2208` |
| TMC2209 | Motor driver | `periph/src/chips/motor/tmc2209` |
| TMC5160 | Motor driver | `periph/src/chips/motor/tmc5160` |
| TMP117 | Temperature sensor | `periph/src/chips/temperature/tmp117` |
| TPIC6B595 | IO expander | `periph/src/chips/io_expander/tpic6b595` |
| UC1611S | Display driver | `periph/src/chips/display/uc1611s` |
| UC8159 | Display driver | `periph/src/chips/display/uc8159` |
| UC8276C | Display driver | `periph/src/chips/display/uc8276c` |
| VL53L0X | Time-of-flight | `periph/src/chips/tof/vl53l0x` |
| VL53L1X | Time-of-flight | `periph/src/chips/tof/vl53l1x` |
| WS2812B | LED driver | `periph/src/chips/led/ws2812b` |
| WS2814 | LED driver | `periph/src/chips/led/ws2814` |

## Links

- [GitHub](https://github.com/tuhde/Periph)
- [Node-RED nodes](https://www.npmjs.com/search?q=node-red-contrib-periph)
